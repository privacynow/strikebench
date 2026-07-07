package io.liftandshift.model;

import java.math.BigDecimal;

/** Normalized underlying quote. All prices are BigDecimal; null when unknown. */
public record Quote(
        String symbol,
        String description,
        BigDecimal last,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal prevClose,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        Long volume,
        boolean optionable,
        long asOfEpochMs,
        String source,
        Freshness freshness
) {
    /** Best available mark: mid of bid/ask, else last, else prevClose. */
    public BigDecimal mark() {
        if (bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0) {
            return bid.add(ask).divide(BigDecimal.valueOf(2), io.liftandshift.util.Money.PRICE_SCALE, java.math.RoundingMode.HALF_UP);
        }
        if (last != null && last.signum() > 0) return last;
        return prevClose;
    }
}
