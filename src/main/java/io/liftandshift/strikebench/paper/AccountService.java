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

    /**
     * The paper account for a given signed-in user. When auth is off the userId is
     * {@link io.liftandshift.strikebench.auth.AuthService#LOCAL_USER} (or null) and this is exactly
     * {@link #getOrCreateDefault()} — the single shared account, unchanged. For a real user it
     * returns their account; the FIRST real user to arrive claims the pre-auth (unclaimed) account
     * so the owner keeps their history; later users get a fresh funded account.
     */
    public Account getOrCreateDefaultForUser(String userId) {
        if (userId == null || io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(userId)) {
            return getOrCreateDefault();
        }
        return db.tx(c -> {
            List<Account> mine = Db.queryOn(c,
                    "SELECT * FROM accounts WHERE user_id=? AND type='PAPER' ORDER BY created_at LIMIT 1",
                    AccountService::map, userId);
            if (!mine.isEmpty()) return mine.getFirst();

            String now = now();
            List<String> orphan = Db.queryOn(c,
                    "SELECT id FROM accounts WHERE user_id IS NULL AND type='PAPER' ORDER BY created_at LIMIT 1",
                    r -> r.str("id"));
            if (!orphan.isEmpty()) {
                // Claim atomically: the WHERE user_id IS NULL guard + rowcount makes a concurrent
                // first sign-in (which blocks on the row lock, then sees it claimed) fall through
                // rather than double-adopt the same account.
                int claimed = Db.execOn(c, "UPDATE accounts SET user_id=?, updated_at=? WHERE id=? AND user_id IS NULL",
                        userId, now, orphan.getFirst());
                if (claimed == 1) return get(c, orphan.getFirst());
                List<Account> mineNow = Db.queryOn(c,
                        "SELECT * FROM accounts WHERE user_id=? AND type='PAPER' ORDER BY created_at LIMIT 1",
                        AccountService::map, userId);
                if (!mineNow.isEmpty()) return mineNow.getFirst();
            }
            String id = Ids.account();
            long cash = cfg.defaultStartingCashCents();
            Db.execOn(c, "INSERT INTO accounts(id,user_id,name,type,starting_cash_cents,cash_cents,reserved_cents,has_traded,created_at,updated_at) VALUES (?,?,?,?,?,?,?,0,?,?)",
                    id, userId, "Paper Account", "PAPER", cash, cash, 0, now, now);
            Db.execOn(c, "INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo) VALUES (?,?,?,?,?,?,?,?)",
                    id, null, now, "DEPOSIT", cash, cash, 0, "initial paper funding");
            return get(c, id);
        });
    }

    /** A separate, owner-scoped account for the explicit built-in Demo market. */
    public Account getOrCreateDemoForUser(String userId) {
        String owner = userId == null || io.liftandshift.strikebench.auth.AuthService.LOCAL_USER.equals(userId)
                ? null : userId;
        return db.tx(c -> {
            List<Account> existing = owner == null
                    ? Db.queryOn(c, "SELECT * FROM accounts WHERE user_id IS NULL AND type='DEMO' ORDER BY created_at LIMIT 1",
                            AccountService::map)
                    : Db.queryOn(c, "SELECT * FROM accounts WHERE user_id=? AND type='DEMO' ORDER BY created_at LIMIT 1",
                            AccountService::map, owner);
            if (!existing.isEmpty()) return existing.getFirst();
            String id = Ids.account();
            long cash = cfg.defaultStartingCashCents();
            String now = now();
            Db.execOn(c, "INSERT INTO accounts(id,user_id,name,type,starting_cash_cents,cash_cents,reserved_cents,"
                            + "has_traded,created_at,updated_at) VALUES (?,?,?,?,?,?,?,0,?,?)",
                    id, owner, "Demo Account", "DEMO", cash, cash, 0, now, now);
            Db.execOn(c, "INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,"
                            + "reserved_after_cents,memo) VALUES (?,?,?,?,?,?,?,?)",
                    id, null, now, "DEPOSIT", cash, cash, 0, "initial demo funding");
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
     * Serializes every mutation of one account's cash, reserve, positions, and pledged shares.
     * Services must take this lock before inspecting any of those dependent rows; otherwise two
     * concurrent requests can both spend the same buying power or pledge the same share lot.
     */
    static Account getForUpdate(Connection c, String id) throws SQLException {
        List<Account> rows = Db.queryOn(c, "SELECT * FROM accounts WHERE id=? FOR UPDATE", AccountService::map, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("no such account " + id);
        return rows.getFirst();
    }

    /**
     * Sets cash to startingCash and clears the reserve. Requires confirm; blocked once the
     * account has traded unless force. Ledger stays append-only: open trades are voided with
     * reserve releases, then a single RESET row absorbs the cash difference.
     */
    /** Resets the single default account (legacy/no-scope entry — used when auth is off). */
    public Account reset(long startingCashCents, boolean confirm, boolean force) {
        return resetAccount(getOrCreateDefault().id(), startingCashCents, confirm, force);
    }

    /** Resets a SPECIFIC account by id — the per-user entry so a user only ever resets their own. */
    public Account resetAccount(String accountId, long startingCashCents, boolean confirm, boolean force) {
        if (!confirm) throw new IllegalArgumentException("reset requires confirm=true");
        if (startingCashCents <= 0) throw new IllegalArgumentException("startingCash must be positive");
        Account result = db.tx(c -> {
            Account acct = getForUpdate(c, accountId);
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

    public List<LedgerEntry> ledger(String accountId, int page, int size) {
        int offset = Math.max(0, page) * size;
        return db.query("SELECT * FROM ledger WHERE account_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                AccountService::mapLedger, accountId, size, offset);
    }

    /** The SIMULATION account for a world — the ONLY lane allowed to trade against it. */
    /** On-connection variant for SimulationSessions.createAtomic — one transaction, no orphans. */
    public String createForWorldOn(java.sql.Connection c, String worldId, String name) throws java.sql.SQLException {
        String id = io.liftandshift.strikebench.util.Ids.newId("acct");
        String now = java.time.Instant.now().toString();
        Db.execOn(c, "INSERT INTO accounts(id,name,type,starting_cash_cents,cash_cents,reserved_cents,has_traded,created_at,updated_at,world_id) "
                        + "VALUES (?,?,?,?,?,?,0,?,?,?)",
                id, name == null ? "Simulation" : name, "SIMULATION",
                10_000_000L, 10_000_000L, 0L, now, now, worldId);
        return id;
    }

    public Account getOrCreateForWorld(String worldId, String name) {
        var rows = db.query("SELECT * FROM accounts WHERE world_id=? LIMIT 1", AccountService::map, worldId);
        if (!rows.isEmpty()) return rows.getFirst();
        String id = io.liftandshift.strikebench.util.Ids.newId("acct");
        String now = java.time.Instant.now().toString();
        db.exec("INSERT INTO accounts(id,name,type,starting_cash_cents,cash_cents,reserved_cents,has_traded,created_at,updated_at,world_id) "
                        + "VALUES (?,?,?,?,?,?,0,?,?,?)",
                id, name == null ? "Simulation" : name, "SIMULATION",
                10_000_000L, 10_000_000L, 0L, now, now, worldId);
        return get(id);
    }

    static Account map(Db.Row r) {
        return new Account(r.str("id"), r.str("name"), r.str("type"),
                r.lng("starting_cash_cents"), r.lng("cash_cents"), r.lng("reserved_cents"),
                r.bool("has_traded"), r.str("created_at"), r.str("updated_at"), r.str("world_id"));
    }

    static LedgerEntry mapLedger(Db.Row r) {
        return new LedgerEntry(r.lng("id"), r.str("account_id"), r.str("trade_id"), r.str("ts"),
                r.str("type"), r.lng("amount_cents"), r.lng("cash_after_cents"), r.lng("reserved_after_cents"), r.str("memo"));
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
