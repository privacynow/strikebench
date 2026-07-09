package io.liftandshift.strikebench.paper;

/** A paper (or, later, shadow-live) account. All balances are exact integer cents. */
public record Account(
        String id,
        String name,
        String type,
        long startingCashCents,
        long cashCents,
        long reservedCents,
        boolean hasTraded,
        String createdAt,
        String updatedAt
) {
    public long buyingPowerCents() { return cashCents - reservedCents; }
}
