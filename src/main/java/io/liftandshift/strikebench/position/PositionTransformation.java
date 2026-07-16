package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;
import io.liftandshift.strikebench.strategy.StrategyCatalog;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One before/after contract for every position-changing action. Pricing inputs are supplied by the
 * existing evaluator; this class only compares structure identity, obligations, and evaluated risk.
 */
public final class PositionTransformation {
    private PositionTransformation() {}

    public enum Action {
        CLOSE, PARTIAL_CLOSE, LEG_CLOSE, ROLL, ADD_LEG, REMOVE_LEG,
        ADD_STOCK, REMOVE_STOCK, ASSIGNMENT, EXERCISE, EXPIRATION
    }

    public static Preview preview(Request request) {
        if (request == null || request.action() == null || request.before() == null || request.beforeRisk() == null) {
            throw new IllegalArgumentException("a transformation needs an action, current position, and current evaluated risk");
        }
        if (request.after() != null && request.afterRisk() == null) {
            throw new IllegalArgumentException("a surviving position requires a fresh-eyes after assessment");
        }
        if (request.after() != null && (!request.before().symbol().equals(request.after().symbol())
                || request.before().lane() != request.after().lane()
                || request.before().source() != request.after().source())) {
            throw new IllegalArgumentException("a transformation cannot switch symbol, execution lane, or package source");
        }
        if (request.after() == null && !Set.of(Action.CLOSE, Action.EXPIRATION, Action.ASSIGNMENT, Action.EXERCISE)
                .contains(request.action())) {
            throw new IllegalArgumentException(request.action() + " must describe the surviving position");
        }
        if (request.after() != null && sameComposition(request.before(), request.after())) {
            throw new IllegalArgumentException("the proposed action does not change the position");
        }

        StrategyCatalog.PositionIdentity beforeIdentity = StrategyCatalog.identify(request.before());
        StrategyCatalog.PositionIdentity afterIdentity = StrategyCatalog.identify(request.after());
        Obligations beforeObligations = obligations(request.before());
        Obligations afterObligations = obligations(request.after());
        List<String> warnings = new ArrayList<>();
        warnings.addAll(hedgeRemovalWarnings(request.before(), request.after()));

        if (request.action() == Action.PARTIAL_CLOSE && request.after() != null) {
            long removed = Math.max(0, request.before().packageQuantity() - request.after().packageQuantity());
            warnings.add("Partial close: " + removed + " of " + request.before().packageQuantity()
                    + " packages close; " + request.after().packageQuantity() + " survive as " + afterIdentity.label() + ".");
        }
        if (request.action() == Action.ROLL) {
            if (request.realizedClosingCents() == null) {
                throw new IllegalArgumentException("a roll preview requires the realized result of its closing leg");
            }
            warnings.add("This roll realizes " + Money.fmt(request.realizedClosingCents())
                    + " on the closing leg. The replacement is judged fresh-eyes as " + afterIdentity.label()
                    + "; the old loss is not carried forward or hidden.");
        }
        if ((request.action() == Action.ASSIGNMENT || request.action() == Action.EXERCISE)
                && request.after() != null && hasLongOption(request.after())) {
            warnings.add("The converted leg leaves another option in place. Its risk and expiration remain visible in the surviving "
                    + afterIdentity.label() + ".");
        }
        boolean recordedFact = request.before().lane() == PositionDomain.ExecutionLane.REAL;
        if (request.afterRisk() != null && !request.afterRisk().mechanicallyEligible()) {
            warnings.add(recordedFact
                    ? "The resulting position fails the Practice placement checks, but an exact broker-reported fact remains recordable. "
                            + String.join(" ", request.afterRisk().blockReasons())
                    : "Teaching case only: the resulting position is not eligible to apply. "
                            + String.join(" ", request.afterRisk().blockReasons()));
        }
        if (afterIdentity.blockedByDefault()) {
            warnings.add("The resulting " + afterIdentity.label() + (recordedFact
                    ? " is blocked as a Practice suggestion; an exact broker-reported fact remains recordable."
                    : " is a blocked-family teaching case, not an executable suggestion."));
        }

        Delta delta = new Delta(delta(request.beforeRisk().maxLossCents(), risk(request.afterRisk()).maxLossCents()),
                delta(request.beforeRisk().reserveCents(), risk(request.afterRisk()).reserveCents()),
                delta(beforeObligations.putAssignmentCashCents(), afterObligations.putAssignmentCashCents()),
                Math.subtractExact(afterObligations.callDeliveryShares(), beforeObligations.callDeliveryShares()));
        boolean identityChanged = !beforeIdentity.label().equals(afterIdentity.label())
                || request.after() == null || request.before().packageQuantity() != request.after().packageQuantity();
        boolean applicable = recordedFact || request.after() == null || request.afterRisk().mechanicallyEligible()
                && !afterIdentity.blockedByDefault();
        String fingerprint = fingerprint(request);
        return new Preview(request.action(), beforeIdentity, afterIdentity, request.beforeRisk(), request.afterRisk(),
                beforeObligations, afterObligations, delta, identityChanged, applicable,
                request.realizedClosingCents(), List.copyOf(warnings), fingerprint);
    }

    private static RiskSnapshot risk(RiskSnapshot value) {
        return value == null ? new RiskSnapshot(0L, 0L, null, true, List.of(), "cash") : value;
    }

    private static Long delta(Long before, Long after) {
        return before == null || after == null ? null : Math.subtractExact(after, before);
    }

    private static long delta(long before, long after) { return Math.subtractExact(after, before); }

    private static Obligations obligations(PositionPackage position) {
        if (position == null) return new Obligations(0, 0, 0, false, List.of());
        long putCash = 0, callShares = 0, shortStockShares = 0;
        Set<String> expirations = new LinkedHashSet<>();
        for (PositionPackage.Leg leg : position.legs()) {
            long units = Math.multiplyExact(leg.quantity(), (long) leg.multiplier());
            if (leg.expiration() != null) expirations.add(leg.expiration().toString());
            if (sell(leg) && put(leg)) putCash = Math.addExact(putCash, Money.centsFromPrice(leg.strike(), units));
            if (sell(leg) && call(leg)) callShares = Math.addExact(callShares, units);
            if (sell(leg) && stock(leg)) shortStockShares = Math.addExact(shortStockShares, units);
        }
        long coveredShares = position.legs().stream().filter(l -> buy(l) && stock(l))
                .mapToLong(l -> Math.multiplyExact(l.quantity(), (long) l.multiplier())).sum();
        long callSpreadCover = coveredCallUnits(position);
        boolean uncappedUpside = callShares > Math.addExact(coveredShares, callSpreadCover) || shortStockShares > 0;
        return new Obligations(putCash, callShares, shortStockShares, uncappedUpside,
                expirations.stream().sorted().toList());
    }

    private static long coveredCallUnits(PositionPackage position) {
        List<PositionPackage.Leg> longCalls = position.legs().stream().filter(l -> buy(l) && call(l))
                .sorted(Comparator.comparing(PositionPackage.Leg::expiration)
                        .thenComparing(PositionPackage.Leg::strike)).toList();
        long[] remaining = longCalls.stream().mapToLong(PositionTransformation::units).toArray();
        List<PositionPackage.Leg> shortCalls = position.legs().stream().filter(l -> sell(l) && call(l))
                .sorted(Comparator.comparing(PositionPackage.Leg::expiration)
                        .thenComparing(PositionPackage.Leg::strike, Comparator.reverseOrder())).toList();
        long covered = 0;
        for (PositionPackage.Leg shortCall : shortCalls) {
            long needed = units(shortCall);
            for (int i = 0; i < longCalls.size() && needed > 0; i++) {
                PositionPackage.Leg longCall = longCalls.get(i);
                if (remaining[i] == 0 || !longCall.expiration().equals(shortCall.expiration())
                        || longCall.strike().compareTo(shortCall.strike()) <= 0) continue;
                long matched = Math.min(needed, remaining[i]);
                needed = Math.subtractExact(needed, matched);
                remaining[i] = Math.subtractExact(remaining[i], matched);
                covered = Math.addExact(covered, matched);
            }
        }
        return covered;
    }

    private static List<String> hedgeRemovalWarnings(PositionPackage before, PositionPackage after) {
        if (after == null) return List.of();
        List<String> out = new ArrayList<>();
        for (PositionPackage.Leg hedge : before.legs()) {
            if (!buy(hedge) || stock(hedge)) continue;
            long removedHedgeUnits = Math.max(0, Math.subtractExact(units(hedge), contractUnits(after, hedge)));
            if (removedHedgeUnits == 0) continue;
            PositionPackage.Leg exposed = before.legs().stream().filter(shortLeg -> sell(shortLeg)
                            && shortLeg.optionType().equalsIgnoreCase(hedge.optionType())
                            && shortLeg.expiration().equals(hedge.expiration())
                            && (put(hedge) ? shortLeg.strike().compareTo(hedge.strike()) > 0
                            : shortLeg.strike().compareTo(hedge.strike()) < 0)
                            && contractUnits(after, shortLeg) > 0)
                    .findFirst().orElse(null);
            if (exposed == null) continue;
            long dollarsPerPoint = Math.min(removedHedgeUnits, contractUnits(after, exposed));
            String direction = put(hedge) ? "below " + hedge.strike().stripTrailingZeros().toPlainString()
                    : "above " + hedge.strike().stripTrailingZeros().toPlainString();
            out.add("This " + contract(hedge) + " protects the short " + contract(exposed)
                    + ". Removing it leaves about $" + dollarsPerPoint + " more loss per $1 move " + direction + ".");
        }
        long beforeStock = before.legs().stream().filter(l -> buy(l) && stock(l)).mapToLong(PositionTransformation::units).sum();
        long afterStock = after.legs().stream().filter(l -> buy(l) && stock(l)).mapToLong(PositionTransformation::units).sum();
        long afterShortCalls = after.legs().stream().filter(l -> sell(l) && call(l)).mapToLong(PositionTransformation::units).sum();
        if (afterStock < beforeStock && afterShortCalls > afterStock + coveredCallUnits(after)) {
            out.add("Removing these shares leaves part of the short-call obligation uncovered; losses can grow as the stock rises.");
        }
        return out;
    }

    private static long contractUnits(PositionPackage position, PositionPackage.Leg target) {
        return position.legs().stream().filter(candidate -> sameContract(candidate, target))
                .mapToLong(PositionTransformation::units).sum();
    }

    private static boolean sameContract(PositionPackage.Leg a, PositionPackage.Leg b) {
        return upper(a.action()).equals(upper(b.action()))
                && upper(a.instrumentType()).equals(upper(b.instrumentType()))
                && upper(a.optionType()).equals(upper(b.optionType()))
                && java.util.Objects.equals(a.expiration(), b.expiration())
                && (a.strike() == null ? b.strike() == null : b.strike() != null && a.strike().compareTo(b.strike()) == 0);
    }

    private static boolean sameComposition(PositionPackage before, PositionPackage after) {
        if (before.packageQuantity() != after.packageQuantity() || before.legs().size() != after.legs().size()) return false;
        List<String> a = before.legs().stream().map(PositionTransformation::legKey).sorted().toList();
        List<String> b = after.legs().stream().map(PositionTransformation::legKey).sorted().toList();
        return a.equals(b);
    }

    private static String legKey(PositionPackage.Leg leg) {
        return String.join("|", upper(leg.action()), upper(leg.instrumentType()), upper(leg.optionType()),
                leg.strike() == null ? "" : leg.strike().stripTrailingZeros().toPlainString(),
                leg.expiration() == null ? "" : leg.expiration().toString(), String.valueOf(leg.quantity()),
                String.valueOf(leg.multiplier()));
    }

    private static String fingerprint(Request request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Json.canonical(request).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot fingerprint transformation preview", e);
        }
    }

    private static String contract(PositionPackage.Leg leg) {
        return leg.strike().stripTrailingZeros().toPlainString() + " " + upper(leg.optionType()).toLowerCase(Locale.ROOT);
    }

    private static boolean hasLongOption(PositionPackage position) {
        return position.legs().stream().anyMatch(l -> buy(l) && !stock(l));
    }

    private static boolean stock(PositionPackage.Leg leg) { return "STOCK".equals(upper(leg.instrumentType())); }
    private static boolean call(PositionPackage.Leg leg) { return "CALL".equals(upper(leg.optionType())); }
    private static boolean put(PositionPackage.Leg leg) { return "PUT".equals(upper(leg.optionType())); }
    private static boolean buy(PositionPackage.Leg leg) { return "BUY".equals(upper(leg.action())); }
    private static boolean sell(PositionPackage.Leg leg) { return "SELL".equals(upper(leg.action())); }
    private static long units(PositionPackage.Leg leg) { return Math.multiplyExact(leg.quantity(), (long) leg.multiplier()); }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    public record Request(Action action, PositionPackage before, PositionPackage after,
                          RiskSnapshot beforeRisk, RiskSnapshot afterRisk, Long realizedClosingCents) {}

    public record RiskSnapshot(Long maxLossCents, long reserveCents, Long maxProfitCents,
                               boolean mechanicallyEligible, List<String> blockReasons, String evidenceBasis) {
        public RiskSnapshot {
            blockReasons = blockReasons == null ? List.of() : List.copyOf(blockReasons);
            if (reserveCents < 0) throw new IllegalArgumentException("reserve cannot be negative");
        }
    }

    public record Obligations(long putAssignmentCashCents, long callDeliveryShares,
                              long shortStockShares, boolean uncappedUpside,
                              List<String> expirations) {}

    public record Delta(Long maxLossCents, long reserveCents,
                        long putAssignmentCashCents, long callDeliveryShares) {}

    public record Preview(Action action,
                          StrategyCatalog.PositionIdentity beforeIdentity,
                          StrategyCatalog.PositionIdentity afterIdentity,
                          RiskSnapshot beforeRisk, RiskSnapshot afterRisk,
                          Obligations beforeObligations, Obligations afterObligations,
                          Delta delta, boolean identityChanged, boolean applicable,
                          Long realizedClosingCents, List<String> warnings,
                          String fingerprint) {}
}
