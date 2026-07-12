package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Faithful one-time SQLite -> Postgres cutover (the merge-day migration path). */
class SqliteToPostgresEtlTest {

    private Db pg;

    @AfterEach void closeDb() { if (pg != null) pg.close(); }

    /** Builds a legacy SQLite database in its final V1+V2+V3 state, populated with sample data. */
    private String legacySqlite(Path dir) throws SQLException {
        String path = dir.resolve("options-lab.db").toString();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE accounts (
                  id TEXT PRIMARY KEY, name TEXT NOT NULL,
                  type TEXT NOT NULL CHECK (type IN ('PAPER','LIVE')),
                  starting_cash_cents INTEGER NOT NULL, cash_cents INTEGER NOT NULL,
                  reserved_cents INTEGER NOT NULL DEFAULT 0, has_traded INTEGER NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""");
            st.executeUpdate("""
                CREATE TABLE trades (
                  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, symbol TEXT NOT NULL, strategy TEXT NOT NULL,
                  status TEXT NOT NULL, qty INTEGER NOT NULL, legs_json TEXT NOT NULL, thesis TEXT, horizon TEXT,
                  risk_mode TEXT, entry_underlying_cents INTEGER NOT NULL, entry_net_premium_cents INTEGER NOT NULL,
                  max_loss_cents INTEGER NOT NULL, max_profit_cents INTEGER, breakevens_json TEXT NOT NULL,
                  pop_entry REAL, fees_open_cents INTEGER NOT NULL DEFAULT 0, fees_close_cents INTEGER NOT NULL DEFAULT 0,
                  realized_pnl_cents INTEGER, close_reason TEXT, entry_snapshot_json TEXT NOT NULL,
                  is_live INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, closed_at TEXT, updated_at TEXT NOT NULL,
                  intent TEXT, shares_locked INTEGER NOT NULL DEFAULT 0)""");
            st.executeUpdate("""
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL, trade_id TEXT, ts TEXT NOT NULL,
                  type TEXT NOT NULL, amount_cents INTEGER NOT NULL, cash_after_cents INTEGER NOT NULL,
                  reserved_after_cents INTEGER NOT NULL, memo TEXT)""");
            st.executeUpdate("""
                CREATE TABLE trade_marks (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id TEXT NOT NULL, ts TEXT NOT NULL,
                  underlying_px_cents INTEGER, close_cost_cents INTEGER, unrealized_cents INTEGER,
                  pop_now REAL, freshness TEXT, detail_json TEXT)""");
            st.executeUpdate("""
                CREATE TABLE positions (
                  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, symbol TEXT NOT NULL, shares INTEGER NOT NULL,
                  avg_cost_cents INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""");
            st.executeUpdate("""
                CREATE TABLE audit (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, account_id TEXT, trade_id TEXT,
                  action TEXT NOT NULL, level TEXT NOT NULL DEFAULT 'INFO', detail_json TEXT)""");
            st.executeUpdate("CREATE TABLE settings (k TEXT PRIMARY KEY, v TEXT NOT NULL, updated_at TEXT NOT NULL)");

            // Account whose cash/reserve MUST equal its latest ledger row.
            st.executeUpdate("INSERT INTO accounts VALUES ('acct_1','Paper','PAPER',10000000,9950000,20000,1,'2026-01-01T00:00:00Z','2026-07-01T00:00:00Z')");
            // 4 ledger rows ending at cash_after=9950000, reserved_after=20000.
            st.executeUpdate("INSERT INTO ledger (account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo) VALUES "
                    + "('acct_1',NULL,'2026-01-01T00:00:00Z','DEPOSIT',10000000,10000000,0,'open'),"
                    + "('acct_1','tr_1','2026-02-01T00:00:00Z','PREMIUM_OPEN',-45000,9955000,0,'debit'),"
                    + "('acct_1','tr_1','2026-02-01T00:00:00Z','FEE',-5000,9950000,0,'fees'),"
                    + "('acct_1','tr_1','2026-02-01T00:00:00Z','RESERVE_HOLD',20000,9950000,20000,'hold')");
            st.executeUpdate("INSERT INTO trades (id,account_id,symbol,strategy,status,qty,legs_json,entry_underlying_cents,"
                    + "entry_net_premium_cents,max_loss_cents,breakevens_json,entry_snapshot_json,created_at,updated_at,intent,shares_locked) VALUES "
                    + "('tr_1','acct_1','AAPL','COVERED_CALL','ACTIVE',1,'[]',25500,45000,20000,'[]','{}','2026-02-01T00:00:00Z','2026-02-01T00:00:00Z','INCOME',100)");
            st.executeUpdate("INSERT INTO trade_marks (trade_id,ts,underlying_px_cents,close_cost_cents,unrealized_cents,pop_now,freshness) VALUES "
                    + "('tr_1','2026-03-01T00:00:00Z',26000,-40000,5000,0.62,'DELAYED')");
            st.executeUpdate("INSERT INTO positions VALUES ('pos_1','acct_1','AAPL',100,25000,'2026-01-15T00:00:00Z','2026-01-15T00:00:00Z')");
            st.executeUpdate("INSERT INTO audit (ts,account_id,trade_id,action,level,detail_json) VALUES "
                    + "('2026-02-01T00:00:00Z','acct_1','tr_1','TRADE_OPEN','INFO','{}')");
            st.executeUpdate("INSERT INTO settings VALUES ('universe.sector','CORE','2026-06-01T00:00:00Z')");
        }
        return path;
    }

    @Test void migratesFaithfullyAndVerifies(@TempDir Path dir) throws SQLException {
        String sqlitePath = legacySqlite(dir);
        pg = TestDb.fresh();

        var result = SqliteToPostgresEtl.runFromFile(sqlitePath, pg);

        // Clean migration, no problems.
        assertThat(result.problems()).isEmpty();
        assertThat(result.ok()).isTrue();

        // Row counts preserved per table.
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("ledger")).isEqualTo(4);
        assertThat(count("trades")).isEqualTo(1);
        assertThat(count("trade_marks")).isEqualTo(1);
        assertThat(count("positions")).isEqualTo(1);
        assertThat(count("audit")).isEqualTo(1);
        assertThat(count("settings")).isEqualTo(1);

        // Ledger invariant: account cash/reserve equal the latest ledger row.
        var acct = pg.query("SELECT cash_cents, reserved_cents, user_id FROM accounts WHERE id='acct_1'",
                r -> new Object[]{r.lng("cash_cents"), r.lng("reserved_cents"), r.str("user_id")}).getFirst();
        assertThat(acct[0]).isEqualTo(9950000L);
        assertThat(acct[1]).isEqualTo(20000L);
        // Postgres-only column (absent in source) stays null.
        assertThat(acct[2]).isNull();

        // Exact values survived the copy (ids, intent/shares_locked from V2, doubles).
        var trade = pg.query("SELECT intent, shares_locked, max_loss_cents FROM trades WHERE id='tr_1'",
                r -> r.str("intent") + "|" + r.intv("shares_locked") + "|" + r.lng("max_loss_cents")).getFirst();
        assertThat(trade).isEqualTo("INCOME|100|20000");
        assertThat(pg.query("SELECT pop_now FROM trade_marks WHERE trade_id='tr_1'", r -> r.dbl("pop_now")).getFirst())
                .isEqualTo(0.62);
        // Ledger ids preserved verbatim.
        assertThat(pg.query("SELECT string_agg(id::text, ',' ORDER BY id) AS ids FROM ledger", r -> r.str("ids")).getFirst())
                .isEqualTo("1,2,3,4");
    }

    @Test void resetsIdentitySequencesSoNewInsertsDoNotCollide(@TempDir Path dir) throws SQLException {
        String sqlitePath = legacySqlite(dir);
        pg = TestDb.fresh();
        SqliteToPostgresEtl.runFromFile(sqlitePath, pg);

        // A new ledger row (no explicit id) must land AFTER the migrated max (4), not collide at 1.
        pg.exec("INSERT INTO ledger (account_id, ts, type, amount_cents, cash_after_cents, reserved_after_cents) "
                + "VALUES (?,?,?,?,?,?)", "acct_1", "2026-07-08T00:00:00Z", "ADJUSTMENT", 0L, 9950000L, 20000L);
        long newMax = pg.query("SELECT MAX(id) AS m FROM ledger", r -> r.lng("m")).getFirst();
        assertThat(newMax).isEqualTo(5L);
    }

    private long count(String table) {
        return pg.query("SELECT count(*) AS c FROM " + table, r -> r.lng("c")).getFirst();
    }
}
