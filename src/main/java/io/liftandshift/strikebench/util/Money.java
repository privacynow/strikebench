package io.liftandshift.strikebench.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money helpers. Ledger and account balances are exact integer cents; analytics use doubles. */
public final class Money {

    /** Max scale for per-share BigDecimal prices. */
    public static final int PRICE_SCALE = 4;

    private Money() {}

    public static long toCents(double dollars) {
        return BigDecimal.valueOf(dollars).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static long toCents(BigDecimal dollars) {
        return dollars.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Per-share price × share count → exact integer cents (HALF_UP on sub-cent remainders). */
    public static long centsFromPrice(BigDecimal perShare, long shares) {
        return perShare.multiply(BigDecimal.valueOf(shares)).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Integer cents → BigDecimal dollars, scale 2. */
    public static BigDecimal priceFromCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }

    public static double toDollars(long cents) {
        return cents / 100.0;
    }

    /** Formats cents as e.g. "$1,234.56" or "-$12.30" for logs/audit messages. */
    public static String fmt(long cents) {
        long abs = Math.abs(cents);
        return (cents < 0 ? "-$" : "$") + String.format("%,d.%02d", abs / 100, abs % 100);
    }
}
