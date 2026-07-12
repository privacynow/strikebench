package io.liftandshift.strikebench.sim;

/**
 * THE shared deterministic randomness foundation: counter-based gaussians that are a pure function
 * of (seed, stream, key, index). No hidden state, no consumption order — two callers can never
 * shift each other's draws, and any draw is reproducible in isolation. The live simulated market
 * ({@code SimulatedWorld}) and the research resampling machinery ({@code BootstrapSampler},
 * conditional-bootstrap path producers, and parametric {@code PathGenerator}) all draw from here.
 */
public final class RandomStreams {

    private RandomStreams() {}

    /** splitmix64 finalizer — the shared bit mixer. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Standard normal draw, pure in (seed, stream, key, index) — Box–Muller on mixed uniforms. */
    public static double gaussian(long seed, long stream, long key, long index) {
        long h = seed;
        h = mix(h ^ stream); h = mix(h ^ key); h = mix(h ^ index);
        long h2 = mix(h ^ 0xD1B54A32D192ED03L);
        double u1 = (h >>> 11) * 0x1.0p-53 + 1e-12;
        double u2 = (h2 >>> 11) * 0x1.0p-53;
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /** Uniform draw in [0,1), pure in the same coordinates as {@link #gaussian}. */
    public static double uniform(long seed, long stream, long key, long index) {
        long h = seed;
        h = mix(h ^ stream); h = mix(h ^ key); h = mix(h ^ index);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Uniform int in [0, bound), pure in (seed, stream, key, index). */
    public static int uniformInt(long seed, long stream, long key, long index, int bound) {
        long h = seed;
        h = mix(h ^ stream); h = mix(h ^ key); h = mix(h ^ index);
        return (int) Math.floorMod(h, Math.max(1, bound));
    }

    /**
     * A local cursor over one independent counter stream. Consumption order is confined to one
     * path/key; adding another path, symbol, or comparison cannot shift this stream.
     */
    public static Cursor cursor(long seed, long stream, long key) {
        return new Cursor(seed, stream, key);
    }

    public static final class Cursor {
        private final long seed;
        private final long stream;
        private final long key;
        private long index;

        private Cursor(long seed, long stream, long key) {
            this.seed = seed;
            this.stream = stream;
            this.key = key;
        }

        public double uniform() { return RandomStreams.uniform(seed, stream, key, index++); }
        public double gaussian() { return RandomStreams.gaussian(seed, stream, key, index++); }
        public int nextInt(int bound) { return RandomStreams.uniformInt(seed, stream, key, index++, bound); }

        /** Standardized Student-t draw (unit variance) for nu &gt; 2. */
        public double studentT(double nu) {
            if (nu <= 2.5) nu = 2.5;
            double z = gaussian();
            // Chi-square(nu) is Gamma(nu/2, scale=2). Rounding nu to an integer silently changed
            // the selected distribution and made the variance normalization wrong for expert inputs.
            double chi2 = 2.0 * gamma(nu / 2.0);
            double t = z / Math.sqrt(chi2 / nu);
            return t / Math.sqrt(nu / (nu - 2.0));
        }

        /** Marsaglia-Tsang gamma(shape, scale=1), driven only by this deterministic cursor. */
        private double gamma(double shape) {
            if (!(shape > 0) || !Double.isFinite(shape)) throw new IllegalArgumentException("gamma shape must be positive");
            if (shape < 1.0) {
                return gamma(shape + 1.0) * Math.pow(Math.max(1e-15, uniform()), 1.0 / shape);
            }
            double d = shape - 1.0 / 3.0;
            double c = 1.0 / Math.sqrt(9.0 * d);
            while (true) {
                double x = gaussian();
                double v = 1.0 + c * x;
                if (v <= 0) continue;
                v = v * v * v;
                double u = uniform();
                if (u < 1.0 - 0.0331 * x * x * x * x
                        || Math.log(Math.max(1e-15, u)) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                    return d * v;
                }
            }
        }

        /** Knuth Poisson draw; scenario step intensities are deliberately small. */
        public int poisson(double lambda) {
            if (lambda <= 0) return 0;
            double limit = Math.exp(-lambda);
            int k = 0;
            double product = 1;
            do { k++; product *= uniform(); } while (product > limit);
            return k - 1;
        }
    }
}
