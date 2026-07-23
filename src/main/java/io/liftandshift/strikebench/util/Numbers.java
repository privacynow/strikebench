package io.liftandshift.strikebench.util;

/**
 * THE small rounding helpers, previously copied as a private {@code round2}/{@code round4} in a
 * dozen services. One definition each so display rounding can never drift.
 */
public final class Numbers {

    private Numbers() {}

    /** Round to 2 decimal places (half-up via Math.round). */
    public static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Round to 4 decimal places (half-up via Math.round). */
    public static double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
