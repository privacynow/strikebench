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

    /**
     * Normalizes a route-level world token to the service-layer convention: {@code null} is the
     * OBSERVED baseline, any other id names a simulated world. THE one definition, so a controller
     * cannot re-spell (and drift) this sentinel — it was copied byte-for-byte across eight of them.
     */
    public static String worldParam(String world) {
        return world == null || "observed".equals(world) ? null : world;
    }

    /**
     * True when the world token names a live simulated exchange — i.e. NOT the OBSERVED or DEMO
     * replayable lanes. THE one definition of the "this is a generated market" guard, previously
     * re-spelled as {@code !"observed".equals(x) && !"demo".equals(x)} in six places.
     */
    public static boolean isSimulatedWorld(String world) {
        return !"observed".equals(world) && !"demo".equals(world);
    }

    public static MarketLane of(String worldId, boolean fixturesOnly,
                                io.liftandshift.strikebench.db.AnalysisContext context) {
        MarketLane market = of(worldId, fixturesOnly);
        return (market == OBSERVED || market == DEMO) && context != null && context.synthetic()
                ? SCENARIO : market;
    }
}
