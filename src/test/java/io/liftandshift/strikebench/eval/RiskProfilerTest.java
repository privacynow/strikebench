package io.liftandshift.strikebench.eval;

import org.junit.jupiter.api.Test;

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
}
