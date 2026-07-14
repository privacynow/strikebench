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

    /** Maps a persisted Plan's trading-session count back to the nearest named horizon. */
    public static Horizon fromTradingSessions(Integer sessions) {
        if (sessions == null) return MONTH;
        if (sessions <= 1) return ZERO_DTE;
        if (sessions <= 10) return WEEK;
        if (sessions <= 45) return MONTH;
        return QUARTER;
    }
}
