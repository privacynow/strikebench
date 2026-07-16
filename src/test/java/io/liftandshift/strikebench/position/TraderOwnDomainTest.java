package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.eval.FourOutputAssessment;
import io.liftandshift.strikebench.eval.StanceVector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraderOwnDomainTest {

    @Test
    void threeAxesStayIndependentAndPositionPackagesAcceptAllThreeSources() {
        assertThat(PositionDomain.AnalysisArtifactState.values()).containsExactly(
                PositionDomain.AnalysisArtifactState.DRAFT,
                PositionDomain.AnalysisArtifactState.FROZEN,
                PositionDomain.AnalysisArtifactState.RETIRED);
        assertThat(PositionDomain.ExecutionLane.values()).containsExactly(
                PositionDomain.ExecutionLane.NONE,
                PositionDomain.ExecutionLane.PRACTICE,
                PositionDomain.ExecutionLane.REAL);
        assertThat(PositionDomain.PositionState.values()).doesNotContainNull();

        var leg = new PositionPackage.Leg(0, "BUY", "STOCK", "MU", null, null, null,
                100, 1, new BigDecimal("979.30"), PositionDomain.PriceAuthority.BROKER_REPORTED);
        for (PositionDomain.PackageSource source : PositionDomain.PackageSource.values()) {
            PositionDomain.ExecutionLane lane = source == PositionDomain.PackageSource.HYPOTHETICAL_DRAFT
                    ? PositionDomain.ExecutionLane.NONE : source == PositionDomain.PackageSource.PRACTICE_TRADE
                    ? PositionDomain.ExecutionLane.PRACTICE : PositionDomain.ExecutionLane.REAL;
            assertThat(new PositionPackage("pkg-" + source, source, lane, "MU", 1, null,
                    OffsetDateTime.parse("2026-07-15T12:00:00Z"), List.of(leg)).source()).isEqualTo(source);
        }
    }

    @Test
    void campaignFormulasPinMuBasisModeVsMeanAndExactCash() {
        long netCredit = CampaignMath.campaignNetCredit(List.of(200_000L, -179_000L, 200_200L));
        assertThat(netCredit).isEqualTo(221_200L);
        assertThat(CampaignMath.campaignAdjustedEconomicBasisPerShareCents(
                9_800_000L, netCredit, 0, 0, 100)).isEqualTo(95_788L);

        var yields = CampaignMath.realizedVsHeadlineYield(200_000, 9_800_000,
                -244_000, 9_800_000, 24, 60);
        assertThat(yields.headlinePeriodPct()).isEqualByComparingTo("2.040816");
        assertThat(yields.realizedPeriodPct()).isEqualByComparingTo("-2.489796");
        assertThat(yields.headlineAnnualizedPct()).isPositive();
        assertThat(yields.realizedAnnualizedPct()).isNegative();

        assertThat(CampaignMath.scenarioOutcomeCents(-465_000, netCredit)).isEqualTo(-243_800L);
        assertThat(CampaignMath.churnRoundTripCostCents(11_800, 14_000, 100, 200))
                .isEqualTo(220_200L);
    }

    @Test
    void attributionAndBenchmarksUseOnlyDatedOrExplicitlyTaggedCash() {
        var holdings = List.of(new CampaignMath.HoldingWindow("NVDA", LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-03-01"), 100));
        var dividends = List.of(
                new CampaignMath.Dividend("NVDA", LocalDate.parse("2026-02-01"), 25),
                new CampaignMath.Dividend("NVDA", LocalDate.parse("2026-04-01"), 25));
        assertThat(CampaignMath.attributedDividends(holdings, dividends)).isEqualTo(2_500);
        assertThat(CampaignMath.explicitlyTaggedInterest("campaign-a", List.of(
                new CampaignMath.TaggedInterest("campaign-a", 1_200),
                new CampaignMath.TaggedInterest("campaign-b", 9_900)))).isEqualTo(1_200);
        assertThat(CampaignMath.cashBenchmark(List.of(
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-01-01"), 100_000),
                new CampaignMath.DatedCashFlow(LocalDate.parse("2026-02-01"), -10_000)),
                LocalDate.parse("2026-03-01"), 0)).isEqualTo(90_000);

        assertThat(CampaignMath.buyAndHoldBenchmark(
                List.of(new CampaignMath.DatedCashFlow(LocalDate.parse("2026-01-01"), 100_000)),
                List.of(new CampaignMath.DatedPrice(LocalDate.parse("2026-01-01"), 10_000),
                        new CampaignMath.DatedPrice(LocalDate.parse("2026-03-01"), 12_000)),
                List.of(new CampaignMath.BenchmarkDividend(LocalDate.parse("2026-02-01"), 100)),
                LocalDate.parse("2026-03-01"), CampaignMath.DividendTreatment.CASH)).isEqualTo(121_000);
    }

    @Test
    void packageRoundingPreservesSignedTotalWithoutInventingExtraCents() {
        List<Long> allocated = CampaignMath.largestRemainderCents(List.of(
                new BigDecimal("100.40"), new BigDecimal("-30.10"), new BigDecimal("-20.30")), 50);
        assertThat(allocated).containsExactly(100L, -30L, -20L);
        assertThat(CampaignMath.reconciles(allocated, 50)).isTrue();
        assertThatThrownBy(() -> CampaignMath.largestRemainderCents(
                List.of(new BigDecimal("1.10"), new BigDecimal("1.10")), 9))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reconcile");
    }

    @Test
    void stanceAggregationUsesFixedMoneyUnitsAndFailsOnOverflow() {
        var a = new StanceVector(1_000, -20, 50, 30, 10L, 20L, 30L, 40L, 20);
        var b = new StanceVector(-250, 5, -10, 15, 1L, 2L, 3L, 4L, 45);
        assertThat(StanceVector.aggregate(List.of(a, b))).isEqualTo(
                new StanceVector(750, -15, 40, 45, 11L, 22L, 33L, 44L, 45));
        assertThatThrownBy(() -> StanceVector.aggregate(Arrays.asList(
                new StanceVector(Long.MAX_VALUE, 0, 0, 0, 0L, 0L, 0L, 0L, 1),
                new StanceVector(1, 0, 0, 0, 0L, 0L, 0L, 0L, 1))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void participationSeparatesLocalDeltaFromTerminalCaptureAndRegimePoints() {
        var profile = ParticipationProfile.fromExactValues(150_000, 1_000_000,
                50_000, 50_000, 100, 10_000, 12_000,
                LocalDate.parse("2026-08-21"), "terminal payoff over the named interval",
                List.of(new ParticipationProfile.RegimePoint(11_000, "upside participation ends")));
        assertThat(profile.localParticipationBps()).isEqualTo(1_500);
        assertThat(profile.terminalUpsideCaptureBps()).isZero();
        assertThat(profile.regimePoints()).singleElement();
    }

    @Test
    void practiceAndRealImpactsCannotBePutInEachOthersSlot() {
        var practice = new FourOutputAssessment.PortfolioImpact(PositionDomain.ExecutionLane.PRACTICE,
                1, 2, 3, 4, 10.0, 20.0, List.of(), "dollar delta");
        var real = new FourOutputAssessment.PortfolioImpact(PositionDomain.ExecutionLane.REAL,
                5, 6, 7, 8, 30.0, 40.0, List.of(), "dollar delta");
        assertThat(new FourOutputAssessment.PortfolioImpacts(practice, real, List.of()).practice()).isSameAs(practice);
        assertThatThrownBy(() -> new FourOutputAssessment.PortfolioImpacts(real, practice, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordingMatrixRejectsMissingModeledAndInvalidZeroFills() {
        var brokerStock = new RecordingPolicy.LegFact("STOCK", "OPEN", "MU", null,
                new BigDecimal("979.30"), 100, 1, PositionDomain.PriceAuthority.BROKER_REPORTED);
        RecordingPolicy.validate(RecordingPolicy.EventType.TRADE, List.of(brokerStock));

        assertThatThrownBy(() -> RecordingPolicy.validate(RecordingPolicy.EventType.TRADE, List.of(
                new RecordingPolicy.LegFact("OPTION", "OPEN", "MU", new BigDecimal("980"),
                        new BigDecimal("20"), 1, 100, PositionDomain.PriceAuthority.MODELED))))
                .hasMessageContaining("modeled marks");
        assertThatThrownBy(() -> RecordingPolicy.validate(RecordingPolicy.EventType.TRADE, List.of(
                new RecordingPolicy.LegFact("OPTION", "OPEN", "MU", new BigDecimal("980"),
                        null, 1, 100, PositionDomain.PriceAuthority.BROKER_REPORTED))))
                .hasMessageContaining("exact price");

        var expired = new RecordingPolicy.LegFact("OPTION", "CLOSE", "MU", new BigDecimal("980"),
                BigDecimal.ZERO, 1, 100, PositionDomain.PriceAuthority.BROKER_REPORTED);
        RecordingPolicy.validate(RecordingPolicy.EventType.EXPIRATION, List.of(expired));
        var delivered = new RecordingPolicy.LegFact("STOCK", "OPEN", "MU", null,
                new BigDecimal("980"), 100, 1, PositionDomain.PriceAuthority.BROKER_REPORTED);
        RecordingPolicy.validate(RecordingPolicy.EventType.ASSIGNMENT, List.of(expired, delivered));
        assertThatThrownBy(() -> RecordingPolicy.validate(RecordingPolicy.EventType.ASSIGNMENT,
                List.of(expired, new RecordingPolicy.LegFact("STOCK", "OPEN", "MU", null,
                        new BigDecimal("979"), 100, 1, PositionDomain.PriceAuthority.BROKER_REPORTED))))
                .hasMessageContaining("option strike");
        assertThatThrownBy(() -> RecordingPolicy.validate(RecordingPolicy.EventType.ASSIGNMENT,
                List.of(expired, new RecordingPolicy.LegFact("STOCK", "OPEN", "MU", null,
                        new BigDecimal("980"), 99, 1, PositionDomain.PriceAuthority.BROKER_REPORTED))))
                .hasMessageContaining("quantity");
    }
}
