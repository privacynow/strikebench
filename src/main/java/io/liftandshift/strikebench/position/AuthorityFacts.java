package io.liftandshift.strikebench.position;

/**
 * One authority-bearing grammar for monetary and rate facts shared by lifecycle, account,
 * and Book receipts. Availability and provenance travel with the number; an unavailable fact
 * can never silently become zero.
 */
public final class AuthorityFacts {
    private AuthorityFacts() {}

    public record MoneyFact(Long cents, PositionDomain.FactAuthority authority, String basis) {
        public MoneyFact {
            if (authority == null) throw new IllegalArgumentException("money-fact authority is required");
            required(basis, "money-fact basis");
            if (authority == PositionDomain.FactAuthority.UNAVAILABLE && cents != null) {
                throw new IllegalArgumentException("an unavailable money fact cannot claim an amount");
            }
            if (authority != PositionDomain.FactAuthority.UNAVAILABLE && cents == null) {
                throw new IllegalArgumentException("an available money fact needs an amount");
            }
            nonNegative(cents, "money fact");
        }

        public static MoneyFact unavailable(String reason) {
            return new MoneyFact(null, PositionDomain.FactAuthority.UNAVAILABLE, reason);
        }
    }

    public record RateFact(Double annualRatePct, Long concurrentIncomeCents,
                           PositionDomain.FactAuthority authority, String basis) {
        public RateFact {
            if (authority == null) throw new IllegalArgumentException("rate-fact authority is required");
            required(basis, "rate-fact basis");
            if (authority == PositionDomain.FactAuthority.UNAVAILABLE
                    && (annualRatePct != null || concurrentIncomeCents != null)) {
                throw new IllegalArgumentException("an unavailable rate fact cannot claim a rate or income");
            }
            if (authority != PositionDomain.FactAuthority.UNAVAILABLE && annualRatePct == null) {
                throw new IllegalArgumentException("an available rate fact needs a rate");
            }
            if (annualRatePct != null && (!Double.isFinite(annualRatePct) || annualRatePct < 0)) {
                throw new IllegalArgumentException("annual rate must be finite and non-negative");
            }
            nonNegative(concurrentIncomeCents, "concurrent income");
        }

        public static RateFact unavailable(String reason) {
            return new RateFact(null, null, PositionDomain.FactAuthority.UNAVAILABLE, reason);
        }
    }

    /** Signed deltas such as reconciliation differences; unlike balances, these may be negative. */
    public record SignedMoneyFact(Long cents, PositionDomain.FactAuthority authority, String basis) {
        public SignedMoneyFact {
            if (authority == null) throw new IllegalArgumentException("signed-money authority is required");
            required(basis, "signed-money basis");
            if (authority == PositionDomain.FactAuthority.UNAVAILABLE && cents != null) {
                throw new IllegalArgumentException("an unavailable signed-money fact cannot claim an amount");
            }
            if (authority != PositionDomain.FactAuthority.UNAVAILABLE && cents == null) {
                throw new IllegalArgumentException("an available signed-money fact needs an amount");
            }
        }

        public static SignedMoneyFact unavailable(String reason) {
            return new SignedMoneyFact(null, PositionDomain.FactAuthority.UNAVAILABLE, reason);
        }
    }

    private static void required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    }

    private static void nonNegative(Long value, String label) {
        if (value != null && value < 0) throw new IllegalArgumentException(label + " cannot be negative");
    }
}
