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
        StanceProfiler.Result metrics = stance.profile(c, ctx, ev, vol);
        Explanation exp = explainer.explain(c, spec, cap, vol, rsk, ev, ctx, metrics.participation());
        FourOutputAssessment assessment = assessment(sb, economics, metrics.impliedStance(),
                metrics.stance(), ctx.portfolioExposure(), ctx.declared());
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
        StanceProfiler.Result metrics = stance.profile(c, ctx, ev, vol);
        Explanation exp = explainer.explain(c, spec, cap, vol, rsk, ev, ctx, metrics.participation());
        FourOutputAssessment assessment = assessment(exactScore, economics, metrics.impliedStance(),
                metrics.stance(), ctx.portfolioExposure(), ctx.declared());
        return new StrategyEvaluation(Ids.newId("eval"), spec, c, cap, vol, rsk, ev, plan,
                exactScore, assessment, metrics.stance(), metrics.participation(), metrics.impliedStance(),
                IvContext.from(c.entryNetPremiumCents(), vol), metrics.coverage(), exp);
    }

    private static FourOutputAssessment assessment(ScoreBreakdown score, EconomicAssessment economics,
                                                    ImpliedStance impliedStance, StanceVector stance,
                                                    PortfolioExposureContext exposure,
                                                    DeclaredObjective declared) {
        var mechanics = new FourOutputAssessment.MechanicalAssessment(
                score != null && score.gatePassed(), score == null ? List.of("Mechanical assessment unavailable")
                : score.gateFailures());
        var impacts = portfolioImpacts(exposure, stance);
        return new FourOutputAssessment(mechanics, economics,
                objectiveCoherence(declared, impliedStance, stance), impacts);
    }

    /**
     * The headline diagnostic (§3.8/§3.9): the DECLARED objective against the position's
     * implied stance, judged on the direction and duration axes. The engine's own stance
     * classification is the implied side — this method never re-derives exposure thresholds,
     * so coherence can not drift from participation or book aggregation.
     */
    static FourOutputAssessment.ObjectiveCoherence objectiveCoherence(
            DeclaredObjective declared, ImpliedStance implied, StanceVector stance) {
        if (declared == null || !declared.declaresAnything()) {
            return new FourOutputAssessment.ObjectiveCoherence(
                    FourOutputAssessment.Coherence.UNDECLARED,
                    implied == null ? "Implied direction unavailable" : implied.summary(),
                    "No versioned objective is attached to this assessment.",
                    java.util.List.of("Declare an objective to compare the position's stance and duration; economics are unchanged."));
        }
        if (implied == null || stance == null) {
            return new FourOutputAssessment.ObjectiveCoherence(
                    FourOutputAssessment.Coherence.UNAVAILABLE,
                    "The position's implied stance could not be computed.",
                    "Duration cannot be compared without a stance vector.",
                    java.util.List.of("Coherence is withheld rather than guessed; economics are unchanged."));
        }
        var reasons = new java.util.ArrayList<String>();

        // ---- Direction axis: declared thesis/objective vs the engine's implied direction.
        FourOutputAssessment.Coherence direction;
        String directionAssessment;
        String thesis = declared.thesis();
        if ("VOLATILE".equals(thesis)) {
            var volPosture = implied.volatility();
            direction = volPosture == ImpliedStance.Shape.LONG ? FourOutputAssessment.Coherence.COHERENT
                    : volPosture == ImpliedStance.Shape.SHORT ? FourOutputAssessment.Coherence.INCOHERENT
                    : FourOutputAssessment.Coherence.MIXED;
            directionAssessment = "You declared a big move either way; this position is "
                    + (volPosture == ImpliedStance.Shape.LONG ? "long volatility — it profits from that move."
                    : volPosture == ImpliedStance.Shape.SHORT ? "SHORT volatility — a big move is what hurts it."
                    : "roughly flat on volatility.");
        } else if (thesis != null) {
            var wanted = "BULLISH".equals(thesis) ? ImpliedStance.Direction.BULLISH
                    : "BEARISH".equals(thesis) ? ImpliedStance.Direction.BEARISH
                    : ImpliedStance.Direction.NEUTRAL;
            var actual = implied.direction();
            if (actual == wanted) {
                direction = FourOutputAssessment.Coherence.COHERENT;
            } else if (wanted != ImpliedStance.Direction.NEUTRAL && actual != ImpliedStance.Direction.NEUTRAL) {
                direction = FourOutputAssessment.Coherence.INCOHERENT;
            } else {
                direction = FourOutputAssessment.Coherence.MIXED;
            }
            directionAssessment = "Declared " + wanted.name().toLowerCase(java.util.Locale.ROOT)
                    + "; the position reads " + actual.name().toLowerCase(java.util.Locale.ROOT)
                    + " (" + implied.summary() + ")";
        } else if ("INCOME".equals(declared.objective())) {
            // Shares-agnostic income: the honest axis is carry, not direction.
            var carry = implied.carry();
            direction = carry == ImpliedStance.Carry.POSITIVE ? FourOutputAssessment.Coherence.COHERENT
                    : carry == ImpliedStance.Carry.NEGATIVE ? FourOutputAssessment.Coherence.INCOHERENT
                    : FourOutputAssessment.Coherence.MIXED;
            directionAssessment = "Income declared; the position's carry is "
                    + carry.name().toLowerCase(java.util.Locale.ROOT)
                    + (carry == ImpliedStance.Carry.NEGATIVE ? " — it pays to wait, it does not get paid." : ".");
        } else if ("HEDGE".equals(declared.objective())) {
            var tail = implied.primaryTail();
            direction = tail == ImpliedStance.Tail.LIMITED ? FourOutputAssessment.Coherence.COHERENT
                    : tail == ImpliedStance.Tail.DOWNSIDE ? FourOutputAssessment.Coherence.INCOHERENT
                    : FourOutputAssessment.Coherence.MIXED;
            directionAssessment = "Hedge declared; the position's primary tail risk is "
                    + tail.name().toLowerCase(java.util.Locale.ROOT)
                    + (tail == ImpliedStance.Tail.DOWNSIDE ? " — it adds the very exposure a hedge should cap." : ".");
        } else {
            direction = FourOutputAssessment.Coherence.MIXED;
            directionAssessment = "The declared objective names no direction; the position reads "
                    + implied.direction().name().toLowerCase(java.util.Locale.ROOT) + ".";
        }
        reasons.add(directionAssessment);

        // ---- Duration axis: the declared horizon vs how long the structure actually lives.
        FourOutputAssessment.Coherence duration = FourOutputAssessment.Coherence.COHERENT;
        String durationAssessment;
        Integer horizonCal = declared.horizonCalendarDays();
        int lives = stance.durationCalendarDays();
        boolean incomeCycles = "INCOME".equals(declared.objective());
        if (horizonCal == null) {
            durationAssessment = "No horizon declared; the structure lives ~" + lives + " calendar days.";
        } else if (incomeCycles && lives <= horizonCal) {
            durationAssessment = "Income cycles shorter than the " + horizonCal
                    + "-day objective are the point: this cycle lives ~" + lives + " days.";
        } else if (lives < Math.round(horizonCal * 0.8f)) {
            duration = FourOutputAssessment.Coherence.INCOHERENT;
            durationAssessment = "The structure expires in ~" + lives + " calendar days but the declared view needs ~"
                    + horizonCal + " — it ends before the thesis can play out.";
        } else if (lives > horizonCal * 3L) {
            duration = FourOutputAssessment.Coherence.MIXED;
            durationAssessment = "The structure pays for ~" + lives + " calendar days of exposure against a ~"
                    + horizonCal + "-day view — most of that time is not part of the declared thesis.";
        } else {
            durationAssessment = "The structure's ~" + lives + " calendar days cover the declared ~"
                    + horizonCal + "-day view.";
        }
        reasons.add(durationAssessment);
        if (declared.source() != null) reasons.add("Declared side: " + declared.source() + ". Economics are unchanged by this diagnostic.");

        var verdict = worst(direction, duration);
        return new FourOutputAssessment.ObjectiveCoherence(verdict, directionAssessment, durationAssessment,
                java.util.List.copyOf(reasons));
    }

    private static FourOutputAssessment.Coherence worst(FourOutputAssessment.Coherence a,
                                                        FourOutputAssessment.Coherence b) {
        if (a == FourOutputAssessment.Coherence.INCOHERENT || b == FourOutputAssessment.Coherence.INCOHERENT) {
            return FourOutputAssessment.Coherence.INCOHERENT;
        }
        if (a == FourOutputAssessment.Coherence.MIXED || b == FourOutputAssessment.Coherence.MIXED) {
            return FourOutputAssessment.Coherence.MIXED;
        }
        return FourOutputAssessment.Coherence.COHERENT;
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
