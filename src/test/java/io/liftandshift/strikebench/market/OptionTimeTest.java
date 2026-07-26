package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OptionTimeTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 24);

    @Test
    void sameDayContractIsLiveBeforeFinalBellAndExpiredAtFinalBell() {
        OptionTime.Measure before = OptionTime.toExpiry(
                Instant.parse("2026-07-24T19:59:59Z"), FRIDAY);
        OptionTime.Measure atClose = OptionTime.toExpiry(
                Instant.parse("2026-07-24T20:00:00Z"), FRIDAY);

        assertThat(before.state()).isEqualTo(OptionTime.State.LIVE_0DTE);
        assertThat(before.calendarDays()).isZero();
        assertThat(before.sessions()).isZero();
        assertThat(before.years()).isEqualTo(OptionTime.LIVE_0DTE_MODEL_YEARS);
        assertThat(before.basis()).contains("live 0DTE").contains("0.5 calendar day");

        assertThat(atClose.state()).isEqualTo(OptionTime.State.EXPIRED);
        assertThat(atClose.years()).isNull();
        assertThat(atClose.hasModelTime()).isFalse();
        assertThat(atClose.hasManagementClock()).isFalse();
    }

    @Test
    void marketCalendarExcludesHolidayAndWeekendWithoutChangingCalendarYearFraction() {
        OptionTime.Measure time = OptionTime.toExpiry(
                Instant.parse("2026-07-02T16:00:00Z"), LocalDate.of(2026, 7, 6));

        assertThat(time.state()).isEqualTo(OptionTime.State.LIVE);
        assertThat(time.calendarDays()).isEqualTo(4);
        assertThat(time.sessions()).isEqualTo(1);
        assertThat(time.years()).isEqualTo(4.0 / 365.0);
    }

    @Test
    void noOptionAndExpiredReceiptsNeverCarryModelYears() {
        OptionTime.Measure stockOnly = OptionTime.nearest(
                List.of(Leg.stockShares(LegAction.BUY, 100, new BigDecimal("100"))),
                Instant.parse("2026-07-24T16:00:00Z"));
        OptionTime.Measure expired = OptionTime.toExpiry(
                Instant.parse("2026-07-24T16:00:00Z"), LocalDate.of(2026, 7, 23));

        assertThat(stockOnly.state()).isEqualTo(OptionTime.State.NO_OPTION);
        assertThat(stockOnly.years()).isNull();
        assertThat(expired.state()).isEqualTo(OptionTime.State.EXPIRED);
        assertThat(expired.years()).isNull();
    }

    @Test
    void sessionsOnlyPersistenceStaysPartialAndInventsNeitherCalendarDaysNorYears() {
        OptionTime.Measure restored = OptionTime.ofRecordedUnits(12, null);

        assertThat(restored.state()).isEqualTo(OptionTime.State.PARTIAL);
        assertThat(restored.sessions()).isEqualTo(12);
        assertThat(restored.calendarDays()).isEqualTo(-1);
        assertThat(restored.years()).isNull();
        assertThat(restored.basis()).contains("calendar days and model years unavailable");
    }
}
