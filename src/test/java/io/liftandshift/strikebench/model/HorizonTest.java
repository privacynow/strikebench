package io.liftandshift.strikebench.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HorizonTest {
    @Test
    void keepsResearchSessionsSeparateFromExpirationCalendarDays() {
        assertThat(Horizon.WEEK.tradingSessions()).isEqualTo(5);
        assertThat(Horizon.WEEK.expiryCalendarDays()).isEqualTo(7);
        assertThat(Horizon.MONTH.tradingSessions()).isEqualTo(21);
        assertThat(Horizon.MONTH.expiryCalendarDays()).isEqualTo(35);
        assertThat(Horizon.QUARTER.tradingSessions()).isEqualTo(63);
        assertThat(Horizon.QUARTER.expiryCalendarDays()).isEqualTo(90);
    }

    @Test
    void mapsPersistedPlanSessionsThroughOneVocabulary() {
        assertThat(Horizon.fromTradingSessions(1)).isEqualTo(Horizon.ZERO_DTE);
        assertThat(Horizon.fromTradingSessions(5)).isEqualTo(Horizon.WEEK);
        assertThat(Horizon.fromTradingSessions(21)).isEqualTo(Horizon.MONTH);
        assertThat(Horizon.fromTradingSessions(63)).isEqualTo(Horizon.QUARTER);
    }
}
