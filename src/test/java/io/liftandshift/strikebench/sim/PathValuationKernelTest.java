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

    @Test
    void frozenDatedPackageUsesOneCanonicalSignUnitAndPerLegIvLoop() {
        LocalDate asOf = LocalDate.parse("2026-07-24");
        List<Leg> legs = List.of(
                Leg.option(LegAction.SELL, OptionType.PUT, new BigDecimal("95"),
                        LocalDate.parse("2026-08-21"), 1, BigDecimal.ZERO, 100),
                Leg.option(LegAction.BUY, OptionType.PUT, new BigDecimal("90"),
                        LocalDate.parse("2026-08-21"), 1, BigDecimal.ZERO, 100),
                Leg.stock(LegAction.BUY, 2, BigDecimal.ZERO));

        double value = PathValuationKernel.valueAtDate(
                legs, java.util.Arrays.asList(0.40, 0.35, null),
                2, 100.0, asOf, 0.04);

        double years = io.liftandshift.strikebench.market.OptionTime.atSessionClose(
                asOf, LocalDate.parse("2026-08-21")).years();
        double shortPut = io.liftandshift.strikebench.pricing.BlackScholes.price(
                false, 100, 95, years, 0.04, 0, 0.40);
        double longPut = io.liftandshift.strikebench.pricing.BlackScholes.price(
                false, 100, 90, years, 0.04, 0, 0.35);
        assertThat(value).isCloseTo(
                -shortPut * 100 * 2 + longPut * 100 * 2 + 100 * 100 * 2 * 2,
                org.assertj.core.data.Offset.offset(0.000001));
    }

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
