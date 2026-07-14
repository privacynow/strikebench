package io.liftandshift.strikebench.recommend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskBudgetPolicyTest {
    @Test
    void aRoundedZeroBudgetNeverExpandsToAllBuyingPower() {
        assertThat(RiskBudgetPolicy.requestBudgetCents(
                RecommendationEngine.RiskMode.CONSERVATIVE, 1, null, null)).isZero();
    }

    @Test
    void explicitPercentAndAbsoluteCapUseTheTighterLimit() {
        assertThat(RiskBudgetPolicy.requestBudgetCents(
                RecommendationEngine.RiskMode.AGGRESSIVE, 10_000_000, 0.02, 150_000L))
                .isEqualTo(150_000L);
    }
}
