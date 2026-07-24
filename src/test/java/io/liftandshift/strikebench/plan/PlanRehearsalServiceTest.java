package io.liftandshift.strikebench.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanRehearsalServiceTest {
    private static final double[][] PATHS = {
            {100, 102, 104},
            {100, 100, 101},
            {100, 94, 96},
            {100, 110, 90}
    };

    @Test void selectionsHaveStablePlanMeanings() {
        assertThat(PlanRehearsalService.selectPath(PATHS, PlanRehearsalService.Selection.SAMPLE,
                2, "bullish", "f")).isEqualTo(2);
        assertThat(PlanRehearsalService.selectPath(PATHS, PlanRehearsalService.Selection.TYPICAL,
                null, "bullish", "f")).isEqualTo(1);
        assertThat(PlanRehearsalService.selectPath(PATHS, PlanRehearsalService.Selection.FAVORABLE,
                null, "bullish", "f")).isEqualTo(0);
        assertThat(PlanRehearsalService.selectPath(PATHS, PlanRehearsalService.Selection.ADVERSE,
                null, "bullish", "f")).isEqualTo(3);
        assertThat(PlanRehearsalService.selectPath(PATHS, PlanRehearsalService.Selection.FAVORABLE,
                null, "bearish", "f")).isEqualTo(3);
        assertThat(PlanRehearsalService.selectPath(PATHS, PlanRehearsalService.Selection.STRESS,
                null, "bullish", "f")).isEqualTo(3);
    }
}
