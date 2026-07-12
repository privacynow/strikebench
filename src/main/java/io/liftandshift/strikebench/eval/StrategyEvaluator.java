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

    /** Economic truth for one exact ticket; ranking gates are replaced by the preview's own
     * mechanical verdict, while risk/evidence still use the shared producers. */
    public EconomicAssessment assessExact(Candidate c, EvalContext ctx, boolean mechanicallyEligible,
                                          List<String> mechanicalFailures, long roundTripFeesCents) {
        RiskProfile rsk = risk.profile(c, ctx);
        EvidenceProfile ev = evidence.assemble(c, ctx);
        return EconomicAssessment.assessExact(c, rsk, ev, ctx, mechanicallyEligible,
                mechanicalFailures, roundTripFeesCents);
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
