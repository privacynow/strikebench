package io.liftandshift.strikebench.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * US equity/options session model: regular trading 9:30–16:00 ET, Monday–Friday, EXCLUDING
 * NYSE full-day holidays (computed by rule for 2020–2035: New Year's, MLK, Presidents', Good
 * Friday, Memorial Day, Juneteenth (from 2022), Independence Day, Labor Day, Thanksgiving,
 * Christmas — with Saturday→Friday / Sunday→Monday observation shifts). Half days (early
 * closes) are NOT modeled; they count as full sessions.
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

    private static final Set<LocalDate> HOLIDAYS = buildHolidays(2020, 2035);
    /** Exchange-wide closures that do not follow an annual rule. */
    private static final Set<LocalDate> SPECIAL_CLOSURES = Set.of(
            LocalDate.of(2025, 1, 9) // National Day of Mourning for President Jimmy Carter
    );

    private MarketHours() {}

    /** True during regular trading hours on a trading day (weekends + NYSE holidays excluded). */
    public static boolean isRegularSession(Instant now) {
        ZonedDateTime et = now.atZone(EASTERN);
        if (!isTradingDay(et.toLocalDate())) return false;
        LocalTime t = et.toLocalTime();
        return !t.isBefore(OPEN) && t.isBefore(CLOSE);
    }

    /** True once the contract's final bell (16:00 ET on expiration day) has passed. */
    public static boolean contractDead(LocalDate expiration, Instant now) {
        Instant finalBell = expiration.atTime(CLOSE).atZone(EASTERN).toInstant();
        return !now.isBefore(finalBell);
    }

    /** Weekday and not an NYSE holiday. */
    public static boolean isTradingDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY
                && !HOLIDAYS.contains(d) && !SPECIAL_CLOSURES.contains(d);
    }

    /**
     * Trading days in the half-open interval (from, to] — the natural "sessions remaining until
     * expiry" count when called as tradingDaysBetween(today, expiration). Never negative.
     */
    public static int tradingDaysBetween(LocalDate from, LocalDate to) {
        if (to == null || from == null || !to.isAfter(from)) return 0;
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (isTradingDay(d)) n++;
        }
        return n;
    }

    /** Date reached after exactly {@code sessions} trading sessions, using the same holiday table. */
    public static LocalDate tradingDateAfter(LocalDate from, int sessions) {
        if (from == null || sessions <= 0) return from;
        LocalDate d = from;
        int remaining = sessions;
        while (remaining > 0) {
            d = d.plusDays(1);
            if (isTradingDay(d)) remaining--;
        }
        return d;
    }

    // ---- NYSE full-day holidays by rule ----

    private static Set<LocalDate> buildHolidays(int fromYear, int toYear) {
        Set<LocalDate> out = new HashSet<>();
        for (int y = fromYear; y <= toYear; y++) {
            out.add(observed(LocalDate.of(y, 1, 1)));                       // New Year's Day
            out.add(nthWeekday(y, 1, DayOfWeek.MONDAY, 3));                 // MLK Day
            out.add(nthWeekday(y, 2, DayOfWeek.MONDAY, 3));                 // Presidents' Day
            out.add(easterSunday(y).minusDays(2));                          // Good Friday
            out.add(lastWeekday(y, 5, DayOfWeek.MONDAY));                   // Memorial Day
            if (y >= 2022) out.add(observed(LocalDate.of(y, 6, 19)));       // Juneteenth (NYSE from 2022)
            out.add(observed(LocalDate.of(y, 7, 4)));                       // Independence Day
            out.add(nthWeekday(y, 9, DayOfWeek.MONDAY, 1));                 // Labor Day
            out.add(nthWeekday(y, 11, DayOfWeek.THURSDAY, 4));              // Thanksgiving
            out.add(observed(LocalDate.of(y, 12, 25)));                     // Christmas
        }
        return out;
    }

    /** Saturday holidays are observed Friday; Sunday holidays are observed Monday. */
    private static LocalDate observed(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case SATURDAY -> d.minusDays(1);
            case SUNDAY -> d.plusDays(1);
            default -> d;
        };
    }

    private static LocalDate nthWeekday(int year, int month, DayOfWeek dow, int nth) {
        LocalDate d = LocalDate.of(year, month, 1);
        int seen = 0;
        while (true) {
            if (d.getDayOfWeek() == dow && ++seen == nth) return d;
            d = d.plusDays(1);
        }
    }

    private static LocalDate lastWeekday(int year, int month, DayOfWeek dow) {
        LocalDate d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (d.getDayOfWeek() != dow) d = d.minusDays(1);
        return d;
    }

    /** Anonymous Gregorian computus. */
    private static LocalDate easterSunday(int y) {
        int a = y % 19, b = y / 100, c = y % 100;
        int d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(y, month, day);
    }
}
