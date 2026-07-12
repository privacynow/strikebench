package io.liftandshift.strikebench.eval;

import java.util.Collections;

/** IV rank/percentile (from observed history), variance risk premium, and expected move. */
public final class VolatilityProfiler {

    /** Below this many history points, rank/percentile are null rather than fabricated. */
    private static final int MIN_HISTORY = 10;

    public VolatilityProfile profile(EvalContext ctx) {
        Double atm = ctx.atmIv();
        Double rv = ctx.realizedVol30();
        Double vrp = (atm != null && rv != null) ? atm - rv : null;
        Double expectedMove = (atm != null && ctx.daysToExpiry() > 0)
                ? atm * Math.sqrt(ctx.daysToExpiry() / 365.0) : null;

        int n = ctx.ivHistory().size();
        Double rank = null, pct = null;
        String source;
        if (atm != null && n >= MIN_HISTORY) {
            double min = Collections.min(ctx.ivHistory());
            double max = Collections.max(ctx.ivHistory());
            rank = max > min ? clamp(100.0 * (atm - min) / (max - min)) : 50.0;
            long below = ctx.ivHistory().stream().filter(v -> v < atm).count();
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
