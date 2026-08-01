package io.liftandshift.strikebench.recommend;

/**
 * Provenance for share context used to construct and assess an option package.
 *
 * <p>A share count changes the mechanics of covered calls, collars, protective puts, and covered
 * strangles. It therefore cannot remain an unlabeled number. Account-backed shares may support an
 * endorsement and an order. Hypothetical shares are useful for education and analysis, but must
 * remain comparisons and cannot be pledged by a real order. ACQUISITION_TARGET is the distinct
 * ACQUIRE meaning: shares the user wants, not shares the user claims to own.</p>
 */
public record HoldingsEvidence(
        Provenance provenance,
        Integer shares,
        Long costBasisCents,
        String basis,
        String destinationAccountId,
        String custodyType,
        Long observedAtEpochMs
) {
    public enum Provenance {
        ACCOUNT_BACKED,
        HYPOTHETICAL_HOLDINGS,
        ACQUISITION_TARGET,
        /**
         * A readable pre-provenance result. The package remains available for inspection, but
         * the historical row did not capture enough evidence to pledge shares or endorse it.
         */
        LEGACY_UNVERIFIED
    }

    public HoldingsEvidence {
        if (provenance == null) {
            throw new IllegalArgumentException("holdings evidence requires provenance");
        }
        if (shares != null && shares < 0) {
            throw new IllegalArgumentException("holdings evidence shares cannot be negative");
        }
        if (costBasisCents != null && costBasisCents < 0) {
            throw new IllegalArgumentException("holdings evidence cost basis cannot be negative");
        }
        destinationAccountId = blankToNull(destinationAccountId);
        custodyType = blankToNull(custodyType);
        basis = basis == null || basis.isBlank() ? defaultBasis(provenance) : basis;
    }

    /** Compatibility shape for non-custody evidence. ACCOUNT_BACKED remains deliberately unbound. */
    public HoldingsEvidence(Provenance provenance, Integer shares, Long costBasisCents, String basis) {
        this(provenance, shares, costBasisCents, basis, null, null, null);
    }

    public static HoldingsEvidence accountBacked(Integer shares, Long basisCents) {
        return new HoldingsEvidence(Provenance.ACCOUNT_BACKED, shares, basisCents,
                defaultBasis(Provenance.ACCOUNT_BACKED));
    }

    public static HoldingsEvidence accountBacked(
            Integer shares, Long basisCents, String destinationAccountId,
            String custodyType, Long observedAtEpochMs) {
        return new HoldingsEvidence(Provenance.ACCOUNT_BACKED, shares, basisCents,
                defaultBasis(Provenance.ACCOUNT_BACKED), destinationAccountId,
                custodyType, observedAtEpochMs);
    }

    public static HoldingsEvidence hypothetical(Integer shares, Long basisCents) {
        return new HoldingsEvidence(Provenance.HYPOTHETICAL_HOLDINGS, shares, basisCents,
                defaultBasis(Provenance.HYPOTHETICAL_HOLDINGS));
    }

    public static HoldingsEvidence acquisitionTarget(Integer shares, Long basisCents) {
        return new HoldingsEvidence(Provenance.ACQUISITION_TARGET, shares, basisCents,
                defaultBasis(Provenance.ACQUISITION_TARGET));
    }

    public static HoldingsEvidence legacyUnverified(Integer shares, Long basisCents) {
        return new HoldingsEvidence(Provenance.LEGACY_UNVERIFIED, shares, basisCents,
                defaultBasis(Provenance.LEGACY_UNVERIFIED));
    }

    /** Eligibility is derived exclusively from provenance; callers cannot publish contradictory flags. */
    @com.fasterxml.jackson.annotation.JsonProperty("endorsementEligible")
    public boolean endorsementEligible() {
        return provenance == Provenance.ACCOUNT_BACKED && destinationAccountId != null;
    }

    /** Eligibility is derived exclusively from provenance; callers cannot publish contradictory flags. */
    @com.fasterxml.jackson.annotation.JsonProperty("placementEligible")
    public boolean placementEligible() {
        return endorsementEligible();
    }

    public boolean matchesDestination(String accountId) {
        return placementEligible() && accountId != null
                && destinationAccountId.equals(accountId.trim());
    }

    public static HoldingsEvidence forProvenance(
            Provenance provenance, Integer shares, Long basisCents) {
        return forProvenance(provenance, shares, basisCents, null, null, null);
    }

    public static HoldingsEvidence forProvenance(
            Provenance provenance, Integer shares, Long basisCents,
            String destinationAccountId, String custodyType, Long observedAtEpochMs) {
        if (provenance == null) return null;
        return switch (provenance) {
            case ACCOUNT_BACKED -> accountBacked(shares, basisCents, destinationAccountId,
                    custodyType, observedAtEpochMs);
            case HYPOTHETICAL_HOLDINGS -> hypothetical(shares, basisCents);
            case ACQUISITION_TARGET -> acquisitionTarget(shares, basisCents);
            case LEGACY_UNVERIFIED -> legacyUnverified(shares, basisCents);
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultBasis(Provenance provenance) {
        return switch (provenance) {
            case ACCOUNT_BACKED ->
                    "Shares and basis were resolved from the destination account.";
            case HYPOTHETICAL_HOLDINGS ->
                    "Shares and basis are a user-supplied hypothetical used only for analysis.";
            case ACQUISITION_TARGET ->
                    "Shares state the requested acquisition size; they are not an owned holding.";
            case LEGACY_UNVERIFIED ->
                    "This historical result predates holdings provenance; its package is readable, "
                            + "but its shares cannot authorize endorsement or placement.";
        };
    }
}
