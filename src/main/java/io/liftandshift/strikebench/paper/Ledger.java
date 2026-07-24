package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * THE one home for the append-only ledger's SQL: the INSERT, the row mapper, and the reserve-type
 * predicate. The insert was hand-inlined in AccountService (×5) and in a private helper each in
 * TradeService and PositionsService; the mapper and the outstanding-reserve read were reached
 * cross-service. Package-private in {@code paper}; every caller lives here.
 *
 * <p>The caller supplies {@code ts} (an ISO string from its own clock) and the post-application
 * {@code cash_after}/{@code reserved_after} snapshots — this seam never stamps a clock or recomputes
 * a balance, so the ledger row's {@code ts} stays equal to the paired {@code accounts.updated_at}.
 * The auto-serial {@code id} is never written (history {@code ORDER BY id} and reversal memos depend
 * on insertion order).
 */
final class Ledger {

    private Ledger() {}

    static final String INSERT_SQL =
            "INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo) "
          + "VALUES (?,?,?,?,?,?,?,?)";
    static final String RESERVE_TYPES = "('RESERVE_HOLD','RESERVE_RELEASE')";

    /** The one canonical append. {@code tradeId} may be null (account-level DEPOSIT/RESET & equity rows). */
    static void append(Connection c, String accountId, String tradeId, String ts, String type,
                       long amountCents, long cashAfterCents, long reservedAfterCents, String memo)
            throws SQLException {
        Db.execOn(c, INSERT_SQL, accountId, tradeId, ts, type,
                amountCents, cashAfterCents, reservedAfterCents, memo);
    }

    /** Canonical LedgerEntry mapper (relocated from AccountService.mapLedger). */
    static LedgerEntry map(Db.Row r) {
        return new LedgerEntry(r.lng("id"), r.str("account_id"), r.str("trade_id"), r.str("ts"),
                r.str("type"), r.lng("amount_cents"), r.lng("cash_after_cents"),
                r.lng("reserved_after_cents"), r.str("memo"));
    }

    /** Outstanding reserve for ONE trade (relocated from TradeService.outstandingReserve). */
    static long outstandingReserve(Connection c, String tradeId) throws SQLException {
        return Db.queryOn(c,
                "SELECT COALESCE(SUM(amount_cents),0) AS n FROM ledger WHERE trade_id=? AND type IN " + RESERVE_TYPES,
                r -> r.lng("n"), tradeId).getFirst();
    }
}
