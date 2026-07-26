package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.eval.EvalContext;
import io.liftandshift.strikebench.eval.RiskProfile;
import io.liftandshift.strikebench.eval.RiskProfiler;
import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.support.TestPrices;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class RiskNeutralEvaluationReceiptTest {
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);
    private static final Instant AS_OF = Instant.parse("2026-07-22T15:30:00Z");
    private static final long SPOT_CENTS = 10_000L;
    private static final double IV = 0.30;
    private static final double RATE = 0.04;

    @Test
    void oneFingerprintOwnsCandidatePreviewPopEvAndNamedScenarioMasses() {
        Fixture fixture = fixture(IV, RATE, SPOT_CENTS, AS_OF,
                TestPrices.optionOnly(20_000L));
        Candidate candidate = candidate(fixture, fixture.receipt());
        TradePreview preview = preview(fixture, fixture.receipt());
        RiskProfile risk = new RiskProfiler().profile(candidate, context(fixture));

        assertThat(candidate.marketImpliedRisk()).isSameAs(fixture.receipt());
        assertThat(preview.marketImpliedRisk()).isSameAs(fixture.receipt());
        assertThat(risk.marketImpliedRisk()).isSameAs(fixture.receipt());
        assertThat(candidate.marketImpliedRisk().fingerprint())
                .isEqualTo(preview.marketImpliedRisk().fingerprint())
                .isEqualTo(risk.marketImpliedRisk().fingerprint());
        assertThat(fixture.receipt().priceFingerprint())
                .isEqualTo(fixture.price().fingerprint());
        assertThat(fixture.receipt().underlyingCents()).isEqualTo(SPOT_CENTS);
        assertThat(fixture.receipt().marketIv()).isEqualTo(IV);
        assertThat(fixture.receipt().riskFreeRate()).isEqualTo(RATE);
        assertThat(fixture.receipt().time()).isEqualTo(fixture.time());

        assertThat(candidate.pop()).isEqualTo(fixture.receipt().pop());
        assertThat(candidate.expectedValueCents())
                .isEqualTo(fixture.receipt().expectedValueCents());
        assertThat(preview.popEntry()).isEqualTo(fixture.receipt().pop());
        assertThat(preview.expectedValueCents())
                .isEqualTo(fixture.receipt().expectedValueCents());
        assertThat(risk.pop()).isEqualTo(fixture.receipt().pop());
        assertThat(risk.expectedValueCents())
                .isEqualTo(fixture.receipt().expectedValueCents());

        Map<io.liftandshift.strikebench.model.ScenarioStory, Double> captured =
                fixture.receipt().scenarioMasses().stream().collect(
                        java.util.stream.Collectors.toMap(
                                RiskNeutralAnalyzer.ScenarioMass::story,
                                RiskNeutralAnalyzer.ScenarioMass::probability));
        assertThat(risk.scenarios()).hasSize(captured.size());
        assertThat(risk.scenarios()).allSatisfy(scenario ->
                assertThat(scenario.prob()).isEqualTo(captured.get(scenario.story())));
        assertThat(risk.scenarios().stream().mapToDouble(RiskProfile.Scenario::prob).sum())
                .isCloseTo(1.0, offset(0.001));
    }

    @Test
    void candidateAndRiskProfileDoNotStoreParallelPopOrEvComponents() {
        assertThat(java.util.Arrays.stream(Candidate.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("pop", "expectedValueCents");
        assertThat(java.util.Arrays.stream(RiskProfile.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("pop", "expectedValueCents");
    }

    @Test
    void everyCapturedInputParticipatesInTheFingerprint() {
        var price = TestPrices.optionOnly(20_000L);
        String base = fixture(IV, RATE, SPOT_CENTS, AS_OF, price).receipt().fingerprint();

        assertThat(fixture(IV + 0.01, RATE, SPOT_CENTS, AS_OF, price).receipt().fingerprint())
                .isNotEqualTo(base);
        assertThat(fixture(IV, RATE + 0.01, SPOT_CENTS, AS_OF, price).receipt().fingerprint())
                .isNotEqualTo(base);
        assertThat(fixture(IV, RATE, SPOT_CENTS + 100, AS_OF, price).receipt().fingerprint())
                .isNotEqualTo(base);
        assertThat(fixture(IV, RATE, SPOT_CENTS, AS_OF.plusSeconds(86_400), price)
                .receipt().fingerprint()).isNotEqualTo(base);
        assertThat(fixture(IV, RATE, SPOT_CENTS, AS_OF,
                price(21_000L, "other-package-price")).receipt().fingerprint()).isNotEqualTo(base);
    }

    @Test
    void missingOrMismatchedReceiptFailsClosed() {
        Fixture fixture = fixture(IV, RATE, SPOT_CENTS, AS_OF,
                TestPrices.optionOnly(20_000L));
        Candidate unavailable = unavailableCandidate(fixture);
        RiskProfile risk = new RiskProfiler().profile(unavailable, context(fixture));

        assertThat(unavailable.marketImpliedRisk().available()).isFalse();
        assertThat(risk.pop()).isNull();
        assertThat(risk.expectedValueCents()).isNull();
        assertThat(risk.scenarios()).allSatisfy(s -> assertThat(s.prob()).isNull());

        Fixture otherPrice = fixture(IV, RATE, SPOT_CENTS, AS_OF,
                price(21_000L, "other-package-price"));
        assertThatThrownBy(() -> candidate(otherPrice, fixture.receipt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("package-price fingerprint");
        assertThatThrownBy(() -> RiskNeutralAnalyzer.analyze(fixture.curve(),
                io.liftandshift.strikebench.paper.PackagePriceReceipt.unavailable(
                        1, io.liftandshift.strikebench.paper.PackagePriceReceipt.FeeSide.OPENING,
                        "missing exact price"),
                SPOT_CENTS, IV, fixture.time(), RATE, List.of(new BigDecimal("100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("package-price fingerprint");
    }

    private static Fixture fixture(double iv, double rate, long spotCents, Instant asOf,
                                   io.liftandshift.strikebench.paper.PackagePriceReceipt price) {
        Leg leg = Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("100"), EXPIRY,
                1, new BigDecimal("2.00"));
        PayoffCurve curve = PayoffCurve.of(List.of(leg), 1);
        OptionTime.Measure time = OptionTime.toExpiry(asOf, EXPIRY);
        RiskNeutralAnalyzer.Receipt receipt = RiskNeutralAnalyzer.analyze(curve, price,
                spotCents, iv, time, rate, List.of(new BigDecimal("100")));
        return new Fixture(leg, curve, price, time, receipt);
    }

    private static io.liftandshift.strikebench.paper.PackagePriceReceipt price(
            long grossCents, String fingerprint) {
        return io.liftandshift.strikebench.paper.PackagePriceReceipt.of(1, grossCents,
                grossCents, 0L, null, null,
                io.liftandshift.strikebench.paper.PackagePriceReceipt.FeeSide.OPENING,
                grossCents, io.liftandshift.strikebench.paper.OrderInstruction.market(),
                io.liftandshift.strikebench.paper.OrderInstruction.Executability.IMMEDIATE,
                io.liftandshift.strikebench.paper.PackagePriceReceipt.ValuationBasis.EXECUTABLE_BOOK,
                "fixture", "DELAYED", 1_785_000_000_000L, fingerprint);
    }

    private static Candidate candidate(Fixture fixture, RiskNeutralAnalyzer.Receipt receipt) {
        return new Candidate("CASH_SECURED_PUT", "Cash-secured put", "acquisition_income",
                "SELL 100P", List.of(LegView.of(fixture.leg())), 1, fixture.price(),
                20_000L, 980_000L, List.of("98"), 0.9, "DELAYED", List.of(), 0.8,
                "Exact package", "Credit", "Assignment", "Price changes", "Sell put",
                "INCOME", List.of("INCOME", "ACQUIRE"), 0.5, null, "98.00", null,
                false, null, null, receipt);
    }

    private static Candidate unavailableCandidate(Fixture fixture) {
        return new Candidate("CASH_SECURED_PUT", "Cash-secured put", "acquisition_income",
                "SELL 100P", List.of(LegView.of(fixture.leg())), 1, fixture.price(),
                20_000L, 980_000L, List.of("98"),
                0.9, "DELAYED", List.of(), 0.8, "Unavailable", "Credit", "Assignment",
                "Price changes", "Sell put", "INCOME", List.of("INCOME"), 0.5,
                null, null, null, false, null, null,
                RiskNeutralAnalyzer.Receipt.unavailable(
                        "This fixture deliberately has no market-implied evaluation."));
    }

    private static TradePreview preview(Fixture fixture,
                                        RiskNeutralAnalyzer.Receipt receipt) {
        return new TradePreview(true, List.of(), List.of(), 980_000L, 20_000L,
                List.of("98"), receipt.pop(), receipt.expectedValueCents(), 980_000L,
                10_000_000L, 10_019_935L, 0L, 980_000L, 10_000_000L, 9_039_935L,
                "DELAYED", DataEvidence.of("fixture", Freshness.DELAYED), SPOT_CENTS,
                0.5, List.of(), List.of(), Map.of("marketImpliedRisk", receipt),
                fixture.price(), receipt);
    }

    private static EvalContext context(Fixture fixture) {
        return new EvalContext("TEST", SPOT_CENTS,
                LocalDate.ofInstant(fixture.time().asOf(),
                        io.liftandshift.strikebench.market.MarketHours.EASTERN),
                fixture.time(), IV, 0.25, List.of(), 10_000_000L, true, RATE,
                DataEvidence.of("fixture rate", Freshness.EOD), null, null, null, List.of(),
                DataEvidence.of("fixture history", Freshness.EOD));
    }

    private record Fixture(Leg leg, PayoffCurve curve,
                           io.liftandshift.strikebench.paper.PackagePriceReceipt price,
                           OptionTime.Measure time, RiskNeutralAnalyzer.Receipt receipt) {}
}
