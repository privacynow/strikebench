package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.strategy.CapitalRequirement;
import io.liftandshift.strikebench.strategy.StrategyCatalog;

/**
 * Backend-owned account-fit result for one ranked candidate. It keeps economic downside,
 * collateral/buying-power use, and the user's declared loss appetite as separate facts.
 * Economics remains the endorsement authority; this result only orders candidates within the
 * same economic tier and explains why a collateral trade may exceed the loss preference.
 */
public record AccountFitAssessment(
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
    public static AccountFitAssessment assess(CapitalRequirement requirement,
                                           long availableBuyingPowerCents,
                                           Long lossAppetiteCents) {
        long available = Math.max(0L, availableBuyingPowerCents);
        if (requirement == null || !requirement.available()) {
            return new AccountFitAssessment("UNAVAILABLE",
                    requirement == null ? StrategyCatalog.FundingClass.UNCLASSIFIED
                            : requirement.fundingClass(),
                    requirement == null ? null : requirement.maximumLossCents(),
                    lossAppetiteCents,
                    requirement == null ? null : requirement.buyingPowerRequiredCents(),
                    available, null, null, 0,
                    requirement == null ? "No capital-use result was supplied."
                            : requirement.unavailableReason());
        }
        long capital = requirement.buyingPowerRequiredCents();
        long maximumLoss = requirement.maximumLossCents();
        boolean withinBuyingPower = capital <= available;
        Boolean withinLoss = lossAppetiteCents == null ? null : maximumLoss <= lossAppetiteCents;
        if (!withinBuyingPower) {
            return new AccountFitAssessment("EXCEEDS_BUYING_POWER", requirement.fundingClass(),
                    maximumLoss, lossAppetiteCents, capital, available, withinLoss, false, 0,
                    "The exact package requires more buying power than the destination account has available.");
        }
        if (withinLoss == null) {
            return new AccountFitAssessment("LOSS_APPETITE_UNDECLARED", requirement.fundingClass(),
                    maximumLoss, null, capital, available, null, true, 2,
                    "Capital fits the account; no per-idea loss appetite was declared.");
        }
        if (withinLoss) {
            return new AccountFitAssessment("FITS_LOSS_APPETITE", requirement.fundingClass(),
                    maximumLoss, lossAppetiteCents, capital, available, true, true, 3,
                    "Maximum economic downside fits the declared per-idea loss appetite.");
        }
        if (requirement.fundingClass() == StrategyCatalog.FundingClass.CASH_COLLATERAL) {
            return new AccountFitAssessment("COLLATERAL_OUTSIDE_LOSS_APPETITE",
                    requirement.fundingClass(), maximumLoss, lossAppetiteCents, capital, available,
                    false, true, 2,
                    "The cash collateral fits available buying power, but maximum economic downside exceeds the declared loss appetite.");
        }
        return new AccountFitAssessment("EXCEEDS_LOSS_APPETITE", requirement.fundingClass(),
                maximumLoss, lossAppetiteCents, capital, available, false, true, 1,
                "Maximum economic downside exceeds the declared per-idea loss appetite.");
    }
}
