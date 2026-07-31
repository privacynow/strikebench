package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.strategy.CapitalRequirement;
import io.liftandshift.strikebench.strategy.StrategyCatalog;

/**
 * Backend-owned account-fit receipt for one ranked candidate. It keeps economic downside,
 * collateral/buying-power use, and the user's declared loss appetite as separate facts.
 * Economics remains the endorsement authority; this receipt only orders candidates within the
 * same economic tier and explains why a collateral trade may exceed the loss preference.
 */
public record AccountFitReceipt(
        String status,
        StrategyCatalog.FundingClass fundingClass,
        Long maximumLossCents,
        Long lossAppetiteCents,
        Long capitalRequiredCents,
        long availableBuyingPowerCents,
        Boolean withinLossAppetite,
        Boolean withinBuyingPower,
        int rankingTier,
        String basis
) {
    public static AccountFitReceipt assess(CapitalRequirement requirement,
                                           long availableBuyingPowerCents,
                                           Long lossAppetiteCents) {
        long available = Math.max(0L, availableBuyingPowerCents);
        if (requirement == null || !requirement.available()) {
            return new AccountFitReceipt("UNAVAILABLE",
                    requirement == null ? StrategyCatalog.FundingClass.UNCLASSIFIED
                            : requirement.fundingClass(),
                    requirement == null ? null : requirement.maximumLossCents(),
                    lossAppetiteCents,
                    requirement == null ? null : requirement.buyingPowerRequiredCents(),
                    available, null, null, 0,
                    requirement == null ? "No capital-use receipt was supplied."
                            : requirement.unavailableReason());
        }
        long capital = requirement.buyingPowerRequiredCents();
        long maximumLoss = requirement.maximumLossCents();
        boolean withinBuyingPower = capital <= available;
        Boolean withinLoss = lossAppetiteCents == null ? null : maximumLoss <= lossAppetiteCents;
        if (!withinBuyingPower) {
            return new AccountFitReceipt("EXCEEDS_BUYING_POWER", requirement.fundingClass(),
                    maximumLoss, lossAppetiteCents, capital, available, withinLoss, false, 0,
                    "The exact package requires more buying power than the destination account has available.");
        }
        if (withinLoss == null) {
            return new AccountFitReceipt("LOSS_APPETITE_UNDECLARED", requirement.fundingClass(),
                    maximumLoss, null, capital, available, null, true, 2,
                    "Capital fits the account; no per-idea loss appetite was declared.");
        }
        if (withinLoss) {
            return new AccountFitReceipt("FITS_LOSS_APPETITE", requirement.fundingClass(),
                    maximumLoss, lossAppetiteCents, capital, available, true, true, 3,
                    "Maximum economic downside fits the declared per-idea loss appetite.");
        }
        if (requirement.fundingClass() == StrategyCatalog.FundingClass.CASH_COLLATERAL) {
            return new AccountFitReceipt("COLLATERAL_OUTSIDE_LOSS_APPETITE",
                    requirement.fundingClass(), maximumLoss, lossAppetiteCents, capital, available,
                    false, true, 2,
                    "The cash collateral fits available buying power, but maximum economic downside exceeds the declared loss appetite.");
        }
        return new AccountFitReceipt("EXCEEDS_LOSS_APPETITE", requirement.fundingClass(),
                maximumLoss, lossAppetiteCents, capital, available, false, true, 1,
                "Maximum economic downside exceeds the declared per-idea loss appetite.");
    }
}
