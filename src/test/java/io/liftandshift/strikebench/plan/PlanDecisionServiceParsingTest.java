package io.liftandshift.strikebench.plan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanDecisionServiceParsingTest {
    @Test void exactDecisionNumbersPreservePrecisionAndRejectMalformedValues() {
        assertThat(PlanDecisionService.decisionPrice("12.3456"))
                .isEqualByComparingTo(new BigDecimal("12.3456"));
        assertThat(PlanDecisionService.decisionDecimal("0.275")).isEqualTo(0.275);
        assertThat(PlanDecisionService.decisionInteger("3", 1)).isEqualTo(3);
        assertThat(PlanDecisionService.decisionPrice(null)).isNull();

        assertThatThrownBy(() -> PlanDecisionService.decisionPrice("not-a-price"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid price");
        assertThatThrownBy(() -> PlanDecisionService.decisionDecimal("NaN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid decimal");
        assertThatThrownBy(() -> PlanDecisionService.decisionInteger(0, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid ratio");
    }
}
