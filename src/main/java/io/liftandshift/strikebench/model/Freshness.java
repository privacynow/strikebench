package io.liftandshift.strikebench.model;

/** How trustworthy/current a piece of market data is. Surfaced throughout the UI. */
public enum Freshness {
    REALTIME,   // live quote from an entitled feed
    DELAYED,    // typically 15-20 min delayed (must be labeled in UI)
    EOD,        // end-of-day/previous close data
    MODELED,    // derived from a model (e.g. theoretical price), not observed
    SIMULATED,  // a live SIMULATED MARKET world: dynamic, reproducible model data (never real)
    FIXTURE,    // deterministic built-in demo data
    STALE,      // cached data older than its freshness gate
    MISSING;    // no data available

    // SIMULATED sits BELOW every observed tier: a generated quote is coherent and tradable in
    // its own lane, but it must never roll up as more trustworthy than real (even delayed) data.
    private static final Freshness[] RANK = {REALTIME, DELAYED, EOD, STALE, SIMULATED, MODELED, FIXTURE, MISSING};

    /**
     * A LIVE observed tier — a real feed that a staleness gate may downgrade to STALE once it ages
     * past its window. Kept lock-step with {@code DataEvidence.executableIn}'s observed-age branch
     * (see FreshnessObservedLiveTest): adding a live tier must update both.
     */
    public boolean isObservedLive() {
        return this == REALTIME || this == DELAYED;
    }

    /** The less trustworthy of the two — for aggregating a position's overall freshness. */
    public static Freshness worse(Freshness a, Freshness b) {
        if (a == null) return b == null ? MISSING : b;
        if (b == null) return a;
        int ia = 0, ib = 0;
        for (int i = 0; i < RANK.length; i++) {
            if (RANK[i] == a) ia = i;
            if (RANK[i] == b) ib = i;
        }
        return ia >= ib ? a : b;
    }
}
