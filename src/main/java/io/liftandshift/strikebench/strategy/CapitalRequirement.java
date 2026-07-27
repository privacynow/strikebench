package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.paper.PackagePriceReceipt;

/**
 * The canonical capital-use receipt for one exact priced package.
 *
 * <p>The four amounts answer different questions and must never be substituted for one another:
 * maximum loss is the package's payoff risk; reserve is the future liability held by the Practice
 * ledger after the opening cash flow; buying-power required is the net reduction in available
 * buying power at entry; economic exposure is the structural denominator used for return
 * comparisons. A cash-secured put therefore has maximum loss net of its premium, strike-cash
 * reserve, and buying-power use equal to maximum loss plus opening fees.</p>
 */
@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record CapitalRequirement(
        StrategyCatalog.FundingClass fundingClass,
        StrategyCatalog.CapitalBasis capitalBasis,
        Long maximumLossCents,
        Long reserveCents,
        Long buyingPowerRequiredCents,
        Long economicExposureCents,
        String basis,
        String unavailableReason
) {
    public CapitalRequirement {
        fundingClass = fundingClass == null
                ? StrategyCatalog.FundingClass.UNCLASSIFIED : fundingClass;
        capitalBasis = capitalBasis == null
                ? StrategyCatalog.CapitalBasis.EXACT_PACKAGE_ASSESSMENT : capitalBasis;
        if (maximumLossCents != null && maximumLossCents < 0
                || reserveCents != null && reserveCents < 0
                || buyingPowerRequiredCents != null && buyingPowerRequiredCents < 0
                || economicExposureCents != null && economicExposureCents < 0) {
            throw new IllegalArgumentException("capital-use amounts cannot be negative");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("capital-use basis is required");
        }
        boolean complete = maximumLossCents != null && reserveCents != null
                && buyingPowerRequiredCents != null && economicExposureCents != null;
        if (!complete && (unavailableReason == null || unavailableReason.isBlank())) {
            throw new IllegalArgumentException(
                    "an incomplete capital-use receipt must state what is unavailable");
        }
    }

    public boolean available() {
        return maximumLossCents != null && reserveCents != null
                && buyingPowerRequiredCents != null && economicExposureCents != null;
    }

    /**
     * Composes the exact receipt from the package price, finite payoff risk, and catalog identity.
     * {@code heldShareContext} means the shares already belong to the account; their downside
     * remains economic exposure but does not create a second cash reserve.
     */
    public static CapitalRequirement of(StrategyCatalog.PositionIdentity identity,
                                        PackagePriceReceipt price,
                                        Long maximumLossCents,
                                        Long combinedMaximumLossCents,
                                        boolean heldShareContext) {
        StrategyCatalog.FundingClass funding = identity == null
                ? StrategyCatalog.FundingClass.UNCLASSIFIED : identity.fundingClass();
        StrategyCatalog.CapitalBasis capitalBasis = identity == null
                ? StrategyCatalog.CapitalBasis.EXACT_PACKAGE_ASSESSMENT : identity.capitalBasis();
        String basis = basis(funding, capitalBasis);
        if (price == null || !price.priced()) {
            return unavailable(funding, capitalBasis, basis,
                    price == null ? "No package-price receipt was supplied."
                            : price.unavailableReason());
        }
        if (maximumLossCents == null || maximumLossCents < 0) {
            return unavailable(funding, capitalBasis, basis,
                    "The exact package has no finite maximum-loss receipt.");
        }
        if (capitalBasis == StrategyCatalog.CapitalBasis.COMBINED_POSITION_MAXIMUM_LOSS
                && combinedMaximumLossCents == null) {
            return unavailable(funding, capitalBasis, basis,
                    "The share-backed package has no combined-position maximum-loss receipt.");
        }
        if (funding == StrategyCatalog.FundingClass.UNDEFINED_RISK
                || capitalBasis == StrategyCatalog.CapitalBasis.UNBOUNDED) {
            return unavailable(funding, capitalBasis, basis,
                    "Undefined-risk packages have no finite capital-use receipt.");
        }
        Long grossNet = price.grossPackageNetCents();
        Long afterFeeNet = price.afterFeeNetCents();
        if (grossNet == null || afterFeeNet == null) {
            return unavailable(funding, capitalBasis, basis,
                    "The package price does not state both its gross and after-fee opening cash flow.");
        }
        long reserve = reserveCents(maximumLossCents, grossNet, heldShareContext);
        long buyingPower = buyingPowerRequiredCents(reserve, afterFeeNet);
        Long economic = switch (capitalBasis) {
            case NONE -> 0L;
            case MAXIMUM_LOSS -> maximumLossCents;
            case STRIKE_CASH_COLLATERAL -> reserve;
            case COMBINED_POSITION_MAXIMUM_LOSS ->
                    combinedMaximumLossCents == null ? maximumLossCents
                            : Math.max(maximumLossCents, combinedMaximumLossCents);
            case EXACT_PACKAGE_ASSESSMENT -> maximumLossCents;
            case UNBOUNDED -> null;
        };
        if (economic == null) {
            return unavailable(funding, capitalBasis, basis,
                    "The exact package has no finite economic-exposure denominator.");
        }
        return new CapitalRequirement(funding, capitalBasis, maximumLossCents, reserve,
                buyingPower, economic, basis, null);
    }

    /** Practice ledger reserve: future loss not already paid or secured by existing shares. */
    public static long reserveCents(long maximumLossCents, long grossOpeningNetCents,
                                    boolean heldShareContext) {
        if (maximumLossCents < 0) {
            throw new IllegalArgumentException("maximum loss cannot be negative");
        }
        return heldShareContext ? 0L
                : Math.max(0L, Math.addExact(maximumLossCents, grossOpeningNetCents));
    }

    /** Net entry reduction in available buying power after opening cash and reserve move together. */
    public static long buyingPowerRequiredCents(long reserveCents, long afterFeeOpeningNetCents) {
        if (reserveCents < 0) throw new IllegalArgumentException("reserve cannot be negative");
        return Math.max(0L, Math.subtractExact(reserveCents, afterFeeOpeningNetCents));
    }

    /**
     * Early structural affordability before an executable price receipt has captured commission.
     * This is deliberately named differently from {@link #buyingPowerRequiredCents}: it may reject
     * an obviously unaffordable package, but it cannot publish or approve exact opening buying
     * power. The priced Candidate/preview gate always adds the captured opening commission.
     */
    public static long structuralBuyingPowerBeforeFeesCents(long reserveCents,
                                                            long grossOpeningNetCents) {
        return buyingPowerRequiredCents(reserveCents, grossOpeningNetCents);
    }

    private static CapitalRequirement unavailable(StrategyCatalog.FundingClass funding,
                                                  StrategyCatalog.CapitalBasis capitalBasis,
                                                  String basis, String reason) {
        return new CapitalRequirement(funding, capitalBasis, null, null, null, null,
                basis, reason == null || reason.isBlank()
                        ? "Capital use is unavailable." : reason);
    }

    private static String basis(StrategyCatalog.FundingClass funding,
                                StrategyCatalog.CapitalBasis capitalBasis) {
        return switch (capitalBasis) {
            case NONE -> "This package requires no new capital.";
            case MAXIMUM_LOSS ->
                    "Defined-risk economic exposure is the exact package maximum loss.";
            case STRIKE_CASH_COLLATERAL ->
                    "Cash collateral is the exact future liability reserved after the opening credit.";
            case COMBINED_POSITION_MAXIMUM_LOSS ->
                    "Economic exposure includes the held or purchased shares and the option package.";
            case UNBOUNDED -> "Undefined risk has no finite capital denominator.";
            case EXACT_PACKAGE_ASSESSMENT ->
                    "Capital use is measured from this exact package's price and payoff.";
        } + " Funding class: " + funding.name() + ".";
    }
}
