package io.liftandshift.strikebench.strategy;

import java.util.Set;

import static io.liftandshift.strikebench.strategy.StrategyIntent.ACQUIRE;
import static io.liftandshift.strikebench.strategy.StrategyIntent.DIRECTIONAL;
import static io.liftandshift.strikebench.strategy.StrategyIntent.EXIT;
import static io.liftandshift.strikebench.strategy.StrategyIntent.HEDGE;
import static io.liftandshift.strikebench.strategy.StrategyIntent.INCOME;

/**
 * The strategy catalog. riskRank gates recommendation by risk mode:
 * 1 = learning, 2 = conservative, 3 = balanced, 4 = aggressive, 99 = never recommended
 * (undefined-risk families are blocked by default everywhere and shown only as rejected examples).
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
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    DEBIT_PUT_SPREAD("Bear put (debit) spread", Set.of(Thesis.BEARISH), true, false, false, false, 1,
            DIRECTIONAL, Set.of(DIRECTIONAL)),
    CREDIT_CALL_SPREAD("Bear call (credit) spread", Set.of(Thesis.BEARISH, Thesis.NEUTRAL), true, false, false, false, 2,
            INCOME, Set.of(INCOME, DIRECTIONAL)),
    CREDIT_PUT_SPREAD("Bull put (credit) spread", Set.of(Thesis.BULLISH, Thesis.NEUTRAL), true, false, false, false, 2,
            INCOME, Set.of(INCOME, DIRECTIONAL)),
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
            INCOME, Set.of(INCOME, DIRECTIONAL)),
    DIAGONAL_CALL("Call diagonal spread", Set.of(Thesis.BULLISH), true, false, false, true, 4,
            INCOME, Set.of(INCOME, DIRECTIONAL)),
    DIAGONAL_PUT("Put diagonal spread", Set.of(Thesis.BEARISH), true, false, false, true, 4,
            INCOME, Set.of(INCOME, DIRECTIONAL)),
    COVERED_CALL("Covered call", Set.of(Thesis.NEUTRAL, Thesis.BULLISH), true, false, true, false, 1,
            INCOME, Set.of(INCOME, EXIT)),
    CASH_SECURED_PUT("Cash-secured put", Set.of(Thesis.NEUTRAL, Thesis.BULLISH), true, false, false, false, 2,
            INCOME, Set.of(INCOME, ACQUIRE)),
    PROTECTIVE_COLLAR("Protective collar", Set.of(Thesis.NEUTRAL, Thesis.BULLISH), true, false, true, false, 2,
            HEDGE, Set.of(HEDGE, EXIT)),
    PROTECTIVE_PUT("Protective put", Set.of(Thesis.BULLISH, Thesis.NEUTRAL), true, false, true, false, 1,
            HEDGE, Set.of(HEDGE)),

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
    private final boolean needsStock;
    private final boolean multiExpiration;
    private final int riskRank;
    private final StrategyIntent primaryIntent;
    private final Set<StrategyIntent> intents;

    StrategyFamily(String display, Set<Thesis> thesisFits, boolean definedRisk, boolean blockedByDefault,
                   boolean needsStock, boolean multiExpiration, int riskRank,
                   StrategyIntent primaryIntent, Set<StrategyIntent> intents) {
        this.display = display;
        this.thesisFits = thesisFits;
        this.definedRisk = definedRisk;
        this.blockedByDefault = blockedByDefault;
        this.needsStock = needsStock;
        this.multiExpiration = multiExpiration;
        this.riskRank = riskRank;
        this.primaryIntent = primaryIntent;
        this.intents = intents;
    }

    public String display() { return display; }
    public boolean fits(Thesis t) { return thesisFits.contains(t); }
    public boolean definedRisk() { return definedRisk; }
    public boolean blockedByDefault() { return blockedByDefault; }
    public boolean needsStock() { return needsStock; }
    public boolean multiExpiration() { return multiExpiration; }
    public int riskRank() { return riskRank; }
    public StrategyIntent primaryIntent() { return primaryIntent; }
    public Set<StrategyIntent> intents() { return intents; }
    public boolean servesIntent(StrategyIntent intent) { return intents.contains(intent); }
}
