package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalContextTimeTest {

    @Test
    void marketAwareContextKeepsCalendarTradingAndYearUnitsDistinct() {
        LocalDate asOf = LocalDate.of(2026, 7, 24);
        OptionTime.Measure time = OptionTime.toExpiry(
                Instant.parse("2026-07-24T16:00:00Z"), LocalDate.of(2026, 8, 21));
        EvalContext context = new EvalContext("AAPL", 20_000L, asOf, time,
                0.30, 0.25, List.of(), 1_000_000L, true, 0.04,
                DataEvidence.of("treasury", Freshness.EOD), null, null, null, List.of(),
                DataEvidence.of("stored history", Freshness.EOD));

        assertThat(context.calendarDaysToExpiry()).isEqualTo(28);
        assertThat(context.tradingSessionsToExpiry()).isEqualTo(time.sessions());
        assertThat(context.yearsToExpiry()).isEqualTo(time.years());
        assertThat(context.timeToExpiry().basis()).contains("calendar days").contains("trading session");
        assertThat(context.timeToExpiry().state()).isEqualTo(OptionTime.State.LIVE);
    }

    @Test
    void legacyCalendarOnlyFixtureDoesNotInventTradingSessions() {
        EvalContext context = new EvalContext("AAPL", 20_000L, LocalDate.of(2026, 7, 24),
                28, 0.30, 0.25, List.of(), 1_000_000L, true, 0.04,
                DataEvidence.of("treasury", Freshness.EOD), null);

        assertThat(context.calendarDaysToExpiry()).isEqualTo(28);
        assertThat(context.tradingSessionsToExpiry()).isEqualTo(-1);
        assertThat(context.timeToExpiry().basis()).contains("trading sessions unavailable");
        assertThat(context.timeToExpiry().state()).isEqualTo(OptionTime.State.PARTIAL);
    }

    @Test
    void expiredContextRetainsTypedAbsenceInsteadOfReceivingModelYears() {
        Instant afterClose = Instant.parse("2026-07-24T20:00:00Z");
        OptionTime.Measure expired = OptionTime.toExpiry(afterClose, LocalDate.of(2026, 7, 24));
        EvalContext context = new EvalContext("AAPL", 20_000L, LocalDate.of(2026, 7, 24),
                expired, 0.30, 0.25, List.of(), 1_000_000L, false, 0.04,
                DataEvidence.of("treasury", Freshness.EOD), null, null, null, List.of(),
                DataEvidence.of("stored history", Freshness.EOD));

        assertThat(context.timeToExpiry().state()).isEqualTo(OptionTime.State.EXPIRED);
        assertThat(context.hasModelTime()).isFalse();
        assertThat(context.yearsToExpiry()).isNull();
    }
}
