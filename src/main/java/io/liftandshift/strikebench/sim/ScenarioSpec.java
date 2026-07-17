package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.market.MarketHours;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The knobs behind a synthetic scenario. Beginner scenario cards ("Sell off, then rebound",
 * "Volatility crush") are just named presets of this; Expert mode exposes every field. Units are
 * annualized where noted so σ/drift are the language a quant expects (with an honest per-step
 * conversion inside the generator). Deterministic by {@code seed}.
 *
 * <p>{@code waypoints} are the scenario canvas's authored pins: "the price is at this level on
 * this trading day." Levels are RATIOS of spot so a spec stays symbol-relative (1.05 = +5%).
 * How the generator honors them is model-dependent and honestly labeled — see
 * {@link PathGenerator.WaypointFill}.
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
        int paths,
        List<Waypoint> waypoints) {  // authored pins, ordered by dayIndex (empty = plain Monte Carlo)

    public ScenarioSpec {
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        int prevDay = 0;
        for (Waypoint w : waypoints) {
            if (w == null) throw new IllegalArgumentException("waypoints must not contain nulls");
            if (w.dayIndex() <= prevDay) {
                throw new IllegalArgumentException("waypoints must be strictly ordered by trading day"
                        + " (day " + w.dayIndex() + " after day " + prevDay + ")");
            }
            if (w.dayIndex() > horizonDays) {
                throw new IllegalArgumentException("waypoint day " + w.dayIndex()
                        + " lies beyond the scenario horizon of " + horizonDays + " trading days");
            }
            prevDay = w.dayIndex();
        }
    }

    /** The pre-canvas constructor shape: a plain spec with no authored waypoints. */
    public ScenarioSpec(PathModel model, Shape shape, int horizonDays, int stepsPerDay,
                        double driftAnnual, double volAnnual, double jumpsPerYear, double jumpMean,
                        double jumpVol, double tailNu, Heston heston, long seed, int paths) {
        this(model, shape, horizonDays, stepsPerDay, driftAnnual, volAnnual, jumpsPerYear,
                jumpMean, jumpVol, tailNu, heston, seed, paths, List.of());
    }

    /**
     * One authored pin: on trading day {@code dayIndex} (1-based, end of that day) the price is
     * {@code priceRatio} × spot. {@code tolerance} (same ratio units, optional) is the author's
     * acceptance band around the level — carried for the studio's band rendering and scoring;
     * path generation pins the exact level.
     */
    public record Waypoint(int dayIndex, double priceRatio, Double tolerance) {
        public Waypoint {
            if (dayIndex < 1) throw new IllegalArgumentException("waypoint dayIndex must be >= 1 (day 0 is spot)");
            if (!(priceRatio > 0) || !Double.isFinite(priceRatio)) {
                throw new IllegalArgumentException("waypoint priceRatio must be a positive finite ratio of spot");
            }
            if (tolerance != null && (!(tolerance >= 0) || !Double.isFinite(tolerance))) {
                throw new IllegalArgumentException("waypoint tolerance must be a non-negative finite ratio");
            }
        }

        public Waypoint(int dayIndex, double priceRatio) { this(dayIndex, priceRatio, null); }
    }

    public enum PathModel { GBM, BROWNIAN_BRIDGE, BLOCK_BOOTSTRAP, STUDENT_T, JUMP_DIFFUSION, HESTON }

    /** Shape = a deterministic guide the stochastic model rides on. */
    public enum Shape { GRIND_UP, GRIND_DOWN, SELLOFF_REBOUND, RALLY_FADE, CHOP, GAP_UP, GAP_DOWN, EVENT_JUMP }

    /** Heston stochastic variance: dv = κ(θ−v)dt + ξ√v dW_v, corr(dW_s,dW_v)=ρ, v(0)=v0 (variance). */
    public record Heston(double kappa, double theta, double xi, double rho, double v0) {
        public static Heston fromVol(double volAnnual) {
            double v = volAnnual * volAnnual;
            return new Heston(3.0, v, Math.max(0.05, volAnnual * 0.5), -0.6, v);
        }
    }

    public int totalSteps() { return Math.max(1, horizonDays * Math.max(1, stepsPerDay)); }

    /**
     * Years per step (252 trading days/yr). LEGACY on purpose: stored-fan fingerprints and the
     * IV-path replay check ({@code PlanOutcomeService.loadEnsemble}) re-derive trajectories from
     * this exact constant, so its meaning is frozen. Calendar-honest authoring flows use
     * {@link #calendarDt(LocalDate)} / {@link #calendarHorizonDays(LocalDate, LocalDate)} instead.
     */
    public double dt() { return (1.0 / 252.0) / Math.max(1, stepsPerDay); }

    // ---- Trading-calendar derivation (the scenario canvas's honest clock) ----

    /**
     * The calendar-honest horizon for an authored scenario: actual NYSE sessions in
     * (anchor, expiry], never the raw calendar-day difference. At least 1.
     */
    public static int calendarHorizonDays(LocalDate anchor, LocalDate expiry) {
        if (anchor == null || expiry == null) throw new IllegalArgumentException("anchor and expiry dates are required");
        return Math.max(1, MarketHours.tradingDaysBetween(anchor, expiry));
    }

    /**
     * The real trading dates this spec's steps land on: session 1..horizonDays after {@code anchor},
     * skipping weekends and NYSE holidays — the scenario canvas's day table.
     */
    public static List<LocalDate> sessionDates(LocalDate anchor, int horizonDays) {
        if (anchor == null) throw new IllegalArgumentException("anchor date is required");
        java.util.ArrayList<LocalDate> out = new java.util.ArrayList<>(Math.max(1, horizonDays));
        LocalDate d = anchor;
        for (int i = 0; i < Math.max(1, horizonDays); i++) {
            d = MarketHours.tradingDateAfter(d, 1);
            out.add(d);
        }
        return List.copyOf(out);
    }

    /**
     * Calendar-aware years per step for the authoring flow: the horizon's REAL elapsed calendar
     * time (anchor to the horizon's last session, consulting the NYSE holiday table) divided by
     * the step count — a week containing a holiday spans more clock time than 1/252-per-day
     * pretends. New method rather than a change to {@link #dt()} so stored-fan fingerprints and
     * replays stay valid.
     */
    public double calendarDt(LocalDate anchor) {
        if (anchor == null) throw new IllegalArgumentException("anchor date is required");
        int days = Math.clamp(horizonDays, 1, 756);
        LocalDate last = MarketHours.tradingDateAfter(anchor, days);
        double years = ChronoUnit.DAYS.between(anchor, last) / 365.0;
        return years / Math.max(1, days * Math.max(1, stepsPerDay));
    }

    /**
     * Calendar time carried by each simulated sub-step.  A Friday-close to Monday-close move gets
     * three calendar days of variance while adjacent sessions get one; exchange holidays behave
     * the same way.  Intraday sub-steps divide that session interval evenly.  This is the clock
     * used by new path matrices and canvas valuation; {@link #dt()} remains only for replaying
     * already-fingerprinted legacy artifacts.
     */
    public double[] calendarStepYears(LocalDate anchor) {
        if (anchor == null) throw new IllegalArgumentException("anchor date is required");
        int spd = Math.max(1, stepsPerDay);
        int days = Math.clamp(horizonDays, 1, 756);
        double[] out = new double[days * spd];
        LocalDate prior = anchor;
        int at = 0;
        for (LocalDate session : sessionDates(anchor, days)) {
            double sessionYears = Math.max(1, ChronoUnit.DAYS.between(prior, session)) / 365.0;
            for (int i = 0; i < spd; i++) out[at++] = sessionYears / spd;
            prior = session;
        }
        return out;
    }

    // ---- Beginner presets (each card is one of these) ----

    public static ScenarioSpec preset(Shape shape, int horizonDays, double volAnnual, long seed, int paths) {
        double v = volAnnual <= 0 ? 0.25 : volAnnual;
        return switch (shape) {
            case GRIND_UP -> base(PathModel.GBM, shape, horizonDays, 0.15, v * 0.8, seed, paths);
            case GRIND_DOWN -> base(PathModel.GBM, shape, horizonDays, -0.15, v * 0.8, seed, paths);
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
        // Waypoints survive sanity untouched except when the horizon itself was clamped below a
        // pin's day: an unreachable pin is dropped (sane() coerces, it never throws).
        List<Waypoint> keptWaypoints = waypoints;
        if (!waypoints.isEmpty() && waypoints.getLast().dayIndex() > days) {
            keptWaypoints = waypoints.stream().filter(w -> w.dayIndex() <= days).toList();
        }
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
                seed, Math.clamp(paths <= 0 ? 200 : paths, 1, Math.min(5000, maxPaths)),
                keptWaypoints);
    }

    /** Same scenario, different vol — the calibration hook (volAnnual<=0 means "use market vol"). */
    public ScenarioSpec withVol(double vol) {
        return new ScenarioSpec(model, shape, horizonDays, stepsPerDay, driftAnnual, vol,
                jumpsPerYear, jumpMean, jumpVol, tailNu, heston, seed, paths, waypoints);
    }

    /** Same scenario, different path count (persisting a dataset needs exactly ONE path). */
    public ScenarioSpec withPaths(int n) {
        return new ScenarioSpec(model, shape, horizonDays, stepsPerDay, driftAnnual, volAnnual,
                jumpsPerYear, jumpMean, jumpVol, tailNu, heston, seed, n, waypoints);
    }

    /** Same scenario, authored pins — the scenario canvas's entry point. */
    public ScenarioSpec withWaypoints(List<Waypoint> pins) {
        return new ScenarioSpec(model, shape, horizonDays, stepsPerDay, driftAnnual, volAnnual,
                jumpsPerYear, jumpMean, jumpVol, tailNu, heston, seed, paths, pins);
    }

    private static double clampD(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
