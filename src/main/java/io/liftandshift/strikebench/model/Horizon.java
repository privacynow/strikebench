package io.liftandshift.strikebench.model;

import java.util.Locale;

/**
 * Product horizon vocabulary with its two deliberately different units.
 *
 * <p>Research paths and Plan evidence count trading sessions. Listed-option selection uses
 * calendar days because expirations are calendar dates. Keeping both values on one enum prevents
 * a quarter from silently becoming either 63 or 90 depending on which screen typed the constant.</p>
 */
public enum Horizon {
    ZERO_DTE("0DTE", 1, 0),
    WEEK("week", 5, 7),
    MONTH("month", 21, 35),
    QUARTER("quarter", 63, 90);

    private final String key;
    private final int tradingSessions;
    private final int expiryCalendarDays;

    Horizon(String key, int tradingSessions, int expiryCalendarDays) {
        this.key = key;
        this.tradingSessions = tradingSessions;
        this.expiryCalendarDays = expiryCalendarDays;
    }

    public String key() { return key; }
    public int tradingSessions() { return tradingSessions; }
    public int expiryCalendarDays() { return expiryCalendarDays; }

    public static Horizon parse(String raw) {
        String value = raw == null ? "month" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "0dte", "day", "1d" -> ZERO_DTE;
            case "week" -> WEEK;
            case "quarter" -> QUARTER;
            default -> MONTH;
        };
    }

    /** Exact-or-named trading sessions at every engine boundary. */
    public static int tradingSessions(String raw) {
        Integer exact = exactSessions(raw);
        return exact == null ? parse(raw).tradingSessions() : exact;
    }

    /** Exact-or-named calendar-day anchor used only to select listed expirations. */
    public static int expiryCalendarDays(String raw) {
        Integer exact = exactSessions(raw);
        return exact == null ? parse(raw).expiryCalendarDays()
                : Math.max(1, Math.round(exact * 7f / 5f));
    }

    private static Integer exactSessions(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[1-9]\\d{0,2}d")) return null;
        int sessions = Integer.parseInt(value.substring(0, value.length() - 1));
        if (sessions > 756) throw new IllegalArgumentException("horizon sessions must be between 1 and 756");
        return sessions;
    }

    /** Maps a persisted Plan's trading-session count back to the nearest named horizon. */
    public static Horizon fromTradingSessions(Integer sessions) {
        if (sessions == null) return MONTH;
        if (sessions <= 1) return ZERO_DTE;
        if (sessions <= 10) return WEEK;
        if (sessions <= 45) return MONTH;
        return QUARTER;
    }

    /**
     * Exact Plan-to-engine contract. Named horizons remain useful request shortcuts, but a Plan's
     * declared 30 sessions must not silently become the MONTH bucket's 21 sessions on the way to
     * recommendation, evidence, or order review.
     */
    public static String exactTradingSessions(Integer sessions) {
        if (sessions == null) return null;
        if (sessions < 1 || sessions > 756) {
            throw new IllegalArgumentException("horizon sessions must be between 1 and 756");
        }
        return sessions + "d";
    }
}
