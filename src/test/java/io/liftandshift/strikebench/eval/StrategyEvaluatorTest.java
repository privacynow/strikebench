package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.position.PositionDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Phase-2 backbone: producers + evaluator assemble a coherent, honest evaluation. */
class StrategyEvaluatorTest {

    private final StrategyEvaluator evaluator = new StrategyEvaluator();

    /** A $250/$255 debit call spread on AAPL: $2.00 debit, $3.00 max profit, $2.00 max loss. */
    private Candidate debitCallSpread(String freshness, double confidence) {
        List<LegView> legs = List.of(
                new LegView("BUY", "CALL", "250", "2026-08-21", 1, "4.00", 100, "OPEN"),
                new LegView("SELL", "CALL", "255", "2026-08-21", 1, "2.00", 100, "OPEN"));
        // $2.00 debit and $5.00 width per share -> $200 debit / $300 profit / $200 loss PER CONTRACT (cents).
        return new Candidate("DEBIT_CALL_SPREAD", "Bull call spread", "debit_vertical", "BUY 250C / SELL 255C Aug21",
                legs, 1, -20_000L, 30_000L, 20_000L, List.of("252.00"),
                0.45, 2_000L, 0.70, freshness, List.of(),
                confidence, "Cheap defined-risk way to play a move up",
                "Up to $300 if AAPL is above $255", "Loses the $200 debit if AAPL stays flat/down",
                "AAPL closes below $250 at expiry", "You risk $200 to make up to $300",
                "DIRECTIONAL", List.of("DIRECTIONAL"),
                0.30, null, null, null, false, null, null);
    }

    private EvalContext ctx() {
        return new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25,
                List.of(0.20, 0.22, 0.24, 0.26, 0.28, 0.30, 0.32, 0.34, 0.36, 0.38, 0.40, 0.29),
                10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury", io.liftandshift.strikebench.model.Freshness.EOD), null);
    }

    @Test void assemblesEveryDimensionCoherently() {
        StrategyEvaluation e = evaluator.evaluate(debitCallSpread("DELAYED", 0.6),
                new StrategySpec("AAPL", "DEBIT_CALL_SPREAD", "DIRECTIONAL", "month", "bullish", "balanced", "decision"),
                ctx());

        // Capital: incremental == economic (defined risk); best-case ROC $300/$200 = 150%.
        assertThat(e.capital().incrementalCents()).isEqualTo(20_000);
        assertThat(e.capital().economicCents()).isEqualTo(20_000);
        assertThat(e.capital().returnOnCapitalPct()).isCloseTo(150.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(e.capital().annualizedRocPct()).isGreaterThan(e.capital().returnOnCapitalPct()); // labeled, scaled up

        // Volatility: ATM IV, expected move, and a real IV rank/percentile from 12 days of history.
        assertThat(e.volatility().atmIv()).isEqualTo(0.30);
        assertThat(e.volatility().ivRankPct()).isNotNull();
        assertThat(e.volatility().expectedMovePct()).isCloseTo(0.086, org.assertj.core.data.Offset.offset(0.01));
        assertThat(e.volatility().varianceRiskPremium()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));

        // Risk: a real 7-point payoff grid; worst case is the $200 debit; best case $300.
        assertThat(e.risk().scenarios()).hasSize(7);
        assertThat(e.risk().maxLossCents()).isEqualTo(20_000);
        assertThat(e.risk().tailLossCents()).isEqualTo(20_000);
        long best = e.risk().scenarios().stream().mapToLong(RiskProfile.Scenario::pnlCents).max().orElse(0);
        assertThat(best).isEqualTo(30_000);

        // Evidence: rolls up to the worst dimension (EOD history beats DELAYED pricing), still observed.
        assertThat(e.evidence().rollup().isObserved()).isTrue();
        assertThat(e.evidence().perDimension()).containsKeys("pricing", "greeks", "volatility", "liquidity", "history", "rates");

        // Score: gate passes; final score in range; six named components that never stand alone.
        assertThat(e.score().gatePassed()).isTrue();
        assertThat(e.score().components()).hasSize(7); // +Expected value: the DecisionPolicy's primary economics
        assertThat(e.rankScore()).isBetween(0.0, 100.0);
        assertThat(e.decisionScore()).isBetween(1.0, 100.0);

        // Management: a real plan with a debit-trade summary and rules.
        assertThat(e.management().summary()).containsIgnoringCase("debit");
        assertThat(e.management().rules()).isNotEmpty();

        // Explanation: carries the honest assumptions.
        assertThat(e.explanation().assumptions())
                .anyMatch(a -> a.toLowerCase().contains("raw model outputs")
                        && a.toLowerCase().contains("after costs"));
    }

    @Test void exactPositionRetainsFullProposalAssessmentAndUsesTicketMechanicalGate() {
        Candidate candidate = debitCallSpread("DELAYED", 0.6);
        StrategySpec spec = new StrategySpec("AAPL", "DEBIT_CALL_SPREAD", "DIRECTIONAL",
                "month", "bullish", "balanced", "decision");
        StrategyEvaluation proposal = evaluator.evaluate(candidate, spec, ctx());
        StrategyEvaluation exact = evaluator.assessExact(candidate, spec, ctx(), true, List.of(), 260);

        assertThat(exact.capital()).isEqualTo(proposal.capital());
        assertThat(exact.volatility()).isEqualTo(proposal.volatility());
        assertThat(exact.risk()).isEqualTo(proposal.risk());
        assertThat(exact.evidence()).isEqualTo(proposal.evidence());
        assertThat(exact.management()).isEqualTo(proposal.management());
        assertThat(exact.score().components()).isEqualTo(proposal.score().components());
        assertThat(exact.assessment().economics()).isEqualTo(proposal.assessment().economics());
        assertThat(exact.stance()).isEqualTo(proposal.stance());
        assertThat(exact.participation()).isEqualTo(proposal.participation());
        assertThat(exact.impliedStance()).isEqualTo(proposal.impliedStance());
        assertThat(exact.coverage()).isEqualTo(proposal.coverage());
        assertThat(exact.decisionScore()).isEqualTo(proposal.decisionScore());

        StrategyEvaluation refused = evaluator.assessExact(candidate, spec, ctx(), false,
                List.of("executable quote unavailable"), 260);
        assertThat(refused.viable()).isFalse();
        assertThat(refused.decisionScore()).isZero();
        assertThat(refused.score().gateFailures()).contains("executable quote unavailable");
        assertThat(refused.assessment().economics().placement()).isEqualTo("MECHANICALLY_INELIGIBLE");
        assertThat(refused.capital()).isEqualTo(proposal.capital());
        assertThat(refused.risk()).isEqualTo(proposal.risk());

        EvalContext noBuyingPower = new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25,
                ctx().ivHistory(), 100L, true, 65, 0, 0.04, ctx().rateEvidence(), null);
        StrategyEvaluation accountRefusal = evaluator.assessExact(candidate, spec, noBuyingPower,
                true, List.of(), 260);
        assertThat(accountRefusal.assessment().economics().reasons())
                .anyMatch(reason -> reason.contains("buying power"));
    }

    @Test void demoDataIsHaircutAndLabeled() {
        StrategyEvaluation live = evaluator.evaluate(debitCallSpread("DELAYED", 0.6), null, ctx());
        StrategyEvaluation demo = evaluator.evaluate(debitCallSpread("FIXTURE", 0.6), null, ctx());

        assertThat(demo.evidenceLevel()).isEqualTo(EvidenceLevel.DEMO_FIXTURE);
        assertThat(demo.evidence().perDimension().get("history")).isEqualTo(EvidenceLevel.DEMO_FIXTURE);
        assertThat(demo.evidence().perDimension().get("volatility")).isEqualTo(EvidenceLevel.DEMO_FIXTURE);
        // Same trade, demo data -> a strictly lower risk-adjusted score (honesty haircut).
        assertThat(demo.rankScore()).isLessThan(live.rankScore());
        assertThat(demo.explanation().failureModes()).anyMatch(f -> f.contains("DEMO"));
    }

    @Test void generatedPricingCannotBeSoftenedByModeledVolatilityOrRates() {
        EvalContext generated = new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25, List.of(),
                10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of(
                        "simulated rate", io.liftandshift.strikebench.model.Freshness.SIMULATED), null);
        StrategyEvaluation simulated = evaluator.evaluate(debitCallSpread("SIMULATED", 0.6), null, generated);

        assertThat(simulated.evidence().perDimension().get("pricing")).isEqualTo(EvidenceLevel.SIMULATED);
        assertThat(simulated.evidence().perDimension().get("volatility")).isEqualTo(EvidenceLevel.SIMULATED);
        assertThat(simulated.evidence().perDimension().get("rates")).isEqualTo(EvidenceLevel.SIMULATED);
        assertThat(simulated.evidenceLevel()).isEqualTo(EvidenceLevel.SIMULATED);
    }

    @Test void gateBlocksInsufficientBuyingPower() {
        EvalContext broke = new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25, List.of(), 100L, true, 65,
                0, 0.04, io.liftandshift.strikebench.model.DataEvidence.of("treasury", io.liftandshift.strikebench.model.Freshness.EOD), null);
        StrategyEvaluation e = evaluator.evaluate(debitCallSpread("DELAYED", 0.6), null, broke);
        assertThat(e.viable()).isFalse();
        assertThat(e.score().gateFailures()).anyMatch(f -> f.contains("buying power"));
        assertThat(e.rankScore()).isZero();
        assertThat(e.decisionScore()).isZero();
    }

    @Test void missingDailyHistoryIsAnEvidenceLimitationNotAMechanicalFailure() {
        EvalContext candleStarved = new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, null,
                List.of(0.20, 0.22, 0.24, 0.26, 0.28, 0.30, 0.32, 0.34, 0.36, 0.38, 0.40, 0.29),
                10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of(
                        "treasury", io.liftandshift.strikebench.model.Freshness.EOD), null);

        StrategyEvaluation e = evaluator.evaluate(debitCallSpread("DELAYED", 0.6),
                new StrategySpec("AAPL", "DEBIT_CALL_SPREAD", "DIRECTIONAL", "month",
                        "bullish", "balanced", "decision"), candleStarved);

        assertThat(e.evidence().perDimension().get("history")).isEqualTo(EvidenceLevel.UNKNOWN);
        assertThat(e.evidenceLevel()).isEqualTo(EvidenceLevel.UNKNOWN);
        assertThat(e.score().gatePassed()).isTrue();
        assertThat(e.score().gateFailures()).isEmpty();
        assertThat(e.score().components()).filteredOn(c -> c.name().equals("Evidence quality"))
                .singleElement().satisfies(c -> assertThat(c.value()).isZero());
        assertThat(e.viable()).isTrue();
        assertThat(e.decisionScore()).isPositive();
        assertThat(e.assessment().economics().placement()).isNotEqualTo("MECHANICALLY_INELIGIBLE");
        assertThat(e.assessment().economics().verdict())
                .isIn(EconomicAssessment.Verdict.MIXED, EconomicAssessment.Verdict.UNFAVORABLE);
        assertThat(e.assessment().economics().marketEvAfterCostsCents()).isNotNull();
        assertThat(e.assessment().economics().realizedVolEvAfterCostsCents()).isNull();
        assertThat(e.assessment().economics().needsDailyHistory()).isTrue();
        assertThat(e.assessment().economics().actionableFavorable()).isFalse();
    }

    @Test void shortPremiumParticipationAndRegimePointsDoNotMasqueradeAsUpsideOwnership() {
        Candidate shortPut = candidate("CASH_SECURED_PUT", List.of(
                new LegView("SELL", "PUT", "240", "2026-08-21", 1, "3.00", 100, "OPEN")),
                30_000L, 30_000L, 2_370_000L);
        StrategyEvaluation put = evaluator.evaluate(shortPut, null, ctx());

        assertThat(put.participation().localParticipationBps()).isPositive();
        assertThat(put.participation().terminalUpsideCaptureBps()).isZero();
        assertThat(put.participation().terminalDate()).isEqualTo(java.time.LocalDate.parse("2026-08-21"));
        assertThat(put.participation().regimePoints()).singleElement()
                .satisfies(point -> {
                    assertThat(point.priceCents()).isEqualTo(24_000L);
                    assertThat(point.meaning()).contains("assignment exposure");
                });

        Candidate coveredCall = candidate("COVERED_CALL", List.of(
                new LegView("BUY", "STOCK", null, null, 100, "252.00", 1, "OPEN"),
                new LegView("SELL", "CALL", "255", "2026-08-21", 1, "2.00", 100, "OPEN")),
                -2_500_000L, 50_000L, 2_500_000L);
        StrategyEvaluation call = evaluator.evaluate(coveredCall, null, ctx());
        assertThat(call.participation().terminalUpsideCaptureBps()).isBetween(0, 10_000);
        assertThat(call.participation().regimePoints()).anySatisfy(point -> {
            assertThat(point.priceCents()).isEqualTo(25_500L);
            assertThat(point.meaning()).contains("cap");
        });
    }

    @Test void multiExpirationMetricsStayUnknownRatherThanInventingATerminalPayoff() {
        Candidate calendar = candidate("CALENDAR_CALL", List.of(
                new LegView("SELL", "CALL", "255", "2026-08-21", 1, "2.00", 100, "OPEN"),
                new LegView("BUY", "CALL", "255", "2026-09-18", 1, "4.00", 100, "OPEN")),
                -20_000L, null, 20_000L);
        StrategyEvaluation evaluation = evaluator.evaluate(calendar, null, ctx());

        assertThat(evaluation.participation().terminalUpsideCaptureBps()).isNull();
        assertThat(evaluation.stance().downsideLossTwoSigmaCents()).isNull();
        assertThat(evaluation.stance().upsideLossTwoSigmaCents()).isNull();
        assertThat(evaluation.coverage().limitations()).anyMatch(note -> note.contains("multiple expirations"));
        assertThat(evaluation.stance().durationCalendarDays()).isEqualTo(58);
    }

    @Test void debitIvWarningAndAnnualizationCarryTheirEvidenceLimits() {
        EvalContext expensiveVol = new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"),
                30, 0.90, 0.25, ctx().ivHistory(), 10_000_000L, true, 65, 0, 0.04,
                ctx().rateEvidence(), null);
        StrategyEvaluation evaluation = evaluator.evaluate(debitCallSpread("DELAYED", 0.6), null, expensiveVol);

        assertThat(evaluation.ivContext().entrySide()).isEqualTo(IvContext.EntrySide.DEBIT);
        assertThat(evaluation.ivContext().band()).isIn(IvContext.Band.HIGH, IvContext.Band.VERY_HIGH);
        assertThat(evaluation.ivContext().message()).containsIgnoringCase("crush");
        assertThat(evaluation.capital().annualizationNote())
                .contains("theoretical max profit divided by")
                .contains("economic capital")
                .contains("calendar days")
                .contains("repeatable")
                .contains("not assumed");
    }

    @Test void portfolioImpactIsLaneSeparatedAndUsesBeforeAfterDollarDelta() {
        var exposure = new PortfolioExposureContext(PositionDomain.ExecutionLane.PRACTICE,
                2_000_000L, 500_000L, 1_000_000L, true, "test Practice marks");
        EvalContext withBook = new EvalContext("AAPL", 25_200L, java.time.LocalDate.parse("2026-07-22"),
                30, 0.30, 0.25, ctx().ivHistory(), 10_000_000L, true, 65, 0, 0.04,
                ctx().rateEvidence(), exposure);
        StrategyEvaluation evaluation = evaluator.evaluate(debitCallSpread("DELAYED", 0.6), null, withBook);
        var impacts = evaluation.assessment().portfolioImpacts();

        assertThat(impacts.practice()).isNotNull();
        assertThat(impacts.real()).isNull();
        assertThat(impacts.practice().grossExposureAfterCents())
                .isEqualTo(2_000_000L + Math.abs(evaluation.stance().dollarDeltaCents()));
        assertThat(impacts.practice().netExposureAfterCents())
                .isEqualTo(500_000L + evaluation.stance().dollarDeltaCents());
        assertThat(impacts.notes()).allMatch(note -> !note.contains("netted total"));
    }

    @Test void ranksViableFirstThenByScore() {
        var strong = debitCallSpread("DELAYED", 0.9);
        var weak = debitCallSpread("FIXTURE", 0.2);
        List<StrategyEvaluation> ranked = evaluator.evaluateAndRank(List.of(weak, strong), null, ctx());
        assertThat(ranked.get(0).candidate().confidence()).isEqualTo(0.9); // strongest first
    }

    @Test void unknownEconomicsRanksAheadOfKnownUnfavorableEconomics() {
        var scoreHigh = new ScoreBreakdown(true, List.of(), 90, 90, List.of());
        var scoreLow = new ScoreBreakdown(true, List.of(), 40, 40, List.of());
        var unavailable = new EconomicAssessment(EconomicAssessment.Verdict.UNAVAILABLE, "MECHANICS_ONLY",
                "Economics unavailable", "No economic basis", null, null, 0, null, true, List.of());
        var unfavorable = new EconomicAssessment(EconomicAssessment.Verdict.UNFAVORABLE, "LEARN_FROM",
                "Unfavorable", "Known adverse economics", -100L, -100L, 0, -1.0, true, List.of());
        var unknownEval = evaluationForRanking("unknown", scoreLow, unavailable);
        var adverseEval = evaluationForRanking("adverse", scoreHigh, unfavorable);

        assertThat(java.util.stream.Stream.of(adverseEval, unknownEval)
                .sorted(StrategyEvaluator.RANKING).map(StrategyEvaluation::id).toList())
                .containsExactly("unknown", "adverse");
        assertThat(unknownEval.decisionScore()).isGreaterThan(adverseEval.decisionScore());
        assertThat(unknownEval.decisionScore()).isBetween(26.0, 50.0);
        assertThat(adverseEval.decisionScore()).isBetween(1.0, 25.0);
    }

    private static StrategyEvaluation evaluationForRanking(String id, ScoreBreakdown score,
                                                            EconomicAssessment economics) {
        var assessment = new FourOutputAssessment(
                new FourOutputAssessment.MechanicalAssessment(score.gatePassed(), score.gateFailures()),
                economics,
                new FourOutputAssessment.ObjectiveCoherence(FourOutputAssessment.Coherence.UNDECLARED,
                        "test", "test", List.of()),
                new FourOutputAssessment.PortfolioImpacts(null, null, List.of("not selected")));
        return new StrategyEvaluation(id, null, null, null, null, null, null, null, score, assessment,
                null, null, null, null, null, null);
    }

    private static Candidate candidate(String strategy, List<LegView> legs, long entryNet,
                                       Long maxProfit, Long maxLoss) {
        return new Candidate(strategy, strategy.replace('_', ' '), "test", strategy, legs, 1,
                entryNet, maxProfit, maxLoss, List.of(), 0.50, 0L, 0.8, "DELAYED", List.of(),
                0.7, "test", "test", "test", "test", "test", "DIRECTIONAL",
                List.of("DIRECTIONAL"), null, null, null, null, false, null, maxLoss);
    }
}
