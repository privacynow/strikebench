package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.recommend.LegView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeControllerTest {

    @Test
    void payoffUsesTheExactHeldShareCountInsteadOfAssumingOneHundredShares() {
        Leg adjustedCall = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("110"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("1.00"), 10);
        TradeRecord trade = new TradeRecord("tr_exact_shares", "acct", "XYZ", "COVERED_CALL",
                TradeRecord.ACTIVE, 1, List.of(adjustedCall), "income", "30d", "balanced",
                10_000L, 1_000L, 29_000L, 11_000L, List.of("101"), null,
                0L, 0L, null, null, null, "{\"heldShareContextShares\":10}", false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L, "PAPER",
                null, null, null, null, null, null, null);

        var low = TradeController.payoffPoints(trade).stream()
                .filter(point -> "70.00".equals(point.price()))
                .findFirst().orElseThrow();
        assertThat(low.profitCents()).isEqualTo(-29_000L);
    }

    @Test
    void analysisAcceptsLargeFactualQuantityWhilePracticePlacementKeepsItsCap() {
        TradeOpenRequest request = new TradeOpenRequest("AAPL", "CUSTOM", 500,
                List.of(new LegView("BUY", "STOCK", null, null, 1, "250", 1, "OPEN")),
                "bullish", "month", "balanced", "DIRECTIONAL", false,
                null, null, null, "ANALYZE", null, null, "EXECUTED");

        assertThat(TradeController.toAnalysisOpenRequest(request, "tracked-account").qty())
                .isEqualTo(500);
        assertThatThrownBy(() -> TradeController.toOpenRequest(request, "practice-account"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Practice placement");
    }

    @Test
    void unavailableDecisionAssessmentKeepsMechanicalTruthWithoutInventingAScore() {
        ApiResponses.EvaluationReceipt receipt = ApiResponses.EvaluationReceipt.unavailable(
                "Observed decision inputs are unavailable.", true, List.of(), 260L);

        assertThat(receipt.available()).isFalse();
        assertThat(receipt.decisionScore()).isNull();
        assertThat(receipt.viable()).isNull();
        assertThat(receipt.assessment().mechanics().eligible()).isTrue();
        assertThat(receipt.assessment().economics().verdict())
                .isEqualTo(io.liftandshift.strikebench.eval.EconomicAssessment.Verdict.UNAVAILABLE);
        assertThat(receipt.assessment().economics().estimatedRoundTripFeesCents()).isEqualTo(260L);
    }
}
