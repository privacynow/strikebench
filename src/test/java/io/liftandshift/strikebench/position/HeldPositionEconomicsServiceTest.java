package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.eval.DataCoverageReceipt;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.EvidenceLevel;
import io.liftandshift.strikebench.eval.FourOutputAssessment;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ProviderPoliteness;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.paper.TradeService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HeldPositionEconomicsServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2031-07-22T16:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate EXPIRATION = LocalDate.parse("2031-08-07");

    @Test void shortPutUsesAskForCloseAndTransformsFreshEyesWithoutReevaluating() {
        var service = new HeldPositionEconomicsService(CLOCK);
        var request = request(Leg.option(LegAction.SELL, OptionType.PUT,
                new BigDecimal("180"), EXPIRATION, 1, BigDecimal.ZERO));
        TradePreview preview = preview("0.47", "0.48", "0.475", 4_700L, 1_800_000L);

        PositionLifecycleReceipt receipt = service.compose(request, preview, evaluation());

        assertThat(receipt.currentChoice().freshEyesEconomicsRef())
                .isEqualTo(PositionLifecycleReceipt.FRESH_EYES_ECONOMICS_REF);
        assertThat(receipt.currentChoice().close()).satisfies(close -> {
            assertThat(close.executable()).isTrue();
            assertThat(close.signedMidCloseCashCents()).isEqualTo(-4_750L);
            assertThat(close.signedExecutableCloseCashCents()).isEqualTo(-4_800L);
            assertThat(close.closingFeesCents()).isEqualTo(65L);
            assertThat(close.signedNetCloseCashCents()).isEqualTo(-4_865L);
            assertThat(close.priceAuthority()).isEqualTo(PositionDomain.PriceAuthority.OBSERVED);
        });
        // Substitution = -(4,700 - 65) - (-4,865) = +230. The canonical model values
        // remain untouched; only today's executable cash leg changes.
        assertThat(receipt.currentChoice().holdVsClose().marketEvAfterCostsCents()).isZero();
        assertThat(receipt.currentChoice().holdVsClose().realizedVolEvAfterCostsCents()).isEqualTo(730L);
        assertThat(receipt.currentChoice().holdVsClose().realisticLowAfterCostsCents()).isEqualTo(130L);
        assertThat(receipt.currentChoice().expectedShortfallCents()).isEqualTo(180_000L);

        assertThat(receipt.carryCollateral()).satisfies(carry -> {
            assertThat(carry.grossRemainingPremiumCents()).isEqualTo(4_800L);
            assertThat(carry.calendarDaysRemaining()).isEqualTo(16);
            assertThat(carry.grossAnnualizedRemainingPremiumPct()).isEqualTo(6.0833);
            assertThat(carry.collateral().cents()).isEqualTo(1_800_000L);
            assertThat(carry.collateral().authority()).isEqualTo(PositionDomain.FactAuthority.MODEL_DERIVED);
            assertThat(carry.concurrentCollateralIncome().authority())
                    .isEqualTo(PositionDomain.FactAuthority.UNAVAILABLE);
            assertThat(carry.capitalReleasedByClosing().basis()).contains("not a broker buying-power claim");
        });
        assertThat(receipt.assignmentExit().legs()).singleElement().satisfies(leg -> {
            assertThat(leg.shares()).isEqualTo(100L);
            assertThat(leg.strikeDollarsCents()).isEqualTo(1_800_000L);
            assertThat(leg.freshEyesEffectivePricePerShareCents()).isEqualTo(17_953L);
            assertThat(leg.consequence()).isEqualTo("BUY_SHARES");
        });
        assertThat(receipt.history().available()).isFalse();
        assertThat(receipt.evidence().policyFingerprint()).isEqualTo("FACTS_ONLY");
        assertThat(receipt.evidence().marketSnapshotFingerprint()).hasSize(64);
        assertThat(receipt.positionFingerprint()).hasSize(64);
    }

    @Test void missingOppositeSideRefusesCloseCarryAndHoldEconomics() {
        var service = new HeldPositionEconomicsService(CLOCK);
        var request = request(Leg.option(LegAction.SELL, OptionType.PUT,
                new BigDecimal("180"), EXPIRATION, 1, BigDecimal.ZERO));
        TradePreview preview = preview("0.47", null, "0.475", 4_700L, 1_800_000L);

        PositionLifecycleReceipt receipt = service.compose(request, preview, evaluation());

        assertThat(receipt.currentChoice().close().executable()).isFalse();
        assertThat(receipt.currentChoice().close().unavailableReason()).contains("ask");
        assertThat(receipt.currentChoice().holdVsClose().available()).isFalse();
        assertThat(receipt.carryCollateral().grossRemainingPremiumCents()).isNull();
        assertThat(receipt.currentChoice().limitations()).anyMatch(x -> x.contains("ask"));
    }

    @Test void positionIdentityIgnoresChangingQuotesWhileSnapshotIdentityDoesNot() {
        var service = new HeldPositionEconomicsService(CLOCK);
        var request = request(Leg.option(LegAction.SELL, OptionType.CALL,
                new BigDecimal("220"), EXPIRATION, 1, BigDecimal.ZERO));
        PositionLifecycleReceipt first = service.compose(request,
                preview("2.10", "2.20", "2.15", 21_000L, 0), evaluation());
        PositionLifecycleReceipt later = service.compose(request,
                preview("2.30", "2.40", "2.35", 23_000L, 0), evaluation());

        assertThat(first.positionFingerprint()).isEqualTo(later.positionFingerprint());
        assertThat(first.evidence().marketSnapshotFingerprint())
                .isNotEqualTo(later.evidence().marketSnapshotFingerprint());
        assertThat(first.assignmentExit().legs()).singleElement().satisfies(leg -> {
            assertThat(leg.strikeDollarsCents()).isEqualTo(2_200_000L);
            assertThat(leg.freshEyesEffectivePricePerShareCents()).isEqualTo(22_210L);
            assertThat(leg.consequence()).contains("SELL");
        });
    }

    @Test void canonicalConfirmedEventCrossingFlowsIntoTheLifecycleReceipt() {
        EventService.IssuerEventProvider issuer = symbol -> Optional.of(new EventService.IssuerEvent(
                LocalDate.parse("2031-08-04"), EventService.EventSession.AFTER_CLOSE,
                "NVIDIA Investor Relations", "https://investor.nvidia.com/events",
                CLOCK.instant(), "confirmed-event"));
        EventService events = new EventService(new MarketDataService(List.of(), List.of(), List.of()),
                null, CLOCK, List.of(issuer),
                new ProviderPoliteness("issuer-test", 1, 0, 60_000),
                new ProviderPoliteness("sec-test", 1, 0, 60_000));
        var service = new HeldPositionEconomicsService(CLOCK, events);

        PositionLifecycleReceipt receipt = service.compose(request(Leg.option(LegAction.SELL, OptionType.PUT,
                        new BigDecimal("180"), EXPIRATION, 1, BigDecimal.ZERO)),
                preview("0.47", "0.48", "0.475", 4_700L, 1_800_000L), evaluation());

        assertThat(receipt.assignmentExit().eventEvidenceStatus()).isEqualTo("CONFIRMED");
        assertThat(receipt.assignmentExit().eventCrossings()).singleElement().satisfies(event -> {
            assertThat(event.eventDate()).isEqualTo(LocalDate.parse("2031-08-04"));
            assertThat(event.session()).isEqualTo("AFTER_CLOSE");
            assertThat(event.source()).isEqualTo("NVIDIA Investor Relations");
            assertThat(event.payloadFingerprint()).hasSize(64);
        });
        assertThat(receipt.evidence().sourceRefs()).anyMatch(ref -> ref.startsWith("event:"));
        assertThat(receipt.assignmentExit().limitations())
                .noneMatch(note -> note.contains("Event crossings remain unavailable"));
    }

    private static TradeService.OpenRequest request(Leg leg) {
        return new TradeService.OpenRequest("tracked", "NVDA", "CASH_SECURED_PUT", 1,
                List.of(leg), null, null, null, "INCOME", false,
                null, null, "TEST", "PROPOSED");
    }

    private static TradePreview preview(String bid, String ask, String mid,
                                        long entryNet, long reserve) {
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("action", "SELL");
        leg.put("type", "PUT");
        leg.put("strike", "180");
        leg.put("expiration", EXPIRATION.toString());
        leg.put("ratio", 1);
        leg.put("multiplier", 100);
        leg.put("fill", bid);
        leg.put("bid", bid);
        leg.put("ask", ask);
        leg.put("mid", mid);
        leg.put("freshness", "DELAYED");
        leg.put("provenance", "OBSERVED");
        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("probabilityMap", Map.of("cvar95Cents", -180_000L));
        analytics.put("sourceAsOfEpochMs", 1_942_488_000_000L);
        analytics.put("evaluatedAtEpochMs", 1_942_488_100_000L);
        return new TradePreview(true, List.of(), List.of(), entryNet, 65,
                1_800_000L, entryNet, List.of("179.53"), .90, -100L, reserve,
                100_000_000L, 100_000_000L + entryNet - 65,
                0, reserve, 100_000_000L, 100_000_000L + entryNet - 65 - reserve,
                "DELAYED", DataEvidence.of("observed test book", Freshness.DELAYED),
                21_000L, .10, List.of(leg), List.of(), analytics);
    }

    private static StrategyEvaluation evaluation() {
        var economics = new EconomicAssessment(EconomicAssessment.Verdict.MIXED,
                "COMPARE_CAREFULLY", "Mixed", "Test economics", -230L, 500L,
                130L, -0.01, -100L, 800L, 50L,
                "Market-implied cost benchmark.", "Observed realized-volatility sensitivity.",
                true, List.of("Test receipt."));
        var assessment = new FourOutputAssessment(
                new FourOutputAssessment.MechanicalAssessment(true, List.of()), economics,
                new FourOutputAssessment.ObjectiveCoherence(FourOutputAssessment.Coherence.UNDECLARED,
                        "Undeclared", "Undeclared", List.of()),
                new FourOutputAssessment.PortfolioImpacts(null, null, List.of()));
        var coverage = new DataCoverageReceipt(
                Map.of("pricing", new DataCoverageReceipt.InputCoverage(EvidenceLevel.OBSERVED_DELAYED,
                        "Observed delayed option book.")), "Black-Scholes + exact payoff", List.of());
        return new StrategyEvaluation("evaluation", null, null, null, null, null, null,
                null, null, assessment, null, null, null, null, coverage, null);
    }
}
