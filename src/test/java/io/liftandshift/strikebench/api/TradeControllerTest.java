package io.liftandshift.strikebench.api;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.GreeksView;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.recommend.LegView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeControllerTest {

    @Test
    void heldTradeWirePublishesOneEntryPriceAndTypedCurrentUnavailability() {
        Leg put = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("2.00"));
        TradeRecord trade = new TradeRecord("tr_wire", "acct", "XYZ", "CASH_SECURED_PUT",
                TradeRecord.ACTIVE, 1, List.of(put), "income", "30d", "balanced",
                10_000L, 20_000L, 980_000L, 20_000L, List.of("98"), 0.7,
                65L, 65L, null, null, null, null, false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, "OBSERVED", "DELAYED", "fixture");

        TradeView wire = TradeView.of(trade).withCurrentMark(null, null, null, null, null, null,
                TradeService.CurrentMarketAvailability.unavailable("fixture mark failed"));
        var json = io.liftandshift.strikebench.util.Json.MAPPER.valueToTree(wire);

        assertThat(json.at("/entryPrice/grossPackageNetCents").asLong()).isEqualTo(20_000L);
        assertThat(json.at("/entryPrice/openingFeesCents").asLong()).isEqualTo(65L);
        assertThat(json.has("entryNetPremiumCents")).isFalse();
        assertThat(json.has("feesOpenCents")).isFalse();
        assertThat(json.has("feesCloseCents")).isFalse();
        assertThat(json.at("/currentMarketAvailability/quoteAvailable").asBoolean()).isFalse();
        assertThat(json.at("/currentMarketAvailability/quoteUnavailableReason").asText())
                .isEqualTo("fixture mark failed");
        assertThat(json.at("/currentMarketAvailability/greeksUnavailableReason").asText())
                .isEqualTo("fixture mark failed");
    }

    @Test
    void independentUnderlyingQuoteSurvivesAWholePackageMarkFailure() {
        Leg put = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("2.00"));
        TradeRecord trade = new TradeRecord("tr_quote_only", "acct", "XYZ", "CASH_SECURED_PUT",
                TradeRecord.ACTIVE, 1, List.of(put), "income", "30d", "balanced",
                10_000L, 20_000L, 980_000L, 20_000L, List.of("98"), 0.7,
                65L, 65L, null, null, null, null, false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, "OBSERVED", "DELAYED", "fixture");
        var availability = TradeService.CurrentMarketAvailability
                .unavailable("option package failed")
                .withQuote(true, null);

        TradeView wire = TradeView.of(trade).withCurrentMark(
                10_125L, null, null, null, null, null, availability);
        var json = io.liftandshift.strikebench.util.Json.MAPPER.valueToTree(wire);

        assertThat(json.at("/currentUnderlyingCents").asLong()).isEqualTo(10_125L);
        assertThat(json.at("/currentMarketAvailability/quoteAvailable").asBoolean()).isTrue();
        assertThat(json.at("/currentMarketAvailability/quoteUnavailableReason").isMissingNode())
                .isTrue();
        assertThat(json.at("/currentMarketAvailability/closeAvailable").asBoolean()).isFalse();
        assertThat(json.at("/currentMarketAvailability/closeUnavailableReason").asText())
                .isEqualTo("option package failed");
    }

    @Test
    void heldReceiptFailuresAreContainedToTheExactReceiptThatFailed() {
        Leg put = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("2.00"));
        TradeRecord trade = new TradeRecord("tr_isolated", "acct", "XYZ", "CASH_SECURED_PUT",
                TradeRecord.ACTIVE, 1, List.of(put), "income", "30d", "balanced",
                10_000L, 20_000L, 980_000L, 20_000L, List.of("98"), 0.7,
                65L, 65L, null, null, null, null, false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, "OBSERVED", "DELAYED", "fixture");
        var payoff = TradeController.heldTerminalPayoff(trade);
        var greeks = new GreeksView(20, -0.5, 700, -900);
        var scenarios = TradeController.heldScenarios(
                trade, quote("100.00", Freshness.DELAYED));
        var spotPnl = new ApiResponses.HeldSpotPnl(
                20_000L, 10_000L, "LIVE_MARK", "DELAYED", true, null);

        TradeController.HeldReceipts payoffFailed = TradeController.composeHeldReceipts(
                trade.id(),
                () -> { throw new IllegalStateException("curve fixture failed"); },
                greeks, () -> scenarios, () -> spotPnl);

        assertThat(payoffFailed.terminalPayoff().available()).isFalse();
        assertThat(payoffFailed.terminalPayoff().unavailableReason())
                .contains("terminal payoff").contains("curve fixture failed");
        assertThat(payoffFailed.greeks()).isSameAs(greeks);
        assertThat(payoffFailed.scenarios().available()).isTrue();
        assertThat(payoffFailed.scenarios()).isSameAs(scenarios);
        assertThat(payoffFailed.spotPnl()).isSameAs(spotPnl);

        TradeController.HeldReceipts scenariosFailed = TradeController.composeHeldReceipts(
                trade.id(), () -> payoff, greeks,
                () -> { throw new IllegalStateException("story fixture failed"); },
                () -> spotPnl);

        assertThat(scenariosFailed.terminalPayoff()).isSameAs(payoff);
        assertThat(scenariosFailed.greeks()).isSameAs(greeks);
        assertThat(scenariosFailed.scenarios().available()).isFalse();
        assertThat(scenariosFailed.scenarios().values()).isEmpty();
        assertThat(scenariosFailed.scenarios().unavailableReason())
                .contains("named scenarios").contains("story fixture failed");
        assertThat(scenariosFailed.spotPnl()).isSameAs(spotPnl);

        TradeController.HeldReceipts spotFailed = TradeController.composeHeldReceipts(
                trade.id(), () -> payoff, greeks, () -> scenarios,
                () -> { throw new IllegalStateException("spot fixture failed"); });

        assertThat(spotFailed.terminalPayoff()).isSameAs(payoff);
        assertThat(spotFailed.greeks()).isSameAs(greeks);
        assertThat(spotFailed.scenarios().available()).isTrue();
        assertThat(spotFailed.scenarios()).isSameAs(scenarios);
        assertThat(spotFailed.spotPnl().terminalPnlAtCurrentSpotCents()).isNull();
        assertThat(spotFailed.spotPnl().unavailableReason())
                .contains("spot P/L").contains("spot fixture failed");
    }

    @Test
    void controllerDoesNotSubstituteLegacyPackagePnlForAMissingDecisionPnl() {
        Leg put = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("2.00"));
        TradeRecord trade = new TradeRecord("tr_no_decision_pnl", "acct", "XYZ",
                "CASH_SECURED_PUT", TradeRecord.ACTIVE, 1, List.of(put), "income", "30d",
                "balanced", 10_000L, 20_000L, 980_000L, 20_000L, List.of("98"), 0.7,
                65L, 65L, null, null, null, null, false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, "OBSERVED", "DELAYED", "fixture");
        TradeService.MarkView legacyOnly = new TradeService.MarkView(
                trade.id(), "2026-07-16T12:00:00Z", 10_000L, 1_500L,
                12_345L, null, 0.7, "DELAYED", null, List.of());

        TradeView attached = TradeController.attachCurrentMark(TradeView.of(trade), legacyOnly);

        assertThat(attached.unrealizedPnlCents()).isEqualTo(12_345L);
        assertThat(attached.decisionUnrealizedPnlCents()).isNull();
        assertThat(attached.currentMarketAvailability().decisionPnlAvailable()).isFalse();
    }

    @Test
    void exactPreviewCandidateCarriesTheSameFingerprintWithoutRepricing() {
        LocalDate expiry = LocalDate.parse("2026-08-21");
        Leg put = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"),
                expiry, 1, new BigDecimal("2.00"));
        var price = io.liftandshift.strikebench.support.TestPrices.optionOnly(20_000L);
        var time = io.liftandshift.strikebench.market.OptionTime.toExpiry(
                Instant.parse("2026-07-22T15:30:00Z"), expiry);
        var marketRisk = io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer.analyze(
                io.liftandshift.strikebench.pricing.PayoffCurve.of(List.of(put), 1),
                price, 10_000L, 0.30, time, 0.04, List.of(new BigDecimal("100")));
        var request = new TradeService.OpenRequest("acct", "TEST", "CASH_SECURED_PUT", 1,
                List.of(put), "neutral", "month", "balanced", "INCOME", false,
                null, "TEST", "PROPOSED",
                io.liftandshift.strikebench.paper.OrderInstruction.market());
        Map<String, Object> markedLeg = Map.ofEntries(
                Map.entry("action", "SELL"), Map.entry("type", "PUT"),
                Map.entry("strike", "100"), Map.entry("expiration", expiry.toString()),
                Map.entry("ratio", 1), Map.entry("multiplier", 100),
                Map.entry("fill", "2.00"), Map.entry("bid", "2.00"),
                Map.entry("ask", "2.10"), Map.entry("source", "fixture"),
                Map.entry("freshness", "DELAYED"),
                Map.entry("asOfEpochMs", 1_784_050_200_000L),
                Map.entry("iv", 0.4287), Map.entry("delta", -0.3175));
        var preview = new io.liftandshift.strikebench.paper.TradePreview(
                true, List.of(), List.of(), 980_000L, 20_000L, List.of("98"),
                980_000L,
                10_000_000L, 10_019_935L, 0L, 980_000L,
                10_000_000L, 9_039_935L, "DELAYED",
                io.liftandshift.strikebench.model.DataEvidence.of(
                        "fixture", io.liftandshift.strikebench.model.Freshness.DELAYED),
                10_000L, 0.5, List.of(markedLeg), List.of(),
                Map.of(), price, null, marketRisk);

        var candidate = TradeController.exactPreviewCandidate(request, preview);

        assertThat(candidate.marketImpliedRisk()).isSameAs(preview.marketImpliedRisk());
        assertThat(candidate.marketImpliedRisk().fingerprint())
                .isEqualTo(marketRisk.fingerprint());
        assertThat(candidate.marketImpliedRisk().priceFingerprint())
                .isEqualTo(price.fingerprint());
        assertThat(candidate.marketImpliedRisk().pop()).isEqualTo(marketRisk.pop());
        assertThat(candidate.marketImpliedRisk().expectedValueCents())
                .isEqualTo(marketRisk.expectedValueCents());
        assertThat(candidate.legs().getFirst().quoteIv()).isEqualTo(0.4287);
        assertThat(candidate.legs().getFirst().quoteDelta()).isEqualTo(-0.3175);
    }

    @Test
    void exactPreviewRejectsAnInvalidQuantityInsteadOfPublishingAOneLotReceipt() {
        Leg put = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("2.00"));
        var invalid = new TradeService.OpenRequest("acct", "TEST", "CASH_SECURED_PUT", 0,
                List.of(put), "neutral", "month", "balanced", "INCOME", false,
                null, "TEST", "PROPOSED",
                io.liftandshift.strikebench.paper.OrderInstruction.market());

        assertThatThrownBy(() -> TradeController.exactPreviewNode(invalid, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity >= 1");
    }

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

    @Test
    void payoffKeepsEveryHeldShareWhenShareCountIsNotDivisibleByPackageQuantity() {
        Leg adjustedCall = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("110"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("1.00"), 10);
        TradeRecord trade = new TradeRecord("tr_odd_shares", "acct", "XYZ", "COVERED_CALL",
                TradeRecord.ACTIVE, 3, List.of(adjustedCall), "income", "30d", "balanced",
                10_000L, 3_000L, 90_000L, 33_000L, List.of(), null,
                0L, 0L, null, null, null, "{\"heldShareContextShares\":10}", false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, null, null, null);

        var payoff = TradeController.heldTerminalPayoff(trade);
        var low = payoff.points().stream()
                .filter(point -> point.price().compareTo(new BigDecimal("70.00")) == 0)
                .findFirst().orElseThrow();
        // Ten shares lose $300 at $70; the three adjusted calls retain their $30 credit.
        assertThat(low.profitCents()).isEqualTo(-27_000L);
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
        var atMark = TradeController.heldSpotPnl(
                trade, quote("105.00", Freshness.REALTIME));
        assertThat(atMark.spotBasis()).isEqualTo("LAST");
        assertThat(atMark.spotCents()).isEqualTo(10_500L);
        assertThat(atMark.freshness()).isEqualTo("REALTIME");
        assertThat(atMark.terminalPnlAtCurrentSpotCents())
                .isEqualTo(curveValueAt(trade, new BigDecimal("105.00")));

        // A package that has run far past the served window still gets an exact answer, flagged as
        // outside the drawn curve — where the browser's own interpolation had to say "unavailable".
        var atFar = TradeController.heldSpotPnl(
                trade, quote("200.00", Freshness.REALTIME));
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

    @Test
    void heldScenariosAreAnchoredToTheCurrentQuoteNotTheRecordedEntrySpot() {
        Leg put = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("502.50"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("29.55"));
        TradeRecord trade = new TradeRecord("tr_current_anchor", "acct", "AMD",
                "CASH_SECURED_PUT", TradeRecord.ACTIVE, 1, List.of(put),
                "income", "45d", "balanced",
                55_390L, 295_500L, 4_729_500L, 295_500L, List.of("472.95"), 0.64,
                65L, 65L, null, null, null, null, false,
                "2026-07-23T12:00:00Z", null, "2026-07-23T12:00:00Z", "INCOME", 0L,
                null, "OBSERVED", "STALE", "cboe");
        Quote current = quote("521.55", Freshness.STALE);

        ApiResponses.HeldScenarios receipt = TradeController.heldScenarios(trade, current);
        var gapDown = receipt.values().stream()
                .filter(row -> row.story()
                        == io.liftandshift.strikebench.model.ScenarioStory.GAP_DOWN)
                .findFirst().orElseThrow();

        assertThat(receipt.available()).isTrue();
        assertThat(receipt.anchorSpotCents()).isEqualTo(52_155L);
        assertThat(receipt.anchorBasis()).isEqualTo("LAST");
        assertThat(receipt.freshness()).isEqualTo("STALE");
        assertThat(receipt.source()).isEqualTo("test");
        assertThat(receipt.observedAt()).isEqualTo(1_785_134_400_000L);
        // A 9% fall from today's $521.55 lands near $474.61, still $1.66 above the
        // $472.95 breakeven. It must not retain the +$2,955 result produced by a move from
        // the old $553.90 entry.
        assertThat(gapDown.targetUnderlyingCents()).isEqualTo(47_461L);
        assertThat(gapDown.pnlCents()).isEqualTo(16_605L);
    }

    @Test
    void heldScenarioReceiptNamesUnmodelableAndMissingAnchorStates() {
        Leg near = Leg.option(LegAction.SELL, OptionType.CALL, new BigDecimal("110"),
                LocalDate.parse("2026-08-21"), 1, new BigDecimal("1.00"), 100);
        Leg far = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("110"),
                LocalDate.parse("2026-12-18"), 1, new BigDecimal("3.00"), 100);
        TradeRecord calendar = new TradeRecord("tr_cal_scenarios", "acct", "XYZ", "CALENDAR",
                TradeRecord.ACTIVE, 1, List.of(near, far), "neutral", "30d", "balanced",
                10_000L, -20_000L, 20_000L, null, List.of(), null,
                0L, 0L, null, null, null, null, false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, null, null, null);

        assertThat(TradeController.heldScenarios(calendar, quote("105.00", Freshness.DELAYED))
                .unavailableReason()).contains("mixed-expiration");

        TradeRecord single = new TradeRecord("tr_no_quote", "acct", "XYZ", "COVERED_CALL",
                TradeRecord.ACTIVE, 1, List.of(near), "income", "30d", "balanced",
                10_000L, 1_000L, 29_000L, 11_000L, List.of("101"), null,
                0L, 0L, null, null, null, "{\"heldShareContextShares\":10}", false,
                "2026-07-15T12:00:00Z", null, "2026-07-15T12:00:00Z", "INCOME", 0L,
                null, null, null, null);
        assertThat(TradeController.heldScenarios(single, null).unavailableReason())
                .contains("No current underlying quote").contains("entry price was not substituted");
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
                null, null, "ANALYZE", null, null, "EXECUTED", null);

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

    private static Quote quote(String last, Freshness freshness) {
        return new Quote("XYZ", "Test", new BigDecimal(last), null, null,
                new BigDecimal("100.00"), null, null, 1_000L, true,
                1_785_134_400_000L, "test", freshness);
    }
}
