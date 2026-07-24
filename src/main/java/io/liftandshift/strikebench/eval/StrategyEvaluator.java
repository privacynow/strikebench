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

    /** THE decision-score comparator — the one canonical ranking order, reused by every cross-symbol
     *  scan instead of re-spelling {@code comparingDouble(decisionScore).reversed()} inline. */
    public static final Comparator<StrategyEvaluation> RANKING =
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
        var impacts = PortfolioImpactComposer.compose(exposure, stance);
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
            // Shares-agnostic income: the honest axis is carry, not direction. This is the precise
            // EVAL-time tier of the two-tier income check; RecommendationEngine.intentIncoherence is
            // the cheap GENERATION gate (entry credit) that pre-filters debit structures. Distinct
            // inputs/stages by design — keep them aligned, they are not a duplicate check.
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

    /**
     * Evaluates a set of alternatives and ranks them for the competition: viable (gate-passing)
     * first, then by economic tier, then by risk/evidence quality inside that tier. The monotonic
     * Decision score encodes that exact order; it still travels with its full breakdown, evidence,
     * economics and management plan.
     */
    public List<StrategyEvaluation> evaluateAndRank(List<Candidate> candidates, StrategySpec spec, EvalContext ctx) {
        return candidates.stream()
                // A competition shares its declared symbol/intent/horizon, but each exact package
                // owns its family. Reusing the first candidate's family in every persisted recipe
                // makes an otherwise correct rank impossible to reproduce or audit later.
                .map(c -> evaluate(c, exactCandidateSpec(c, spec), ctx))
                .sorted(RANKING)
                .toList();
    }

    private static StrategySpec exactCandidateSpec(Candidate candidate, StrategySpec competition) {
        if (competition == null) return null;
        String family = candidate != null && candidate.strategy() != null
                && !candidate.strategy().isBlank() ? candidate.strategy() : competition.family();
        return new StrategySpec(competition.symbol(), family, competition.intent(),
                competition.horizon(), competition.thesis(), competition.riskMode(),
                competition.objective());
    }

    /**
     * Chooses the authoritative representative for each strategy family only after every exact
     * expiry/package has passed through DecisionPolicy. The input may be unsorted; the economic
     * tier encoded by {@link StrategyEvaluation#decisionScore()} always wins before within-tier
     * quality. This prevents a raw pre-cost EV/max-loss heuristic from discarding a favorable
     * expiry before fees, realized-volatility evidence, objective coherence, and tail risk exist.
     */
    public static List<StrategyEvaluation> bestPackagePerFamily(List<StrategyEvaluation> evaluations) {
        if (evaluations == null || evaluations.isEmpty()) return List.of();
        java.util.LinkedHashMap<String, StrategyEvaluation> best = new java.util.LinkedHashMap<>();
        evaluations.stream().sorted(RANKING).forEach(evaluation -> {
            String family = evaluation.family();
            if (family == null || family.isBlank()) {
                family = evaluation.candidate() == null ? null : evaluation.candidate().strategy();
            }
            String key = family == null || family.isBlank()
                    ? "candidate:" + (evaluation.candidate() == null ? evaluation.id()
                            : evaluation.candidate().label())
                    : family;
            best.putIfAbsent(key, evaluation);
        });
        return List.copyOf(best.values());
    }
}
