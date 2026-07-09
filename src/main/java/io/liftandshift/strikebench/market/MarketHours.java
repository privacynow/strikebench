package io.liftandshift.strikebench.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * US equity/options session model: regular trading 9:30–16:00 ET, Monday–Friday.
 * Exchange holidays are NOT modeled (documented limitation) — on a holiday this class
 * reports an open weekday session; stale-quote freshness warnings still apply.
 *
 * The critical rule for trade entry: an option expiring on day D is DEAD once
 * 16:00 ET on D has passed, even though the calendar date hasn't rolled over.
 * Trading a same-day expiry after the close is how "riskless" trades against
 * zombie quotes were born.
 */
public final class MarketHours {

    public static final ZoneId EASTERN = ZoneId.of("America/New_York");
    static final LocalTime OPEN = LocalTime.of(9, 30);
    static final LocalTime CLOSE = LocalTime.of(16, 0);

    private MarketHours() {}

    /** True during regular trading hours on a weekday (holidays not modeled). */
    public static boolean isRegularSession(Instant now) {
        ZonedDateTime et = now.atZone(EASTERN);
        DayOfWeek day = et.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime t = et.toLocalTime();
        return !t.isBefore(OPEN) && t.isBefore(CLOSE);
    }

    /** True once the contract's final bell (16:00 ET on expiration day) has passed. */
    public static boolean contractDead(LocalDate expiration, Instant now) {
        Instant finalBell = expiration.atTime(CLOSE).atZone(EASTERN).toInstant();
        return !now.isBefore(finalBell);
    }
}
