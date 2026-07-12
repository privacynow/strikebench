package io.liftandshift.strikebench.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
