package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.model.DataAge;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;

/**
 * How trustworthy the data behind an evaluation dimension is — the honesty backbone of the whole
 * product. Ordered worst-last so a portfolio of dimensions rolls up to its LEAST-certain member
 * (never let one observed number make a modeled recommendation look real). Generalizes the
 * evidence for a single quote to a whole evaluation.
 */
public enum EvidenceLevel {
    OBSERVED_LIVE(0, "Observed (live)"),
    OBSERVED_DELAYED(1, "Observed (delayed)"),
    OBSERVED_EOD(2, "Observed (end-of-day)"),
    // A real feed's book aged past its freshness gate (a closed market serving last-session
    // quotes is the normalized case). Still OBSERVED — advice runs on available data with the age
    // disclosed; only placement re-tests the live book. Never conflate with UNKNOWN: "old real
    // data" and "no data" are different facts.
    OBSERVED_STALE(3, "Observed (stale)"),
    MODELED(4, "Modeled"),
    SIMULATED(5, "Simulated market"),
    DEMO_FIXTURE(6, "Demo data"),
    UNKNOWN(7, "Unknown");

    private final int uncertainty;
    private final String label;

    EvidenceLevel(int uncertainty, String label) {
        this.uncertainty = uncertainty;
        this.label = label;
    }

    public int uncertainty() { return uncertainty; }
    public String label() { return label; }
    public boolean isObserved() { return uncertainty <= OBSERVED_STALE.uncertainty; }

    /** The least-certain (worst) of two levels — the rollup rule for a whole evaluation. */
    public EvidenceLevel worseOf(EvidenceLevel other) {
        return other != null && other.uncertainty > this.uncertainty ? other : this;
    }

    /** Maps the canonical origin and age facts onto an evaluation confidence tier. */
    public static EvidenceLevel fromEvidence(DataEvidence evidence) {
        if (evidence == null) return UNKNOWN;
        DataProvenance provenance = evidence.provenance();
        if (provenance == null || provenance == DataProvenance.MISSING
                || provenance == DataProvenance.MIXED) return UNKNOWN;
        return switch (provenance) {
            case DEMO -> DEMO_FIXTURE;
            case SIMULATED -> SIMULATED;
            case MODELED -> MODELED;
            case OBSERVED, BROKER -> switch (evidence.age() == null ? DataAge.MISSING : evidence.age()) {
                case REALTIME -> OBSERVED_LIVE;
                case DELAYED -> OBSERVED_DELAYED;
                case EOD -> OBSERVED_EOD;
                case STALE -> OBSERVED_STALE;
                case NOT_APPLICABLE, MISSING -> UNKNOWN;
            };
            case MIXED, MISSING -> UNKNOWN;
        };
    }
}
