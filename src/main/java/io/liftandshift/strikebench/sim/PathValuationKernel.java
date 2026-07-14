package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.pricing.BlackScholes;

/** The sole leg-by-leg valuation rule used over generated and historical path ensembles. */
public final class PathValuationKernel {
    private PathValuationKernel() {}

    /** Signed portfolio value in dollars for one strategy unit. */
    public static double value(PathPosition position, double[] path, int step, int steps,
                               int stepsPerDay, double dt, double iv, double annualRate) {
        double underlying = path[Math.min(step, steps)];
        double value = 0;
        for (Leg leg : position.legs()) {
            double sign = leg.action() == LegAction.SELL ? -1 : 1;
            if (leg.isStock()) {
                value += sign * leg.ratio() * Leg.SHARES_PER_CONTRACT * underlying;
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
            value += sign * leg.ratio() * Leg.SHARES_PER_CONTRACT * optionPrice;
        }
        return value;
    }
}
