package io.liftandshift.strikebench.eval;

/**
 * How trustworthy the data behind an evaluation dimension is — the honesty backbone of the whole
 * product. Ordered worst-last so a portfolio of dimensions rolls up to its LEAST-certain member
 * (never let one observed number make a modeled recommendation look real). Generalizes the
 * existing {@code Freshness} enum from a single quote to a whole evaluation.
 */
public enum EvidenceLevel {
    OBSERVED_LIVE(0, "Observed (live)"),
    OBSERVED_DELAYED(1, "Observed (delayed)"),
    OBSERVED_EOD(2, "Observed (end-of-day)"),
    MODELED(3, "Modeled"),
    SIMULATED(4, "Simulated market"),
    DEMO_FIXTURE(5, "Demo data"),
    UNKNOWN(6, "Unknown");

    private final int uncertainty;
    private final String label;

    EvidenceLevel(int uncertainty, String label) {
        this.uncertainty = uncertainty;
        this.label = label;
    }

    public int uncertainty() { return uncertainty; }
    public String label() { return label; }
    public boolean isObserved() { return uncertainty <= OBSERVED_EOD.uncertainty; }

    /** The least-certain (worst) of two levels — the rollup rule for a whole evaluation. */
    public EvidenceLevel worseOf(EvidenceLevel other) {
        return other != null && other.uncertainty > this.uncertainty ? other : this;
    }

    /** Maps a {@code Freshness} name (as carried on candidates/quotes) to an evidence level. */
    public static EvidenceLevel fromFreshness(String freshness) {
        if (freshness == null) return UNKNOWN;
        return switch (freshness.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "REALTIME" -> OBSERVED_LIVE;
            case "DELAYED" -> OBSERVED_DELAYED;
            case "EOD" -> OBSERVED_EOD;
            case "MODELED" -> MODELED;
            case "SIMULATED" -> SIMULATED; // a generated market: honest, coherent, never observed
            case "FIXTURE" -> DEMO_FIXTURE;
            case "STALE", "MISSING" -> UNKNOWN;
            default -> UNKNOWN;
        };
    }
}
