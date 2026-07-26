package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.model.GreeksView;

import java.util.List;

/**
 * The single aggregation owner for current package Greeks.
 *
 * <p>Inputs are per-share/per-unit market Greeks. The result uses the canonical wire units:
 * delta shares, gamma shares per $1, theta cents per day, and vega cents per volatility point.
 * An option leg missing any component makes the package unavailable; missing is never zero.</p>
 */
final class GreeksAggregator {
    private GreeksAggregator() {}

    record LegExposure(boolean stock, int sign, int multiplier, int ratio, int quantity,
                       Double delta, Double gamma, Double theta, Double vega) {
        LegExposure {
            if (sign != -1 && sign != 1) throw new IllegalArgumentException("Greek sign must be -1 or 1");
            if (multiplier < 1 || ratio < 1 || quantity < 1) {
                throw new IllegalArgumentException("Greek deliverable units must be positive");
            }
        }

        double scale() {
            return sign * multiplier * (double) ratio * quantity;
        }
    }

    static GreeksView aggregate(List<LegExposure> legs, double additionalDeltaShares) {
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
        return new GreeksView(
                round(delta, 2), round(gamma, 4),
                round(theta * 100.0, 2), round(vega * 100.0, 2));
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
