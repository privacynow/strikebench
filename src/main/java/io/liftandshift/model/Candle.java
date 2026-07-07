package io.liftandshift.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Daily (or intraday) OHLCV bar. */
public record Candle(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        boolean adjusted
) {}
