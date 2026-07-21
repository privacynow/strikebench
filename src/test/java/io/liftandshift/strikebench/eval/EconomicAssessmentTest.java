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
                List.of(new LegView("BUY", "CALL", "100", "2026-08-21", 1, "4.00", 100, "OPEN"),
                        new LegView("SELL", "CALL", "105", "2026-08-21", 1, "2.00", 100, "OPEN")),
                1, -20_000, 30_000L, 20_000, List.of("102"), pop, 0L, 0.8,
                "DELAYED", List.of(), 0.7, "test", "test", "test", "test", "test",
                "DIRECTIONAL", List.of("DIRECTIONAL"), null, null, null, null, false, null, null);
    }

    private EvalContext ctx() {
        return new EvalContext("AAPL", 10_000, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25, List.of(), 1_000_000, true, 65,
                0, 0.04, io.liftandshift.strikebench.model.DataEvidence.of("treasury", io.liftandshift.strikebench.model.Freshness.EOD), null);
    }

    private EvalContext ctx(int daysToExpiry) {
        return new EvalContext("AMD", 50_000, java.time.LocalDate.parse("2026-07-22"),
                daysToExpiry, 0.35, 0.25, List.of(), 10_000_000, true, 65,
                0, 0.04, io.liftandshift.strikebench.model.DataEvidence.of("treasury",
                io.liftandshift.strikebench.model.Freshness.EOD), null);
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
        EvalContext withOrderFee = new EvalContext("AAPL", 10_000, java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25, List.of(),
                1_000_000, true, 65, 100, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of(
                        "treasury", io.liftandshift.strikebench.model.Freshness.EOD), null);
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

    @Test void observedMaterialEdgeRemainsFavorableWhenVolatilitySensitivityCrossesZero() {
        List<Double> closes = java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> 100.0 + Math.sin(i / 3.0) * 1.5)
                .toList();
        EvalContext withHistory = new EvalContext("AAPL", 10_000,
                java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25,
                List.of(), 1_000_000, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury",
                        io.liftandshift.strikebench.model.Freshness.EOD), null,
                new DeclaredObjective("DIRECTIONAL", "BULLISH", 30, "ACCEPT", "test"),
                null, closes);
        RiskProfile pointEstimateClearsMateriality = new RiskProfile(
                20_000, 30_000L, 0.55, -100L,
                20_000, 0.20, List.of(), 2_000L, "test");

        EconomicAssessment a = EconomicAssessment.assess(candidate(0.55),
                pointEstimateClearsMateriality, observed(), pass(), withHistory);

        assertThat(a.realizedVolEvAfterCostsCents()).isGreaterThan(a.realisticEvMaterialityCents());
        assertThat(a.realisticEvLowAfterCostsCents()).isNotNull().isLessThanOrEqualTo(0);
        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
        assertThat(a.summary()).contains("sensitivity range that crosses zero");
        assertThat(a.reasons()).anyMatch(reason -> reason.contains("model-sensitive"));
    }

    @Test void materiallyAdversePointRemainsUnfavorableWithSensitivityDisclosed() {
        List<Double> closes = java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> 100.0 + Math.sin(i / 3.0) * 1.5)
                .toList();
        EvalContext withHistory = new EvalContext("AAPL", 10_000,
                java.time.LocalDate.parse("2026-07-22"), 30, 0.30, 0.25,
                List.of(), 1_000_000, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury",
                        io.liftandshift.strikebench.model.Freshness.EOD), null,
                new DeclaredObjective("DIRECTIONAL", "BULLISH", 30, "ACCEPT", "test"),
                null, closes);
        RiskProfile adversePoint = new RiskProfile(
                20_000, 30_000L, 0.45, -100L,
                20_000, 0.20, List.of(), -2_000L, "test");

        EconomicAssessment a = EconomicAssessment.assess(candidate(0.45),
                adversePoint, observed(), pass(), withHistory);

        assertThat(a.realizedVolEvAfterCostsCents()).isLessThan(-a.realisticEvMaterialityCents());
        assertThat(a.realisticEvLowAfterCostsCents()).isNotNull();
        assertThat(a.realisticEvHighAfterCostsCents()).isNotNull();
        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.UNFAVORABLE);
        assertThat(a.reasons()).anyMatch(reason -> reason.contains("Realized-volatility sensitivity"));
    }

    @Test void riskNeutralCostBenchmarkCannotVetoObservedIncomeEdge() {
        Candidate income = new Candidate("CASH_SECURED_PUT", "Cash-secured put",
                "acquisition_income", "SELL 95P 2026-09-04",
                List.of(new LegView("SELL", "PUT", "95", "2026-09-04", 1,
                        "3.00", 100, "OPEN")),
                1, 30_000L, 30_000L, 920_000L, List.of("92"), 0.72,
                -5_070L, 0.9, "DELAYED", List.of(), 0.8,
                "income", "premium", "assignment", "volatility expansion", "test",
                "INCOME", List.of("INCOME", "ACQUIRE"), 0.28, 25.0, "92",
                "Collect premium or acquire at $92", false, null, null);
        List<Double> closes = java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> 100.0 + Math.sin(i / 4.0) * 2.0 + i * 0.02)
                .toList();
        EvalContext observedLike = new EvalContext("AAPL", 10_000,
                java.time.LocalDate.parse("2026-07-22"), 45, 0.38, 0.20,
                List.of(), 1_000_000, true, 65, 0, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury",
                        io.liftandshift.strikebench.model.Freshness.EOD), null,
                new DeclaredObjective("INCOME", "NEUTRAL", 30, "ACCEPT", "test"),
                null, closes);
        RiskProfile risk = new RiskProfiler().profile(income, observedLike);
        EconomicAssessment a = EconomicAssessment.assess(
                income, risk, observed(), pass(), observedLike);

        assertThat(a.marketEvAfterCostsCents()).isNegative();
        assertThat(a.realizedVolEvAfterCostsCents()).isGreaterThan(0);
        assertThat(a.realisticEvMaterialityCents())
                .as("the 45-day exposure hurdle catches noise without restoring a full-tail EV veto")
                .isEqualTo(1_595L);
        assertThat(a.realisticEvLowAfterCostsCents()).isPositive();
        assertThat(a.realisticEvHighAfterCostsCents())
                .isGreaterThanOrEqualTo(a.realisticEvLowAfterCostsCents());
        assertThat(a.marketEvRole()).contains("cost benchmark").contains("not an independent edge test");
        assertThat(a.realisticEvBasis()).contains("SENSITIVITY").contains("30 returns");
        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
        assertThat(a.summary()).contains("cost benchmark").contains("not a second edge vote");
    }

    @Test void cashSecuredPutNeedsAHorizonMeaningfulEdgeOnItsAsymmetricExposure() {
        Candidate put = asymmetricIncomeCandidate(false, 100_000L, 5_000_000L);
        RiskProfile roundingSized = new RiskProfile(5_000_000L, 100_000L, 0.72, -5_000L,
                1_000_000L, 0.20, List.of(), 5_130L, "realized-vol test");
        RiskProfile material = new RiskProfile(5_000_000L, 100_000L, 0.72, -5_000L,
                1_000_000L, 0.20, List.of(), 9_330L, "realized-vol test");

        EconomicAssessment small = EconomicAssessment.assess(
                put, roundingSized, observed(), pass(), ctx(45));
        EconomicAssessment earned = EconomicAssessment.assess(
                put, material, observed(), pass(), ctx(45));

        assertThat(small.realizedVolEvAfterCostsCents()).isEqualTo(5_000L);
        assertThat(small.realisticEvMaterialityCents())
                .as("5 bps plus a 1%% annualized 45-day hurdle on $50k")
                .isEqualTo(8_665L);
        assertThat(small.verdict()).isEqualTo(EconomicAssessment.Verdict.MIXED);
        assertThat(earned.realizedVolEvAfterCostsCents()).isEqualTo(9_200L);
        assertThat(earned.verdict()).isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
    }

    @Test void heldShareOverlayUsesCombinedCapitalWithoutMakingMaterialIncomeImpossible() {
        Candidate coveredCall = asymmetricIncomeCandidate(true, 200_000L, 5_000_000L);
        RiskProfile roundingSized = new RiskProfile(5_000_000L, 200_000L, 0.68, -4_000L,
                1_000_000L, 0.20, List.of(), 5_130L, "realized-vol test");
        RiskProfile material = new RiskProfile(5_000_000L, 200_000L, 0.68, -4_000L,
                1_000_000L, 0.20, List.of(), 9_330L, "realized-vol test");

        EconomicAssessment small = EconomicAssessment.assess(
                coveredCall, roundingSized, observed(), pass(), ctx(45));
        EconomicAssessment earned = EconomicAssessment.assess(
                coveredCall, material, observed(), pass(), ctx(45));

        assertThat(small.realisticEvMaterialityCents()).isEqualTo(8_665L);
        assertThat(small.verdict()).isEqualTo(EconomicAssessment.Verdict.MIXED);
        assertThat(earned.realizedVolEvAfterCostsCents()).isEqualTo(9_200L);
        assertThat(earned.verdict()).isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
    }

    @Test void knownFortyFiveDayAmdScaleStillAllowsTheObservedMaterialEdge() {
        Candidate put = asymmetricIncomeCandidate(false, 294_500L, 4_732_500L);
        RiskProfile risk = new RiskProfile(4_732_500L, 294_500L, 0.73, -5_170L,
                950_000L, 0.20, List.of(), 9_341L, "observed AMD scale");

        EconomicAssessment assessment = EconomicAssessment.assess(
                put, risk, observed(), pass(), ctx(45));

        assertThat(assessment.realisticEvMaterialityCents()).isEqualTo(8_835L);
        assertThat(assessment.realizedVolEvAfterCostsCents()).isEqualTo(9_211L);
        assertThat(assessment.verdict()).isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
    }

    @Test void scoreComposerDoesNotMagnifySubThresholdEvOnAsymmetricCapital() {
        Candidate put = asymmetricIncomeCandidate(false, 100_000L, 5_000_000L);
        RiskProfile risk = new RiskProfile(5_000_000L, 100_000L, 0.72, -5_000L,
                1_000_000L, 0.20, List.of(), 5_130L, "realized-vol test");
        CapitalProfile capital = new CapitalProfile(5_000_000L, 5_000_000L,
                2.0, null, 45, "cash collateral", null);

        ScoreBreakdown score = new ScoreComposer().compose(
                put, capital, risk, observed(), ctx(45));
        ScoreBreakdown.Component expectedValue = score.components().stream()
                .filter(component -> component.name().equals("Expected value"))
                .findFirst().orElseThrow();

        assertThat(expectedValue.value())
                .as("$50 on $50k should remain close to the neutral 0.5 score")
                .isBetween(0.50, 0.51);
        assertThat(expectedValue.note()).contains("structure payoff scale $2888");
    }

    private Candidate asymmetricIncomeCandidate(boolean heldShares, Long maxProfit, long maxLoss) {
        String strategy = heldShares ? "COVERED_CALL" : "CASH_SECURED_PUT";
        String type = heldShares ? "CALL" : "PUT";
        String strike = heldShares ? "530" : "485";
        return new Candidate(strategy, heldShares ? "Covered call" : "Cash-secured put",
                heldShares ? "held_income" : "acquisition_income",
                "SELL " + strike + type + " 2026-09-04",
                List.of(new LegView("SELL", type, strike, "2026-09-04", 1,
                        "10.00", 100, "OPEN")),
                1, 100_000L, maxProfit, heldShares ? 0 : maxLoss, List.of(), 0.70,
                -5_000L, 0.9, "DELAYED", List.of(), 0.8,
                "income", "premium", "tail", "volatility", "test",
                "INCOME", List.of("INCOME"), 0.25, 15.0, null,
                "Income test", heldShares, heldShares ? 100 : null,
                heldShares ? maxLoss : null);
    }

    @Test void negativeRiskNeutralCostBenchmarkAloneIsNotAnUnfavorableVerdict() {
        RiskProfile noHistory = new RiskProfile(20_000, 30_000L, 0.55, -5_000L,
                20_000, 0.20, List.of(), null, "realistic measure unavailable");
        EconomicAssessment a = EconomicAssessment.assess(
                candidate(0.55), noHistory, observed(), pass(), ctx());

        assertThat(a.marketEvAfterCostsCents()).isNegative();
        assertThat(a.realizedVolEvAfterCostsCents()).isNull();
        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.MIXED);
        assertThat(a.teachingCase()).isFalse();
        assertThat(a.reasons()).anyMatch(reason -> reason.contains("not an independent edge test"));
    }

    @Test void unrelatedIvRankHistoryDoesNotVetoAnObservedEconomicClaim() {
        RiskProfile risk = new RiskProfile(20_000, 20_000L, 0.55, -100L,
                20_000, 0.20, List.of(), 2_000L, "test");
        Map<String, EvidenceLevel> dimensions = Map.of(
                "pricing", EvidenceLevel.OBSERVED_DELAYED,
                "currentVolatility", EvidenceLevel.OBSERVED_DELAYED,
                "rates", EvidenceLevel.OBSERVED_EOD,
                "history", EvidenceLevel.OBSERVED_EOD,
                "volatility", EvidenceLevel.MODELED,
                "liquidity", EvidenceLevel.UNKNOWN);
        Map<String, EvidenceProfile.ClaimEvidence> claims = Map.of(
                "endorsement", EvidenceProfile.project(dimensions,
                        List.of("pricing", "currentVolatility", "rates", "history"), "economic inputs"));
        EvidenceProfile evidence = EvidenceProfile.of(dimensions, "holistic disclosure", claims);

        EconomicAssessment assessment = EconomicAssessment.assess(
                candidate(0.55), risk, evidence, pass(), ctx());

        assertThat(evidence.rollup()).isEqualTo(EvidenceLevel.UNKNOWN);
        assertThat(evidence.claims().get("endorsement").observed()).isTrue();
        assertThat(assessment.verdict()).isEqualTo(EconomicAssessment.Verdict.FAVORABLE);
        assertThat(assessment.observedEvidence()).isTrue();
    }

    @Test void modeledCurrentVolatilityStillBlocksAnObservedEndorsement() {
        RiskProfile risk = new RiskProfile(20_000, 20_000L, 0.55, -100L,
                20_000, 0.20, List.of(), 2_000L, "test");
        Map<String, EvidenceLevel> dimensions = Map.of(
                "pricing", EvidenceLevel.OBSERVED_DELAYED,
                "currentVolatility", EvidenceLevel.MODELED,
                "rates", EvidenceLevel.OBSERVED_EOD,
                "history", EvidenceLevel.OBSERVED_EOD);
        Map<String, EvidenceProfile.ClaimEvidence> claims = Map.of(
                "endorsement", EvidenceProfile.project(dimensions,
                        List.of("pricing", "currentVolatility", "rates", "history"), "economic inputs"));
        EvidenceProfile evidence = EvidenceProfile.of(dimensions, "modeled current IV", claims);

        EconomicAssessment assessment = EconomicAssessment.assess(
                candidate(0.55), risk, evidence, pass(), ctx());

        assertThat(assessment.verdict()).isEqualTo(EconomicAssessment.Verdict.MIXED);
        assertThat(assessment.observedEvidence()).isFalse();
        assertThat(assessment.reasons()).anyMatch(reason -> reason.contains("currentVolatility"));
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
                List.of(new LegView("SELL", "CALL", "100", "2026-08-21", 1, "2.00", 100, "OPEN"),
                        new LegView("BUY", "CALL", "100", "2026-09-18", 1, "4.00", 100, "OPEN")),
                1, -20_000, null, 20_000, List.of(), null, null, 0.8, "DELAYED", List.of(),
                base.confidence(), base.whyConsidered(), base.bestUpside(), base.biggestRisk(),
                base.wouldInvalidate(), base.beginnerExplanation(), base.intent(), base.intents(), null, null,
                null, null, false, null, null);
        RiskProfile profiled = new RiskProfiler().profile(calendar, ctx());
        EconomicAssessment a = EconomicAssessment.assess(calendar, profiled, observed(), pass(), ctx());

        assertThat(profiled.expectedValueCents()).isNull();
        assertThat(profiled.evHistVolCents()).isNull();
        assertThat(profiled.scenarios()).as("a time spread has no false single-expiration grid").isEmpty();
        assertThat(profiled.terminalPayoff().available()).isFalse();
        assertThat(profiled.terminalPayoff().unavailableReason()).contains("supplied-path valuation");
        assertThat(profiled.evBasisNote()).contains("multi-expiration");
        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);
    }

    @Test void evaluationCarriesExactServerOwnedPayoffCheckpoints() {
        RiskProfile risk = new RiskProfiler().profile(candidate(0.50), ctx());

        assertThat(risk.terminalPayoff().available()).isTrue();
        assertThat(risk.terminalPayoff().schemaVersion()).isEqualTo("risk-terminal-payoff-1");
        assertThat(risk.terminalPayoff().basis()).isEqualTo("EXPIRATION_INTRINSIC");
        assertThat(risk.terminalPayoff().entryBasis()).isEqualTo("CAPTURED_CANDIDATE_NET");
        assertThat(risk.terminalPayoff().anchorSpotCents()).isEqualTo(10_000L);
        assertThat(risk.terminalPayoff().points()).hasSizeGreaterThan(60);
        assertThat(risk.terminalPayoff().points()).anySatisfy(point -> {
            assertThat(point.price()).isEqualByComparingTo("100");
            assertThat(point.profitCents()).isEqualTo(-20_000);
        });
        assertThat(risk.terminalPayoff().points()).anySatisfy(point -> {
            assertThat(point.price()).isEqualByComparingTo("102");
            assertThat(point.profitCents()).isZero();
        });
        assertThat(risk.terminalPayoff().points()).anySatisfy(point -> {
            assertThat(point.price()).isEqualByComparingTo("105");
            assertThat(point.profitCents()).isEqualTo(30_000);
        });
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
                List.of(new LegView("SELL", "CALL", "105", "2026-08-21", 1, "2.00", 100, "OPEN")),
                qty, 20_000L * qty, 70_000L * qty, 0, List.of("98", "105"), 0.60, 0L, 0.8,
                "DELAYED", List.of(), 0.7, "test", "test", "test", "test", "test",
                "INCOME", List.of("INCOME", "EXIT"), 0.30, null, null, null,
                true, 100 * qty, 980_000L * qty);
    }
}
