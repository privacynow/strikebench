package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
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
                "DELAYED", List.of(), 50, 0.7, "test", "test", "test", "test", "test",
                "DIRECTIONAL", List.of("DIRECTIONAL"), null, null, null, null, false, null, null);
    }

    private EvalContext ctx() {
        return new EvalContext("AAPL", 10_000, 30, 0.30, 0.25, List.of(), 1_000_000, true, 65);
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
    }

    @Test void modelDependentTimeSpreadsStayEconomicallyUnavailable() {
        Candidate base = candidate(0.50);
        Candidate calendar = new Candidate("CALENDAR_CALL", "Call calendar", "time", "calendar",
                List.of(new LegView("SELL", "CALL", "100", "2026-08-21", 1, "2.00"),
                        new LegView("BUY", "CALL", "100", "2026-09-18", 1, "4.00")),
                1, -20_000, null, 20_000, List.of(), null, null, 0.8, "DELAYED", List.of(),
                base.score(), base.confidence(), base.whyConsidered(), base.bestUpside(), base.biggestRisk(),
                base.wouldInvalidate(), base.beginnerExplanation(), base.intent(), base.intents(), null, null,
                null, null, false, null, null);
        RiskProfile profiled = new RiskProfiler().profile(calendar, ctx());
        EconomicAssessment a = EconomicAssessment.assess(calendar, profiled, observed(), pass(), ctx());

        assertThat(profiled.expectedValueCents()).isNull();
        assertThat(profiled.evHistVolCents()).isNull();
        assertThat(profiled.evBasisNote()).contains("multi-expiration");
        assertThat(a.verdict()).isEqualTo(EconomicAssessment.Verdict.UNAVAILABLE);
    }
}
