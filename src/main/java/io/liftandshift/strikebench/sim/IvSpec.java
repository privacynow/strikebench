package io.liftandshift.strikebench.sim;

/**
 * The implied-volatility path a scenario assumes. DETERMINISTIC by design: unlike the underlying
 * (which is stochastic), the IV path is a scenario <em>assumption</em> the user sets — "IV drifts
 * down", "IV gets crushed after earnings on day 3" — so it must be exactly what they asked for,
 * reproducible and explainable. Option prices are then modeled (BSM) from this path; that is always
 * labeled MODELED in the evidence.
 */
public record IvSpec(
        double startIv,          // annualized, e.g. 0.32
        double driftPerYear,     // linear IV drift, e.g. -0.10 = fades 10 vol pts/yr
        double meanRevertSpeed,  // pull toward longRunIv (per year); 0 = none
        double longRunIv,
        int eventDay,            // trading day of the catalyst (-1 = none)
        double eventShockPct,    // relative jump at the event: -0.30 = 30% crush, +0.40 = expansion
        double minIv, double maxIv) {

    public static IvSpec flat(double iv) {
        double v = iv <= 0 ? 0.30 : iv;
        return new IvSpec(v, 0, 0, v, -1, 0, 0.03, 4.0);
    }

    public static IvSpec crushAfter(double iv, int eventDay) {
        double v = iv <= 0 ? 0.30 : iv;
        return new IvSpec(v * 1.15, 0, 1.5, v * 0.85, eventDay, -0.35, 0.03, 4.0);
    }

    /** Earnings-style teaching path anchored to the active market's ATM IV, not a canned level. */
    public static IvSpec eventCrushAround(double atmIv, int eventDay) {
        double v = atmIv <= 0 ? 0.30 : atmIv;
        return new IvSpec(v * 1.40, 0, 1.5, v, eventDay, -0.35, 0.03, 4.0);
    }

    public IvSpec sane() {
        double lo = minIv <= 0 ? 0.03 : minIv, hi = maxIv <= lo ? 4.0 : maxIv;
        return new IvSpec(clamp(startIv <= 0 ? 0.30 : startIv, lo, hi), clamp(driftPerYear, -3, 3),
                clamp(meanRevertSpeed, 0, 20), clamp(longRunIv <= 0 ? startIv : longRunIv, lo, hi),
                eventDay, clamp(eventShockPct, -0.9, 3), lo, hi);
    }

    /** The IV at each step (0..steps), deterministic. dt = years per step. */
    public double[] path(int steps, double dt, int stepsPerDay) {
        IvSpec s = sane();
        double[] out = new double[steps + 1];
        double iv = s.startIv();
        for (int i = 0; i <= steps; i++) {
            out[i] = iv;
            iv += s.driftPerYear() * dt + s.meanRevertSpeed() * (s.longRunIv() - iv) * dt;
            int day = i / Math.max(1, stepsPerDay);
            if (s.eventDay() >= 0 && day == s.eventDay() && (i % Math.max(1, stepsPerDay)) == Math.max(1, stepsPerDay) - 1) {
                iv *= (1 + s.eventShockPct()); // the catalyst hits at that day's close
            }
            iv = clamp(iv, s.minIv(), s.maxIv());
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
