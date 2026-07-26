package io.liftandshift.strikebench.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppConfigFeeTest {

    @Test
    void commissionSettingsAreNonNegativeFinancialInputs() {
        AppConfig defaults = new AppConfig(Map.of());
        assertThat(defaults.feePerContractCents()).isEqualTo(65L);
        assertThat(defaults.feePerOrderCents()).isZero();

        assertThatThrownBy(() -> new AppConfig(Map.of(
                "FEE_PER_CONTRACT_CENTS", "-1")).feePerContractCents())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FEE_PER_CONTRACT_CENTS");
        assertThatThrownBy(() -> new AppConfig(Map.of(
                "FEE_PER_ORDER_CENTS", "-1")).feePerOrderCents())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FEE_PER_ORDER_CENTS");
    }
}
