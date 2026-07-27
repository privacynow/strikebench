package io.liftandshift.strikebench.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ScenarioInputValidationTest {

    @Test
    void authoredScenarioValuesAreAcceptedUnchangedOrRejectedRatherThanClamped() {
        var exact = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                45, 2, .10, .32, 0, 0, 0, 6,
                ScenarioSpec.Heston.fromVol(.32), 7, 120);
        assertThat(exact.validated()).isSameAs(exact);

        var excessive = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                757, 1, .10, .32, 0, 0, 0, 6,
                ScenarioSpec.Heston.fromVol(.32), 7, 120);
        assertThatThrownBy(excessive::validated)
                .hasMessageContaining("horizonDays")
                .hasMessageContaining("1 through 756");

        var negativeVol = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                45, 1, .10, -.01, 0, 0, 0, 6,
                ScenarioSpec.Heston.fromVol(.32), 7, 120);
        assertThatThrownBy(negativeVol::validated).hasMessageContaining("volAnnual");
    }

    @Test
    void authoredIvValuesAreAcceptedUnchangedOrRejectedRatherThanClamped() {
        var exact = new IvSpec(.32, -.03, 1.2, .28, 10, -.25, .03, 4);
        assertThat(exact.validated(45)).isSameAs(exact);

        var invalid = new IvSpec(.32, -.03, 1.2, .28, 46, -.25, .03, 4);
        assertThatThrownBy(() -> invalid.validated(45))
                .hasMessageContaining("eventDay")
                .hasMessageContaining("45-session");
    }
}
