package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.position.PositionPackage;
import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.model.Leg;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.OffsetDateTime;

/**
 * Server-owned product catalog. A family is the engine identity; a template is a concrete
 * construction of that family (for example BUY_WRITE constructs a COVERED_CALL by adding stock).
 * Presentation surfaces consume this registry and keep only their local leg-construction functions.
 */
public final class StrategyCatalog {
    private StrategyCatalog() {}

    public record FamilyEntry(
            String name,
            String display,
            String category,
            String summary,
            String payoffShape,
            String structureGroup,
            int foundationalRank,
            boolean definedRisk,
            boolean blockedByDefault,
            boolean multiExpiration,
            boolean needsStock,
            boolean scenarioEnabled,
            boolean backtestEnabled,
            boolean recommendationEnabled,
            String primaryIntent,
            Set<String> intents) {}

    public record TemplateEntry(
            String key,
            String family,
            String display,
            String category,
            String summary,
            String payoffShape,
            boolean blockedByDefault,
            boolean composite) {}

    /** Exact-leg identity from this catalog. It classifies structure only; it never prices or ranks. */
    public record PositionIdentity(String family, String template, String label, String summary,
                                   boolean definedRisk, boolean blockedByDefault, boolean custom) {}

    private record Copy(String family, String key, String display, String category,
                        String summary, String shape, boolean blocked, boolean composite) {}

    private static final Map<String, FamilyEntry> FAMILIES = buildFamilies();
    private static final List<TemplateEntry> TEMPLATES = buildTemplates();

    public static List<FamilyEntry> families() {
        return List.copyOf(FAMILIES.values());
    }

    public static List<TemplateEntry> templates() {
        return TEMPLATES;
    }

    public static FamilyEntry family(StrategyFamily family) {
        return FAMILIES.get(family.name());
    }

    public static boolean backtestEnabled(StrategyFamily family) {
        FamilyEntry entry = family(family);
        return entry != null && entry.backtestEnabled();
    }

    /** One server-owned classifier for the editor, transformations, receipts, and read models. */
    public static PositionIdentity identify(PositionPackage position) {
        if (position == null) return new PositionIdentity(null, null, "Cash / no position",
                "No open legs remain after this action.", true, false, true);
        List<PositionPackage.Leg> stocks = position.legs().stream().filter(StrategyCatalog::stock).toList();
        List<PositionPackage.Leg> options = position.legs().stream().filter(l -> !stock(l)).toList();

        if (stocks.size() == 1 && options.isEmpty()) {
            PositionPackage.Leg stock = stocks.getFirst();
            return customIdentity(buy(stock) ? "Long shares" : "Short shares",
                    buy(stock)
                            ? "The position now consists only of owned shares."
                            : "The position now consists only of short shares.", false);
        }
        if (stocks.size() == 1 && buy(stocks.getFirst())) {
            long shares = units(stocks.getFirst());
            if (options.size() == 1 && sell(options.getFirst()) && call(options.getFirst())
                    && shares == units(options.getFirst())) return identity(StrategyFamily.COVERED_CALL);
            if (options.size() == 1 && buy(options.getFirst()) && put(options.getFirst())
                    && shares == units(options.getFirst())) return identity(StrategyFamily.PROTECTIVE_PUT);
            if (options.size() == 2) {
                PositionPackage.Leg longPut = find(options, "BUY", "PUT");
                PositionPackage.Leg shortCall = find(options, "SELL", "CALL");
                if (longPut != null && shortCall != null
                        && shares == units(longPut) && shares == units(shortCall)) {
                    return identity(StrategyFamily.PROTECTIVE_COLLAR);
                }
                PositionPackage.Leg shortPut = find(options, "SELL", "PUT");
                if (shortPut != null && shortCall != null
                        && shares == units(shortCall) && units(shortPut) == units(shortCall)
                        && shortPut.strike().compareTo(shortCall.strike()) < 0) {
                    return identity(StrategyFamily.COVERED_STRANGLE);
                }
                PositionPackage.Leg overlayCall = find(options, "BUY", "CALL");
                if (overlayCall != null && shortCall != null
                        && shares == units(shortCall) && units(overlayCall) == units(shortCall)
                        && overlayCall.expiration().equals(shortCall.expiration())
                        && overlayCall.strike().compareTo(shortCall.strike()) > 0) {
                    return identity(StrategyFamily.COVERED_CALL_CALL_OVERLAY);
                }
            }
            if (options.size() == 3) {
                PositionPackage.Leg shortCall = find(options, "SELL", "CALL");
                List<PositionPackage.Leg> puts = options.stream().filter(StrategyCatalog::put).toList();
                if (shortCall != null && shares == units(shortCall) && puts.size() == 2
                        && puts.getFirst().expiration().equals(puts.getLast().expiration())) {
                    PositionPackage.Leg floorPut = find(puts, "BUY", "PUT");
                    PositionPackage.Leg fundingPut = find(puts, "SELL", "PUT");
                    if (floorPut != null && fundingPut != null
                            && units(floorPut) == shares && units(fundingPut) == shares
                            && floorPut.strike().compareTo(fundingPut.strike()) > 0) {
                        return identity(StrategyFamily.COVERED_CALL_PUT_SPREAD);
                    }
                }
            }
        }
        if (!stocks.isEmpty()) return customIdentity("Custom stock-and-option position",
                "The exact shares and option legs are analyzed without inventing a standard catalog name.", false);
        if (options.size() == 1) {
            PositionPackage.Leg leg = options.getFirst();
            if (buy(leg)) return identity(call(leg) ? StrategyFamily.LONG_CALL : StrategyFamily.LONG_PUT);
            if (call(leg)) return identity(StrategyFamily.NAKED_CALL);
            return new PositionIdentity(null, null, "Short put",
                    "Cash and protective context determine whether this is cash-secured or naked; the exact assessment names that distinction.",
                    true, false, true);
        }
        if (options.size() == 2) {
            PositionPackage.Leg a = options.get(0), b = options.get(1);
            boolean sameExpiration = a.expiration().equals(b.expiration());
            if (!sameType(a, b) && sameExpiration && units(a) == units(b)) {
                PositionPackage.Leg call = call(a) ? a : b;
                PositionPackage.Leg put = put(a) ? a : b;
                if (buy(call) && buy(put)) return identity(equal(call.strike(), put.strike())
                        ? StrategyFamily.LONG_STRADDLE : StrategyFamily.LONG_STRANGLE);
                if (sell(call) && sell(put)) return identity(equal(call.strike(), put.strike())
                        ? StrategyFamily.SHORT_STRADDLE : StrategyFamily.SHORT_STRANGLE);
                if (buy(call) && sell(put)) return templateIdentity(
                        equal(call.strike(), put.strike()) ? "SYNTHETIC_LONG" : "RISK_REVERSAL", true);
                if (sell(call) && buy(put) && equal(call.strike(), put.strike())) {
                    return templateIdentity("SYNTHETIC_SHORT", false);
                }
            }
            if (sameType(a, b) && !sameExpiration && units(a) == units(b)) {
                PositionPackage.Leg near = a.expiration().isBefore(b.expiration()) ? a : b;
                PositionPackage.Leg far = near == a ? b : a;
                if (sell(near) && buy(far)) {
                    boolean calendar = equal(near.strike(), far.strike());
                    if (call(a)) return identity(calendar ? StrategyFamily.CALENDAR_CALL : StrategyFamily.DIAGONAL_CALL);
                    return identity(calendar ? StrategyFamily.CALENDAR_PUT : StrategyFamily.DIAGONAL_PUT);
                }
            }
            if (sameType(a, b) && sameExpiration && buy(a) != buy(b)) {
                PositionPackage.Leg low = a.strike().compareTo(b.strike()) < 0 ? a : b;
                PositionPackage.Leg sold = sell(a) ? a : b;
                PositionPackage.Leg bought = sold == a ? b : a;
                if (units(bought) == Math.multiplyExact(units(sold), 2L)) {
                    if (call(a) && bought.strike().compareTo(sold.strike()) > 0) {
                        return templateIdentity("CALL_BACKSPREAD", true);
                    }
                    if (put(a) && bought.strike().compareTo(sold.strike()) < 0) {
                        return templateIdentity("PUT_BACKSPREAD", true);
                    }
                }
                if (units(a) == units(b)) {
                    if (call(a)) return identity(buy(low) ? StrategyFamily.DEBIT_CALL_SPREAD : StrategyFamily.CREDIT_CALL_SPREAD);
                    return identity(sell(low) ? StrategyFamily.DEBIT_PUT_SPREAD : StrategyFamily.CREDIT_PUT_SPREAD);
                }
            }
        }
        if (options.size() == 3 && sameTypeAndExpiration(options)) {
            List<PositionPackage.Leg> sorted = new ArrayList<>(options);
            sorted.sort(Comparator.comparing(PositionPackage.Leg::strike));
            if (buy(sorted.get(0)) && sell(sorted.get(1)) && buy(sorted.get(2))
                    && units(sorted.get(1)) == Math.multiplyExact(units(sorted.get(0)), 2L)
                    && units(sorted.get(2)) == units(sorted.get(0))) {
                return identity(call(sorted.getFirst())
                        ? StrategyFamily.LONG_CALL_BUTTERFLY : StrategyFamily.LONG_PUT_BUTTERFLY);
            }
        }
        if (options.size() == 4 && sameExpiration(options)) {
            List<PositionPackage.Leg> puts = options.stream().filter(StrategyCatalog::put)
                    .sorted(Comparator.comparing(PositionPackage.Leg::strike)).toList();
            List<PositionPackage.Leg> calls = options.stream().filter(StrategyCatalog::call)
                    .sorted(Comparator.comparing(PositionPackage.Leg::strike)).toList();
            if (puts.size() == 2 && calls.size() == 2 && buy(puts.get(0)) && sell(puts.get(1))
                    && sell(calls.get(0)) && buy(calls.get(1))
                    && options.stream().mapToLong(StrategyCatalog::units).distinct().count() == 1) {
                return identity(equal(puts.get(1).strike(), calls.get(0).strike())
                        ? StrategyFamily.IRON_BUTTERFLY : StrategyFamily.IRON_CONDOR);
            }
        }
        return customIdentity("Custom structure",
                "The exact legs still receive the same payoff, risk, and outcomes analysis; no catalog name is being invented.", false);
    }

    /** Adapter from the platform's existing exact-leg model into the shared package contract. */
    public static PositionIdentity identify(String symbol, int packageQuantity, List<Leg> legs) {
        if (packageQuantity < 1 || legs == null || legs.isEmpty()) {
            throw new IllegalArgumentException("position identity requires a positive quantity and exact legs");
        }
        List<PositionPackage.Leg> packageLegs = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            packageLegs.add(new PositionPackage.Leg(i, leg.action().name(),
                    leg.isStock() ? "STOCK" : "OPTION", symbol,
                    leg.isStock() ? null : leg.type().name(), leg.strike(), leg.expiration(),
                    Math.multiplyExact(packageQuantity, (long) leg.ratio()), leg.multiplier(),
                    leg.entryPrice(), PositionDomain.PriceAuthority.MODELED));
        }
        return identify(new PositionPackage("catalog-identify", PositionDomain.PackageSource.HYPOTHETICAL_DRAFT,
                PositionDomain.ExecutionLane.NONE, symbol, packageQuantity, null,
                OffsetDateTime.parse("1970-01-01T00:00:00Z"), packageLegs));
    }

    private static PositionIdentity identity(StrategyFamily family) {
        FamilyEntry meta = family(family);
        return new PositionIdentity(family.name(), null, meta.display(), meta.summary(),
                meta.definedRisk(), meta.blockedByDefault(), false);
    }

    private static PositionIdentity templateIdentity(String key, boolean definedRisk) {
        TemplateEntry meta = TEMPLATES.stream().filter(template -> key.equals(template.key()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing catalog template " + key));
        return new PositionIdentity(null, key, meta.display(), meta.summary(), definedRisk,
                meta.blockedByDefault(), true);
    }

    private static PositionIdentity customIdentity(String label, String summary, boolean definedRisk) {
        return new PositionIdentity(null, null, label, summary, definedRisk, false, true);
    }

    private static PositionPackage.Leg find(List<PositionPackage.Leg> legs, String action, String type) {
        return legs.stream().filter(l -> action.equals(upper(l.action())) && type.equals(upper(l.optionType())))
                .findFirst().orElse(null);
    }

    private static boolean sameType(PositionPackage.Leg a, PositionPackage.Leg b) {
        return upper(a.optionType()).equals(upper(b.optionType()));
    }

    private static boolean sameTypeAndExpiration(List<PositionPackage.Leg> legs) {
        return sameExpiration(legs) && legs.stream().allMatch(l -> sameType(l, legs.getFirst()));
    }

    private static boolean sameExpiration(List<PositionPackage.Leg> legs) {
        return legs.stream().allMatch(l -> l.expiration().equals(legs.getFirst().expiration()));
    }

    private static boolean stock(PositionPackage.Leg leg) { return "STOCK".equals(upper(leg.instrumentType())); }
    private static boolean call(PositionPackage.Leg leg) { return "CALL".equals(upper(leg.optionType())); }
    private static boolean put(PositionPackage.Leg leg) { return "PUT".equals(upper(leg.optionType())); }
    private static boolean buy(PositionPackage.Leg leg) { return "BUY".equals(upper(leg.action())); }
    private static boolean sell(PositionPackage.Leg leg) { return "SELL".equals(upper(leg.action())); }
    private static long units(PositionPackage.Leg leg) { return Math.multiplyExact(leg.quantity(), (long) leg.multiplier()); }
    private static boolean equal(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) == 0; }
    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static Map<String, FamilyEntry> buildFamilies() {
        var out = new LinkedHashMap<String, FamilyEntry>();
        add(out, StrategyFamily.LONG_CALL, "Bullish",
                "Stock up big - pay a premium, keep uncapped upside, and limit loss to the premium.",
                "2,20 34,20 62,4", true, true);
        add(out, StrategyFamily.DEBIT_CALL_SPREAD, "Bullish",
                "Stock up some - cheaper than a call because the far upside is sold away.",
                "2,20 22,20 44,8 62,8", true, true);
        add(out, StrategyFamily.CREDIT_PUT_SPREAD, "Bullish",
                "Stock not down - collect a credit while price stays above the short strike.",
                "2,22 20,22 42,8 62,8", true, true);
        add(out, StrategyFamily.CASH_SECURED_PUT, "Bullish",
                "Get paid while waiting to buy shares at a price you chose.",
                "2,26 30,8 62,8", true, true);

        add(out, StrategyFamily.LONG_PUT, "Bearish",
                "Stock down big - pay a premium and gain as it falls, with loss capped at the premium.",
                "2,4 30,20 62,20", true, true);
        add(out, StrategyFamily.DEBIT_PUT_SPREAD, "Bearish",
                "Stock down some - cheaper than a put because the far downside is sold away.",
                "2,8 20,8 42,20 62,20", true, true);
        add(out, StrategyFamily.CREDIT_CALL_SPREAD, "Bearish",
                "Stock not up - collect a credit while price stays below the short strike.",
                "2,8 22,8 44,22 62,22", true, true);

        add(out, StrategyFamily.IRON_CONDOR, "Income & range",
                "Price stays in a range - sell both sides while protective wings cap either loss.",
                "2,22 14,22 24,8 40,8 50,22 62,22", true, true);
        add(out, StrategyFamily.IRON_BUTTERFLY, "Income & range",
                "Price pins near one level - richer credit than a condor, with a narrower sweet spot.",
                "2,22 18,22 32,6 46,22 62,22", true, true);
        add(out, StrategyFamily.CALENDAR_CALL, "Income & time",
                "Sell a near call and own a farther call so the nearer option decays first.",
                "2,22 20,18 32,8 44,18 62,22", true, false);
        add(out, StrategyFamily.CALENDAR_PUT, "Income & time",
                "The put-side calendar: own farther time while selling faster near-term decay.",
                "2,22 20,18 32,8 44,18 62,22", false, false);
        add(out, StrategyFamily.DIAGONAL_CALL, "Income & time",
                "Own a farther call and repeatedly rent out nearer calls at a different strike.",
                "2,24 22,16 36,8 50,16 62,20", true, false);
        add(out, StrategyFamily.DIAGONAL_PUT, "Income & time",
                "The put-side diagonal: a farther put anchors nearer premium sales.",
                "2,20 16,16 30,8 46,16 62,24", false, false);

        add(out, StrategyFamily.LONG_STRADDLE, "Big moves",
                "Buy a call and put at the same strike when direction is unknown but a large move is expected.",
                "2,4 32,24 62,4", true, false);
        add(out, StrategyFamily.LONG_STRANGLE, "Big moves",
                "Buy both sides out of the money - cheaper than a straddle but needs a larger move.",
                "2,6 22,24 42,24 62,6", true, false);

        add(out, StrategyFamily.LONG_CALL_BUTTERFLY, "Pinpoint targets",
                "A low-cost call structure that pays most near one target price.",
                "2,22 18,22 32,4 46,22 62,22", true, true);
        add(out, StrategyFamily.LONG_PUT_BUTTERFLY, "Pinpoint targets",
                "The same pinpoint payoff built with puts.",
                "2,22 18,22 32,4 46,22 62,22", true, true);

        add(out, StrategyFamily.COVERED_CALL, "Shares & income",
                "Own 100 shares and rent out their upside for premium at a chosen sale price.",
                "2,26 34,8 62,8", true, true);
        add(out, StrategyFamily.COVERED_STRANGLE, "Shares & income",
                "A covered call plus a cash-secured put: double premium, and a standing bid to buy more shares below.",
                "2,28 22,15 42,7 62,7", true, false);
        add(out, StrategyFamily.COVERED_CALL_PUT_SPREAD, "Shares & income",
                "A covered call whose premium helps buy a put spread: a protected shelf under the shares down to the lower put strike.",
                "2,26 14,18 28,18 48,8 62,8", true, false);
        add(out, StrategyFamily.COVERED_CALL_CALL_OVERLAY, "Shares & income",
                "A covered call plus a farther long call: income now, and upside participation resumes above the overlay strike.",
                "2,26 30,11 44,11 62,4", true, false);
        add(out, StrategyFamily.PROTECTIVE_PUT, "Shares & protection",
                "Own shares plus a put that creates an insurance floor.",
                "2,14 26,14 62,2", true, true);
        add(out, StrategyFamily.PROTECTIVE_COLLAR, "Shares & protection",
                "Add a floor below and a ceiling above; the sold call helps pay for protection.",
                "2,18 20,18 44,8 62,8", true, true);

        add(out, StrategyFamily.NAKED_CALL, "Undefined risk (blocked)",
                "A sold call with nothing behind it - losses can grow without limit.",
                "2,8 34,8 62,26", false, false);
        add(out, StrategyFamily.NAKED_PUT, "Undefined risk (blocked)",
                "A short put without the cash needed for assignment; shown to explain why it is refused.",
                "2,26 30,8 62,8", false, false);
        add(out, StrategyFamily.SHORT_STRADDLE, "Undefined risk (blocked)",
                "Sell both at the money for premium with uncapped upside risk.",
                "2,24 32,4 62,24", false, false);
        add(out, StrategyFamily.SHORT_STRANGLE, "Undefined risk (blocked)",
                "Sell both sides out of the money, retaining the same uncapped-risk problem.",
                "2,24 22,6 42,6 62,24", false, false);

        if (out.size() != StrategyFamily.values().length) {
            throw new IllegalStateException("Every StrategyFamily must have catalog metadata");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }

    private static void add(Map<String, FamilyEntry> out, StrategyFamily family, String category,
                            String summary, String shape, boolean scenario, boolean backtest) {
        out.put(family.name(), new FamilyEntry(
                family.name(), family.display(), category, summary, shape, family.structureGroup(),
                family.riskRank(), family.definedRisk(), family.blockedByDefault(), family.multiExpiration(),
                family.needsStock(), scenario, backtest, !family.blockedByDefault(),
                family.primaryIntent().name(),
                family.intents().stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet())));
    }

    private static List<TemplateEntry> buildTemplates() {
        var specs = List.of(
                copy("LONG_CALL"), copy("DEBIT_CALL_SPREAD"), copy("CREDIT_PUT_SPREAD"), copy("CASH_SECURED_PUT"),
                alias("COVERED_CALL", "BUY_WRITE", "Covered call (buy-write)", "Shares & income",
                        "Buy 100 shares and rent them out immediately - premium now, capped upside."),
                copy("COVERED_STRANGLE"), copy("COVERED_CALL_PUT_SPREAD"), copy("COVERED_CALL_CALL_OVERLAY"),
                custom("RISK_REVERSAL", "Risk reversal", "Bullish",
                        "Sell a put to help pay for a call - bullish exposure with a large downside reserve.",
                        "2,26 22,14 42,14 62,4", false),
                copy("LONG_PUT"), copy("DEBIT_PUT_SPREAD"), copy("CREDIT_CALL_SPREAD"),
                copy("IRON_CONDOR"), copy("IRON_BUTTERFLY"), copy("CALENDAR_CALL"), copy("CALENDAR_PUT"),
                copy("DIAGONAL_CALL"), copy("DIAGONAL_PUT"), copy("LONG_STRADDLE"), copy("LONG_STRANGLE"),
                custom("CALL_BACKSPREAD", "Call ratio backspread", "Big moves",
                        "Sell one call and buy two farther up - strongest on a violent rally.",
                        "2,16 20,16 34,24 62,2", false),
                custom("PUT_BACKSPREAD", "Put ratio backspread", "Big moves",
                        "Sell one put and buy two farther down - strongest in a sharp selloff.",
                        "2,2 30,24 44,16 62,16", false),
                copy("LONG_CALL_BUTTERFLY"), copy("LONG_PUT_BUTTERFLY"),
                alias("PROTECTIVE_PUT", "MARRIED_PUT", "Married put (protective put)", "Shares & protection",
                        "Buy shares with a put insurance floor."),
                alias("PROTECTIVE_COLLAR", "COLLAR", "Protective collar", "Shares & protection",
                        "Buy shares, add a put floor, and sell a call ceiling to offset cost."),
                alias("DIAGONAL_CALL", "PMCC", "Poor man's covered call", "Income & time",
                        "Use a deep farther-dated call in place of shares, then sell nearer calls against it."),
                custom("SYNTHETIC_LONG", "Synthetic long (stock replacement)", "Shares & exposure",
                        "A long call plus short put at one strike approximates 100 shares with margin risk.",
                        "2,26 62,4", false),
                custom("SYNTHETIC_SHORT", "Synthetic short", "Undefined risk (blocked)",
                        "A long put plus uncovered short call approximates short stock and has unlimited rally risk.",
                        "2,4 62,26", true),
                copy("SHORT_STRADDLE"), copy("SHORT_STRANGLE"), copy("NAKED_CALL"), copy("NAKED_PUT")
        );
        var out = new ArrayList<TemplateEntry>();
        for (Copy spec : specs) {
            FamilyEntry family = spec.family() == null ? null : FAMILIES.get(spec.family());
            out.add(new TemplateEntry(
                    spec.key(), spec.family() == null ? "CUSTOM" : spec.family(),
                    spec.display() != null ? spec.display() : family.display(),
                    spec.category() != null ? spec.category() : family.category(),
                    spec.summary() != null ? spec.summary() : family.summary(),
                    spec.shape() != null ? spec.shape() : family.payoffShape(),
                    spec.blocked() || family != null && family.blockedByDefault(), spec.composite()));
        }
        return List.copyOf(out);
    }

    private static Copy copy(String family) {
        return new Copy(family, family, null, null, null, null, false, false);
    }

    private static Copy alias(String family, String key, String display, String category, String summary) {
        return new Copy(family, key, display, category, summary, null, false, true);
    }

    private static Copy custom(String key, String display, String category, String summary,
                               String shape, boolean blocked) {
        return new Copy(null, key, display, category, summary, shape, blocked, true);
    }
}
