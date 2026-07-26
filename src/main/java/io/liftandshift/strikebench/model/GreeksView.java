package io.liftandshift.strikebench.model;

/**
 * The single position/package Greeks contract exposed by every public response.
 *
 * <p>Every unit is part of its field name: share-equivalent delta, share-equivalent gamma per
 * one-dollar underlying move, theta in cents per day, and vega in cents per one-volatility-point
 * move. Values are already scaled for side, deliverable, ratio, and quantity. A package with an
 * incomplete Greek set omits this whole view rather than publishing a partial sum.</p>
 *
 * <p>This record owns no pricing or aggregation. Existing pricing kernels and
 * {@code GreeksAggregator} remain the calculation authorities; this is only their wire shape.</p>
 */
public record GreeksView(
        double deltaShares,
        double gammaSharesPerDollar,
        double thetaCentsPerDay,
        double vegaCentsPerPoint
) {
    public GreeksView {
        requireFinite("deltaShares", deltaShares);
        requireFinite("gammaSharesPerDollar", gammaSharesPerDollar);
        requireFinite("thetaCentsPerDay", thetaCentsPerDay);
        requireFinite("vegaCentsPerPoint", vegaCentsPerPoint);
    }

    private static void requireFinite(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
