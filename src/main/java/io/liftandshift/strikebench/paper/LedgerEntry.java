package io.liftandshift.strikebench.paper;

/**
 * One append-only ledger row. amount_cents is signed; for RESERVE_HOLD/RESERVE_RELEASE it
 * moves the reserve (not cash). Every row snapshots both balances after application.
 */
public record LedgerEntry(
        long id,
        String accountId,
        String tradeId,
        String ts,
        String type,
        long amountCents,
        long cashAfterCents,
        long reservedAfterCents,
        String memo
) {
    /** Ledger types that move cash; the rest move only the reserve. */
    public static boolean movesCash(String type) {
        return switch (type) {
            case "DEPOSIT", "RESET", "PREMIUM_OPEN", "PREMIUM_CLOSE", "SETTLEMENT", "FEE", "ADJUSTMENT",
                 "STOCK_BUY", "STOCK_SELL" -> true;
            case "RESERVE_HOLD", "RESERVE_RELEASE" -> false;
            default -> throw new IllegalArgumentException("unknown ledger type " + type);
        };
    }
}
