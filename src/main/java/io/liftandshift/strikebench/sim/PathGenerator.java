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

    public static final String MODEL_VERSION = "paths-2";
    private static final long NOISE_STREAM = 0x504154484E4F4953L;
    private static final long EVENT_STREAM = 0x5041544845564E54L;
    private static final long OHLC_STREAM = 0x4F484C4342524944L;

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

        double[] guide = shapeGuide(s.shape(), steps, muTot, amp, sigmaT);
        double[][] out = new double[s.paths()][steps + 1];

        // Timed shocks are OVERNIGHT events, not diffusion steps: at the shock step the
        // deterministic move REPLACES that step's noise (noise increment suppressed), so the
        // promised gap is exactly the promised size on EVERY path. EVENT_JUMP's sign varies
        // per path ("a violent move either way" — still seeded ⇒ reproducible).
        int eventStep = s.shape() == ScenarioSpec.Shape.EVENT_JUMP ? Math.max(1, steps / 2) : -1;
        int gapStep = (s.shape() == ScenarioSpec.Shape.GAP_UP || s.shape() == ScenarioSpec.Shape.GAP_DOWN)
                ? Math.max(1, Math.round(steps * 0.2f)) : -1;
        double eventSize = Math.max(0.05, sigmaT);

        for (int p = 0; p < s.paths(); p++) {
            RandomStreams.Cursor rng = RandomStreams.cursor(s.seed(),
                    NOISE_STREAM + s.model().ordinal(), p);
            double[] noise = noisePath(s, sigma, dt, steps, rng, historicalLogReturns);
            if (gapStep > 0) suppressStepNoise(noise, gapStep);
            if (eventStep > 0) suppressStepNoise(noise, eventStep);
            RandomStreams.Cursor eventRng = RandomStreams.cursor(s.seed(), EVENT_STREAM, p);
            double eventShock = eventStep > 0 ? (eventRng.uniform() < 0.5 ? -eventSize : eventSize) : 0;
            for (int i = 0; i <= steps; i++) {
                double shock = eventStep > 0 && i >= eventStep ? eventShock : 0;
                out[p][i] = s0 * Math.exp(guide[i] + noise[i] + shock);
                if (out[p][i] <= 0 || Double.isNaN(out[p][i])) out[p][i] = s0 * 1e-4;
            }
            out[p][0] = s0;
        }
        return out;
    }

    /** Removes one step's noise increment (continuity preserved) — the shock replaces it. */
    private static void suppressStepNoise(double[] n, int step) {
        if (step < 1 || step >= n.length) return;
        double delta = n[step] - n[step - 1];
        for (int i = step; i < n.length; i++) n[i] -= delta;
    }

    // ---- Shape guide (deterministic log-drift path) ----

    private static double[] shapeGuide(ScenarioSpec.Shape shape, int steps, double muTot, double amp, double sigmaT) {
        double[] g = new double[steps + 1];
        // Gap shapes GUARANTEE their gap: a one-step deterministic jump early in the horizon
        // (a Poisson jump process alone leaves most short-horizon paths gap-free — the card
        // promised a gap, so every path must contain one).
        int gapStep = Math.max(1, Math.round(steps * 0.2f));
        double gapSize = Math.max(0.04, sigmaT);
        for (int i = 0; i <= steps; i++) {
            double f = (double) i / steps;
            double lin = muTot * f;
            g[i] = switch (shape) {
                case SELLOFF_REBOUND -> lin - amp * Math.sin(Math.PI * f); // dip then recover (valley)
                case RALLY_FADE -> lin + amp * Math.sin(Math.PI * f);      // bump then fade (mountain)
                case GAP_UP -> lin + (i >= gapStep ? gapSize : 0);
                case GAP_DOWN -> lin - (i >= gapStep ? gapSize : 0);
                default -> lin;                                            // grind/chop/event ride linear
            };
        }
        return g;
    }

    // ---- Noise models (martingale-corrected cumulative log: E[exp(noise)] = 1, so the guide
    // ---- honestly IS the expected price path — no hidden σ²/2 upward bias) ----

    private static final double GAUSSIAN = 1_000; // nu above the t/gaussian switch = normal innovations

    private double[] noisePath(ScenarioSpec s, double sigma, double dt, int steps,
                               RandomStreams.Cursor rng, double[] hist) {
        return switch (s.model()) {
            case GBM -> gbmNoise(sigma, dt, steps, rng, GAUSSIAN);           // GBM is GAUSSIAN by definition
            case STUDENT_T -> gbmNoise(sigma, dt, steps, rng, s.tailNu());   // fat tails only when asked for
            case JUMP_DIFFUSION -> jumpNoise(s, sigma, dt, steps, rng);
            case HESTON -> hestonNoise(s, dt, steps, rng);
            case BLOCK_BOOTSTRAP -> bootstrapNoise(sigma, dt, steps, rng, hist);
            case BROWNIAN_BRIDGE -> pinBridge(gbmNoise(sigma, dt, steps, rng, GAUSSIAN), steps);
        };
    }

    private static double[] gbmNoise(double sigma, double dt, int steps, RandomStreams.Cursor rng, double nu) {
        double[] n = new double[steps + 1];
        double sd = sigma * Math.sqrt(dt);
        double drag = 0.5 * sigma * sigma * dt; // Itô correction: keeps E[S] on the guide
        for (int i = 1; i <= steps; i++) {
            double z = nu >= 100 ? rng.gaussian() : rng.studentT(nu);
            n[i] = n[i - 1] - drag + sd * z;
        }
        return n;
    }

    private static double[] jumpNoise(ScenarioSpec s, double sigma, double dt, int steps, RandomStreams.Cursor rng) {
        double[] n = new double[steps + 1];
        double sd = sigma * Math.sqrt(dt);
        double lambdaDt = s.jumpsPerYear() * dt;
        // Merton compensator: diffusion drag + λ·(E[e^J]−1) so jumps don't smuggle in drift.
        double drag = 0.5 * sigma * sigma * dt
                + lambdaDt * (Math.exp(s.jumpMean() + 0.5 * s.jumpVol() * s.jumpVol()) - 1);
        for (int i = 1; i <= steps; i++) {
            double inc = -drag + sd * rng.gaussian();
            int nj = rng.poisson(lambdaDt);
            for (int j = 0; j < nj; j++) inc += s.jumpMean() + s.jumpVol() * rng.gaussian();
            n[i] = n[i - 1] + inc;
        }
        return n;
    }

    private static double[] hestonNoise(ScenarioSpec s, double dt, int steps, RandomStreams.Cursor rng) {
        ScenarioSpec.Heston h = s.heston();
        double[] n = new double[steps + 1];
        double v = Math.max(1e-8, h.v0());
        double sqrtDt = Math.sqrt(dt);
        for (int i = 1; i <= steps; i++) {
            double zs = rng.gaussian();
            double zi = rng.gaussian();
            double zv = h.rho() * zs + Math.sqrt(Math.max(0, 1 - h.rho() * h.rho())) * zi;
            double vPos = Math.max(0, v);                                   // full-truncation Euler
            n[i] = n[i - 1] - 0.5 * vPos * dt + Math.sqrt(vPos * dt) * zs;  // Itô-corrected log-noise
            v = v + h.kappa() * (h.theta() - vPos) * dt + h.xi() * Math.sqrt(vPos) * sqrtDt * zv;
        }
        return n;
    }

    /**
     * Resample blocks of REAL mean-removed log-returns — preserves fat tails + autocorrelation.
     * The resampled returns are RESCALED to the requested σ (the vol knob works even with history)
     * and to the step size (daily returns must not be applied verbatim per intraday step).
     */
    private static double[] bootstrapNoise(double sigma, double dt, int steps,
                                           RandomStreams.Cursor rng, double[] hist) {
        if (hist == null || hist.length < 5) {
            // no history to bootstrap from — fall back to plain gaussian noise at the requested vol
            return gbmNoise(sigma, dt, steps, rng, GAUSSIAN);
        }
        double mean = 0;
        for (double r : hist) mean += r;
        mean /= hist.length;
        double var = 0;
        for (double r : hist) var += (r - mean) * (r - mean);
        double histSd = Math.sqrt(Math.max(1e-12, var / Math.max(1, hist.length - 1)));
        double targetSd = sigma * Math.sqrt(dt);          // per-STEP target
        double scale = targetSd / histSd;
        double drag = 0.5 * targetSd * targetSd;
        int block = Math.max(2, Math.min(20, hist.length / 4));
        double[] n = new double[steps + 1];
        int i = 1;
        while (i <= steps) {
            int start = rng.nextInt(hist.length);
            for (int k = 0; k < block && i <= steps; k++, i++) {
                n[i] = n[i - 1] - drag + (hist[(start + k) % hist.length] - mean) * scale;
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
    public double[] intradayBridge(double open, double high, double low, double close, int steps, long seed) {
        steps = Math.max(2, steps);
        RandomStreams.Cursor rng = RandomStreams.cursor(seed, OHLC_STREAM, steps);
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
