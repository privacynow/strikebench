package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.market.OptionTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class ExpectedMoveTest {

    @Test
    void listedExpiryUsesTheCanonicalCalendarClock() {
        OptionTime.Measure time = OptionTime.toExpiry(
                Instant.parse("2026-07-24T18:00:00Z"), LocalDate.parse("2026-08-24"));

        Double move = ExpectedMove.fraction(0.30, time);
        var range = ExpectedMove.listedExpiryRange(100, 0.30, time, 0.04);

        assertThat(move).isCloseTo(0.30 * Math.sqrt(time.years()), offset(1e-12));
        assertThat(range.expectedMoveFraction()).isEqualTo(move);
        assertThat(range.clockBasis()).isEqualTo(ExpectedMove.ClockBasis.LISTED_EXPIRY_CALENDAR);
        assertThat(range.modelYears()).isEqualTo(time.years());
        assertThat(range.timeBasis()).isEqualTo(time.basis());
    }

    @Test
    void scenarioHorizonCannotBeSubstitutedForAListedExpiry() {
        var horizon = new ExpectedMove.ScenarioHorizon(21);
        var range = ExpectedMove.scenarioHorizonRange(100, 0.30, horizon, 0.04);

        assertThat(range.clockBasis()).isEqualTo(ExpectedMove.ClockBasis.SCENARIO_TRADING_SESSIONS);
        assertThat(range.modelYears()).isEqualTo(21.0 / 252.0);
        assertThat(range.timeBasis()).contains("scenario-horizon convention");
        assertThatThrownBy(() -> new ExpectedMove.ScenarioHorizon(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void insufficientInputsStayUnavailable() {
        OptionTime.Measure sessionsOnly = OptionTime.ofRecordedUnits(5, null);
        assertThat(ExpectedMove.fraction(0.30, sessionsOnly)).isNull();
        assertThat(ExpectedMove.listedExpiryRange(100, 0.30, sessionsOnly, 0.04)).isNull();
        assertThat(ExpectedMove.scenarioHorizonRange(
                100, null, new ExpectedMove.ScenarioHorizon(5), 0.04)).isNull();
    }
}
