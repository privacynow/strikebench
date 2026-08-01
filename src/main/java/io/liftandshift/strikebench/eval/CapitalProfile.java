package io.liftandshift.strikebench.eval;

/**
 * Capital efficiency, both ways so neither hides the other:
 *  - incrementalCents: exact opening buying power this trade consumes, including the captured
 *    opening commission and netting the opening cash flow against the reserve.
 *  - economicCents: the full economic exposure (e.g. a covered call ties up the share value, not
 *    just the option's margin). Ranking on incremental alone flatters share-backed trades.
 * annualizedRocPct carries a repeat-the-trade assumption and is ALWAYS a labeled component, never
 * the primary rank.
 */
public record CapitalProfile(
        Long incrementalCents,
        Long economicCents,
        /** The producer result from which both capital amounts above are projected. */
        io.liftandshift.strikebench.strategy.CapitalRequirement requirement,
        AccountFitAssessment accountFit,
        Double returnOnCapitalPct,   // best-case return on economic exposure, null if uncapped/unknown
        Double annualizedRocPct,     // ROC scaled by normalized OptionTime model years; labeled, never primary
        int daysToExpiry,
        String basis,                // human note on what economic exposure represents
        String annualizationNote
) {
    public CapitalProfile {
        if (basis == null || basis.isBlank()) throw new IllegalArgumentException("capital basis is required");
        if (requirement != null && (!java.util.Objects.equals(
                incrementalCents, requirement.buyingPowerRequiredCents())
                || !java.util.Objects.equals(economicCents, requirement.economicExposureCents()))) {
            throw new IllegalArgumentException(
                    "capital profile projections must match the package capital requirement");
        }
        if (annualizedRocPct != null && (annualizationNote == null || annualizationNote.isBlank())) {
            throw new IllegalArgumentException("annualized return requires its repeatability disclosure");
        }
    }
}
