package io.liftandshift.strikebench.model;

import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GreeksViewTest {
    @Test
    void contractHasExactlyOneExplicitUnitVocabulary() {
        GreeksView view = new GreeksView(40.0, -2.0, 600.0, -1_000.0);

        assertThat(Arrays.stream(GreeksView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("deltaShares", "gammaSharesPerDollar",
                        "thetaCentsPerDay", "vegaCentsPerPoint");

        var json = Json.MAPPER.valueToTree(view);
        assertThat(java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(json.fieldNames(), 0), false))
                .containsExactly("deltaShares", "gammaSharesPerDollar",
                        "thetaCentsPerDay", "vegaCentsPerPoint");
        assertThat(json.get("deltaShares").doubleValue()).isEqualTo(40.0);
        assertThat(json.get("gammaSharesPerDollar").doubleValue()).isEqualTo(-2.0);
        assertThat(json.get("thetaCentsPerDay").doubleValue()).isEqualTo(600.0);
        assertThat(json.get("vegaCentsPerPoint").doubleValue()).isEqualTo(-1_000.0);
        assertThat(json.has("gammaShares")).isFalse();
        assertThat(json.has("thetaPerDay")).isFalse();
        assertThat(json.has("vegaPerPoint")).isFalse();
    }

    @Test
    void nonFiniteGreeksCannotReachJson() {
        assertThatThrownBy(() -> new GreeksView(Double.NaN, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deltaShares");
        assertThatThrownBy(() -> new GreeksView(0, Double.POSITIVE_INFINITY, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gammaSharesPerDollar");
        assertThatThrownBy(() -> new GreeksView(0, 0, Double.NEGATIVE_INFINITY, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thetaCentsPerDay");
        assertThatThrownBy(() -> new GreeksView(0, 0, 0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vegaCentsPerPoint");
    }
}
