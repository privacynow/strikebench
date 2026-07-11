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
     * Deterministic MOVING-BLOCK bootstrap 90% CI on the mean (as percent of the input scale).
     * VERBATIM the algorithm previously private to ResearchQuestionEngine — with non-overlapping
     * events callers pass block=1 (iid resampling of independent events).
     */
    public static double[] meanCi(List<Double> rs, long seed, int block, int iterations) {
        int n = rs.size();
        if (n < 5) return new double[]{0, 0};
        int b = Math.max(1, Math.min(block, n));
        Random rnd = new Random(seed);
        double[] means = new double[iterations];
        for (int it = 0; it < iterations; it++) {
            double s = 0; int c = 0;
            while (c < n) {
                int start = rnd.nextInt(n);
                for (int k = 0; k < b && c < n; k++) { s += rs.get((start + k) % n); c++; }
            }
            means[it] = s / n;
        }
        java.util.Arrays.sort(means);
        return new double[]{means[(int) (0.05 * iterations)] * 100, means[(int) (0.95 * iterations)] * 100};
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
