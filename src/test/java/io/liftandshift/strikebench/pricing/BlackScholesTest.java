package io.liftandshift.strikebench.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BlackScholesTest {

    @Test
    void normCdfKnownValues() {
        assertThat(BlackScholes.normCdf(0)).isCloseTo(0.5, within(1e-7));
        assertThat(BlackScholes.normCdf(1.96)).isCloseTo(0.9750021, within(1e-6));
        assertThat(BlackScholes.normCdf(-1.96)).isCloseTo(0.0249979, within(1e-6));
        assertThat(BlackScholes.normCdf(3.0)).isCloseTo(0.9986501, within(1e-6));
    }

    @Test
    void atmOneYearKnownValues() {
        // S=100, K=100, T=1, r=5%, q=0, sigma=20% — textbook values
        double c = BlackScholes.price(true, 100, 100, 1, 0.05, 0, 0.20);
        double p = BlackScholes.price(false, 100, 100, 1, 0.05, 0, 0.20);
        assertThat(c).isCloseTo(10.4506, within(1e-3));
        assertThat(p).isCloseTo(5.5735, within(1e-3));

        assertThat(BlackScholes.delta(true, 100, 100, 1, 0.05, 0, 0.20)).isCloseTo(0.6368, within(1e-3));
        assertThat(BlackScholes.gamma(100, 100, 1, 0.05, 0, 0.20)).isCloseTo(0.018762, within(1e-4));
        assertThat(BlackScholes.vega(100, 100, 1, 0.05, 0, 0.20)).isCloseTo(37.524, within(1e-2));
        assertThat(BlackScholes.theta(true, 100, 100, 1, 0.05, 0, 0.20)).isCloseTo(-6.414, within(1e-2));
    }

    @Test
    void putCallParityHoldsAcrossGrid() {
        double[] spots = {80, 100, 120};
        double[] strikes = {90, 100, 110};
        double[] vols = {0.1, 0.3, 0.6};
        double[] times = {0.05, 0.5, 2.0};
        double r = 0.04, q = 0.01;
        for (double s : spots) for (double k : strikes) for (double v : vols) for (double t : times) {
            double c = BlackScholes.price(true, s, k, t, r, q, v);
            double p = BlackScholes.price(false, s, k, t, r, q, v);
            double parity = s * Math.exp(-q * t) - k * Math.exp(-r * t);
            assertThat(c - p).as("parity S=%s K=%s v=%s t=%s", s, k, v, t).isCloseTo(parity, within(1e-8));
        }
    }

    @Test
    void degenerateInputsReturnIntrinsic() {
        assertThat(BlackScholes.price(true, 110, 100, 0, 0.05, 0, 0.2)).isCloseTo(10, within(1e-9));
        assertThat(BlackScholes.price(false, 90, 100, 0, 0.05, 0, 0.2)).isCloseTo(10, within(1e-9));
        assertThat(BlackScholes.price(true, 90, 100, 0, 0.05, 0, 0.2)).isZero();
        assertThat(BlackScholes.delta(true, 110, 100, 0, 0.05, 0, 0.2)).isEqualTo(1.0);
        assertThat(BlackScholes.delta(false, 110, 100, 0, 0.05, 0, 0.2)).isEqualTo(0.0);
    }

    @Test
    void greekSigns() {
        // Long options: positive gamma/vega; calls decay (negative theta) with q=0
        assertThat(BlackScholes.gamma(100, 105, 0.3, 0.03, 0, 0.25)).isPositive();
        assertThat(BlackScholes.vega(100, 105, 0.3, 0.03, 0, 0.25)).isPositive();
        assertThat(BlackScholes.theta(true, 100, 105, 0.3, 0.03, 0, 0.25)).isNegative();
        assertThat(BlackScholes.delta(false, 100, 105, 0.3, 0.03, 0, 0.25)).isBetween(-1.0, 0.0);
    }
}
