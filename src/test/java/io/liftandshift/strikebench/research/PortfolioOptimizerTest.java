package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.eval.CapitalProfile;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.RiskProfile;
import io.liftandshift.strikebench.eval.ScoreBreakdown;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.eval.StrategySpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Capital allocation across the competition's winners under budget/diversification constraints. */
class PortfolioOptimizerTest {

    private final PortfolioOptimizer optimizer = new PortfolioOptimizer();

    private StrategyEvaluation eval(String symbol, String family, double score, long capital,
                                    Long marketEv, Long historyEv, long tail, boolean gate,
                                    EconomicAssessment.Verdict verdict) {
        var economics = new EconomicAssessment(verdict,
                verdict == EconomicAssessment.Verdict.FAVORABLE ? "WORTH_INVESTIGATING" : "LEARN_FROM",
                verdict.name(), "test economics", marketEv, historyEv, 0, null, true, List.of());
        return new StrategyEvaluation("id-" + symbol + "-" + (int) score,
                new StrategySpec(symbol, family, null, null, null, null, null),
                null,
                new CapitalProfile(capital, capital, null, null, 30, "test"),
                null,
                new RiskProfile(capital, capital, 0.5, marketEv, tail, 0.2, List.of()),
                null, null,
                new ScoreBreakdown(gate, List.of(), score, score, List.of()),
                economics, null);
    }

    @Test void allocatesByDensityRespectingBudgetAndSymbolCap() {
        var a = eval("AAPL", "CREDIT_PUT_SPREAD", 80, 5_000, 100L, 150L, 500, true, EconomicAssessment.Verdict.FAVORABLE);
        var b = eval("AAPL", "DEBIT_CALL_SPREAD", 60, 5_000, 100L, 150L, 500, true, EconomicAssessment.Verdict.FAVORABLE);
        var c = eval("MSFT", "CREDIT_PUT_SPREAD", 70, 10_000, 200L, 250L, 1_000, true, EconomicAssessment.Verdict.FAVORABLE);
        var dead = eval("TSLA", "CREDIT_PUT_SPREAD", 90, 4_000, 999L, 999L, 400, false, EconomicAssessment.Verdict.FAVORABLE);

        var res = optimizer.optimize(List.of(a, b, c, dead),
                new PortfolioOptimizer.Constraints(30_000, 15_000L, 10, 0.5, "DECISION", false)); // per-symbol cap = 15,000

        // A (3 units, $150) and C (1 unit, $100) fund; B blocked by AAPL's symbol cap; dead excluded.
        assertThat(res.allocations()).hasSize(2);
        assertThat(res.allocations().get(0).eval().symbol()).isEqualTo("AAPL"); // best density first
        assertThat(res.allocations().get(0).units()).isEqualTo(3);
        assertThat(res.capitalUsedCents()).isEqualTo(25_000).isLessThanOrEqualTo(30_000);

        // Diversification: no symbol exceeds its cap; both symbols represented.
        assertThat(res.perSymbolCents()).containsEntry("AAPL", 15_000L).containsEntry("MSFT", 10_000L);
        assertThat(res.perSymbolCents().values()).allMatch(v -> v <= 15_000);

        // Portfolio aggregates.
        assertThat(res.marketEvAfterCostsCents()).isEqualTo(3 * 100 + 1 * 200);
        assertThat(res.realizedVolEvAfterCostsCents()).isEqualTo(3 * 150 + 1 * 250);
        assertThat(res.totalTailLossCents()).isEqualTo(3 * 500 + 1 * 1_000);

        // The non-viable one never appears, and the blocked one is explained.
        assertThat(res.allocations()).noneMatch(al -> al.eval().symbol().equals("TSLA"));
        assertThat(res.notes()).anyMatch(n -> n.contains("AAPL"));
    }

    @Test void fundsNothingWhenBudgetTooSmall() {
        var a = eval("AAPL", "CREDIT_PUT_SPREAD", 80, 50_000, 100L, 150L, 500, true, EconomicAssessment.Verdict.FAVORABLE);
        var res = optimizer.optimize(List.of(a),
                new PortfolioOptimizer.Constraints(10_000, null, null, null, "DECISION", false));
        assertThat(res.allocations()).isEmpty();
        assertThat(res.capitalUsedCents()).isZero();
        assertThat(res.notes()).anyMatch(n -> n.contains("Nothing funded"));
    }

    @Test void normalModeFundsNothingWhenEveryIdeaHasNegativeEv() {
        // A universe of viable-but-negative-EV ideas (the fixture iron-butterfly case). An optimizer
        // must NOT present a portfolio the model expects to lose money on as an answer.
        var a = eval("AAPL", "IRON_BUTTERFLY", 80, 5_000, -300L, -250L, 500, true, EconomicAssessment.Verdict.UNFAVORABLE);
        var b = eval("MSFT", "IRON_BUTTERFLY", 70, 5_000, -200L, -150L, 500, true, EconomicAssessment.Verdict.UNFAVORABLE);
        var res = optimizer.optimize(List.of(a, b),
                new PortfolioOptimizer.Constraints(30_000, null, null, null, "DECISION", false));
        assertThat(res.allocations()).isEmpty();
        assertThat(res.capitalUsedCents()).isZero();
        assertThat(res.diagnostic()).isFalse();
        assertThat(res.notes()).anyMatch(n -> n.contains("favorable after-cost economic verdict"));
    }

    @Test void diagnosticModeFundsTheLeastBadSetButLabelsItNegative() {
        var a = eval("AAPL", "IRON_BUTTERFLY", 80, 5_000, -300L, -250L, 500, true, EconomicAssessment.Verdict.UNFAVORABLE);
        var b = eval("MSFT", "IRON_BUTTERFLY", 70, 5_000, -200L, -150L, 500, true, EconomicAssessment.Verdict.UNFAVORABLE);
        var res = optimizer.optimize(List.of(a, b),
                new PortfolioOptimizer.Constraints(30_000, null, null, null, "DECISION", true));
        assertThat(res.allocations()).isNotEmpty();
        assertThat(res.diagnostic()).isTrue();
        assertThat(res.marketEvAfterCostsCents()).isNegative();
        assertThat(res.realizedVolEvAfterCostsCents()).isNegative();
        assertThat(res.notes()).anyMatch(n -> n.contains("DIAGNOSTIC"));
    }

    @Test void normalModeFundsOnlyThePositiveEvIdeasInAMixedField() {
        var good = eval("AAPL", "CREDIT_PUT_SPREAD", 60, 5_000, 250L, 350L, 500, true, EconomicAssessment.Verdict.FAVORABLE);
        var bad = eval("MSFT", "IRON_BUTTERFLY", 90, 5_000, -400L, -350L, 500, true, EconomicAssessment.Verdict.UNFAVORABLE);
        var unknown = eval("QQQ", "CALENDAR_CALL", 85, 5_000, null, null, 500, true, EconomicAssessment.Verdict.UNAVAILABLE);
        var res = optimizer.optimize(List.of(good, bad, unknown),
                new PortfolioOptimizer.Constraints(30_000, null, null, null, "DECISION", false));
        assertThat(res.allocations()).hasSize(1);
        assertThat(res.allocations().get(0).eval().symbol()).isEqualTo("AAPL");
        assertThat(res.marketEvAfterCostsCents()).isPositive();
        assertThat(res.realizedVolEvAfterCostsCents()).isPositive();
    }

    @Test void expertCanOrderTheSameFavorableSetByEitherEvLane() {
        var marketRich = eval("AAPL", "DEBIT_CALL_SPREAD", 60, 5_000, 500L, 100L, 500, true, EconomicAssessment.Verdict.FAVORABLE);
        var historyRich = eval("MSFT", "CREDIT_PUT_SPREAD", 60, 5_000, 100L, 700L, 500, true, EconomicAssessment.Verdict.FAVORABLE);

        var marketFirst = optimizer.optimize(List.of(historyRich, marketRich),
                new PortfolioOptimizer.Constraints(5_000, 5_000L, 1, 1.0, "MARKET_EV", false));
        var historyFirst = optimizer.optimize(List.of(marketRich, historyRich),
                new PortfolioOptimizer.Constraints(5_000, 5_000L, 1, 1.0, "HISTORY_EV", false));

        assertThat(marketFirst.allocations().getFirst().eval().symbol()).isEqualTo("AAPL");
        assertThat(historyFirst.allocations().getFirst().eval().symbol()).isEqualTo("MSFT");
    }

    @Test void obsoleteInternalObjectiveNamesAreRejectedInsteadOfSilentlyRemapped() {
        assertThatThrownBy(() -> optimizer.optimize(List.of(),
                new PortfolioOptimizer.Constraints(5_000, null, null, null, "score", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECISION");
    }
}
