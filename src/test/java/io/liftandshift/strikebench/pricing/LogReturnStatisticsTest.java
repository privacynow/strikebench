package io.liftandshift.strikebench.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class LogReturnStatisticsTest {

    @Test
    void oneKernelOwnsMeanCenteringSamplePopulationAndAnnualization() {
        LogReturnStatistics statistics = LogReturnStatistics.of(new double[]{1, 2, 3});

        assertThat(statistics.observations()).isEqualTo(3);
        assertThat(statistics.mean()).isEqualTo(2);
        assertThat(statistics.centeredReturns()).containsExactly(-1, 0, 1);
        assertThat(statistics.populationStdDev()).isCloseTo(Math.sqrt(2.0 / 3.0),
                offset(1e-12));
        assertThat(statistics.sampleStdDev()).isEqualTo(1);
        assertThat(statistics.annualizedSampleStdDev())
                .isEqualTo(Math.sqrt(LogReturnStatistics.TRADING_SESSIONS_PER_YEAR));
    }

    @Test
    void pricesBecomeExactCloseToCloseLogReturnsAndInputsAreDefensivelyCopied() {
        double[] prices = {100, 110, 121};
        LogReturnStatistics statistics = LogReturnStatistics.fromPrices(prices);
        prices[1] = 1;

        assertThat(statistics.returns()[0]).isCloseTo(Math.log(1.1), offset(1e-12));
        assertThat(statistics.returns()[1]).isCloseTo(Math.log(1.1), offset(1e-12));
        double[] returned = statistics.returns();
        returned[0] = 999;
        assertThat(statistics.returns()[0]).isCloseTo(Math.log(1.1), offset(1e-12));
    }

    @Test
    void invalidPricesAndReturnsFailInsteadOfManufacturingStatistics() {
        assertThatThrownBy(() -> LogReturnStatistics.fromPrices(new double[]{100, 0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive and finite");
        assertThatThrownBy(() -> LogReturnStatistics.of(new double[]{Double.NaN}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThat(LogReturnStatistics.of(new double[0]).sampleStdDev()).isNaN();
        assertThat(LogReturnStatistics.of(new double[]{.01}).sampleStdDev()).isNaN();
    }
}
