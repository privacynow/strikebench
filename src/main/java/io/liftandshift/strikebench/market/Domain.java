package io.liftandshift.strikebench.market;

/** Data domains a provider can serve; /api/status reports health per domain. */
public enum Domain {
    QUOTES, OPTIONS, CANDLES, NEWS, RATES, HISTORICAL_OPTIONS, BROKERAGE
}
