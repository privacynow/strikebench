package io.liftandshift.model;

/** How trustworthy/current a piece of market data is. Surfaced throughout the UI. */
public enum Freshness {
    REALTIME,   // live quote from an entitled feed
    DELAYED,    // typically 15-20 min delayed (must be labeled in UI)
    EOD,        // end-of-day/previous close data
    MODELED,    // derived from a model (e.g. theoretical price), not observed
    FIXTURE,    // deterministic built-in demo data
    STALE,      // cached data older than its freshness gate
    MISSING;    // no data available

    /** True when data is fresh enough to base a new trade on (with warnings for DELAYED/EOD). */
    public boolean tradable() {
        return this == REALTIME || this == DELAYED || this == FIXTURE;
    }

    private static final Freshness[] RANK = {REALTIME, FIXTURE, DELAYED, EOD, MODELED, STALE, MISSING};

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
