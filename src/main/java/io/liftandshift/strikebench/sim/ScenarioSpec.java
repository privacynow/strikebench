package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.util.DataUnavailableException;
import io.liftandshift.strikebench.util.Numbers;

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

    public static final String MISSING_VOLATILITY =
            "Scenario volatility is unresolved. Supply an explicit annual volatility or "
                    + "calibrate it from eligible same-market option evidence before generating paths.";

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
     * Normalized wire coordinate for one simulation step.
     *
     * <p>Every path, band, position checkpoint, and lifecycle boundary is joined by this value
     * in the browser.  Keeping the rounding policy here prevents a three-steps-per-session fan
     * from publishing both {@code 0.333333...} and {@code 0.3333} for the same immutable frame.
     */
    public static double sessionProgress(int step, int stepsPerDay) {
        return Numbers.round4((double) step / Math.max(1, stepsPerDay));
    }

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
     * Calendar time carried by each simulated sub-step.  A Friday-close to Monday-close move gets
     * three calendar days of variance while adjacent sessions get one; exchange holidays behave
     * the same way.  Intraday sub-steps divide that session interval evenly.  This is the clock
     * used by every generated matrix, valuation, stored artifact, and replay.
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
        // Zero is the documented request for market calibration. Preserve it until the
        // market-volatility owner resolves it; a preset must never turn absence into 25%.
        double v = Math.max(0, volAnnual);
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
        return new ScenarioSpec(m, s, days, 1, drift, vol, 0, 0, 0, 6,
                vol > 0 ? Heston.fromVol(vol) : null, seed, paths, List.of());
    }

    private static ScenarioSpec jumpy(Shape s, int days, double vol, double jumpMean, long seed, int paths) {
        return new ScenarioSpec(PathModel.JUMP_DIFFUSION, s, days, 1, 0.05, vol,
                6, jumpMean, Math.max(0.02, Math.abs(jumpMean) * 0.5), 6,
                vol > 0 ? Heston.fromVol(vol) : null, seed, paths, List.of());
    }

    // ---- guards ----

    /**
     * Hard cap on TOTAL work as a product (paths × steps), not just per-field clamps — the
     * per-field maxima multiplied together (756d × 96/day × 5000 paths ≈ 363M points, ~6GB of
     * matrices) would let one request exhaust the heap. 3M points ≈ 50MB peak per request.
     */
    public static final int MAX_TOTAL_POINTS = 3_000_000;

    /**
     * Strict boundary validation for a user-authored scenario. Unlike {@link #sane()}, this method
     * never substitutes or clamps a submitted value: invalid authored inputs are named and
     * rejected before they can become a different financial scenario.
     */
    public ScenarioSpec validated() {
        if (model == null) throw new IllegalArgumentException("scenario model is required");
        if (shape == null) throw new IllegalArgumentException("scenario shape is required");
        requireRange("horizonDays", horizonDays, 1, 756);
        requireRange("stepsPerDay", stepsPerDay, 1, 96);
        requireFiniteRange("driftAnnual", driftAnnual, -2, 2);
        // Exactly zero is the documented request for market calibration; negative volatility is
        // never meaningful and must not become the old canned 25% fallback.
        requireFiniteRange("volAnnual", volAnnual, 0, 5);
        requireFiniteRange("jumpsPerYear", jumpsPerYear, 0, 260);
        requireFiniteRange("jumpMean", jumpMean, -1, 1);
        requireFiniteRange("jumpVol", jumpVol, 0, 1);
        if (tailNu != 0) requireFiniteRange("tailNu", tailNu, 2.5, 200);
        if (model == PathModel.STUDENT_T && tailNu == 0) {
            throw new IllegalArgumentException("tailNu is required for a Student-t scenario");
        }
        if (model == PathModel.HESTON && heston == null) {
            throw new IllegalArgumentException("heston parameters are required for a HESTON scenario");
        }
        if (heston != null) {
            requireFiniteRange("heston.kappa", heston.kappa(), 0, 100);
            requireFiniteRange("heston.theta", heston.theta(), 0, 25);
            requireFiniteRange("heston.xi", heston.xi(), 0, 25);
            requireFiniteRange("heston.rho", heston.rho(), -1, 1);
            requireFiniteRange("heston.v0", heston.v0(), 0, 25);
        }
        requireRange("paths", paths, 1, 5000);
        long totalPoints = Math.multiplyExact((long) paths,
                Math.addExact(Math.multiplyExact((long) horizonDays, (long) stepsPerDay), 1L));
        if (totalPoints > MAX_TOTAL_POINTS) {
            throw new IllegalArgumentException("scenario paths × steps exceeds the "
                    + MAX_TOTAL_POINTS + "-point work limit");
        }
        return this;
    }

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
                !Double.isFinite(volAnnual) || volAnnual <= 0 ? 0 : clampD(volAnnual, 0.01, 5),
                clampD(jumpsPerYear, 0, 260),
                clampD(jumpMean, -1, 1),
                clampD(jumpVol, 0, 1),
                clampD(tailNu <= 0 ? 6 : tailNu, 2.5, 200),
                heston,
                seed, Math.clamp(paths <= 0 ? 200 : paths, 1, Math.min(5000, maxPaths)),
                keptWaypoints);
    }

    /**
     * The one generation boundary for missing volatility. Coercion keeps the explicit zero
     * calibration sentinel intact; only this method permits path generation, and it refuses
     * to manufacture a financial assumption when the market-calibration step did not resolve it.
     */
    public ScenarioSpec resolvedForGeneration() {
        ScenarioSpec resolved = sane();
        if (!(resolved.volAnnual() > 0) || !Double.isFinite(resolved.volAnnual())) {
            throw new DataUnavailableException(MISSING_VOLATILITY);
        }
        if (resolved.model() == PathModel.HESTON && resolved.heston() == null) {
            resolved = new ScenarioSpec(resolved.model(), resolved.shape(), resolved.horizonDays(),
                    resolved.stepsPerDay(), resolved.driftAnnual(), resolved.volAnnual(),
                    resolved.jumpsPerYear(), resolved.jumpMean(), resolved.jumpVol(),
                    resolved.tailNu(), Heston.fromVol(resolved.volAnnual()), resolved.seed(),
                    resolved.paths(), resolved.waypoints());
        }
        return resolved;
    }

    /** Same scenario, different vol — the calibration hook (volAnnual<=0 means "use market vol"). */
    public ScenarioSpec withVol(double vol) {
        Heston resolvedHeston = heston;
        if (model == PathModel.HESTON && (resolvedHeston == null || volAnnual <= 0) && vol > 0) {
            resolvedHeston = Heston.fromVol(vol);
        }
        return new ScenarioSpec(model, shape, horizonDays, stepsPerDay, driftAnnual, vol,
                jumpsPerYear, jumpMean, jumpVol, tailNu, resolvedHeston, seed, paths, waypoints);
    }

    /** Same scenario, different path count (persisting a dataset needs exactly ONE path). */
    public ScenarioSpec withPaths(int n) {
        return new ScenarioSpec(model, shape, horizonDays, stepsPerDay, driftAnnual, volAnnual,
                jumpsPerYear, jumpMean, jumpVol, tailNu, heston, seed, n, waypoints);
    }

    /** Same scenario at a denser stored time grid; paths remain generator-owned and fingerprinted. */
    public ScenarioSpec withStepsPerDay(int n) {
        return new ScenarioSpec(model, shape, horizonDays, n, driftAnnual, volAnnual,
                jumpsPerYear, jumpMean, jumpVol, tailNu, heston, seed, paths, waypoints);
    }

    /** Same scenario, authored pins — the scenario canvas's entry point. */
    public ScenarioSpec withWaypoints(List<Waypoint> pins) {
        return new ScenarioSpec(model, shape, horizonDays, stepsPerDay, driftAnnual, volAnnual,
                jumpsPerYear, jumpMean, jumpVol, tailNu, heston, seed, paths, pins);
    }

    private static double clampD(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void requireRange(String field, int value, int lo, int hi) {
        if (value < lo || value > hi) {
            throw new IllegalArgumentException(field + " must be from " + lo + " through " + hi);
        }
    }

    private static void requireFiniteRange(String field, double value, double lo, double hi) {
        if (!Double.isFinite(value) || value < lo || value > hi) {
            throw new IllegalArgumentException(field + " must be a finite value from " + lo
                    + " through " + hi);
        }
    }
}
