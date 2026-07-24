package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.model.Candle;

import java.util.List;

/**
 * Classifies the trailing regime from the lane's own candles. Thresholds are deliberately
 * blunt — this is a framing lens, not a signal: ±8% over the window separates a trend from
 * chop, and fewer than 30 observed sessions is honestly "unknown".
 */
public final class RegimeProfiler {
    private static final int MIN_SESSIONS = 30;
    private static final double TREND_THRESHOLD_PCT = 8.0;

    private RegimeProfiler() {}

    public static RegimeSnapshot profile(List<Candle> candles, VolatilityProfile vol,
                                         boolean eventSoon, String laneLabel) {
        return profile(candles, vol, eventSoon, null, laneLabel);
    }

    /** Event-aware variant: null {@code eventSoon} means the calendar evidence is unavailable. */
    public static RegimeSnapshot profile(List<Candle> candles, VolatilityProfile vol,
                                         Boolean eventSoon, String eventBasis, String laneLabel) {
        Double vrp = vol == null ? null : vol.varianceRiskPremium();
        Double ivRank = vol == null ? null : vol.ivRankPct();
        if (candles == null || candles.size() < MIN_SESSIONS) {
            return new RegimeSnapshot(null, null, candles == null ? 0 : candles.size(), null,
                    vrp, ivRank, eventSoon, eventBasis,
                    "regime unknown — fewer than " + MIN_SESSIONS + " observed sessions in this lane");
        }
        int sessions = candles.size();
        double first = candles.getFirst().close().doubleValue();
        double last = candles.getLast().close().doubleValue();
        double high = candles.stream().mapToDouble(c -> c.close().doubleValue()).max().orElse(last);
        if (first <= 0 || last <= 0) {
            return new RegimeSnapshot(null, null, sessions, null, vrp, ivRank, eventSoon, eventBasis,
                    "regime unknown — degenerate closes in this lane's history");
        }
        double totalReturnPct = (last / first - 1.0) * 100.0;
        double drawdownPct = high > 0 ? (last / high - 1.0) * 100.0 : 0.0;
        RegimeSnapshot.Trend trend = totalReturnPct > TREND_THRESHOLD_PCT ? RegimeSnapshot.Trend.UP
                : totalReturnPct < -TREND_THRESHOLD_PCT ? RegimeSnapshot.Trend.DOWN
                : RegimeSnapshot.Trend.SIDEWAYS;
        return new RegimeSnapshot(trend, totalReturnPct, sessions, drawdownPct, vrp, ivRank,
                eventSoon, eventBasis,
                sessions + " observed sessions (" + laneLabel + "); trend threshold ±"
                        + (int) TREND_THRESHOLD_PCT + "%");
    }
}
