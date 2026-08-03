package io.liftandshift.strikebench.market;

/** The execution market selected for a request. Analysis datasets are a separate axis. */
public enum MarketMode {
    OBSERVED,
    DEMO,
    SIMULATED,
    SCENARIO;

    /** Resolve one complete market identity. The world and analysis dataset are both mandatory. */
    public static MarketMode of(String worldId, boolean fixturesOnly,
                                io.liftandshift.strikebench.db.AnalysisContext context) {
        if (worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("market world id is required");
        }
        if (context == null) throw new IllegalArgumentException("analysis context is required");
        if ("demo".equalsIgnoreCase(worldId)) return DEMO;
        if (!"observed".equalsIgnoreCase(worldId)) return SIMULATED;
        if (context.synthetic()) return SCENARIO;
        if (fixturesOnly) return DEMO;
        return OBSERVED;
    }

    /**
     * Normalizes a route-level world token to the service convention. Observed stays the explicit
     * {@code observed} identity; it is never encoded as null.
     */
    public static String worldParam(String world) {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("market world id is required");
        }
        return "observed".equalsIgnoreCase(world) ? "observed" : world;
    }

    /** True only for the explicit baseline market identity. */
    public static boolean isObservedWorld(String world) {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("market world id is required");
        }
        return "observed".equalsIgnoreCase(world);
    }

    /**
     * True when the world token names a live simulated exchange — i.e. NOT the OBSERVED or DEMO
     * replayable modes. THE one definition of the "this is a generated market" guard, previously
     * re-spelled as {@code !"observed".equals(x) && !"demo".equals(x)} in six places.
     */
    public static boolean isSimulatedWorld(String world) {
        return !isObservedWorld(world) && !"demo".equalsIgnoreCase(world);
    }
}
