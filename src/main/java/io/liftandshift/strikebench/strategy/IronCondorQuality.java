package io.liftandshift.strikebench.strategy;

import java.math.BigDecimal;

/**
 * Structural compensation and protection quality for a canonical iron condor.
 *
 * <p>This is deliberately narrower than recommendation scoring. It prevents a package from
 * entering the iron-condor comparison field when its executable credit is economic dust or when
 * one protective wing is so much wider than the other that the package is really a broken-wing
 * custom structure. Every package that passes still receives the normal payoff, evidence,
 * economics and decision-policy assessment.</p>
 */
public final class IronCondorQuality {

    /** A 10% gross-width floor is only a viability check, not an endorsement threshold. */
    public static final double MIN_CREDIT_TO_WIDEST_WING = 0.10;

    /** The narrower wing must be at least half the width of the wider protective wing. */
    public static final double MIN_NARROW_TO_WIDE_WING = 0.50;

    private IronCondorQuality() {}

    public record Assessment(double creditToWidestWing, double narrowToWideWing,
                             boolean positiveBoundedCredit, boolean adequateCredit,
                             boolean balancedWings) {
        public boolean viable() {
            return positiveBoundedCredit && adequateCredit && balancedWings;
        }
    }

    /** Assesses raw per-share construction values; ratios are quantity-invariant. */
    public static Assessment assess(BigDecimal putWingWidth, BigDecimal callWingWidth,
                                    BigDecimal executableCredit) {
        if (putWingWidth == null || callWingWidth == null || executableCredit == null
                || putWingWidth.signum() <= 0 || callWingWidth.signum() <= 0) {
            return assessRatios(0.0, 0.0, false);
        }
        BigDecimal widest = putWingWidth.max(callWingWidth);
        BigDecimal narrowest = putWingWidth.min(callWingWidth);
        double creditToWidth = executableCredit.doubleValue() / widest.doubleValue();
        double wingBalance = narrowest.doubleValue() / widest.doubleValue();
        boolean boundedCredit = executableCredit.signum() > 0
                && executableCredit.compareTo(widest) < 0;
        return assessRatios(creditToWidth, wingBalance, boundedCredit);
    }

    /** Assesses already-normalized candidate receipt ratios. */
    public static Assessment assessRatios(double creditToWidestWing, double narrowToWideWing,
                                          boolean positiveBoundedCredit) {
        double credit = finiteNonNegative(creditToWidestWing);
        double balance = finiteNonNegative(narrowToWideWing);
        return new Assessment(credit, balance, positiveBoundedCredit,
                credit + 1e-12 >= MIN_CREDIT_TO_WIDEST_WING,
                balance + 1e-12 >= MIN_NARROW_TO_WIDE_WING);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }
}
