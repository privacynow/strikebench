package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.pricing.BlackScholes;

import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/** The sole leg-by-leg valuation rule used over generated and historical path ensembles. */
public final class PathValuationKernel {
    private PathValuationKernel() {}

    /** One leg on one path/day; values are signed for BUY/SELL and one package quantity. */
    public record LegPoint(double valueDollars, double optionPrice,
                           double deltaShares, double gammaSharesPerDollar,
                           double thetaDollarsPerDay, double vegaDollarsPerPoint,
                           String state, int transformationStep) {}

    /** Signed portfolio value in dollars for one strategy unit. */
    public static double value(PathPosition position, double[] path, int step, int steps,
                               int stepsPerDay, double dt, double iv, double annualRate) {
        double underlying = path[Math.min(step, steps)];
        double value = 0;
        for (Leg leg : position.legs()) {
            double sign = leg.action() == LegAction.SELL ? -1 : 1;
            if (leg.isStock()) {
                value += sign * leg.ratio() * leg.multiplier() * underlying;
                continue;
            }
            boolean call = leg.type() == OptionType.CALL;
            // 0DTE remains alive at t0 and settles at the first simulated closing bell.
            int expiryDay = position.expiryDay(leg);
            int expiryStep = expiryDay <= 0 ? Math.min(stepsPerDay, steps) : expiryDay * stepsPerDay;
            double optionPrice;
            if (step >= expiryStep) {
                double settlementSpot = path[Math.min(expiryStep, steps)];
                optionPrice = Math.max(0, call
                        ? settlementSpot - leg.strike().doubleValue()
                        : leg.strike().doubleValue() - settlementSpot);
            } else {
                double time = (expiryStep - step) * dt;
                optionPrice = BlackScholes.price(call, underlying, leg.strike().doubleValue(),
                        time, annualRate, 0, Math.max(0.01, iv));
            }
            value += sign * leg.ratio() * leg.multiplier() * optionPrice;
        }
        return value;
    }

    /**
     * Canvas valuation on the real session clock and declared strike/term IV surface.  This is an
     * extension of the same BSM/intrinsic kernel above, not a second pricing engine.
     */
    public static LegPoint legPoint(PathPosition position, io.liftandshift.strikebench.model.Leg leg,
                                    double[] path, int step, int steps, int stepsPerDay,
                                    double[] elapsedYears, double legacyIv,
                                    ScenarioCanvasSpec canvas, double annualRate) {
        double[] legacyPath = new double[steps + 1];
        Arrays.fill(legacyPath, legacyIv);
        int transformation = transformationStep(position, leg, path, steps, stepsPerDay,
                elapsedYears, legacyPath, canvas, annualRate);
        return legPoint(position, leg, path, step, steps, stepsPerDay, elapsedYears,
                legacyPath, canvas, annualRate, transformation);
    }

    /** Full-IV-path variant used by the Canvas so prior exercise decisions never change retroactively. */
    public static LegPoint legPoint(PathPosition position, io.liftandshift.strikebench.model.Leg leg,
                                    double[] path, int step, int steps, int stepsPerDay,
                                    double[] elapsedYears, double[] legacyIvPath,
                                    ScenarioCanvasSpec canvas, double annualRate,
                                    int transformation) {
        ScenarioCanvasSpec c = canvas == null ? ScenarioCanvasSpec.defaults() : canvas;
        int at = Math.max(0, Math.min(step, steps));
        double underlying = path[at];
        double sign = leg.action() == LegAction.SELL ? -1 : 1;
        double units = leg.ratio() * (double) leg.multiplier();
        if (leg.isStock()) {
            return new LegPoint(sign * units * underlying, underlying,
                    sign * units, 0, 0, 0, "STOCK", -1);
        }
        boolean call = leg.type() == OptionType.CALL;
        int expiryStep = expiryStep(position, leg, steps, stepsPerDay);
        boolean early = transformation < expiryStep;
        if (at >= transformation) {
            double settleSpot = path[Math.min(transformation, steps)];
            boolean itm = call ? settleSpot > leg.strike().doubleValue()
                    : settleSpot < leg.strike().doubleValue();
            if (!itm) return new LegPoint(0, 0, 0, 0, 0, 0,
                    early ? "EARLY_NOT_EXERCISED" : "EXPIRED_WORTHLESS", transformation);
            if (c.settlementPolicy() == ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM) {
                double physical = call ? underlying - leg.strike().doubleValue()
                        : leg.strike().doubleValue() - underlying;
                double rawDelta = call ? 1 : -1;
                return new LegPoint(sign * units * physical, Math.max(0, physical),
                        sign * units * rawDelta, 0, 0, 0,
                        early ? "EARLY_EXERCISE_TRANSFORMED" : "PHYSICAL_SETTLEMENT_TRANSFORMED",
                        transformation);
            }
            double intrinsic = Math.max(0, call ? settleSpot - leg.strike().doubleValue()
                    : leg.strike().doubleValue() - settleSpot);
            return new LegPoint(sign * units * intrinsic, intrinsic, 0, 0, 0, 0,
                    "CASH_SETTLED", transformation);
        }

        double t = Math.max(0, expiryElapsedYears(position, leg, expiryStep, steps,
                stepsPerDay, elapsedYears)
                - elapsedYears[Math.min(at, elapsedYears.length - 1)]);
        int day = at / Math.max(1, stepsPerDay);
        double iv = c.surfaceIv(day, Math.max(1, steps / Math.max(1, stepsPerDay)),
                ivAt(legacyIvPath, at),
                path[0], underlying, leg.strike().doubleValue(), t);
        double q = c.dividendYieldForPricing();
        double px = BlackScholes.price(call, underlying, leg.strike().doubleValue(), t,
                annualRate, q, iv);
        double delta = BlackScholes.delta(call, underlying, leg.strike().doubleValue(), t,
                annualRate, q, iv);
        double gamma = BlackScholes.gamma(underlying, leg.strike().doubleValue(), t,
                annualRate, q, iv);
        double theta = BlackScholes.theta(call, underlying, leg.strike().doubleValue(), t,
                annualRate, q, iv) / 365.0;
        double vega = BlackScholes.vega(underlying, leg.strike().doubleValue(), t,
                annualRate, q, iv) / 100.0;
        return new LegPoint(sign * units * px, px,
                sign * units * delta, sign * units * gamma,
                sign * units * theta, sign * units * vega, "LIVE_MODELED", transformation);
    }

    public static double valueCanvas(PathPosition position, double[] path, int step, int steps,
                                     int stepsPerDay, double[] elapsedYears, double legacyIv,
                                     ScenarioCanvasSpec canvas, double annualRate) {
        double[] legacyPath = new double[steps + 1];
        Arrays.fill(legacyPath, legacyIv);
        return valueCanvas(position, path, step, steps, stepsPerDay, elapsedYears,
                legacyPath, canvas, annualRate, transformationSteps(position, path, steps,
                        stepsPerDay, elapsedYears, legacyPath, canvas, annualRate));
    }

    public static double valueCanvas(PathPosition position, double[] path, int step, int steps,
                                     int stepsPerDay, double[] elapsedYears, double[] legacyIvPath,
                                     ScenarioCanvasSpec canvas, double annualRate,
                                     int[] transformations) {
        if (transformations == null || transformations.length != position.legs().size()) {
            throw new IllegalArgumentException("one transformation step is required per position leg");
        }
        double value = 0;
        for (int legNo = 0; legNo < position.legs().size(); legNo++) {
            io.liftandshift.strikebench.model.Leg leg = position.legs().get(legNo);
            value += legPoint(position, leg, path, step, steps, stepsPerDay, elapsedYears,
                    legacyIvPath, canvas, annualRate, transformations[legNo]).valueDollars();
        }
        return value;
    }

    /** Resolve path-dependent early exercise once per leg/path, then reuse it for every day. */
    public static int[] transformationSteps(PathPosition position, double[] path, int steps,
                                            int stepsPerDay, double[] elapsedYears,
                                            double[] legacyIvPath, ScenarioCanvasSpec canvas,
                                            double annualRate) {
        int[] out = new int[position.legs().size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = transformationStep(position, position.legs().get(i), path, steps,
                    stepsPerDay, elapsedYears, legacyIvPath, canvas, annualRate);
        }
        return out;
    }

    /** Cumulative year fractions at step 0..N. */
    public static double[] elapsed(double[] stepYears) {
        double[] out = new double[stepYears.length + 1];
        for (int i = 1; i < out.length; i++) out[i] = out[i - 1] + stepYears[i - 1];
        return out;
    }

    private static int transformationStep(PathPosition position,
                                          io.liftandshift.strikebench.model.Leg leg,
                                          double[] path, int steps, int stepsPerDay,
                                          double[] elapsedYears, double[] legacyIvPath,
                                          ScenarioCanvasSpec canvas, double annualRate) {
        if (leg.isStock()) return -1;
        ScenarioCanvasSpec c = canvas == null ? ScenarioCanvasSpec.defaults() : canvas;
        int expiryStep = expiryStep(position, leg, steps, stepsPerDay);
        if (c.exercisePolicy() != ScenarioCanvasSpec.ExercisePolicy.EXTRINSIC_THRESHOLD
                || c.settlementPolicy() != ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM) {
            return expiryStep;
        }
        boolean call = leg.type() == OptionType.CALL;
        int through = Math.min(steps, expiryStep);
        double expiryElapsed = expiryElapsedYears(position, leg, expiryStep, steps,
                stepsPerDay, elapsedYears);
        // Daily closes only: the canvas never invents an intraday exercise timestamp.
        for (int i = Math.max(stepsPerDay, 1); i <= through; i += Math.max(1, stepsPerDay)) {
            double s = path[i];
            double intrinsic = Math.max(0, call ? s - leg.strike().doubleValue()
                    : leg.strike().doubleValue() - s);
            if (intrinsic <= 0) continue;
            double t = Math.max(0, expiryElapsed - elapsedYears[i]);
            int day = i / Math.max(1, stepsPerDay);
            double iv = c.surfaceIv(day, Math.max(1, elapsedYears.length / Math.max(1, stepsPerDay)),
                    ivAt(legacyIvPath, i), path[0], s, leg.strike().doubleValue(), t);
            double option = BlackScholes.price(call, s, leg.strike().doubleValue(), t,
                    annualRate, c.dividendYieldForPricing(), iv);
            if (option - intrinsic <= 0.01) return i;
        }
        return expiryStep;
    }

    private static int expiryStep(PathPosition position, io.liftandshift.strikebench.model.Leg leg,
                                  int steps, int stepsPerDay) {
        int expiryDay = position.expiryDay(leg);
        return expiryDay <= 0 ? Math.min(Math.max(1, stepsPerDay), steps)
                : Math.multiplyExact(expiryDay, Math.max(1, stepsPerDay));
    }

    private static double expiryElapsedYears(PathPosition position,
                                             io.liftandshift.strikebench.model.Leg leg,
                                             int expiryStep, int steps, int stepsPerDay,
                                             double[] elapsedYears) {
        if (expiryStep < elapsedYears.length) return elapsedYears[expiryStep];
        long calendarDays = Math.max(0, ChronoUnit.DAYS.between(position.asOf(), leg.expiration()));
        double exactCalendar = calendarDays / 365.0;
        double afterHorizon = elapsedYears[Math.min(steps, elapsedYears.length - 1)];
        // The expiration lies beyond the path horizon; retain the exact calendar maturity rather
        // than silently settling at the final simulated session.
        return Math.max(exactCalendar, afterHorizon + 1.0 / (365.0 * Math.max(1, stepsPerDay)));
    }

    private static double ivAt(double[] path, int step) {
        if (path == null || path.length == 0) throw new IllegalArgumentException("IV path is required");
        return path[Math.max(0, Math.min(step, path.length - 1))];
    }
}
