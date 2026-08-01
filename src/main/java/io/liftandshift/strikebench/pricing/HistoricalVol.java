package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.model.Candle;

import java.util.List;

/** Annualized historical volatility from daily closes. A ratio, not money — doubles fine. */
public final class HistoricalVol {

    /** A 30-session estimate is too unstable below this shared floor. Research, signals, and
     *  economic assessment all use the same threshold so one screen cannot claim HV exists while
     *  another refuses the realized-volatility EV mode. */
    public static final int MIN_OBSERVATIONS = 20;

    private HistoricalVol() {}

    /** Close-to-close stdev of log returns over the trailing {@code days} bars, annualized. NaN if too little data. */
    public static double annualized(List<Candle> candles, int days) {
        int minimum = Math.max(3, Math.min(MIN_OBSERVATIONS, days));
        if (candles == null || candles.size() < minimum) return Double.NaN;
        int from = Math.max(1, candles.size() - days);
        double[] prices = new double[candles.size() - from + 1];
        for (int i = from - 1; i < candles.size(); i++) {
            prices[i - from + 1] = candles.get(i).close().doubleValue();
        }
        try {
            return LogReturnStatistics.fromPrices(prices).annualizedSampleStdDev();
        } catch (IllegalArgumentException invalidPrice) {
            return Double.NaN;
        }
    }

    /**
     * The trailing-{@code days} annualized vol evaluated at every bar — the same close-to-close
     * log-return stdev as {@link #annualized}, read at each index so a chart can draw one rolling
     * realized-vol series without a second (divergent) client estimator. {@code NaN} until a bar
     * has enough trailing history.
     */
    public static double[] rollingAnnualized(List<Candle> candles, int days) {
        int n = candles == null ? 0 : candles.size();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = annualized(candles.subList(0, i + 1), days);
        }
        return out;
    }
}
