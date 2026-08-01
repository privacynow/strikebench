package io.liftandshift.strikebench.strategy;

import java.util.Set;

import static io.liftandshift.strikebench.strategy.StrategyIntent.ACQUIRE;
import static io.liftandshift.strikebench.strategy.StrategyIntent.DIRECTIONAL;
import static io.liftandshift.strikebench.strategy.StrategyIntent.EXIT;
import static io.liftandshift.strikebench.strategy.StrategyIntent.HEDGE;
import static io.liftandshift.strikebench.strategy.StrategyIntent.INCOME;

/**
 * The strategy catalog. riskRank is a foundational-ordering hint for education surfaces:
 * 1 = foundational through 4 = advanced, 99 = blocked-by-default undefined risk. It never gates
 * the catalog by experience level or capital-risk mode; safety gates and the decision policy own that.
 * intents() answers WHY someone trades the family (see StrategyIntent); the first is primary.
 */
public enum StrategyFamily {

    LONG_CALL("Long call", Set.of(Thesis.BULLISH, Thesis.VOLATILE), true, false, false, false, 1,
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    LONG_PUT("Long put", Set.of(Thesis.BEARISH, Thesis.VOLATILE), true, false, false, false, 1,
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    LONG_STRADDLE("Long straddle", Set.of(Thesis.VOLATILE), true, false, false, false, 2,
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    LONG_STRANGLE("Long strangle", Set.of(Thesis.VOLATILE), true, false, false, false, 2,
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    DEBIT_CALL_SPREAD("Bull call (debit) spread", Set.of(Thesis.BULLISH), true, false, false, false, 1,
            DIRECTIONAL, Set.of(DIRECTIONAL, ACQUIRE)),
    DEBIT_PUT_SPREAD("Bear put (debit) spread", Set.of(Thesis.BEARISH), true, false, false, false, 1,
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    CREDIT_CALL_SPREAD("Bear call (credit) spread", Set.of(Thesis.BEARISH, Thesis.NEUTRAL), true, false, false, false, 2,
            INCOME, Set.of(INCOME, DIRECTIONAL)),
    CREDIT_PUT_SPREAD("Bull put (credit) spread", Set.of(Thesis.BULLISH, Thesis.NEUTRAL), true, false, false, false, 2,
            INCOME, Set.of(INCOME, DIRECTIONAL, ACQUIRE)),
    IRON_CONDOR("Iron condor", Set.of(Thesis.NEUTRAL), true, false, false, false, 2,
            INCOME, Set.of(INCOME)),
    IRON_BUTTERFLY("Iron butterfly", Set.of(Thesis.NEUTRAL), true, false, false, false, 3,
            INCOME, Set.of(INCOME)),
    LONG_CALL_BUTTERFLY("Long call butterfly", Set.of(Thesis.NEUTRAL), true, false, false, false, 3,
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    LONG_PUT_BUTTERFLY("Long put butterfly", Set.of(Thesis.NEUTRAL), true, false, false, false, 3,
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    CALENDAR_CALL("Call calendar spread", Set.of(Thesis.NEUTRAL), true, false, false, true, 3,
            INCOME, Set.of(INCOME, DIRECTIONAL)),
    CALENDAR_PUT("Put calendar spread", Set.of(Thesis.NEUTRAL), true, false, false, true, 3,
            INCOME, Set.of(INCOME, DIRECTIONAL, ACQUIRE)),
    // Diagonals are one normalized structure with two legitimate uses: directional exposure and a
    // defined-risk premium-selling campaign (PMCC/PMCP). Entry debit is not the same fact as carry;
    // the existing objective-coherence evaluator still decides whether the exact package actually
    // can be studied as a managed Income campaign; modeled theta is never treated as cash earned.
    DIAGONAL_CALL("Call diagonal spread", Set.of(Thesis.BULLISH), true, false, false, true, 4,
            INCOME, Set.of(INCOME, DIRECTIONAL)),
    DIAGONAL_PUT("Put diagonal spread", Set.of(Thesis.BEARISH), true, false, false, true, 4,
            INCOME, Set.of(INCOME, DIRECTIONAL)),
    COVERED_CALL("Covered call", Set.of(Thesis.NEUTRAL, Thesis.BULLISH), true, false, true, false, 1,
            INCOME, Set.of(INCOME, EXIT)),
    // A true covered put is short stock plus a short put. "Covered" describes the put's
    // assignment deliverable; it does not cap the short stock's rally loss. The catalog teaches
    // the family, while recommendation and execution remain blocked until authoritative borrow,
    // margin, dividend-liability, recall, and signed short-inventory results exist.
    COVERED_PUT("Covered put", Set.of(Thesis.NEUTRAL, Thesis.BEARISH), false, true,
            StockRequirement.SHORT, false, 99, INCOME, Set.of(INCOME)),
    CASH_SECURED_PUT("Cash-secured put", Set.of(Thesis.NEUTRAL, Thesis.BULLISH), true, false, false, false, 2,
            INCOME, Set.of(INCOME, ACQUIRE)),
    PROTECTIVE_COLLAR("Protective collar", Set.of(Thesis.NEUTRAL, Thesis.BULLISH), true, false, true, false, 2,
            HEDGE, Set.of(HEDGE, EXIT)),
    PROTECTIVE_PUT("Protective put", Set.of(Thesis.BULLISH, Thesis.NEUTRAL), true, false, true, false, 1,
            HEDGE, Set.of(HEDGE)),

    // Composite share-backed structures (folded Phase 9). Defined risk in the bounded sense:
    // worst case is fully quantified up front (shares plus a second assignment), never open-ended.
    COVERED_STRANGLE("Covered strangle", Set.of(Thesis.NEUTRAL, Thesis.BULLISH), true, false, true, false, 3,
            INCOME, Set.of(INCOME, ACQUIRE)),
    COVERED_CALL_PUT_SPREAD("Covered call with put-spread floor", Set.of(Thesis.NEUTRAL, Thesis.BULLISH), true, false, true, false, 3,
            INCOME, Set.of(INCOME, HEDGE)),
    COVERED_CALL_CALL_OVERLAY("Covered call with long-call overlay", Set.of(Thesis.BULLISH, Thesis.NEUTRAL), true, false, true, false, 3,
            INCOME, Set.of(INCOME, DIRECTIONAL)),

    // Undefined risk — blocked by default, never auto-recommended
    NAKED_CALL("Naked (uncovered) short call", Set.of(Thesis.BEARISH, Thesis.NEUTRAL), false, true, false, false, 99,
            INCOME, Set.of(INCOME)),
    NAKED_PUT("Naked short put (unsecured)", Set.of(Thesis.BULLISH, Thesis.NEUTRAL), false, true, false, false, 99,
            INCOME, Set.of(INCOME, ACQUIRE)),
    SHORT_STRADDLE("Short straddle", Set.of(Thesis.NEUTRAL), false, true, false, false, 99,
            INCOME, Set.of(INCOME)),
    SHORT_STRANGLE("Short strangle", Set.of(Thesis.NEUTRAL), false, true, false, false, 99,
            INCOME, Set.of(INCOME));

    public enum Thesis { BULLISH, BEARISH, NEUTRAL, VOLATILE }

    private final String display;
    private final Set<Thesis> thesisFits;
    private final boolean definedRisk;
    private final boolean blockedByDefault;
    public enum StockRequirement { NONE, LONG, SHORT }

    private final StockRequirement stockRequirement;
    private final boolean multiExpiration;
    private final int riskRank;
    private final StrategyIntent primaryIntent;
    private final Set<StrategyIntent> intents;

    StrategyFamily(String display, Set<Thesis> thesisFits, boolean definedRisk, boolean blockedByDefault,
                   boolean needsStock, boolean multiExpiration, int riskRank,
                   StrategyIntent primaryIntent, Set<StrategyIntent> intents) {
        this(display, thesisFits, definedRisk, blockedByDefault,
                needsStock ? StockRequirement.LONG : StockRequirement.NONE,
                multiExpiration, riskRank, primaryIntent, intents);
    }

    StrategyFamily(String display, Set<Thesis> thesisFits, boolean definedRisk, boolean blockedByDefault,
                   StockRequirement stockRequirement, boolean multiExpiration, int riskRank,
                   StrategyIntent primaryIntent, Set<StrategyIntent> intents) {
        this.display = display;
        this.thesisFits = thesisFits;
        this.definedRisk = definedRisk;
        this.blockedByDefault = blockedByDefault;
        this.stockRequirement = stockRequirement;
        this.multiExpiration = multiExpiration;
        this.riskRank = riskRank;
        this.primaryIntent = primaryIntent;
        this.intents = intents;
    }

    public String display() { return display; }
    public boolean fits(Thesis t) { return thesisFits.contains(t); }
    public boolean definedRisk() { return definedRisk; }
    public boolean blockedByDefault() { return blockedByDefault; }
    public StockRequirement stockRequirement() { return stockRequirement; }
    public boolean requiresLongStock() { return stockRequirement == StockRequirement.LONG; }
    public boolean requiresShortStock() { return stockRequirement == StockRequirement.SHORT; }
    public boolean multiExpiration() { return multiExpiration; }
    public int riskRank() { return riskRank; }
    public StrategyIntent primaryIntent() { return primaryIntent; }
    public Set<StrategyIntent> intents() { return intents; }
    public boolean servesIntent(StrategyIntent intent) { return intents.contains(intent); }

    /**
     * Normalized reason an automatically blocked family cannot become a proposed trade. Keeping
     * this beside the family metadata prevents Recommendation, Learn, and New Idea from inventing
     * different explanations for the same safety boundary.
     */
    public String automaticBlockReason() {
        if (this == COVERED_PUT) {
            return "Covered put requires 100 short shares and has unlimited rally risk. "
                    + "Authoritative borrow availability, borrow rate, margin, dividend liability, "
                    + "recall, and short-inventory evidence are unavailable; use the defined-risk "
                    + "poor man's covered put (put diagonal) for an actionable comparison.";
        }
        return display + " has undefined risk and is blocked by default; it remains available for "
                + "payoff education, while automatic ideas use a defined-risk alternative.";
    }

    /**
     * Structural shape group — explicit metadata for PRESENTATION diversity (grouping similar
     * shapes when a result list is summarized). Never used to gate or re-rank the engine's
     * score-ordered truth. The exhaustive switch (no default) makes the compiler force every
     * new family to declare its group.
     */
    public String structureGroup() {
        return switch (this) {
            case CALENDAR_CALL, CALENDAR_PUT, DIAGONAL_CALL, DIAGONAL_PUT -> "time";
            case IRON_BUTTERFLY -> "pin_credit";
            case LONG_CALL_BUTTERFLY, LONG_PUT_BUTTERFLY -> "butterfly";
            case IRON_CONDOR -> "range_credit";
            case CREDIT_CALL_SPREAD, CREDIT_PUT_SPREAD -> "credit_vertical";
            case DEBIT_CALL_SPREAD, DEBIT_PUT_SPREAD -> "debit_vertical";
            case LONG_CALL, LONG_PUT -> "single_long";
            case LONG_STRADDLE, LONG_STRANGLE -> "long_volatility";
            case COVERED_CALL -> "covered_income";
            case COVERED_PUT -> "short_stock_income";
            case COVERED_STRANGLE, COVERED_CALL_PUT_SPREAD, COVERED_CALL_CALL_OVERLAY -> "covered_composite";
            case CASH_SECURED_PUT -> "acquisition_income";
            case PROTECTIVE_COLLAR, PROTECTIVE_PUT -> "stock_protection";
            case NAKED_CALL, NAKED_PUT -> "uncovered_directional";
            case SHORT_STRADDLE, SHORT_STRANGLE -> "short_volatility";
        };
    }
}
