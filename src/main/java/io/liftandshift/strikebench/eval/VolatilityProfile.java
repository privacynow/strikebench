package io.liftandshift.strikebench.eval;

/**
 * Volatility context for the underlying: where implied vol sits versus its own history (rank /
 * percentile), the variance risk premium (implied minus realized), and the market's expected move
 * to expiry. Rank/percentile need a history window; when unavailable they are null with a note in
 * {@link #source()} rather than a fabricated number. Produced by {@code VolatilityProfiler}.
 */
public record VolatilityProfile(
        Double atmIv,             // at-the-money implied vol (annualized), null if no chain
        Double ivRankPct,         // 0..100 within the trailing window, null when history is thin
        Double ivPercentilePct,   // 0..100, null when history is thin
        Double realizedVol30,     // 30-day realized (annualized), null if no candles
        Double varianceRiskPremium, // atmIv - realizedVol30, null if either missing
        Double expectedMovePct,   // atmIv * sqrt(DTE/365), null if no IV/DTE
        int historyDays,          // trading days of IV history used for rank/percentile
        String source             // provenance / why a field is null
) {}
