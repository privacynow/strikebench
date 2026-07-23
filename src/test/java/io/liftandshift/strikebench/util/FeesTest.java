package io.liftandshift.strikebench.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeesTest {

    @Test
    void roundTripDoublesContractAndOrderFeesForOpenAndClose() {
        // 2 contracts × 65c × 2 (open+close) + 0 order fee × 2 = 260c
        assertThat(Fees.roundTripCents(2, 65, 0)).isEqualTo(260);
        // add a 100c per-order fee, charged twice
        assertThat(Fees.roundTripCents(2, 65, 100)).isEqualTo(260 + 200);
    }

    @Test
    void zeroOptionContractsCarryNoOptionCommission() {
        assertThat(Fees.roundTripCents(0, 65, 100)).isZero();
        assertThat(Fees.roundTripCents(-3, 65, 100)).isZero();
    }

    @Test
    void negativeConfiguredFeesAreClampedToZero() {
        assertThat(Fees.roundTripCents(4, -65, -100)).isZero();
    }
}
