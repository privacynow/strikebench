package io.liftandshift.strikebench.paper;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.position.AuthorityFacts;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.position.PositionLifecycleReceipt;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only lifecycle projections over copies of canonical tracked lots. Lot matching and basis
 * allocation mirror tracked accounting; collateral and every risk block are delegated back to
 * {@link PortfolioAccountingService} and {@link BookRiskService}. No projection writes a row.
 */
public final class BookActionProjectionService {
    public static final String SCHEMA_VERSION = "book-action-projection-v1";

    private final PortfolioAccountingService books;
    private final BookRiskService risk;
    private final Clock clock;

    public BookActionProjectionService(PortfolioAccountingService books, BookRiskService risk, Clock clock) {
        this.books = books;
        this.risk = risk;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public ProjectionSet project(String ownerId, String accountId, TradeService.OpenRequest position,
                                 PositionLifecycleReceipt lifecycle) {
        if (position == null || lifecycle == null || position.qty() < 1
                || position.legs() == null || position.legs().isEmpty()) {
            throw new IllegalArgumentException("book projections need an exact held package and lifecycle receipt");
        }
        return project(ownerId, accountId, position, lifecycle, books.summary(ownerId, accountId));
    }

    public ProjectionSet project(String ownerId, String accountId, TradeService.OpenRequest position,
                                 PositionLifecycleReceipt lifecycle,
                                 PortfolioAccountingService.PortfolioSummary currentSummary) {
        if (lifecycle == null || currentSummary == null) {
            throw new IllegalArgumentException("lifecycle and current account summary are required");
        }
        return project(ownerId, accountId, position, lifecycle.currentChoice().close(),
                lifecycle.positionFingerprint(), currentSummary);
    }

    ProjectionSet project(String ownerId, String accountId, TradeService.OpenRequest position,
                          PositionLifecycleReceipt.CloseQuote close, String positionFingerprint) {
        return project(ownerId, accountId, position, close, positionFingerprint,
                books.summary(ownerId, accountId));
    }

    private ProjectionSet project(String ownerId, String accountId, TradeService.OpenRequest position,
                                  PositionLifecycleReceipt.CloseQuote close, String positionFingerprint,
                                  PortfolioAccountingService.PortfolioSummary summary) {
        if (position == null || close == null || position.qty() < 1
                || position.legs() == null || position.legs().isEmpty()) {
            throw new IllegalArgumentException("book projections need an exact held package and close receipt");
        }
        List<PortfolioAccountingService.LotView> original = books.lots(ownerId, accountId, false);
        List<ActionProjection> actions = new ArrayList<>();
        actions.add(snapshot(ownerId, accountId, "HOLD", 0, position.qty(), original,
                summary.bookCashCents(), BasisEffect.none(), ExecutableCost.none(), true, null,
                List.of(new ActionStep("HOLD", "AVAILABLE", "The recorded lots remain unchanged."))));

        ActionProjection closeAll = null;
        for (int quantity = 1; quantity <= position.qty(); quantity++) {
            String action = quantity == position.qty() ? "CLOSE_ALL"
                    : quantity == 1 ? "CLOSE_ONE" : "CLOSE_K";
            ActionProjection projected = closeProjection(ownerId, accountId, action, quantity,
                    summary.bookCashCents(), original, position, close);
            actions.add(projected);
            if (quantity == position.qty()) closeAll = projected;
        }

        if (hasShort(position, OptionType.PUT)) {
            actions.add(conversionProjection(ownerId, accountId, "ASSIGNMENT", OptionType.PUT,
                    summary.bookCashCents(), original, position));
        }
        if (hasShort(position, OptionType.CALL)) {
            actions.add(conversionProjection(ownerId, accountId, "CALL_AWAY", OptionType.CALL,
                    summary.bookCashCents(), original, position));
        }
        if (closeAll != null) {
            actions.add(new ActionProjection("ROLL", position.qty(), 0, false,
                    "A roll is two decisions. The close step is priced; an exact replacement package is required before the open step can be projected.",
                    closeAll.snapshot(), closeAll.basisEffect(), closeAll.executableCost(),
                    List.of(new ActionStep("CLOSE_EXISTING", closeAll.available() ? "AVAILABLE" : "UNAVAILABLE",
                                    closeAll.available() ? "Uses the executable close receipt." : closeAll.unavailableReason()),
                            new ActionStep("OPEN_REPLACEMENT", "UNAVAILABLE",
                                    "No exact replacement contracts, executable opening price, or opening fees were supplied.")),
                    fingerprint("ROLL", position, closeAll.snapshot())));
        }
        OffsetDateTime observedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return new ProjectionSet(SCHEMA_VERSION, accountId,
                positionFingerprint, observedAt, List.copyOf(actions),
                "Hypothetical copies of tracked lots only. Existing accounting owns lot/basis arithmetic; "
                        + "existing BookRiskService owns Greeks, theme, expiry, and stress; existing executable "
                        + "close evidence owns costs. Broker margin and tax-return effects remain unavailable.");
    }

    private ActionProjection closeProjection(String ownerId, String accountId, String action, int quantity,
                                             long currentCash, List<PortfolioAccountingService.LotView> original,
                                             TradeService.OpenRequest position,
                                             PositionLifecycleReceipt.CloseQuote close) {
        if (!close.executable()) {
            return unavailable(action, quantity, position.qty() - quantity, close.unavailableReason(), position);
        }
        Mutation mutation = reducePackage(original, position, quantity, false);
        if (!mutation.available()) {
            return unavailable(action, quantity, position.qty() - quantity, mutation.unavailableReason(), position);
        }
        long signedCash = proportional(close.signedExecutableCloseCashCents(), quantity, position.qty());
        long signedOptionCash = proportional(close.signedOptionExecutableCloseCashCents(), quantity, position.qty());
        long fees = proportional(close.closingFeesCents(), quantity, position.qty());
        long signedNet = Math.subtractExact(signedCash, fees);
        long cashAfter = Math.addExact(currentCash, signedNet);
        BasisEffect basis = new BasisEffect(mutation.taxBasisRemovedCents(),
                mutation.economicBasisRemovedCents(), 0L, 0L,
                "Recorded opening basis allocated pro rata from the exact lots that would close. "
                        + "Wash-sale and final tax character are computed only if an action is recorded.");
        ExecutableCost cost = new ExecutableCost(signedCash, signedOptionCash, fees, signedNet,
                close.priceAuthority(), close.basis());
        return snapshot(ownerId, accountId, action, quantity, position.qty() - quantity,
                mutation.lots(), cashAfter, basis, cost, true, null,
                List.of(new ActionStep("CLOSE_EXISTING", "AVAILABLE",
                        "Executable closing sides and closing fees are applied to " + quantity + " package(s).")));
    }

    private ActionProjection conversionProjection(String ownerId, String accountId, String action,
                                                  OptionType type, long currentCash,
                                                  List<PortfolioAccountingService.LotView> original,
                                                  TradeService.OpenRequest position) {
        Mutation optionRemoval = reducePackage(original, position, position.qty(), true, type);
        if (!optionRemoval.available()) {
            return unavailable(action, position.qty(), 0, optionRemoval.unavailableReason(), position);
        }
        long strikeCash = shortStrikeCash(position, type);
        List<PortfolioAccountingService.LotView> lots = new ArrayList<>(optionRemoval.lots());
        long cashAfter;
        long stockBasisAdded = 0;
        long stockBasisRemoved = 0;
        String basisNote;
        if (type == OptionType.PUT) {
            cashAfter = Math.subtractExact(currentCash, strikeCash);
            long shares = shortDeliverableShares(position, type);
            stockBasisAdded = Math.max(0, Math.subtractExact(strikeCash,
                    optionRemoval.taxBasisRemovedCents()));
            lots.add(syntheticLongStock(position.symbol(), shares, stockBasisAdded,
                    Math.max(0, Math.subtractExact(strikeCash,
                            optionRemoval.economicBasisRemovedCents()))));
            basisNote = "Modeled put assignment transfers recorded short-put proceeds into long-stock basis; "
                    + "the canonical accounting owner remains authoritative if recorded.";
        } else {
            StockReduction shares = reduceLongShares(lots, position.symbol(),
                    shortDeliverableShares(position, type));
            if (!shares.available()) {
                return unavailable(action, position.qty(), 0, shares.unavailableReason(), position);
            }
            lots = new ArrayList<>(shares.lots());
            cashAfter = Math.addExact(currentCash, strikeCash);
            stockBasisRemoved = shares.taxBasisRemovedCents();
            basisNote = "Modeled call-away removes recorded long shares and their allocated basis; final realized "
                    + "gain, holding term, wash-sale, and tax character remain accounting-time facts.";
        }
        BasisEffect basis = new BasisEffect(optionRemoval.taxBasisRemovedCents(),
                optionRemoval.economicBasisRemovedCents(), stockBasisAdded, stockBasisRemoved, basisNote);
        ExecutableCost cost = new ExecutableCost(type == OptionType.PUT ? -strikeCash : strikeCash,
                0L, 0L, type == OptionType.PUT ? -strikeCash : strikeCash,
                PositionDomain.PriceAuthority.MODELED,
                "Strike settlement only. Broker assignment/exercise fees and cash-vs-physical treatment are unavailable.");
        return snapshot(ownerId, accountId, action, position.qty(), 0, lots, cashAfter,
                basis, cost, true, null,
                List.of(new ActionStep(action, "MODELED",
                        "Physical conversion at contractual strike; broker-specific settlement remains disclosed.")));
    }

    private ActionProjection snapshot(String ownerId, String accountId, String action,
                                      int quantityAffected, int quantityRemaining,
                                      List<PortfolioAccountingService.LotView> lots, long cash,
                                      BasisEffect basis, ExecutableCost cost, boolean available,
                                      String unavailableReason, List<ActionStep> steps) {
        var collateral = books.projectCollateral(lots, cash);
        var bookRisk = risk.projectAccount(ownerId, accountId, lots, cash);
        BookSnapshot snapshot = new BookSnapshot(
                new AuthorityFacts.SignedMoneyFact(cash, PositionDomain.FactAuthority.MODEL_DERIVED,
                        "Recorded tracked cash plus the modeled action cash effect; not broker buying power."),
                new AuthorityFacts.MoneyFact(collateral.knownBlockedCashCents(),
                        PositionDomain.FactAuthority.MODEL_DERIVED,
                        "Canonical tracked-lot collateral pairing; not broker margin."),
                new AuthorityFacts.MoneyFact(collateral.cashSecuredPutObligationCents(),
                        PositionDomain.FactAuthority.MODEL_DERIVED,
                        "Gross unpaired short-put strike obligation after the hypothetical action."),
                shareQuantities(lots), collateral, bookRisk);
        return new ActionProjection(action, quantityAffected, quantityRemaining, available,
                unavailableReason, snapshot, basis, cost, List.copyOf(steps),
                fingerprint(action + ":" + quantityAffected, snapshot, basis, cost));
    }

    private static ActionProjection unavailable(String action, int quantityAffected,
                                                int quantityRemaining, String reason,
                                                TradeService.OpenRequest position) {
        return new ActionProjection(action, quantityAffected, quantityRemaining, false, reason,
                null, BasisEffect.none(), ExecutableCost.unavailable(reason), List.of(),
                fingerprint(action + ":unavailable", position.symbol(), quantityAffected, reason));
    }

    private static Mutation reducePackage(List<PortfolioAccountingService.LotView> original,
                                          TradeService.OpenRequest position, int packageQuantity,
                                          boolean shortOnly) {
        return reducePackage(original, position, packageQuantity, shortOnly, null);
    }

    private static Mutation reducePackage(List<PortfolioAccountingService.LotView> original,
                                          TradeService.OpenRequest position, int packageQuantity,
                                          boolean shortOnly, OptionType onlyType) {
        Map<String, Long> needed = new LinkedHashMap<>();
        for (Leg leg : position.legs()) {
            if (shortOnly && leg.action() != LegAction.SELL) continue;
            if (onlyType != null && (leg.isStock() || leg.type() != onlyType)) continue;
            needed.merge(key(position.symbol(), leg),
                    Math.multiplyExact((long) leg.ratio(), packageQuantity), Math::addExact);
        }
        if (needed.isEmpty()) return new Mutation(false, List.of(), 0, 0,
                "The exact package has no matching legs for this action.");
        List<PortfolioAccountingService.LotView> out = new ArrayList<>();
        long taxRemoved = 0, economicRemoved = 0;
        for (var lot : original) {
            long demand = needed.getOrDefault(key(lot), 0L);
            if (demand == 0) {
                out.add(lot);
                continue;
            }
            long take = Math.min(demand, lot.remainingQuantity());
            long taxPart = allocate(lot.remainingOpenAmountCents(), take, lot.remainingQuantity());
            long economicPart = allocate(lot.economicRemainingOpenAmountCents(), take, lot.remainingQuantity());
            taxRemoved = Math.addExact(taxRemoved, taxPart);
            economicRemoved = Math.addExact(economicRemoved, economicPart);
            needed.put(key(lot), Math.subtractExact(demand, take));
            long remaining = Math.subtractExact(lot.remainingQuantity(), take);
            if (remaining > 0) {
                out.add(withRemaining(lot, remaining,
                        Math.subtractExact(lot.remainingOpenAmountCents(), taxPart),
                        Math.subtractExact(lot.economicRemainingOpenAmountCents(), economicPart)));
            }
        }
        List<String> missing = needed.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> e.getValue() + " unit(s) of " + e.getKey()).toList();
        return missing.isEmpty()
                ? new Mutation(true, List.copyOf(out), taxRemoved, economicRemoved, null)
                : new Mutation(false, List.of(), 0, 0,
                "Recorded lots do not contain the exact package quantity: " + String.join(", ", missing));
    }

    private static StockReduction reduceLongShares(List<PortfolioAccountingService.LotView> original,
                                                   String symbol, long sharesNeeded) {
        long remainingNeed = sharesNeeded;
        long taxRemoved = 0, economicRemoved = 0;
        List<PortfolioAccountingService.LotView> out = new ArrayList<>();
        for (var lot : original) {
            if (remainingNeed == 0 || !"STOCK".equals(lot.instrumentType())
                    || !"LONG".equals(lot.side()) || !symbol.equalsIgnoreCase(lot.symbol())) {
                out.add(lot);
                continue;
            }
            long availableShares = Math.multiplyExact(lot.remainingQuantity(), (long) lot.multiplier());
            long takeShares = Math.min(remainingNeed, availableShares);
            if (takeShares % lot.multiplier() != 0) {
                return new StockReduction(false, List.of(), 0, 0,
                        "Recorded adjusted-share lots cannot satisfy the call deliverable exactly.");
            }
            long take = takeShares / lot.multiplier();
            long taxPart = allocate(lot.remainingOpenAmountCents(), take, lot.remainingQuantity());
            long economicPart = allocate(lot.economicRemainingOpenAmountCents(), take, lot.remainingQuantity());
            taxRemoved = Math.addExact(taxRemoved, taxPart);
            economicRemoved = Math.addExact(economicRemoved, economicPart);
            remainingNeed = Math.subtractExact(remainingNeed, takeShares);
            long remaining = Math.subtractExact(lot.remainingQuantity(), take);
            if (remaining > 0) out.add(withRemaining(lot, remaining,
                    Math.subtractExact(lot.remainingOpenAmountCents(), taxPart),
                    Math.subtractExact(lot.economicRemainingOpenAmountCents(), economicPart)));
        }
        return remainingNeed == 0
                ? new StockReduction(true, List.copyOf(out), taxRemoved, economicRemoved, null)
                : new StockReduction(false, List.of(), 0, 0,
                "Call-away needs " + sharesNeeded + " recorded long shares; " + remainingNeed + " are unavailable.");
    }

    private static PortfolioAccountingService.LotView withRemaining(
            PortfolioAccountingService.LotView lot, long remaining,
            long remainingTax, long remainingEconomic) {
        return new PortfolioAccountingService.LotView(lot.id(), lot.instrumentType(), lot.side(),
                lot.symbol(), lot.optionType(), lot.strike(), lot.expiration(), lot.multiplier(),
                lot.openedAt(), lot.originalQuantity(), remaining, lot.originalOpenAmountCents(),
                remainingTax, "OPEN", lot.section1256(), lot.economicOriginalOpenAmountCents(),
                remainingEconomic);
    }

    private static PortfolioAccountingService.LotView syntheticLongStock(String symbol, long shares,
                                                                         long taxBasis,
                                                                         long economicBasis) {
        return new PortfolioAccountingService.LotView("projection-assignment-stock", "STOCK", "LONG",
                symbol.toUpperCase(Locale.ROOT), null, null, null, 1,
                "1970-01-01T00:00:00Z", shares, shares, taxBasis, taxBasis,
                "OPEN", false, economicBasis, economicBasis);
    }

    private static Map<String, Long> shareQuantities(List<PortfolioAccountingService.LotView> lots) {
        Map<String, Long> shares = new LinkedHashMap<>();
        for (var lot : lots) {
            if (!"STOCK".equals(lot.instrumentType())) continue;
            long quantity = Math.multiplyExact(lot.remainingQuantity(), (long) lot.multiplier());
            shares.merge(lot.symbol(), "SHORT".equals(lot.side()) ? -quantity : quantity, Math::addExact);
        }
        return Map.copyOf(shares);
    }

    private static boolean hasShort(TradeService.OpenRequest position, OptionType type) {
        return position.legs().stream().anyMatch(leg -> !leg.isStock()
                && leg.action() == LegAction.SELL && leg.type() == type);
    }

    private static long shortStrikeCash(TradeService.OpenRequest position, OptionType type) {
        long cash = 0;
        for (Leg leg : position.legs()) {
            if (leg.isStock() || leg.action() != LegAction.SELL || leg.type() != type) continue;
            long units = Math.multiplyExact(Math.multiplyExact((long) leg.ratio(), position.qty()),
                    leg.multiplier());
            cash = Math.addExact(cash, Money.centsFromPrice(leg.strike(), units));
        }
        return cash;
    }

    private static long shortDeliverableShares(TradeService.OpenRequest position, OptionType type) {
        long shares = 0;
        for (Leg leg : position.legs()) {
            if (leg.isStock() || leg.action() != LegAction.SELL || leg.type() != type) continue;
            shares = Math.addExact(shares, Math.multiplyExact(
                    Math.multiplyExact((long) leg.ratio(), position.qty()), leg.multiplier()));
        }
        return shares;
    }

    private static String key(String symbol, Leg leg) {
        return String.join("|", leg.isStock() ? "STOCK" : "OPTION",
                leg.action() == LegAction.BUY ? "LONG" : "SHORT", symbol.toUpperCase(Locale.ROOT),
                leg.isStock() ? "" : leg.type().name(), decimal(leg.strike()),
                leg.expiration() == null ? "" : leg.expiration().toString(), String.valueOf(leg.multiplier()));
    }

    private static String key(PortfolioAccountingService.LotView lot) {
        return String.join("|", lot.instrumentType(), lot.side(), lot.symbol().toUpperCase(Locale.ROOT),
                lot.optionType() == null ? "" : lot.optionType(), decimal(lot.strike()),
                lot.expiration() == null ? "" : lot.expiration().toString(), String.valueOf(lot.multiplier()));
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static long allocate(long amount, long qty, long totalQty) {
        if (qty == totalQty) return amount;
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(qty))
                .divide(BigDecimal.valueOf(totalQty), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long proportional(long amount, long qty, long totalQty) {
        return allocate(amount, qty, totalQty);
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    Json.canonical(java.util.Arrays.asList(values)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            throw new IllegalStateException("cannot fingerprint book action projection: "
                    + root.getClass().getSimpleName() + ": " + root.getMessage(), e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectionSet(String schemaVersion, String accountId, String positionFingerprint,
                                OffsetDateTime observedAt, List<ActionProjection> actions,
                                String basis) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionProjection(String action, int quantityAffected, int quantityRemaining,
                                   boolean available, String unavailableReason,
                                   BookSnapshot snapshot, BasisEffect basisEffect,
                                   ExecutableCost executableCost, List<ActionStep> steps,
                                   String fingerprint) {}

    public record BookSnapshot(AuthorityFacts.SignedMoneyFact cash,
                               AuthorityFacts.MoneyFact encumbrance,
                               AuthorityFacts.MoneyFact shortPutObligation,
                               Map<String, Long> sharesBySymbol,
                               PortfolioAccountingService.CollateralView collateral,
                               BookRiskService.AccountRisk risk) {}

    public record BasisEffect(long optionTaxBasisRemovedCents,
                              long optionEconomicBasisRemovedCents,
                              long stockTaxBasisAddedCents,
                              long stockTaxBasisRemovedCents,
                              String basis) {
        static BasisEffect none() {
            return new BasisEffect(0, 0, 0, 0, "No lot or basis change.");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecutableCost(Long signedCashCents, Long signedOptionCashCents,
                                 Long feesCents, Long signedNetCashCents,
                                 PositionDomain.PriceAuthority authority, String basis) {
        static ExecutableCost none() {
            return new ExecutableCost(0L, 0L, 0L, 0L,
                    PositionDomain.PriceAuthority.MODELED, "No transaction; no executable cost.");
        }

        static ExecutableCost unavailable(String reason) {
            return new ExecutableCost(null, null, null, null, null, reason);
        }
    }

    public record ActionStep(String action, String status, String basis) {}

    private record Mutation(boolean available, List<PortfolioAccountingService.LotView> lots,
                            long taxBasisRemovedCents, long economicBasisRemovedCents,
                            String unavailableReason) {}

    private record StockReduction(boolean available, List<PortfolioAccountingService.LotView> lots,
                                  long taxBasisRemovedCents, long economicBasisRemovedCents,
                                  String unavailableReason) {}
}
