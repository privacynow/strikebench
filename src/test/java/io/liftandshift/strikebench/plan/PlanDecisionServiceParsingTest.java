package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.paper.OrderInstruction;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.pricing.ProbabilityMap;
import io.liftandshift.strikebench.pricing.RiskNeutralAnalyzer;
import io.liftandshift.strikebench.support.TestMarketRiskReceipts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanDecisionServiceParsingTest {
    @Test void exactDecisionNumbersPreservePrecisionAndRejectMalformedValues() {
        assertThat(PlanDecisionService.decisionPrice("12.3456"))
                .isEqualByComparingTo(new BigDecimal("12.3456"));
        assertThat(PlanDecisionService.decisionDecimal("0.275")).isEqualTo(0.275);
        assertThat(PlanDecisionService.decisionInteger("3", "ratio")).isEqualTo(3);
        assertThat(PlanDecisionService.decisionPrice(null)).isNull();

        assertThatThrownBy(() -> PlanDecisionService.decisionPrice("not-a-price"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid price");
        assertThatThrownBy(() -> PlanDecisionService.decisionDecimal("NaN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid decimal");
        assertThatThrownBy(() -> PlanDecisionService.decisionInteger(0, "ratio"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid ratio");
    }

    @Test
    void createTimeReceiptMustMatchEveryReviewedFactNotOnlyNetAndFee() {
        PackagePriceReceipt reviewed = PackagePriceReceipt.of(1, 10_000L, 10_000L, 0L,
                130L, 260L, PackagePriceReceipt.FeeSide.OPENING, 10_000L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                "cboe", "DELAYED", 1_000L, "reviewed-fingerprint");
        PackagePriceReceipt restampedBook = PackagePriceReceipt.of(1, 10_000L, 10_000L, 0L,
                130L, 260L, PackagePriceReceipt.FeeSide.OPENING, 10_000L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                "cboe", "DELAYED", 2_000L, "restamped-fingerprint");
        PackagePriceReceipt movedBook = PackagePriceReceipt.of(1, 10_000L, 10_000L, 0L,
                130L, 260L, PackagePriceReceipt.FeeSide.OPENING, 9_900L,
                OrderInstruction.market(), OrderInstruction.Executability.IMMEDIATE,
                PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                "cboe", "DELAYED", 2_000L, "different-book-fingerprint");
        TradeRecord trade = new TradeRecord(
                "tr", "acct", "AAPL", "CUSTOM", TradeRecord.ACTIVE, 1, List.of(),
                null, null, null, 20_000L, 10_000L, 50_000L, 10_000L, List.of(),
                .5, 130L, 0L, null, null, null, "{}", false,
                "2026-07-26T00:00:00Z", null, "2026-07-26T00:00:00Z",
                null, 0L, null, "OBSERVED", "DELAYED", "cboe");
        var reviewedRange =
                io.liftandshift.strikebench.sim.SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                        200, .30,
                        new io.liftandshift.strikebench.pricing.ExpectedMove.ScenarioHorizon(21),
                        "2026-08-21", 26, .04);
        var movedRange =
                io.liftandshift.strikebench.sim.SimulationEngine.MarketImpliedRange.forScenarioHorizon(
                        200, .40,
                        new io.liftandshift.strikebench.pricing.ExpectedMove.ScenarioHorizon(21),
                        "2026-08-21", 26, .04);

        TradePreview reviewedFacts = preview(reviewed, risk(reviewed, .5), reviewedRange, 1_000L);
        TradePreview movedPricePreview = preview(movedBook, risk(movedBook, .5), reviewedRange, 1_000L);
        TradePreview movedRiskPreview = preview(reviewed, risk(reviewed, .7), reviewedRange, 2_000L);
        TradePreview movedRangePreview = preview(reviewed, risk(reviewed, .5), movedRange, 1_000L);
        TradePreview sameFactsLater = preview(restampedBook, risk(restampedBook, .5), reviewedRange, 9_000L);

        assertThatThrownBy(() -> PlanDecisionService.frozenPreview(
                reviewedFacts, trade, movedPricePreview))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed after review");
        assertThatThrownBy(() -> PlanDecisionService.frozenPreview(
                reviewedFacts, trade, movedRiskPreview))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decision evidence changed");
        assertThatThrownBy(() -> PlanDecisionService.frozenPreview(
                reviewedFacts, trade, movedRangePreview))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decision evidence changed");
        assertThat(PlanDecisionService.frozenPreview(reviewedFacts, trade, sameFactsLater))
                .isSameAs(sameFactsLater);
    }

    private static TradePreview preview(PackagePriceReceipt price,
                                        RiskNeutralAnalyzer.Receipt marketRisk,
                                        io.liftandshift.strikebench.sim.SimulationEngine.MarketImpliedRange
                                                marketImpliedRange,
                                        long evaluatedAtEpochMs) {
        return new TradePreview(true, List.of(), List.of(), 50_000L, 10_000L, List.of(),
                0L, 1_000_000L, 1_009_870L, 0L, 0L,
                1_000_000L, 1_009_870L, "DELAYED", null, 20_000L, null,
                List.of(Map.of(
                        "action", "SELL", "type", "PUT", "strike", "100",
                        "bidCents", 200L, "askCents", 210L,
                        "asOfEpochMs", evaluatedAtEpochMs)),
                List.of(), Map.of("evaluatedAtEpochMs", evaluatedAtEpochMs),
                price, marketImpliedRange, marketRisk);
    }

    private static RiskNeutralAnalyzer.Receipt risk(PackagePriceReceipt price,
                                                     double pMaxLoss) {
        RiskNeutralAnalyzer.Receipt seed =
                TestMarketRiskReceipts.receipt(price, .5, 1_000L);
        var probability = new ProbabilityMap.Result(.2, .1, pMaxLoss,
                Math.max(0, .7 - pMaxLoss), -10_000L, -20_000L, List.of(),
                "typed decision fixture");
        return new RiskNeutralAnalyzer.Receipt(
                seed.schemaVersion(), seed.modelVersion(), true, null,
                seed.fingerprint(), seed.priceFingerprint(), seed.underlyingCents(),
                seed.marketIv(), seed.riskFreeRate(), seed.time(), probability,
                seed.expectedValueCents(), seed.sensitivity(), seed.scenarioMasses());
    }
}
