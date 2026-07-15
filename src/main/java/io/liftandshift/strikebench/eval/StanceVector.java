package io.liftandshift.strikebench.eval;

import java.util.Collection;

/** Fixed-unit exposure primitive shared by participation, coherence, and book aggregation. */
public record StanceVector(
        long dollarDeltaCents,
        long gammaDollarDeltaCentsPerOnePercentMove,
        long vegaCentsPerVolPoint,
        long thetaCentsPerDay,
        long downsideLossOneSigmaCents,
        long downsideLossTwoSigmaCents,
        long upsideLossOneSigmaCents,
        long upsideLossTwoSigmaCents,
        int durationCalendarDays
) {
    public StanceVector {
        if (durationCalendarDays < 0) throw new IllegalArgumentException("duration cannot be negative");
        if (downsideLossOneSigmaCents < 0 || downsideLossTwoSigmaCents < 0
                || upsideLossOneSigmaCents < 0 || upsideLossTwoSigmaCents < 0) {
            throw new IllegalArgumentException("scenario losses cannot be negative");
        }
    }

    public static StanceVector aggregate(Collection<StanceVector> vectors) {
        long delta = 0, gamma = 0, vega = 0, theta = 0, down1 = 0, down2 = 0, up1 = 0, up2 = 0;
        int duration = 0;
        if (vectors != null) for (StanceVector v : vectors) if (v != null) {
            delta = Math.addExact(delta, v.dollarDeltaCents);
            gamma = Math.addExact(gamma, v.gammaDollarDeltaCentsPerOnePercentMove);
            vega = Math.addExact(vega, v.vegaCentsPerVolPoint);
            theta = Math.addExact(theta, v.thetaCentsPerDay);
            down1 = Math.addExact(down1, v.downsideLossOneSigmaCents);
            down2 = Math.addExact(down2, v.downsideLossTwoSigmaCents);
            up1 = Math.addExact(up1, v.upsideLossOneSigmaCents);
            up2 = Math.addExact(up2, v.upsideLossTwoSigmaCents);
            duration = Math.max(duration, v.durationCalendarDays);
        }
        return new StanceVector(delta, gamma, vega, theta, down1, down2, up1, up2, duration);
    }
}
