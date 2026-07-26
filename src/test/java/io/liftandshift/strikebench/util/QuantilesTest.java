package io.liftandshift.strikebench.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuantilesTest {

    @Test
    void valuesUseOneTypeSevenInterpolationRule() {
        assertThat(Quantiles.of(new double[]{1, 2, 3, 4}, .50)).isEqualTo(2.5);
        assertThat(Quantiles.of(new double[]{1, 2, 3, 4}, .75)).isEqualTo(3.25);
        assertThat(Quantiles.of(new long[]{100, 200, 300, 400}, .50)).isEqualTo(250);
    }

    @Test
    void representativeIndexesUseOneRealElementFloorRankRule() {
        assertThat(Quantiles.index(4, .50)).isEqualTo(1);
        assertThat(Quantiles.index(4, .75)).isEqualTo(2);
        assertThat(Quantiles.index(4, -1)).isZero();
        assertThat(Quantiles.index(4, 2)).isEqualTo(3);
    }
}
