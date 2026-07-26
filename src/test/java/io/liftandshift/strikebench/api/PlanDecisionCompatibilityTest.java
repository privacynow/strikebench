package io.liftandshift.strikebench.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanDecisionCompatibilityTest {

    @Test
    void legacyMarketEchoRequiresAnActuallyPublishedMarketPrice() {
        assertThat(PlanDecisionController.isLegacyMarketEcho(12_300L, 12_300L)).isTrue();
        assertThat(PlanDecisionController.isLegacyMarketEcho(12_300L, 12_301L)).isFalse();
        assertThat(PlanDecisionController.isLegacyMarketEcho(12_300L, null)).isFalse();
        assertThat(PlanDecisionController.isLegacyMarketEcho(null, null)).isFalse();
    }
}
