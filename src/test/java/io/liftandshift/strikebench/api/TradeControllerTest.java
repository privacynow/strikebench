package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
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
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, null, null, null);

        // §5.4: ONE held payoff receipt. The second `price`/`profitCents` list is deleted, so the
        // held-share arithmetic is asserted on the receipt every surface actually reads.
        var payoff = TradeController.heldTerminalPayoff(trade);
        assertThat(payoff.available()).isTrue();
        var low = payoff.points().stream()
                .filter(point -> point.price().compareTo(new BigDecimal("70.00")) == 0)
                .findFirst().orElseThrow();
        assertThat(low.profitCents()).isEqualTo(-29_000L);
    }

    /**
     * §5.4: "If price holds" is an ENGINE figure. The browser may interpolate the served polyline to
     * place pixels; it may not originate the number. The receipt states the exact curve value at the
     * spot it names, and it agrees with the polyline it ships alongside.
     */
    @Test
    void ifPriceHoldsIsTheEnginesOwnCurveValueAtTheSpotTheReceiptNames() {
        Leg shortCall = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("110"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("1.00"), 10);
        TradeRecord trade = new TradeRecord("tr_holds", "acct", "XYZ", "COVERED_CALL",
                TradeRecord.ACTIVE, 1, List.of(shortCall), "income", "30d", "balanced",
                10_000L, 1_000L, 29_000L, 11_000L, List.of("101"), null,
                0L, 0L, null, null, null, "{\"heldShareContextShares\":10}", false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, null, null, null);

        // No current mark: the entry remains the curve anchor, but it is never substituted as
        // today's quote.
        var atEntry = TradeController.heldSpotPnl(trade, null);
        assertThat(atEntry.spotBasis()).isNull();
        assertThat(atEntry.spotCents()).isNull();
        assertThat(atEntry.terminalPnlAtCurrentSpotCents()).isNull();
        assertThat(atEntry.unavailableReason())
                .contains("No current underlying quote")
                .contains("entry price was not substituted");

        // With a live mark the figure moves to the live spot — and still equals the served curve.
        var mark = new TradeService.MarkView("tr_holds", "2026-07-16T12:00:00Z", 10_500L,
                null, null, null, null, "REALTIME", null, List.of());
        var atMark = TradeController.heldSpotPnl(trade, mark);
        assertThat(atMark.spotBasis()).isEqualTo("LIVE_MARK");
        assertThat(atMark.spotCents()).isEqualTo(10_500L);
        assertThat(atMark.freshness()).isEqualTo("REALTIME");
        assertThat(atMark.terminalPnlAtCurrentSpotCents())
                .isEqualTo(curveValueAt(trade, new BigDecimal("105.00")));

        // A package that has run far past the served window still gets an exact answer, flagged as
        // outside the drawn curve — where the browser's own interpolation had to say "unavailable".
        var farMark = new TradeService.MarkView("tr_holds", "2026-07-16T12:00:00Z", 20_000L,
                null, null, null, null, "REALTIME", null, List.of());
        var atFar = TradeController.heldSpotPnl(trade, farMark);
        assertThat(atFar.withinServedCurve()).isFalse();
        assertThat(atFar.terminalPnlAtCurrentSpotCents()).isNotNull();
        assertThat(atFar.unavailableReason()).isNull();
    }

    /**
     * §3.2: a mixed-expiration package has no single-date curve, so the holds-P/L is refused WITH a
     * reason instead of being reported as a 0.
     */
    @Test
    void ifPriceHoldsIsRefusedWithAReasonWhenNoSingleDateCurveExists() {
        Leg near = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("110"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("1.00"), 100);
        Leg far = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("110"),
                LocalDate.parse("2026-12-18"), 1, new BigDecimal("3.00"), 100);
        TradeRecord calendar = new TradeRecord("tr_cal", "acct", "XYZ", "CALENDAR",
                TradeRecord.ACTIVE, 1, List.of(near, far), "neutral", "30d", "balanced",
                10_000L, -20_000L, 20_000L, null, List.of(), null,
                0L, 0L, null, null, null, null, false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, null, null, null);

        var refused = TradeController.heldSpotPnl(calendar, null);
        assertThat(refused.terminalPnlAtCurrentSpotCents()).isNull();
        assertThat(refused.spotCents()).isNull();
        assertThat(refused.unavailableReason()).contains("mixed-expiration");
        // The payoff receipt refuses for the SAME reason — one curve, one story.
        assertThat(TradeController.heldTerminalPayoff(calendar).available()).isFalse();
    }

    /** Linear interpolation of the SERVED polyline — what the browser is still allowed to do. */
    private static long curveValueAt(TradeRecord trade, BigDecimal price) {
        var points = TradeController.heldTerminalPayoff(trade).points();
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            if (price.compareTo(b.price()) > 0) continue;
            double span = b.price().subtract(a.price()).doubleValue();
            double w = span == 0 ? 0 : price.subtract(a.price()).doubleValue() / span;
            return Math.round(a.profitCents() + (b.profitCents() - a.profitCents()) * w);
        }
        throw new IllegalStateException("price " + price + " is outside the served curve");
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

        assertThatThrownBy(() -> ApiResponses.EvaluationReceipt.unavailable(
                "Observed decision inputs are unavailable.", true, List.of(), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fees cannot be negative");
    }
}
