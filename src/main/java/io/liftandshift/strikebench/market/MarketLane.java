package io.liftandshift.strikebench.market;

/** The execution market selected for a request. Analysis datasets are a separate axis. */
public enum MarketLane {
    OBSERVED,
    DEMO,
    SIMULATED,
    SCENARIO;

    public static MarketLane of(String worldId, boolean fixturesOnly) {
        if ("demo".equalsIgnoreCase(worldId)) return DEMO;
        if (worldId != null && !worldId.isBlank() && !"observed".equalsIgnoreCase(worldId)) return SIMULATED;
        if (fixturesOnly) return DEMO;
        return OBSERVED;
    }

    public static MarketLane of(String worldId, boolean fixturesOnly,
                                io.liftandshift.strikebench.db.AnalysisContext context) {
        MarketLane market = of(worldId, fixturesOnly);
        return (market == OBSERVED || market == DEMO) && context != null && context.synthetic()
                ? SCENARIO : market;
    }
}
