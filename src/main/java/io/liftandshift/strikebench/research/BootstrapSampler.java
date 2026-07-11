package io.liftandshift.strikebench.research;

import io.liftandshift.strikebench.sim.RandomStreams;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The shared resampling foundation (adversarial-review consolidation): the event study's bootstrap
 * confidence interval and the conditional-bootstrap path producer live HERE, not as private loops
 * inside individual engines. The CI method keeps the exact legacy algorithm and seeding so
 * validated study outputs do not drift under the refactor; the path resampler is new machinery on
 * the counter-based {@link RandomStreams} (order-independent, reproducible per draw).
 */
public final class BootstrapSampler {

    private BootstrapSampler() {}

    private static final long S_PATHS = 0x5851F42D4C957F2DL; // stream id for path resampling

    /**
     * Compatibility overload for the original deterministic moving-block 90% interval.
     */
    public static double[] meanCi(List<Double> rs, long seed, int block, int iterations) {
        return meanCi(rs, seed, block, iterations, 90);
    }

    /** Deterministic moving-block bootstrap interval at the requested two-sided confidence. */
    public static double[] meanCi(List<Double> rs, long seed, int block, int iterations, int confidencePct) {
        int n = rs.size();
        if (n < 5) return new double[]{0, 0};
        int b = Math.max(1, Math.min(block, n));
        int iters = Math.clamp(iterations, 200, 10_000);
        int confidence = confidencePct == 90 || confidencePct == 99 ? confidencePct : 95;
        double tail = (1.0 - confidence / 100.0) / 2.0;
        Random rnd = new Random(seed);
        double[] means = new double[iters];
        for (int it = 0; it < iters; it++) {
            double s = 0; int c = 0;
            while (c < n) {
                int start = rnd.nextInt(n);
                for (int k = 0; k < b && c < n; k++) { s += rs.get((start + k) % n); c++; }
            }
            means[it] = s / n;
        }
        java.util.Arrays.sort(means);
        int lo = Math.clamp((int) Math.floor(tail * iters), 0, iters - 1);
        int hi = Math.clamp((int) Math.ceil((1.0 - tail) * iters) - 1, 0, iters - 1);
        return new double[]{means[lo] * 100, means[hi] * 100};
    }

    /**
     * CONDITIONAL BOOTSTRAP of whole analog paths: resamples complete forward windows (with
     * replacement) up to {@code target} paths, preserving each path's empirical shape and tails —
     * never mixing days across events. Deterministic per (seed, index) via counter streams.
     */
    public static List<List<Double>> resamplePaths(List<List<Double>> analogs, int target, long seed) {
        List<List<Double>> out = new ArrayList<>(Math.max(0, target));
        if (analogs == null || analogs.isEmpty()) return out;
        for (int i = 0; i < target; i++) {
            int pick = RandomStreams.uniformInt(seed, S_PATHS, 0, i, analogs.size());
            out.add(analogs.get(pick));
        }
        return out;
    }
}
