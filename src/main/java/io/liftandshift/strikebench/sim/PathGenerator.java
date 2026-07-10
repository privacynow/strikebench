package io.liftandshift.strikebench.sim;

/**
 * Generates synthetic underlying price paths for a {@link ScenarioSpec}. A path is a deterministic
 * <em>shape guide</em> (the trader's view: grind up, valley, mountain, chop, gap) plus a mean-zero
 * stochastic <em>noise</em> from the chosen model (GBM, Student-t, jump-diffusion, Heston stochastic
 * vol, block-bootstrap of real returns, or an endpoint-pinned Brownian bridge). Seeded ⇒ reproducible.
 *
 * <p>Honesty is built in: daily closes are anchors, not intraday truth. {@link #intradayBridge} fills
 * a within-day path constrained to the real open/high/low/close — the VALUES of H/L are respected and
 * both are touched, but their intraday TIMING is synthetic. Callers surface that in the evidence.
 */
public final class PathGenerator {

    /** Paths as prices: result[pathIndex][0..totalSteps], result[*][0] == s0. */
    public double[][] generate(ScenarioSpec spec, double s0, double[] historicalLogReturns) {
        ScenarioSpec s = spec.sane();
        int steps = s.totalSteps();
        double dt = s.dt();
        double sigma = s.volAnnual();
        double t = steps * dt;                       // horizon in years
        double muTot = s.driftAnnual() * t;          // total log-drift over the horizon
        double sigmaT = sigma * Math.sqrt(Math.max(t, 1e-9));
        double amp = Math.max(Math.abs(muTot) * 0.5, sigmaT * 0.9); // valley/mountain excursion

        double[] guide = shapeGuide(s.shape(), steps, muTot, amp);
        double[][] out = new double[s.paths()][steps + 1];
        Rng rng = new Rng(s.seed());

        for (int p = 0; p < s.paths(); p++) {
            double[] noise = noisePath(s, sigma, dt, steps, rng, historicalLogReturns);
            for (int i = 0; i <= steps; i++) {
                out[p][i] = s0 * Math.exp(guide[i] + noise[i]);
                if (out[p][i] <= 0 || Double.isNaN(out[p][i])) out[p][i] = s0 * 1e-4;
            }
            out[p][0] = s0;
        }
        return out;
    }

    // ---- Shape guide (deterministic log-drift path) ----

    private static double[] shapeGuide(ScenarioSpec.Shape shape, int steps, double muTot, double amp) {
        double[] g = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double f = (double) i / steps;
            double lin = muTot * f;
            g[i] = switch (shape) {
                case SELLOFF_REBOUND -> lin - amp * Math.sin(Math.PI * f); // dip then recover (valley)
                case RALLY_FADE -> lin + amp * Math.sin(Math.PI * f);      // bump then fade (mountain)
                default -> lin;                                            // grind/chop/gaps ride linear
            };
        }
        return g;
    }

    // ---- Noise models (mean-zero cumulative log) ----

    private double[] noisePath(ScenarioSpec s, double sigma, double dt, int steps, Rng rng, double[] hist) {
        return switch (s.model()) {
            case GBM -> gbmNoise(sigma, dt, steps, rng, 6);
            case STUDENT_T -> gbmNoise(sigma, dt, steps, rng, s.tailNu());
            case JUMP_DIFFUSION -> jumpNoise(s, sigma, dt, steps, rng);
            case HESTON -> hestonNoise(s, dt, steps, rng);
            case BLOCK_BOOTSTRAP -> bootstrapNoise(sigma, steps, rng, hist);
            case BROWNIAN_BRIDGE -> pinBridge(gbmNoise(sigma, dt, steps, rng, 6), steps);
        };
    }

    private static double[] gbmNoise(double sigma, double dt, int steps, Rng rng, double nu) {
        double[] n = new double[steps + 1];
        double sd = sigma * Math.sqrt(dt);
        for (int i = 1; i <= steps; i++) {
            double z = nu >= 100 ? rng.gaussian() : rng.studentT(nu);
            n[i] = n[i - 1] + sd * z;
        }
        return n;
    }

    private static double[] jumpNoise(ScenarioSpec s, double sigma, double dt, int steps, Rng rng) {
        double[] n = new double[steps + 1];
        double sd = sigma * Math.sqrt(dt);
        double lambdaDt = s.jumpsPerYear() * dt;
        for (int i = 1; i <= steps; i++) {
            double inc = sd * rng.gaussian();
            int nj = rng.poisson(lambdaDt);
            for (int j = 0; j < nj; j++) inc += s.jumpMean() + s.jumpVol() * rng.gaussian();
            n[i] = n[i - 1] + inc;
        }
        return n;
    }

    private static double[] hestonNoise(ScenarioSpec s, double dt, int steps, Rng rng) {
        ScenarioSpec.Heston h = s.heston();
        double[] n = new double[steps + 1];
        double v = Math.max(1e-8, h.v0());
        double sqrtDt = Math.sqrt(dt);
        for (int i = 1; i <= steps; i++) {
            double zs = rng.gaussian();
            double zi = rng.gaussian();
            double zv = h.rho() * zs + Math.sqrt(Math.max(0, 1 - h.rho() * h.rho())) * zi;
            double vPos = Math.max(0, v);                       // full-truncation Euler
            n[i] = n[i - 1] + Math.sqrt(vPos * dt) * zs;         // driftless log-noise (guide carries drift)
            v = v + h.kappa() * (h.theta() - vPos) * dt + h.xi() * Math.sqrt(vPos) * sqrtDt * zv;
        }
        return n;
    }

    /** Resample blocks of REAL mean-removed log-returns — preserves fat tails + autocorrelation. */
    private static double[] bootstrapNoise(double sigma, int steps, Rng rng, double[] hist) {
        if (hist == null || hist.length < 5) {
            // no history to bootstrap from — fall back to a plain GBM noise at the requested vol
            return gbmNoise(sigma, 1.0 / 252.0, steps, rng, 6);
        }
        double mean = 0;
        for (double r : hist) mean += r;
        mean /= hist.length;
        int block = Math.max(2, Math.min(20, hist.length / 4));
        double[] n = new double[steps + 1];
        int i = 1;
        while (i <= steps) {
            int start = rng.nextInt(hist.length);
            for (int k = 0; k < block && i <= steps; k++, i++) {
                n[i] = n[i - 1] + (hist[(start + k) % hist.length] - mean);
            }
        }
        return n;
    }

    /** Pin a noise path to 0 at both ends (Brownian bridge) so the path hits its guide endpoints. */
    private static double[] pinBridge(double[] n, int steps) {
        double end = n[steps];
        for (int i = 0; i <= steps; i++) n[i] -= (double) i / steps * end;
        return n;
    }

    // ---- OHLC-constrained intraday fill ----

    /**
     * An intraday path constrained to the real daily O/H/L/C. Endpoints are exactly open→close; the
     * running max touches high and the running min touches low (values respected, timing synthetic).
     */
    public double[] intradayBridge(double open, double high, double low, double close, int steps, Rng rng) {
        steps = Math.max(2, steps);
        low = Math.min(low, Math.min(open, close));
        high = Math.max(high, Math.max(open, close));
        // A Brownian bridge in price space from open to close.
        double vol = Math.max(high - low, Math.abs(close - open)) * 0.5 + 1e-6;
        double[] w = new double[steps + 1];
        for (int i = 1; i <= steps; i++) w[i] = w[i - 1] + rng.gaussian();
        double[] line = new double[steps + 1];
        double[] dev = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            line[i] = open + (close - open) * i / steps;
            dev[i] = (w[i] - (double) i / steps * w[steps]) * (vol / Math.sqrt(steps)); // pinned bridge dev
        }
        double devMax = 0, devMin = 0;
        for (double d : dev) { devMax = Math.max(devMax, d); devMin = Math.min(devMin, d); }
        double lineMax = Math.max(open, close), lineMin = Math.min(open, close);
        double su = devMax > 1e-9 ? (high - lineMax) / devMax : 0;
        double sd = devMin < -1e-9 ? (lineMin - low) / (-devMin) : 0;

        double[] out = new double[steps + 1];
        int iMax = 0, iMin = 0;
        for (int i = 0; i <= steps; i++) {
            double d = dev[i] > 0 ? dev[i] * su : dev[i] * sd;
            out[i] = Math.max(low, Math.min(high, line[i] + d));
            if (out[i] > out[iMax]) iMax = i;
            if (out[i] < out[iMin]) iMin = i;
        }
        out[0] = open; out[steps] = close;
        if (iMax != 0 && iMax != steps) out[iMax] = high; // guarantee the high is touched
        if (iMin != 0 && iMin != steps) out[iMin] = low;  // guarantee the low is touched
        return out;
    }
}
