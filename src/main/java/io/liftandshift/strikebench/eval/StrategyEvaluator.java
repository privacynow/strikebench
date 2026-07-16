package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.util.Ids;

import java.util.Comparator;
import java.util.List;

/**
 * The Phase-2 backbone: assembles a {@link StrategyEvaluation} for a candidate by running every
 * producer against one shared {@link EvalContext}, and ranks a set of alternatives for the
 * recommendations-as-a-competition view. Pure and deterministic — no I/O, no clock — so it is
 * trivially testable; the live context is assembled by the caller.
 */
public final class StrategyEvaluator {

    static final Comparator<StrategyEvaluation> RANKING =
            Comparator.comparingDouble(StrategyEvaluation::decisionScore).reversed();

    private final CapitalProfiler capital = new CapitalProfiler();
    private final VolatilityProfiler volatility = new VolatilityProfiler();
    private final RiskProfiler risk = new RiskProfiler();
    private final EvidenceAssembler evidence = new EvidenceAssembler();
    private final ManagementPlanner management = new ManagementPlanner();
    private final ScoreComposer score = new ScoreComposer();
    private final Explainer explainer = new Explainer();

    public StrategyEvaluation evaluate(Candidate c, StrategySpec spec, EvalContext ctx) {
        CapitalProfile cap = capital.profile(c, ctx);
        VolatilityProfile vol = volatility.profile(ctx);
        RiskProfile rsk = risk.profile(c, ctx);
        EvidenceProfile ev = evidence.assemble(c, ctx);
        ManagementPlan plan = management.plan(c, spec);
        ScoreBreakdown sb = score.compose(c, cap, rsk, ev, ctx);
        EconomicAssessment economics = EconomicAssessment.assess(c, rsk, ev, sb, ctx);
        Explanation exp = explainer.explain(c, spec, cap, vol, rsk, ev, ctx);
        return new StrategyEvaluation(Ids.newId("eval"), spec, c, cap, vol, rsk, ev, plan, sb, economics, exp);
    }

    /**
     * Complete decision assessment for one exact ticket. The same producers used for proposals
     * remain authoritative; the executable ticket preview contributes the final mechanical gate.
     */
    public StrategyEvaluation assessExact(Candidate c, StrategySpec spec, EvalContext ctx,
                                          boolean mechanicallyEligible,
                                          List<String> mechanicalFailures, long roundTripFeesCents) {
        CapitalProfile cap = capital.profile(c, ctx);
        VolatilityProfile vol = volatility.profile(ctx);
        RiskProfile rsk = risk.profile(c, ctx);
        EvidenceProfile ev = evidence.assemble(c, ctx);
        ManagementPlan plan = management.plan(c, spec);
        ScoreBreakdown rankedScore = score.compose(c, cap, rsk, ev, ctx);
        java.util.LinkedHashSet<String> failures = new java.util.LinkedHashSet<>(rankedScore.gateFailures());
        if (mechanicalFailures != null) failures.addAll(mechanicalFailures);
        boolean exactGate = mechanicallyEligible && rankedScore.gatePassed() && failures.isEmpty();
        ScoreBreakdown exactScore = new ScoreBreakdown(exactGate, List.copyOf(failures),
                rankedScore.normalizedScore(), exactGate ? rankedScore.riskAdjustedScore() : 0,
                rankedScore.components());
        EconomicAssessment economics = EconomicAssessment.assessExact(c, rsk, ev, ctx, exactGate,
                List.copyOf(failures), roundTripFeesCents);
        Explanation exp = explainer.explain(c, spec, cap, vol, rsk, ev, ctx);
        return new StrategyEvaluation(Ids.newId("eval"), spec, c, cap, vol, rsk, ev, plan,
                exactScore, economics, exp);
    }

    /**
     * Evaluates a set of alternatives and ranks them for the competition: viable (gate-passing)
     * first, then by economic tier, then by risk/evidence quality inside that tier. The monotonic
     * Decision score encodes that exact order; it still travels with its full breakdown, evidence,
     * economics and management plan.
     */
    public List<StrategyEvaluation> evaluateAndRank(List<Candidate> candidates, StrategySpec spec, EvalContext ctx) {
        return candidates.stream()
                .map(c -> evaluate(c, spec, ctx))
                .sorted(RANKING)
                .toList();
    }
}
