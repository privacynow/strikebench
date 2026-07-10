package io.liftandshift.strikebench.sim;

/**
 * The knobs behind a synthetic scenario. Beginner scenario cards ("Sell off, then rebound",
 * "Volatility crush") are just named presets of this; Expert mode exposes every field. Units are
 * annualized where noted so σ/drift are the language a quant expects (with an honest per-step
 * conversion inside the generator). Deterministic by {@code seed}.
 */
public record ScenarioSpec(
        PathModel model,
        Shape shape,
        int horizonDays,        // trading days in the scenario
        int stepsPerDay,        // 1 = daily; >1 = intraday granularity
        double driftAnnual,     // annualized drift μ (e.g. 0.08)
        double volAnnual,       // annualized realized vol σ (e.g. 0.30)
        double jumpsPerYear,    // Merton jump intensity λ (expected jumps/yr)
        double jumpMean,        // mean jump log-size
        double jumpVol,         // jump log-size stdev
        double tailNu,          // Student-t degrees of freedom (fat tails)
        Heston heston,          // stochastic-vol params (variance units are annualized)
        long seed,
        int paths) {

    public enum PathModel { GBM, BROWNIAN_BRIDGE, BLOCK_BOOTSTRAP, STUDENT_T, JUMP_DIFFUSION, HESTON }

    /** Shape = a deterministic guide the stochastic model rides on. */
    public enum Shape { GRIND_UP, SELLOFF_REBOUND, RALLY_FADE, CHOP, GAP_UP, GAP_DOWN, EVENT_JUMP }

    /** Heston stochastic variance: dv = κ(θ−v)dt + ξ√v dW_v, corr(dW_s,dW_v)=ρ, v(0)=v0 (variance). */
    public record Heston(double kappa, double theta, double xi, double rho, double v0) {
        public static Heston fromVol(double volAnnual) {
            double v = volAnnual * volAnnual;
            return new Heston(3.0, v, Math.max(0.05, volAnnual * 0.5), -0.6, v);
        }
    }

    public int totalSteps() { return Math.max(1, horizonDays * Math.max(1, stepsPerDay)); }

    /** Years per step (252 trading days/yr). */
    public double dt() { return (1.0 / 252.0) / Math.max(1, stepsPerDay); }

    // ---- Beginner presets (each card is one of these) ----

    public static ScenarioSpec preset(Shape shape, int horizonDays, double volAnnual, long seed, int paths) {
        double v = volAnnual <= 0 ? 0.25 : volAnnual;
        return switch (shape) {
            case GRIND_UP -> base(PathModel.GBM, shape, horizonDays, 0.15, v * 0.8, seed, paths);
            case SELLOFF_REBOUND -> base(PathModel.GBM, shape, horizonDays, 0.05, v * 1.4, seed, paths);
            case RALLY_FADE -> base(PathModel.GBM, shape, horizonDays, -0.05, v * 1.2, seed, paths);
            case CHOP -> base(PathModel.GBM, shape, horizonDays, 0.0, v, seed, paths);
            case GAP_UP -> jumpy(shape, horizonDays, v, 0.06, seed, paths);
            case GAP_DOWN -> jumpy(shape, horizonDays, v, -0.06, seed, paths);
            case EVENT_JUMP -> jumpy(shape, horizonDays, v, 0.0, seed, paths);
        };
    }

    private static ScenarioSpec base(PathModel m, Shape s, int days, double drift, double vol, long seed, int paths) {
        return new ScenarioSpec(m, s, days, 1, drift, vol, 0, 0, 0, 6, Heston.fromVol(vol), seed, paths);
    }

    private static ScenarioSpec jumpy(Shape s, int days, double vol, double jumpMean, long seed, int paths) {
        return new ScenarioSpec(PathModel.JUMP_DIFFUSION, s, days, 1, 0.05, vol,
                6, jumpMean, Math.max(0.02, Math.abs(jumpMean) * 0.5), 6, Heston.fromVol(vol), seed, paths);
    }

    // ---- guards ----

    /**
     * Hard cap on TOTAL work as a product (paths × steps), not just per-field clamps — the
     * per-field maxima multiplied together (756d × 96/day × 5000 paths ≈ 363M points, ~6GB of
     * matrices) would let one request exhaust the heap. 3M points ≈ 50MB peak per request.
     */
    public static final int MAX_TOTAL_POINTS = 3_000_000;

    public ScenarioSpec sane() {
        int days = Math.clamp(horizonDays, 1, 756);
        int spd = Math.clamp(stepsPerDay, 1, 96);
        int stepsPlus1 = days * spd + 1;
        int maxPaths = Math.max(20, MAX_TOTAL_POINTS / stepsPlus1);
        return new ScenarioSpec(model == null ? PathModel.GBM : model,
                shape == null ? Shape.CHOP : shape,
                days,
                spd,
                clampD(driftAnnual, -2, 2),
                clampD(volAnnual <= 0 ? 0.25 : volAnnual, 0.01, 5),
                clampD(jumpsPerYear, 0, 260),
                clampD(jumpMean, -1, 1),
                clampD(jumpVol, 0, 1),
                clampD(tailNu <= 0 ? 6 : tailNu, 2.5, 200),
                heston == null ? Heston.fromVol(volAnnual <= 0 ? 0.25 : volAnnual) : heston,
                seed, Math.clamp(paths <= 0 ? 200 : paths, 1, Math.min(5000, maxPaths)));
    }

    /** Same scenario, different path count (persisting a dataset needs exactly ONE path). */
    public ScenarioSpec withPaths(int n) {
        return new ScenarioSpec(model, shape, horizonDays, stepsPerDay, driftAnnual, volAnnual,
                jumpsPerYear, jumpMean, jumpVol, tailNu, heston, seed, n);
    }

    private static double clampD(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
