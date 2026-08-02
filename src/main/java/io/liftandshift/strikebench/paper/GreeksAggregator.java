package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.model.GreeksView;

import java.util.List;

/**
 * The single aggregation owner for current package Greeks.
 *
 * <p>Inputs are per-share/per-unit market Greeks. The result uses the normalized wire units:
 * delta shares, gamma shares per $1, theta cents per day, and vega cents per volatility point.
 * An option leg missing any component makes the package unavailable; missing is never zero.</p>
 */
public final class GreeksAggregator {
    private GreeksAggregator() {}

    public record LegExposure(boolean stock, int sign, int multiplier, int ratio, long quantity,
                              Double delta, Double gamma, Double theta, Double vega) {
        public LegExposure {
            if (sign != -1 && sign != 1) throw new IllegalArgumentException("Greek sign must be -1 or 1");
            if (multiplier < 1 || ratio < 1 || quantity < 1) {
                throw new IllegalArgumentException("Greek deliverable units must be positive");
            }
        }

        double scale() {
            return sign * multiplier * (double) ratio * quantity;
        }
    }

    public static GreeksView aggregate(List<LegExposure> legs, double additionalDeltaShares) {
        if ((legs == null || legs.isEmpty()) && additionalDeltaShares == 0) return null;
        double delta = additionalDeltaShares;
        double gamma = 0;
        double theta = 0;
        double vega = 0;
        boolean any = additionalDeltaShares != 0;
        if (legs != null) for (LegExposure leg : legs) {
            if (leg == null) return null;
            double scale = leg.scale();
            if (leg.stock()) {
                // Stock delta is a contract identity, not a provider field. A malformed/stale
                // snapshot must not change one share from delta 1 into anything else.
                delta += scale;
                // A stock leg has exactly zero gamma/theta/vega; market marks need not repeat them.
            } else {
                if (leg.delta() == null || leg.gamma() == null
                        || leg.theta() == null || leg.vega() == null) {
                    return null;
                }
                delta += leg.delta() * scale;
                gamma += leg.gamma() * scale;
                theta += leg.theta() * scale;
                vega += leg.vega() * scale;
            }
            any = true;
        }
        if (!any) return null;
        // Preserve calculator precision in the authoritative result. Rounding belongs at the
        // presentation boundary; rounding share delta here before converting it to dollar delta
        // changes a real exposure fact (not merely its display).
        return new GreeksView(delta, gamma, theta * 100.0, vega * 100.0);
    }

    /** Additive dollar-delta exposure, in cents, at the same captured underlying price. */
    public static Long dollarDeltaCents(GreeksView greeks, long underlyingCents) {
        return greeks == null ? null : dollarDeltaFromSharesCents(greeks.deltaShares(), underlyingCents);
    }

    /**
     * Dollar-delta conversion for a consumer that owns an honest delta-only result rather than a
     * complete Delta/Gamma/Theta/Vega set.
     */
    public static Long dollarDeltaFromSharesCents(double deltaShares, long underlyingCents) {
        if (!Double.isFinite(deltaShares) || underlyingCents <= 0) return null;
        return roundLong(deltaShares * underlyingCents);
    }

    /**
     * Change in dollar delta, in cents, for a stated percentage underlying move.
     *
     * <p>The conversion lives beside the one Greek aggregation owner so tracked Book, Practice,
     * and modeled stance cannot each invent a different shares-per-dollar to cents convention.</p>
     */
    public static Long gammaDollarDeltaCentsForPercentMove(
            GreeksView greeks, long underlyingCents, double movePct) {
        if (greeks == null || underlyingCents <= 0 || !Double.isFinite(movePct)) return null;
        double underlyingDollars = underlyingCents / 100.0;
        double dollarMove = underlyingDollars * movePct / 100.0;
        return roundLong(greeks.gammaSharesPerDollar() * dollarMove * underlyingCents);
    }

    private static Long roundLong(double value) {
        if (!Double.isFinite(value) || value > Long.MAX_VALUE || value < Long.MIN_VALUE) return null;
        return Math.round(value);
    }
}
