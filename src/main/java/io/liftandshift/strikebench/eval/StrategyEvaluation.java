package io.liftandshift.strikebench.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        EconomicAssessment economics,
        Explanation explanation
) {
    /** Quality within one economic tier: weighted factors after evidence/tail/DTE haircuts. */
    public double rankScore() { return score == null ? 0.0 : score.riskAdjustedScore(); }
    public boolean viable() { return score != null && score.gatePassed(); }
    public EconomicAssessment.Verdict economicVerdict() {
        return economics == null ? EconomicAssessment.Verdict.UNAVAILABLE : economics.verdict();
    }

    /**
     * The one 0-100 score whose numeric order exactly matches the product's decision order.
     * A failed mechanical gate is 0. Viable ideas then occupy non-overlapping economic bands:
     * unfavorable 1-25, unavailable 26-50, mixed 51-75, favorable 76-100. The risk/evidence
     * score orders ideas inside a band. Gaps keep rounded UI values monotonic across bands.
     */
    @JsonProperty("decisionScore")
    public double decisionScore() {
        if (!viable()) return 0.0;
        double withinTier = Math.max(0.0, Math.min(100.0, rankScore()));
        return round(1.0 + economicVerdict().rank() * 25.0 + withinTier * 0.24);
    }

    private static double round(double value) { return Math.round(value * 1000.0) / 1000.0; }

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
