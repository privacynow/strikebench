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
    private final StanceProfiler stance = new StanceProfiler();

    public StrategyEvaluation evaluate(Candidate c, StrategySpec spec, EvalContext ctx) {
        CapitalProfile cap = capital.profile(c, ctx);
        VolatilityProfile vol = volatility.profile(ctx);
        RiskProfile rsk = risk.profile(c, ctx);
        EvidenceProfile ev = evidence.assemble(c, ctx);
        ManagementPlan plan = management.plan(c, spec);
        ScoreBreakdown sb = score.compose(c, cap, rsk, ev, ctx);
        EconomicAssessment economics = EconomicAssessment.assess(c, rsk, ev, sb, ctx);
        Explanation exp = explainer.explain(c, spec, cap, vol, rsk, ev, ctx);
        StanceProfiler.Result metrics = stance.profile(c, ctx, ev, vol);
        FourOutputAssessment assessment = assessment(sb, economics, metrics.impliedStance(),
                metrics.stance(), ctx.portfolioExposure());
        return new StrategyEvaluation(Ids.newId("eval"), spec, c, cap, vol, rsk, ev, plan, sb,
                assessment, metrics.stance(), metrics.participation(), metrics.impliedStance(),
                IvContext.from(c.entryNetPremiumCents(), vol), metrics.coverage(), exp);
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
        StanceProfiler.Result metrics = stance.profile(c, ctx, ev, vol);
        FourOutputAssessment assessment = assessment(exactScore, economics, metrics.impliedStance(),
                metrics.stance(), ctx.portfolioExposure());
        return new StrategyEvaluation(Ids.newId("eval"), spec, c, cap, vol, rsk, ev, plan,
                exactScore, assessment, metrics.stance(), metrics.participation(), metrics.impliedStance(),
                IvContext.from(c.entryNetPremiumCents(), vol), metrics.coverage(), exp);
    }

    private static FourOutputAssessment assessment(ScoreBreakdown score, EconomicAssessment economics,
                                                    ImpliedStance impliedStance, StanceVector stance,
                                                    PortfolioExposureContext exposure) {
        var mechanics = new FourOutputAssessment.MechanicalAssessment(
                score != null && score.gatePassed(), score == null ? List.of("Mechanical assessment unavailable")
                : score.gateFailures());
        var coherence = new FourOutputAssessment.ObjectiveCoherence(
                FourOutputAssessment.Coherence.UNDECLARED,
                impliedStance == null ? "Implied direction unavailable" : impliedStance.summary(),
                "No versioned objective is attached to this assessment.",
                List.of("Declare an objective to compare the position's stance and duration; economics are unchanged."));
        var impacts = portfolioImpacts(exposure, stance);
        return new FourOutputAssessment(mechanics, economics, coherence, impacts);
    }

    private static FourOutputAssessment.PortfolioImpacts portfolioImpacts(
            PortfolioExposureContext exposure, StanceVector stance) {
        if (exposure == null || stance == null) {
            return new FourOutputAssessment.PortfolioImpacts(null, null, List.of(
                    "No destination portfolio was selected, so before/after exposure is unavailable.",
                    "Practice and Real impacts are always reported separately and are never netted."));
        }
        long added = stance.dollarDeltaCents();
        long addedGross = absolute(added);
        long grossAfter = Math.addExact(exposure.grossDollarDeltaCents(), addedGross);
        long netAfter = Math.addExact(exposure.netDollarDeltaCents(), added);
        long symbolAfter = Math.addExact(exposure.symbolGrossDollarDeltaCents(), addedGross);
        Double beforePct = percent(exposure.symbolGrossDollarDeltaCents(), exposure.grossDollarDeltaCents());
        Double afterPct = percent(symbolAfter, grossAfter);
        var impact = new FourOutputAssessment.PortfolioImpact(exposure.lane(),
                exposure.grossDollarDeltaCents(), grossAfter,
                exposure.netDollarDeltaCents(), netAfter, beforePct, afterPct,
                List.of("This package adds " + signedDollars(added)
                        + " of modeled dollar delta to the selected lane.",
                        "Focused-symbol gross concentration moves from " + percentLabel(beforePct)
                                + " to " + percentLabel(afterPct) + "."),
                exposure.basis());
        List<String> notes = exposure.complete() ? List.of(
                "Practice and Real impacts are always reported separately and are never netted.") : List.of(
                "Existing exposure is partial because one or more current positions lacked a complete mark or delta.",
                "Practice and Real impacts are always reported separately and are never netted.");
        return exposure.lane() == io.liftandshift.strikebench.position.PositionDomain.ExecutionLane.PRACTICE
                ? new FourOutputAssessment.PortfolioImpacts(impact, null, notes)
                : new FourOutputAssessment.PortfolioImpacts(null, impact, notes);
    }

    private static long absolute(long value) {
        if (value == Long.MIN_VALUE) throw new ArithmeticException("dollar delta overflow");
        return Math.abs(value);
    }

    private static Double percent(long part, long total) {
        return total <= 0 ? null : Math.round(part * 10_000.0 / total) / 100.0;
    }

    private static String percentLabel(Double value) {
        return value == null ? "not measurable on an empty book" : String.format(java.util.Locale.ROOT, "%.2f%%", value);
    }

    private static String signedDollars(long cents) {
        return (cents >= 0 ? "+" : "-") + io.liftandshift.strikebench.util.Money.fmt(absolute(cents));
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
