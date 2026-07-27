package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.model.DataEvidence;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskProfilerTest {

    @Test
    void worstNamedScenarioCarriesItsExactLossDenominatorAndSeverity() {
        var receipt = RiskProfiler.worstScenario(List.of(
                new RiskProfile.Scenario(-0.20, -9_100L, 0.05),
                new RiskProfile.Scenario(-0.09, -4_000L, 0.10),
                new RiskProfile.Scenario(0.0, 1_000L, 0.30)), 10_000L);

        assertThat(receipt.severityAvailable()).isTrue();
        assertThat(receipt.underlyingMovePct()).isEqualTo(-0.20);
        assertThat(receipt.pnlCents()).isEqualTo(-9_100L);
        assertThat(receipt.lossCents()).isEqualTo(9_100L);
        assertThat(receipt.comparisonLossCents()).isEqualTo(10_000L);
        assertThat(receipt.comparisonBasis()).isEqualTo("EXACT_MAXIMUM_LOSS");
        assertThat(receipt.lossSharePct()).isEqualTo(91.0);
        assertThat(receipt.severity()).isEqualTo(RiskProfile.ScenarioSeverity.SEVERE);
        assertThat(receipt.unavailableReason()).isNull();
    }

    @Test
    void worstScenarioWithholdsSeverityWithoutARealDenominator() {
        var noScenarios = RiskProfiler.worstScenario(List.of(), 5_000L);
        assertThat(noScenarios.severityAvailable()).isFalse();
        assertThat(noScenarios.unavailableReason()).contains("No named scenario");

        var noDenominator = RiskProfiler.worstScenario(
                List.of(new RiskProfile.Scenario(-0.20, -500L, null)), 0L);
        assertThat(noDenominator.severityAvailable()).isFalse();
        assertThat(noDenominator.pnlCents()).isEqualTo(-500L);
        assertThat(noDenominator.severity()).isNull();
        assertThat(noDenominator.unavailableReason()).contains("no positive exact maximum-loss");
    }

    @Test
    void jumpTailRequiresObservedRegimeIvTimeAndEventEvidence() {
        EvalContext noRegime = context(null, 0.30, OptionTime.ofCalendarDays(30));
        assertThat(RiskProfiler.jumpTailEvidenceGap(noRegime)).contains("market-regime");

        RegimeSnapshot unknownEvent = new RegimeSnapshot(
                RegimeSnapshot.Trend.SIDEWAYS, 0.0, 30, -1.0,
                0.05, 70.0, null, "earnings calendar unavailable", "test");
        assertThat(RiskProfiler.jumpTailEvidenceGap(
                context(unknownEvent, 0.30, OptionTime.ofCalendarDays(30))))
                .contains("event proximity")
                .contains("earnings calendar unavailable");

        RegimeSnapshot complete = new RegimeSnapshot(
                RegimeSnapshot.Trend.SIDEWAYS, 0.0, 30, -1.0,
                0.05, 70.0, false, "observed event calendar", "test");
        assertThat(RiskProfiler.jumpTailEvidenceGap(
                context(complete, null, OptionTime.ofCalendarDays(30))))
                .contains("ATM option IV");
        assertThat(RiskProfiler.jumpTailEvidenceGap(
                context(complete, 0.30, new OptionTime.Measure(
                        OptionTime.State.PARTIAL, -1, -1, null, null, null,
                        "time unavailable"))))
                .contains("time to expiry");
        assertThat(RiskProfiler.jumpTailEvidenceGap(
                context(complete, 0.30, OptionTime.ofCalendarDays(30))))
                .isNull();
    }

    private static EvalContext context(
            RegimeSnapshot regime, Double atmIv, OptionTime.Measure time) {
        return new EvalContext("AAPL", 25_000L, LocalDate.of(2026, 7, 22), time,
                atmIv, 0.25, List.of(), 1_000_000L, true, 0.04,
                DataEvidence.missing("test rate"), null, null, regime, List.of(),
                DataEvidence.missing("test history"));
    }
}
