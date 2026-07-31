package io.liftandshift.strikebench.util;

/**
 * THE quantile convention for every simulated distribution. One linear-interpolation rule
 * (type-7 / numpy's default) so a preview band, a terminal distribution, a canvas fan and a
 * path-ensemble fan can never quote three different p10s off the same sorted sample. This replaces
 * the hand-inlined copies that had silently diverged — floor-index (SimulationEngine.q,
 * ScenarioSimulator.pct, ScenarioCanvasValuator.pct), round-index (PathEnsembleService) and two
 * already-correct interpolators (SimulationEngine.quantile, PlanOutcomeService.quantile).
 *
 * <p>{@link #of} answers "what VALUE sits at this percentile" and interpolates between neighbours.
 * {@link #index} answers "which ELEMENT is representative of this percentile" — you cannot show
 * half a path, so a representative-element pick returns a real order statistic (floor rank), which
 * is a genuinely different question, not a fourth convention.
 *
 * <p>Callers pass an ASCENDING-sorted array; {@code p} is clamped to [0,1].
 */
public final class Quantiles {

    private Quantiles() {}

    /** Linear-interpolated quantile of an ascending {@code double[]} (empty → NaN). */
    public static double of(double[] sorted, double p) {
        if (sorted.length == 0) return Double.NaN;
        if (sorted.length == 1) return sorted[0];
        double pos = clamp01(p) * (sorted.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        double w = pos - lo;
        return sorted[lo] * (1 - w) + sorted[hi] * w;
    }

    /** Linear-interpolated quantile of an ascending {@code long[]}, rounded to the nearest long (empty → 0). */
    public static long of(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        if (sorted.length == 1) return sorted[0];
        double pos = clamp01(p) * (sorted.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        double w = pos - lo;
        return Math.round(sorted[lo] * (1 - w) + sorted[hi] * w);
    }

    /** Floor-rank index into an ascending array of {@code n} elements — for picking a REAL representative element. */
    public static int index(int n, double p) {
        if (n <= 1) return 0;
        return Math.max(0, Math.min(n - 1, (int) Math.floor(clamp01(p) * (n - 1))));
    }

    private static double clamp01(double p) { return Math.max(0, Math.min(1, p)); }
}
