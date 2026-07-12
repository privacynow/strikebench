package io.liftandshift.strikebench.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ImpliedVolTest {

    @Test
    void roundTripsAcrossVolGrid() {
        double[] vols = {0.10, 0.15, 0.25, 0.40, 0.60, 0.80, 1.20};
        double[] strikes = {85, 100, 115};
        for (double sigma : vols) for (double k : strikes) for (boolean call : new boolean[]{true, false}) {
            double px = BlackScholes.price(call, 100, k, 0.5, 0.04, 0.0, sigma);
            double solved = ImpliedVol.solve(call, px, 100, k, 0.5, 0.04, 0.0);
            assertThat(solved).as("sigma=%s k=%s call=%s", sigma, k, call).isCloseTo(sigma, within(1e-4));
        }
    }

    @Test
    void arbitrageViolatingPricesReturnNaN() {
        // Call worth more than the spot violates upper no-arb bound
        assertThat(ImpliedVol.solve(true, 150, 100, 100, 0.5, 0.04, 0)).isNaN();
        // Below intrinsic-forward floor
        assertThat(ImpliedVol.solve(true, 0.0001, 100, 50, 1.0, 0.05, 0)).isNaN();
        // Degenerate inputs
        assertThat(ImpliedVol.solve(true, 5, 100, 100, 0, 0.04, 0)).isNaN();
        assertThat(ImpliedVol.solve(true, -1, 100, 100, 0.5, 0.04, 0)).isNaN();
    }
}
