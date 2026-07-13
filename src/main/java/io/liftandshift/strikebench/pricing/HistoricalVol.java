package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.model.Candle;

import java.util.List;

/** Annualized historical volatility from daily closes. A ratio, not money — doubles fine. */
public final class HistoricalVol {

    private static final int TRADING_DAYS = 252;
    /** A 30-session estimate is too unstable below this shared floor. Research, signals, and
     *  economic assessment all use the same threshold so one screen cannot claim HV exists while
     *  another refuses the realized-volatility EV lane. */
    public static final int MIN_OBSERVATIONS = 20;

    private HistoricalVol() {}

    /** Close-to-close stdev of log returns over the trailing {@code days} bars, annualized. NaN if too little data. */
    public static double annualized(List<Candle> candles, int days) {
        int minimum = Math.max(3, Math.min(MIN_OBSERVATIONS, days));
        if (candles == null || candles.size() < minimum) return Double.NaN;
        int from = Math.max(1, candles.size() - days);
        double[] rets = new double[candles.size() - from];
        for (int i = from; i < candles.size(); i++) {
            double prev = candles.get(i - 1).close().doubleValue();
            double cur = candles.get(i).close().doubleValue();
            if (prev <= 0 || cur <= 0) return Double.NaN;
            rets[i - from] = Math.log(cur / prev);
        }
        if (rets.length < 2) return Double.NaN;
        double mean = 0;
        for (double r : rets) mean += r;
        mean /= rets.length;
        double var = 0;
        for (double r : rets) var += (r - mean) * (r - mean);
        var /= (rets.length - 1);
        return Math.sqrt(var * TRADING_DAYS);
    }
}
