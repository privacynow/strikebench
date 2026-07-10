package io.liftandshift.strikebench.sim;

import java.util.Random;

/**
 * Deterministic random source for the simulation engine — same seed ⇒ identical paths, which is the
 * reproducibility contract the whole feature rests on (a saved run must regenerate exactly). Wraps
 * java.util.Random and adds the innovations the path models need: standard normal, standardized
 * Student-t (unit variance), and a Poisson count for jump arrivals.
 */
public final class Rng {

    private final Random r;

    public Rng(long seed) { this.r = new Random(seed); }

    public double uniform() { return r.nextDouble(); }

    public double gaussian() { return r.nextGaussian(); }

    public int nextInt(int bound) { return r.nextInt(bound); }

    /**
     * A Student-t innovation SCALED to unit variance, so it drops into the same place as a standard
     * normal but with fatter tails. For ν&gt;2 the raw t has variance ν/(ν−2); we divide by its sd so
     * the simulated realized vol still matches the requested σ. ν≤2 (undefined variance) falls back to
     * a large ν (near-normal) to stay well-behaved.
     */
    public double studentT(double nu) {
        if (nu <= 2.5) nu = 2.5;
        double z = gaussian();
        // chi-square(nu) as a sum of nu (rounded) squared normals is costly; use the gamma route via
        // a simple approximation: t = z / sqrt(chi2/nu). Build chi2 from a small set of normals.
        double chi2 = 0;
        int k = (int) Math.round(nu);
        if (k < 1) k = 1;
        for (int i = 0; i < k; i++) { double g = gaussian(); chi2 += g * g; }
        double t = z / Math.sqrt(chi2 / k);
        double sd = Math.sqrt(nu / (nu - 2.0));
        return t / sd;
    }

    /** Poisson-distributed count with mean {@code lambda} (Knuth's algorithm; fine for small means). */
    public int poisson(double lambda) {
        if (lambda <= 0) return 0;
        double l = Math.exp(-lambda);
        int k = 0;
        double p = 1;
        do { k++; p *= uniform(); } while (p > l);
        return k - 1;
    }
}
