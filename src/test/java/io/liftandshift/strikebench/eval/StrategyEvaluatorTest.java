package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Phase-2 backbone: producers + evaluator assemble a coherent, honest evaluation. */
class StrategyEvaluatorTest {

    private final StrategyEvaluator evaluator = new StrategyEvaluator();

    /** A $250/$255 debit call spread on AAPL: $2.00 debit, $3.00 max profit, $2.00 max loss. */
    private Candidate debitCallSpread(String freshness, double confidence) {
        List<LegView> legs = List.of(
                new LegView("BUY", "CALL", "250", "2026-08-21", 1, "4.00"),
                new LegView("SELL", "CALL", "255", "2026-08-21", 1, "2.00"));
        // $2.00 debit and $5.00 width per share -> $200 debit / $300 profit / $200 loss PER CONTRACT (cents).
        return new Candidate("DEBIT_CALL_SPREAD", "Bull call spread", "debit_vertical", "BUY 250C / SELL 255C Aug21",
                legs, 1, -20_000L, 30_000L, 20_000L, List.of("252.00"),
                0.45, 2_000L, 0.70, freshness, List.of(),
                55.0, confidence, "Cheap defined-risk way to play a move up",
                "Up to $300 if AAPL is above $255", "Loses the $200 debit if AAPL stays flat/down",
                "AAPL closes below $250 at expiry", "You risk $200 to make up to $300",
                "DIRECTIONAL", List.of("DIRECTIONAL"),
                0.30, null, null, null, false, null, null);
    }

    private EvalContext ctx() {
        return new EvalContext("AAPL", 25_200L, 30, 0.30, 0.25,
                List.of(0.20, 0.22, 0.24, 0.26, 0.28, 0.30, 0.32, 0.34, 0.36, 0.38, 0.40, 0.29),
                10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury", io.liftandshift.strikebench.model.Freshness.EOD));
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
        EvalContext generated = new EvalContext("AAPL", 25_200L, 30, 0.30, 0.25, List.of(),
                10_000_000L, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of(
                        "simulated rate", io.liftandshift.strikebench.model.Freshness.SIMULATED));
        StrategyEvaluation simulated = evaluator.evaluate(debitCallSpread("SIMULATED", 0.6), null, generated);

        assertThat(simulated.evidence().perDimension().get("pricing")).isEqualTo(EvidenceLevel.SIMULATED);
        assertThat(simulated.evidence().perDimension().get("volatility")).isEqualTo(EvidenceLevel.SIMULATED);
        assertThat(simulated.evidence().perDimension().get("rates")).isEqualTo(EvidenceLevel.SIMULATED);
        assertThat(simulated.evidenceLevel()).isEqualTo(EvidenceLevel.SIMULATED);
    }

    @Test void gateBlocksInsufficientBuyingPower() {
        EvalContext broke = new EvalContext("AAPL", 25_200L, 30, 0.30, 0.25, List.of(), 100L, true, 65,
                0, 0.04, io.liftandshift.strikebench.model.DataEvidence.of("treasury", io.liftandshift.strikebench.model.Freshness.EOD));
        StrategyEvaluation e = evaluator.evaluate(debitCallSpread("DELAYED", 0.6), null, broke);
        assertThat(e.viable()).isFalse();
        assertThat(e.score().gateFailures()).anyMatch(f -> f.contains("buying power"));
        assertThat(e.rankScore()).isZero();
        assertThat(e.decisionScore()).isZero();
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
        var unknownEval = new StrategyEvaluation("unknown", null, null, null, null, null, null, null,
                scoreLow, unavailable, null);
        var adverseEval = new StrategyEvaluation("adverse", null, null, null, null, null, null, null,
                scoreHigh, unfavorable, null);

        assertThat(java.util.stream.Stream.of(adverseEval, unknownEval)
                .sorted(StrategyEvaluator.RANKING).map(StrategyEvaluation::id).toList())
                .containsExactly("unknown", "adverse");
        assertThat(unknownEval.decisionScore()).isGreaterThan(adverseEval.decisionScore());
        assertThat(unknownEval.decisionScore()).isBetween(26.0, 50.0);
        assertThat(adverseEval.decisionScore()).isBetween(1.0, 25.0);
    }
}
