package io.liftandshift.strikebench.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HorizonTest {

    @Test void exactPlanSessionsDoNotCollapseIntoNamedBuckets() {
        assertThat(Horizon.exactTradingSessions(1)).isEqualTo("1d");
        assertThat(Horizon.exactTradingSessions(30)).isEqualTo("30d");
        assertThat(Horizon.exactTradingSessions(45)).isEqualTo("45d");
        assertThat(Horizon.tradingSessions("30d")).isEqualTo(30);
        assertThat(Horizon.expiryCalendarDays("30d")).isEqualTo(42);
        assertThat(Horizon.tradingSessions("month")).isEqualTo(21);
    }
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
