package io.liftandshift.strikebench.recommend;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
        ACQUISITION_TARGET
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

    /** One authority for whether these shares are verified account holdings. */
    @JsonIgnore
    public boolean isAccountBacked() {
        return provenance == Provenance.ACCOUNT_BACKED && destinationAccountId != null;
    }

    public boolean matchesDestination(String accountId) {
        return isAccountBacked() && accountId != null
                && destinationAccountId.equals(accountId.trim());
    }

    public static HoldingsEvidence forProvenance(
            Provenance provenance, Integer shares, Long basisCents,
            String destinationAccountId, String custodyType, Long observedAtEpochMs) {
        if (provenance == null) return null;
        return switch (provenance) {
            case ACCOUNT_BACKED -> new HoldingsEvidence(provenance, shares, basisCents,
                    defaultBasis(provenance), destinationAccountId, custodyType,
                    observedAtEpochMs);
            case HYPOTHETICAL_HOLDINGS, ACQUISITION_TARGET ->
                    new HoldingsEvidence(provenance, shares, basisCents,
                            defaultBasis(provenance), null, null, null);
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
        };
    }
}
