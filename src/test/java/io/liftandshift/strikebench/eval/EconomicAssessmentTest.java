package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.pricing.PayoffCurve;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicAssessmentTest {

    private Candidate candidate(double pop) {
        return new Candidate("DEBIT_CALL_SPREAD", "Bull call spread", "debit_vertical", "BUY 100C / SELL 105C",
                List.of(new LegView("BUY", "CALL", "100", "2026-08-21", 1, "4.00"),
                        new LegView("SELL", "CALL", "105", "2026-08-21", 1, "2.00")),
                1, -20_000, 30_000L, 20_000, List.of("102"), pop, 0L, 0.8,
                "DELAYED", List.of(), 0.7, "test", "test", "test", "test", "test",
                "DIRECTIONAL", List.of("DIRECTIONAL"), null, null, null, null, false, null, null);
    }

    private EvalContext ctx() {
        return new EvalContext("AAPL", 10_000, 30, 0.30, 0.25, List.of(), 1_000_000, true, 65,
                0, 0.04, io.liftandshift.strikebench.model.DataEvidence.of("treasury", io.liftandshift.strikebench.model.Freshness.EOD));
    }

    private EvidenceProfile observed() {
        return EvidenceProfile.of(Map.of("pricing", EvidenceLevel.OBSERVED_DELAYED,
                "history", EvidenceLevel.OBSERVED_EOD), "test");
    }

    private ScoreBreakdown pass() {
        return new ScoreBreakdown(true, List.of(), 50, 50, List.of());
    }

    @Test void materiallyNegativeEconomicsStayAvailableAsATeachingCase() {
        RiskProfile risk = new RiskProfile(20_000, 30_000L, 0.24, -5_000L,
                20_000, 0.20, List.of(), -4_000L, "test");
        EconomicAssessment a = EconomicAssessment.assess(candidate(0.24), risk, observed(), pass(), ctx());

        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.UNFAVORABLE);
        assertThat(a.placement()).isEqualTo("LEARN_FROM");
        assertThat(a.summary()).contains("mechanically valid").contains("not mistake availability for endorsement");
        assertThat(a.marketEvAfterCostsCents()).isNegative();
    }

    @Test void economicVerdictIncludesFlatOrderFeesAsWellAsContractFees() {
        EvalContext withOrderFee = new EvalContext("AAPL", 10_000, 30, 0.30, 0.25, List.of(),
                1_000_000, true, 65, 100, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of(
                        "treasury", io.liftandshift.strikebench.model.Freshness.EOD));
        RiskProfile risk = new RiskProfile(20_000, 30_000L, 0.50, 1_000L,
                20_000, 0.20, List.of(), 1_000L, "test");

        EconomicAssessment a = EconomicAssessment.assess(candidate(0.50), risk, observed(), pass(), withOrderFee);

        // 2 option legs x $0.65 x entry/close + $1.00 order fee x entry/close.
        assertThat(a.estimatedRoundTripFeesCents()).isEqualTo(460L);
        assertThat(a.marketEvAfterCostsCents()).isEqualTo(540L);
        assertThat(a.realizedVolEvAfterCostsCents()).isEqualTo(540L);
    }

    @Test void lowProbabilityAloneNeverRejectsAPositivePayoffTrade() {
        RiskProfile risk = new RiskProfile(20_000, 80_000L, 0.20, 5_000L,
                20_000, 0.20, List.of(), null, "test");
        EconomicAssessment a = EconomicAssessment.assess(candidate(0.20), risk, observed(), pass(), ctx());

        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.MIXED);
        assertThat(a.teachingCase()).isFalse();
        assertThat(a.reasons()).anyMatch(x -> x.contains("Low probability") || x.contains("below 30%"));
    }

    @Test void observedRealizedVolEdgeThatSurvivesCostsCanBeFavorable() {
        RiskProfile risk = new RiskProfile(20_000, 20_000L, 0.55, -100L,
                20_000, 0.20, List.of(), 2_000L, "test");
        EconomicAssessment a = EconomicAssessment.assess(candidate(0.55), risk, observed(), pass(), ctx());

        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
        assertThat(a.favorable()).isTrue();
    }

    @Test void generatedEvidenceCanTeachAFavorableCaseWithoutClaimingObservedEdge() {
        RiskProfile risk = new RiskProfile(20_000, 20_000L, 0.55, 2_000L,
                20_000, 0.20, List.of(), 3_000L, "test");
        EvidenceProfile demo = EvidenceProfile.of(Map.of("pricing", EvidenceLevel.DEMO_FIXTURE), "demo");
        EconomicAssessment a = EconomicAssessment.assess(candidate(0.55), risk, demo, pass(), ctx());

        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
        assertThat(a.label()).containsIgnoringCase("teaching market");
        assertThat(a.summary()).contains("not evidence of a live-market edge");
        assertThat(a.observedEvidence()).isFalse();
        assertThat(a.actionableFavorable()).isFalse();
    }

    @Test void observedPricesWithAModeledWeakLinkCannotBecomeAnObservedEndorsement() {
        RiskProfile risk = new RiskProfile(20_000, 20_000L, 0.55, -100L,
                20_000, 0.20, List.of(), 2_000L, "test");
        EvidenceProfile incomplete = EvidenceProfile.of(Map.of(
                "pricing", EvidenceLevel.OBSERVED_DELAYED,
                "history", EvidenceLevel.OBSERVED_EOD,
                "rates", EvidenceLevel.MODELED), "modeled rate");
        EconomicAssessment a = EconomicAssessment.assess(candidate(0.55), risk, incomplete, pass(), ctx());

        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.MIXED);
        assertThat(a.label()).containsIgnoringCase("incomplete evidence");
        assertThat(a.actionableFavorable()).isFalse();
    }

    @Test void modeledPricingFallbackIsIncompleteEvidenceNotATeachingMarket() {
        RiskProfile risk = new RiskProfile(20_000, 20_000L, 0.55, -100L,
                20_000, 0.20, List.of(), 2_000L, "test");
        EvidenceProfile modeledPricing = EvidenceProfile.of(Map.of(
                "pricing", EvidenceLevel.MODELED,
                "history", EvidenceLevel.OBSERVED_EOD), "modeled chain fallback");

        EconomicAssessment a = EconomicAssessment.assess(
                candidate(0.55), risk, modeledPricing, pass(), ctx());

        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.MIXED);
        assertThat(a.label()).containsIgnoringCase("incomplete evidence");
        assertThat(a.summary()).doesNotContainIgnoringCase("teaching market")
                .doesNotContainIgnoringCase("generated market");
        assertThat(a.actionableFavorable()).isFalse();
    }

    @Test void modelDependentTimeSpreadsStayEconomicallyUnavailable() {
        Candidate base = candidate(0.50);
        Candidate calendar = new Candidate("CALENDAR_CALL", "Call calendar", "time", "calendar",
                List.of(new LegView("SELL", "CALL", "100", "2026-08-21", 1, "2.00"),
                        new LegView("BUY", "CALL", "100", "2026-09-18", 1, "4.00")),
                1, -20_000, null, 20_000, List.of(), null, null, 0.8, "DELAYED", List.of(),
                base.confidence(), base.whyConsidered(), base.bestUpside(), base.biggestRisk(),
                base.wouldInvalidate(), base.beginnerExplanation(), base.intent(), base.intents(), null, null,
                null, null, false, null, null);
        RiskProfile profiled = new RiskProfiler().profile(calendar, ctx());
        EconomicAssessment a = EconomicAssessment.assess(calendar, profiled, observed(), pass(), ctx());

        assertThat(profiled.expectedValueCents()).isNull();
        assertThat(profiled.evHistVolCents()).isNull();
        assertThat(profiled.evBasisNote()).contains("multi-expiration");
        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);
    }

    @Test void realizedVolLaneUsesTheExactPackagePrice() {
        Candidate base = candidate(0.50);
        Candidate repriced = new Candidate(base.strategy(), base.displayName(), base.structureGroup(), base.label(),
                base.legs(), base.qty(), -15_000, base.maxProfitCents(), 15_000, base.breakevens(), base.pop(),
                base.expectedValueCents(), base.liquidityScore(), base.freshness(), base.warnings(),
                base.confidence(), base.whyConsidered(), base.bestUpside(), base.biggestRisk(), base.wouldInvalidate(),
                base.beginnerExplanation(), base.intent(), base.intents(), base.assignmentProb(),
                base.annualizedYieldPct(), base.effectivePrice(), base.intentNote(), base.usesHeldShares(),
                base.sharesNeeded(), base.combinedMaxLossCents());

        RiskProfile risk = new RiskProfiler().profile(repriced, ctx());
        var legs = repriced.legs().stream().map(LegView::toLeg).toList();
        long markedEntry = PayoffCurve.of(legs, 1).entryNetPremiumCents();
        long adjust = repriced.entryNetPremiumCents() - markedEntry;
        long expected = PayoffCurve.of(legs, 1, adjust)
                .expectedValueCents(100.0, 0.25, 30.0 / 365.0, 0);

        assertThat(adjust).isEqualTo(5_000);
        assertThat(risk.evHistVolCents()).isEqualTo(expected);
    }

    @Test void heldShareRiskIncludesSharesOnceAtEveryQuantity() {
        Candidate one = heldCoveredCall(1);
        Candidate three = heldCoveredCall(3);
        RiskProfile r1 = new RiskProfiler().profile(one, ctx());
        RiskProfile r3 = new RiskProfiler().profile(three, ctx());

        assertThat(r1.maxLossCents()).isEqualTo(one.combinedMaxLossCents());
        assertThat(r3.maxLossCents()).isEqualTo(three.combinedMaxLossCents());
        assertThat(r1.evHistVolCents()).isNotNull();
        assertThat(Math.abs(r3.evHistVolCents() - r1.evHistVolCents() * 3)).isLessThanOrEqualTo(1L);
        assertThat(r3.scenarios()).hasSameSizeAs(r1.scenarios());
        for (int i = 0; i < r1.scenarios().size(); i++) {
            assertThat(r3.scenarios().get(i).pnlCents()).isEqualTo(r1.scenarios().get(i).pnlCents() * 3);
        }
    }

    private Candidate heldCoveredCall(int qty) {
        return new Candidate("COVERED_CALL", "Covered call", "shares_income", "SELL 105C",
                List.of(new LegView("SELL", "CALL", "105", "2026-08-21", 1, "2.00")),
                qty, 20_000L * qty, 70_000L * qty, 0, List.of("98", "105"), 0.60, 0L, 0.8,
                "DELAYED", List.of(), 0.7, "test", "test", "test", "test", "test",
                "INCOME", List.of("INCOME", "EXIT"), 0.30, null, null, null,
                true, 100 * qty, 980_000L * qty);
    }
}
