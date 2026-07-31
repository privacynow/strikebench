package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.position.PositionDomain;
import io.liftandshift.strikebench.util.Money;

import java.util.List;

/**
 * One pure before/after dollar-delta composer shared by candidate evaluation and cross-book Scout.
 * Practice and Real lanes remain separate outputs and are never numerically netted.
 */
public final class PortfolioImpactComposer {
    private PortfolioImpactComposer() {}

    public static FourOutputAssessment.PortfolioImpacts compose(
            PortfolioExposureContext exposure, StanceVector stance) {
        if (exposure == null || stance == null) {
            return new FourOutputAssessment.PortfolioImpacts(null, null, List.of(
                    "No destination portfolio was selected, so before/after exposure is unavailable.",
                    "Practice and Real impacts are always reported separately and are never netted."));
        }
        long added = stance.dollarDeltaCents();
        long addedGross = absolute(added);
        long grossAfter = Math.addExact(exposure.grossDollarDeltaCents(), addedGross);
        long netAfter = Math.addExact(exposure.netDollarDeltaCents(), added);
        long symbolAfter = Math.addExact(exposure.symbolGrossDollarDeltaCents(), addedGross);
        Double beforePct = percent(exposure.symbolGrossDollarDeltaCents(),
                exposure.grossDollarDeltaCents());
        Double afterPct = percent(symbolAfter, grossAfter);
        var impact = new FourOutputAssessment.PortfolioImpact(exposure.lane(),
                exposure.grossDollarDeltaCents(), grossAfter,
                exposure.netDollarDeltaCents(), netAfter, beforePct, afterPct,
                List.of("This package adds " + signedDollars(added)
                                + " of modeled dollar delta to the selected lane.",
                        "Focused-symbol gross concentration moves from " + percentLabel(beforePct)
                                + " to " + percentLabel(afterPct) + "."),
                exposure.basis());
        List<String> notes = exposure.complete() ? List.of(
                "Practice and Real impacts are always reported separately and are never netted.") : List.of(
                "Existing exposure is partial because one or more current positions lacked a complete mark or delta.",
                "Practice and Real impacts are always reported separately and are never netted.");
        return exposure.lane() == PositionDomain.ExecutionLane.PRACTICE
                ? new FourOutputAssessment.PortfolioImpacts(impact, null, notes)
                : new FourOutputAssessment.PortfolioImpacts(null, impact, notes);
    }

    private static long absolute(long value) {
        if (value == Long.MIN_VALUE) throw new ArithmeticException("dollar delta overflow");
        return Math.abs(value);
    }

    private static Double percent(long part, long total) {
        return total <= 0 ? null : Math.round(part * 10_000.0 / total) / 100.0;
    }

    private static String percentLabel(Double value) {
        return value == null ? "not measurable on an empty book"
                : String.format(java.util.Locale.ROOT, "%.2f%%", value);
    }

    private static String signedDollars(long cents) {
        return (cents >= 0 ? "+" : "-") + Money.fmt(absolute(cents));
    }
}
