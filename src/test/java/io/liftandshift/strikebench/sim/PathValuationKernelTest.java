package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PathValuationKernelTest {

    @Test void expirationValueUsesTheExactContractMultiplier() {
        LocalDate asOf = LocalDate.parse("2026-07-15");
        Leg standard = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"),
                asOf, 1, BigDecimal.ZERO, 100);
        Leg adjusted = Leg.option(LegAction.BUY, OptionType.CALL, new BigDecimal("100"),
                asOf, 1, BigDecimal.ZERO, 10);
        double[] path = {110};

        double standardValue = PathValuationKernel.value(
                new PathPosition(asOf, List.of(standard)), path, 0, 0, 1, 1.0 / 252, 0.25, 0.04);
        double adjustedValue = PathValuationKernel.value(
                new PathPosition(asOf, List.of(adjusted)), path, 0, 0, 1, 1.0 / 252, 0.25, 0.04);

        assertThat(standardValue).isEqualTo(1_000.0);
        assertThat(adjustedValue).isEqualTo(100.0);
    }
}
