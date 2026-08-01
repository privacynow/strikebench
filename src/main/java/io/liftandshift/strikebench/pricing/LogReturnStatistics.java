package io.liftandshift.strikebench.pricing;

/**
 * THE descriptive-statistics kernel for close-to-close log returns.
 *
 * <p>Callers remain responsible for choosing their observation window and for deciding whether a
 * sample or population estimator answers their question.  This class owns the arithmetic shared
 * by historical-volatility results, research regime classification, bootstrap path calibration,
 * joint-ensemble evidence, and simulated-world validation: log-return construction, mean,
 * centered returns, sample/population standard deviation, and 252-session annualization.</p>
 */
public final class LogReturnStatistics {

    public static final double TRADING_SESSIONS_PER_YEAR = 252.0;

    private final double[] returns;
    private final double mean;
    private final double squaredDeviations;

    private LogReturnStatistics(double[] values) {
        returns = values.clone();
        double total = 0;
        for (double value : returns) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("log returns must be finite");
            }
            total += value;
        }
        mean = returns.length == 0 ? Double.NaN : total / returns.length;
        double sum = 0;
        for (double value : returns) {
            double centered = value - mean;
            sum += centered * centered;
        }
        squaredDeviations = sum;
    }

    /** Statistics for already-computed log returns. */
    public static LogReturnStatistics of(double[] returns) {
        return new LogReturnStatistics(returns == null ? new double[0] : returns);
    }

    /**
     * Statistics for a sequence of strictly positive prices.  The returned observations are
     * {@code log(prices[i] / prices[i-1])}; fewer than two prices yields an empty sample.
     */
    public static LogReturnStatistics fromPrices(double[] prices) {
        if (prices == null || prices.length < 2) return of(new double[0]);
        double[] returns = new double[prices.length - 1];
        for (int i = 1; i < prices.length; i++) {
            double previous = prices[i - 1], current = prices[i];
            if (!(previous > 0) || !(current > 0)
                    || !Double.isFinite(previous) || !Double.isFinite(current)) {
                throw new IllegalArgumentException("prices must be positive and finite");
            }
            returns[i - 1] = Math.log(current / previous);
        }
        return of(returns);
    }

    public int observations() {
        return returns.length;
    }

    public double mean() {
        return mean;
    }

    /** Defensive copy of the exact log-return observations. */
    public double[] returns() {
        return returns.clone();
    }

    /** Mean-removed observations used by empirical bootstrap generators. */
    public double[] centeredReturns() {
        double[] centered = returns.clone();
        for (int i = 0; i < centered.length; i++) centered[i] -= mean;
        return centered;
    }

    /** Population standard deviation (divide by n); NaN for an empty sample. */
    public double populationStdDev() {
        return returns.length == 0 ? Double.NaN
                : Math.sqrt(Math.max(0, squaredDeviations / returns.length));
    }

    /** Sample standard deviation (divide by n-1); NaN until two observations exist. */
    public double sampleStdDev() {
        return returns.length < 2 ? Double.NaN
                : Math.sqrt(Math.max(0, squaredDeviations / (returns.length - 1)));
    }

    public double annualizedPopulationStdDev() {
        return annualize(populationStdDev());
    }

    public double annualizedSampleStdDev() {
        return annualize(sampleStdDev());
    }

    private static double annualize(double dailyStdDev) {
        return dailyStdDev * Math.sqrt(TRADING_SESSIONS_PER_YEAR);
    }

    @Override
    public String toString() {
        return "LogReturnStatistics[observations=" + returns.length + ", mean=" + mean
                + ", sampleStdDev=" + sampleStdDev() + "]";
    }
}
