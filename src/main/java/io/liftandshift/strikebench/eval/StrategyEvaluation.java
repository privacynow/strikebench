package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;

/**
 * The unified, scored, evidence-labeled recommendation record — the object the whole product ranks,
 * persists, explains, and (in Phase 3) competes against its alternatives. It composes the concrete
 * {@link Candidate} with the producer-owned sub-profiles; the convenience accessors expose the
 * typed fields the {@code strategy_evaluation} table and the ranking sort read.
 */
public record StrategyEvaluation(
        String id,
        StrategySpec spec,
        Candidate candidate,
        CapitalProfile capital,
        VolatilityProfile volatility,
        RiskProfile risk,
        EvidenceProfile evidence,
        ManagementPlan management,
        ScoreBreakdown score,
        Explanation explanation
) {
    /** The final rank value (risk-adjusted, 0 when the gate fails). Never stands alone in the UI. */
    public double rankScore() { return score == null ? 0.0 : score.riskAdjustedScore(); }
    public boolean viable() { return score != null && score.gatePassed(); }

    public EvidenceLevel evidenceLevel() { return evidence == null ? EvidenceLevel.UNKNOWN : evidence.rollup(); }
    public Long evCents() { return risk == null ? null : risk.expectedValueCents(); }
    public Double pop() { return risk == null ? null : risk.pop(); }
    public Long maxLossCents() { return risk == null ? null : risk.maxLossCents(); }
    public long tailLossCents() { return risk == null ? 0 : risk.tailLossCents(); }
    public Double roc() { return capital == null ? null : capital.returnOnCapitalPct(); }
    public Double annRoc() { return capital == null ? null : capital.annualizedRocPct(); }
    public Long capitalIncrementalCents() { return capital == null ? null : capital.incrementalCents(); }
    public Long capitalEconomicCents() { return capital == null ? null : capital.economicCents(); }
    public Double assignmentProb() { return candidate == null ? null : candidate.assignmentProb(); }
    public String symbol() { return spec == null ? null : spec.symbol(); }
    public String family() { return spec == null ? (candidate == null ? null : candidate.strategy()) : spec.family(); }
}
