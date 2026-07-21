package io.liftandshift.strikebench.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.liftandshift.strikebench.position.ParticipationProfile;
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
        FourOutputAssessment assessment,
        StanceVector stance,
        ParticipationProfile participation,
        ImpliedStance impliedStance,
        IvContext ivContext,
        DataCoverageReceipt coverage,
        Explanation explanation
) {
    /** Quality within one economic tier: weighted factors after evidence/tail/DTE haircuts. */
    public double rankScore() { return score == null ? 0.0 : score.riskAdjustedScore(); }
    public boolean viable() { return score != null && score.gatePassed(); }
    public EconomicAssessment.Verdict economicVerdict() {
        return assessment == null || assessment.economics() == null
                ? EconomicAssessment.Verdict.UNAVAILABLE : assessment.economics().verdict();
    }

    /**
     * The one 0-100 score whose numeric order exactly matches the product's decision order.
     * A failed mechanical gate is 0. Viable ideas then occupy non-overlapping economic bands:
     * unfavorable 1-25, unavailable 26-50, mixed 51-75, favorable 76-100. The risk/evidence
     * score orders ideas inside a band, with a bounded objective-coherence tilt when the user
     * declared a view. Coherence never changes the economic verdict or crosses an economic band:
     * an adverse but coherent package cannot outrank a favorable alternative. Gaps keep rounded
     * UI values monotonic across bands.
     */
    @JsonProperty("decisionScore")
    public double decisionScore() {
        if (!viable()) return 0.0;
        double withinTier = Math.max(0.0, Math.min(100.0, rankScore())) * objectiveFitMultiplier();
        return round(1.0 + economicVerdict().rank() * 25.0 + withinTier * 0.24);
    }

    /**
     * A declared objective is a ranking tilt, not a catalog gate. Undeclared assessments retain
     * their original score; a coherent expression keeps full credit; mixed/unavailable/incoherent
     * expressions receive progressively bounded haircuts within the same economic tier.
     */
    private double objectiveFitMultiplier() {
        if (assessment == null || assessment.coherence() == null) return 1.0;
        return switch (assessment.coherence().verdict()) {
            case UNDECLARED, COHERENT -> 1.0;
            case MIXED -> 0.90;
            case UNAVAILABLE -> 0.80;
            case INCOHERENT -> 0.65;
        };
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
    /** The exact candidate owns its family; a competition-level spec may describe the first
     * candidate and must never relabel every other package in the field as that family. */
    public String family() {
        if (candidate != null && candidate.strategy() != null && !candidate.strategy().isBlank()) {
            return candidate.strategy();
        }
        return spec == null ? null : spec.family();
    }
}
