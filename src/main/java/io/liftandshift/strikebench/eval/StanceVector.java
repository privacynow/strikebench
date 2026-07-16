package io.liftandshift.strikebench.eval;

import java.util.Collection;

/** Fixed-unit exposure primitive shared by participation, coherence, and book aggregation. */
public record StanceVector(
        long dollarDeltaCents,
        long gammaDollarDeltaCentsPerOnePercentMove,
        long vegaCentsPerVolPoint,
        long thetaCentsPerDay,
        Long downsideLossOneSigmaCents,
        Long downsideLossTwoSigmaCents,
        Long upsideLossOneSigmaCents,
        Long upsideLossTwoSigmaCents,
        int durationCalendarDays
) {
    public StanceVector {
        if (durationCalendarDays < 0) throw new IllegalArgumentException("duration cannot be negative");
        if (negative(downsideLossOneSigmaCents) || negative(downsideLossTwoSigmaCents)
                || negative(upsideLossOneSigmaCents) || negative(upsideLossTwoSigmaCents)) {
            throw new IllegalArgumentException("scenario losses cannot be negative");
        }
    }

    private static boolean negative(Long value) { return value != null && value < 0; }

    public static StanceVector aggregate(Collection<StanceVector> vectors) {
        long delta = 0, gamma = 0, vega = 0, theta = 0;
        Long down1 = 0L, down2 = 0L, up1 = 0L, up2 = 0L;
        int duration = 0;
        if (vectors != null) for (StanceVector v : vectors) if (v != null) {
            delta = Math.addExact(delta, v.dollarDeltaCents);
            gamma = Math.addExact(gamma, v.gammaDollarDeltaCentsPerOnePercentMove);
            vega = Math.addExact(vega, v.vegaCentsPerVolPoint);
            theta = Math.addExact(theta, v.thetaCentsPerDay);
            down1 = sumKnown(down1, v.downsideLossOneSigmaCents);
            down2 = sumKnown(down2, v.downsideLossTwoSigmaCents);
            up1 = sumKnown(up1, v.upsideLossOneSigmaCents);
            up2 = sumKnown(up2, v.upsideLossTwoSigmaCents);
            duration = Math.max(duration, v.durationCalendarDays);
        }
        return new StanceVector(delta, gamma, vega, theta, down1, down2, up1, up2, duration);
    }

    private static Long sumKnown(Long left, Long right) {
        return left == null || right == null ? null : Math.addExact(left, right);
    }
}
