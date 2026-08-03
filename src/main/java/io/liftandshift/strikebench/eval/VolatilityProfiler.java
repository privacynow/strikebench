package io.liftandshift.strikebench.eval;

import java.util.Collections;

/** IV rank/percentile (from observed history), variance risk premium, and expected move. */
public final class VolatilityProfiler {

    /** Below this many history points, rank/percentile are null rather than fabricated. */
    public static final int MIN_HISTORY = 10;

    public record Input(Double atmIv, Double realizedVol30, java.util.List<Double> ivHistory,
                        io.liftandshift.strikebench.market.OptionTime.Measure timeToExpiry) {
        public Input {
            if (ivHistory == null) throw new IllegalArgumentException("IV history is required");
            ivHistory = java.util.List.copyOf(ivHistory);
        }
    }

    /** Shared read model for Research and candidate evaluation. Keeping the rank calculation here
     * prevents a detail page from inventing different thresholds or percentile math. */
    public VolatilityProfile profile(Input input) {
        if (input == null) throw new IllegalArgumentException("volatility input is required");
        Double atm = input.atmIv();
        Double rv = input.realizedVol30();
        java.util.List<Double> ivHistory = input.ivHistory();
        Double vrp = (atm != null && rv != null) ? atm - rv : null;
        var expectedMoveResult = io.liftandshift.strikebench.pricing.ExpectedMove
                .listedExpiry(atm, input.timeToExpiry());
        Double expectedMove = expectedMoveResult == null ? null : expectedMoveResult.fraction();

        int n = ivHistory.size();
        Double rank = null, pct = null;
        String source;
        if (atm != null && n >= MIN_HISTORY) {
            double min = Collections.min(ivHistory);
            double max = Collections.max(ivHistory);
            rank = max > min ? clamp(100.0 * (atm - min) / (max - min)) : 50.0;
            long below = ivHistory.stream().filter(v -> v < atm).count();
            pct = 100.0 * below / n;
            source = "IV rank/percentile over " + n + " observed days of snapshots";
        } else if (atm == null) {
            source = "no chain IV available";
        } else {
            source = "IV history too thin for rank/percentile (" + n + " day(s); need " + MIN_HISTORY + ")";
        }
        return new VolatilityProfile(atm, rank, pct, rv, vrp, expectedMove, n, source);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(100.0, v)); }
}
