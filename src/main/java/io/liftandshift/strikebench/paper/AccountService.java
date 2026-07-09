package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Ids;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Single default paper account: creation, lookup, and confirmed resets. */
public final class AccountService {

    private final Db db;
    private final AppConfig cfg;
    private final AuditLog audit;
    private final Clock clock;

    public AccountService(Db db, AppConfig cfg, AuditLog audit, Clock clock) {
        this.db = db;
        this.cfg = cfg;
        this.audit = audit;
        this.clock = clock;
    }

    public Account getOrCreateDefault() {
        return db.tx(c -> {
            List<Account> existing = Db.queryOn(c, "SELECT * FROM accounts WHERE type='PAPER' ORDER BY created_at LIMIT 1", AccountService::map);
            if (!existing.isEmpty()) return existing.getFirst();
            String id = Ids.account();
            long cash = cfg.defaultStartingCashCents();
            String now = now();
            Db.execOn(c, "INSERT INTO accounts(id,name,type,starting_cash_cents,cash_cents,reserved_cents,has_traded,created_at,updated_at) VALUES (?,?,?,?,?,?,0,?,?)",
                    id, "Paper Account", "PAPER", cash, cash, 0, now, now);
            Db.execOn(c, "INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo) VALUES (?,?,?,?,?,?,?,?)",
                    id, null, now, "DEPOSIT", cash, cash, 0, "initial paper funding");
            return get(c, id);
        });
    }

    public Account get(String id) {
        return db.with(c -> get(c, id));
    }

    static Account get(Connection c, String id) throws SQLException {
        List<Account> rows = Db.queryOn(c, "SELECT * FROM accounts WHERE id=?", AccountService::map, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("no such account " + id);
        return rows.getFirst();
    }

    /**
     * Sets cash to startingCash and clears the reserve. Requires confirm; blocked once the
     * account has traded unless force. Ledger stays append-only: open trades are voided with
     * reserve releases, then a single RESET row absorbs the cash difference.
     */
    public Account reset(long startingCashCents, boolean confirm, boolean force) {
        if (!confirm) throw new IllegalArgumentException("reset requires confirm=true");
        if (startingCashCents <= 0) throw new IllegalArgumentException("startingCash must be positive");
        Account result = db.tx(c -> {
            Account acct = getOrCreateDefaultOn(c);
            if (acct.hasTraded() && !force) {
                throw new IllegalStateException("account has trades; pass force=true to reset anyway");
            }
            String now = now();
            long cash = acct.cashCents();
            long reserved = acct.reservedCents();

            // Void open trades and release their reserve
            List<String> openTrades = Db.queryOn(c, "SELECT id FROM trades WHERE account_id=? AND status='ACTIVE'", r -> r.str("id"), acct.id());
            for (String tradeId : openTrades) {
                long tradeReserve = TradeService.outstandingReserve(c, tradeId);
                if (tradeReserve != 0) {
                    reserved -= tradeReserve;
                    Db.execOn(c, "INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo) VALUES (?,?,?,?,?,?,?,?)",
                            acct.id(), tradeId, now, "RESERVE_RELEASE", -tradeReserve, cash, reserved, "released by account reset");
                }
                Db.execOn(c, "UPDATE trades SET status='DELETED', close_reason='RESET', closed_at=?, updated_at=? WHERE id=?", now, now, tradeId);
            }

            // Equity positions vanish with the reset — the RESET row absorbs their cash value
            Db.execOn(c, "DELETE FROM positions WHERE account_id=?", acct.id());

            long diff = startingCashCents - cash;
            cash = startingCashCents;
            Db.execOn(c, "INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo) VALUES (?,?,?,?,?,?,?,?)",
                    acct.id(), null, now, "RESET", diff, cash, reserved, "account reset");
            Db.execOn(c, "UPDATE accounts SET cash_cents=?, reserved_cents=?, starting_cash_cents=?, has_traded=0, updated_at=? WHERE id=?",
                    cash, reserved, startingCashCents, now, acct.id());
            return get(c, acct.id());
        });
        audit.log(result.id(), null, "ACCOUNT_RESET", "INFO", Map.of("startingCashCents", startingCashCents, "force", force));
        return result;
    }

    private Account getOrCreateDefaultOn(Connection c) throws SQLException {
        List<Account> existing = Db.queryOn(c, "SELECT * FROM accounts WHERE type='PAPER' ORDER BY created_at LIMIT 1", AccountService::map);
        if (!existing.isEmpty()) return existing.getFirst();
        throw new IllegalStateException("no paper account exists yet");
    }

    public List<LedgerEntry> ledger(String accountId, int page, int size) {
        int offset = Math.max(0, page) * size;
        return db.query("SELECT * FROM ledger WHERE account_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                AccountService::mapLedger, accountId, size, offset);
    }

    static Account map(Db.Row r) {
        return new Account(r.str("id"), r.str("name"), r.str("type"),
                r.lng("starting_cash_cents"), r.lng("cash_cents"), r.lng("reserved_cents"),
                r.bool("has_traded"), r.str("created_at"), r.str("updated_at"));
    }

    static LedgerEntry mapLedger(Db.Row r) {
        return new LedgerEntry(r.lng("id"), r.str("account_id"), r.str("trade_id"), r.str("ts"),
                r.str("type"), r.lng("amount_cents"), r.lng("cash_after_cents"), r.lng("reserved_after_cents"), r.str("memo"));
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
