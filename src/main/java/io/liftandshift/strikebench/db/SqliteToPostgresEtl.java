package io.liftandshift.strikebench.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-time faithful migration of the legacy SQLite paper-trading database into the reworked
 * PostgreSQL schema — the merge-day cutover path (supersedes {@code Main.migrateLegacyDefaultDb}).
 *
 * The copy is schema-introspecting, not hard-coded: for each table it copies the INTERSECTION of
 * the SQLite and Postgres columns, so Postgres-only additions (accounts.user_id, backtests.user_id)
 * simply keep their defaults. Values are coerced to the exact Postgres column type so nothing is
 * silently truncated or type-mismatched. The whole load runs in ONE transaction — any error rolls
 * the entire migration back, never leaving a half-migrated database.
 *
 * The target Postgres database MUST be freshly migrated and EMPTY (no auto-created default account);
 * a primary-key collision aborts the run rather than merging into live data.
 *
 * After the copy, it VERIFIES per-table row counts and the ledger invariant (each account's current
 * cash/reserve equals its latest ledger row) and reports every problem it finds.
 */
public final class SqliteToPostgresEtl {

    private static final Logger log = LoggerFactory.getLogger("io.liftandshift.strikebench.data.LegacyImport");

    /** FK-safe insertion order (accounts before its children; trades before trade_marks). */
    private static final List<String> TABLES = List.of(
            "accounts", "trades", "positions", "ledger", "trade_marks",
            "audit", "secrets", "settings", "live_orders", "backtests");

    /** Tables whose {@code id} is a Postgres IDENTITY we insert explicitly, then bump the sequence. */
    private static final Set<String> IDENTITY_TABLES = Set.of("ledger", "trade_marks", "audit");

    private SqliteToPostgresEtl() {}

    public record TableResult(String table, int copied) {}

    public record EtlResult(List<TableResult> tables, List<String> checks, List<String> problems) {
        public boolean ok() { return problems.isEmpty(); }
        public int totalRows() { return tables.stream().mapToInt(TableResult::copied).sum(); }
    }

    /** Migrates {@code data/foo.db} — pass a filesystem path — into the given Postgres pool. */
    public static EtlResult runFromFile(String sqlitePath, Db pg) {
        return run("jdbc:sqlite:" + sqlitePath, pg);
    }

    public static EtlResult run(String sqliteJdbcUrl, Db pg) {
        List<TableResult> results = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        try (Connection src = DriverManager.getConnection(sqliteJdbcUrl)) {
            // Atomic load: one PG transaction for the whole copy.
            pg.tx(dst -> {
                for (String table : TABLES) {
                    if (!sqliteHasTable(src, table)) {
                        checks.add(table + ": absent in source (skipped)");
                        continue;
                    }
                    Map<String, String> pgTypes = pgColumnTypes(dst, table);
                    List<String> cols = new ArrayList<>();
                    for (String c : sqliteColumns(src, table)) {
                        if (pgTypes.containsKey(c)) cols.add(c);
                    }
                    if (cols.isEmpty()) { problems.add(table + ": no common columns"); continue; }
                    int copied = copyTable(src, dst, table, cols, pgTypes);
                    results.add(new TableResult(table, copied));
                    if (IDENTITY_TABLES.contains(table) && cols.contains("id")) resetIdentity(dst, table);
                }
                return null;
            });

            // Verify AFTER commit: source vs destination counts.
            for (TableResult tr : results) {
                int srcN = scalarInt(src, "SELECT count(*) FROM " + tr.table());
                long dstN = pg.query("SELECT count(*) AS c FROM " + tr.table(), r -> r.lng("c")).getFirst();
                checks.add(tr.table() + ": source=" + srcN + " dest=" + dstN);
                if (srcN != dstN) problems.add("row-count mismatch on " + tr.table() + " (src=" + srcN + ", dst=" + dstN + ")");
            }
            verifyLedgerInvariant(pg, checks, problems);
        } catch (SQLException e) {
            throw new Db.DbException(e);
        }

        if (problems.isEmpty()) {
            log.info("Legacy data import verified: {} rows across {} data groups", totalRows(results), results.size());
        } else {
            log.error("Legacy data import found {} problem(s): {}", problems.size(), problems);
        }
        return new EtlResult(results, checks, problems);
    }

    private static int totalRows(List<TableResult> r) { return r.stream().mapToInt(TableResult::copied).sum(); }

    // ---- copy ----

    private static int copyTable(Connection src, Connection dst, String table,
                                 List<String> cols, Map<String, String> pgTypes) throws SQLException {
        String colList = String.join(",", cols);
        String placeholders = String.join(",", java.util.Collections.nCopies(cols.size(), "?"));
        String insert = "INSERT INTO " + table + " (" + colList + ") VALUES (" + placeholders + ")";
        int n = 0;
        try (Statement sel = src.createStatement();
             ResultSet rs = sel.executeQuery("SELECT " + colList + " FROM " + table);
             PreparedStatement ins = dst.prepareStatement(insert)) {
            while (rs.next()) {
                for (int i = 0; i < cols.size(); i++) {
                    ins.setObject(i + 1, coerce(rs.getObject(cols.get(i)), pgTypes.get(cols.get(i))));
                }
                ins.addBatch();
                if (++n % 500 == 0) ins.executeBatch();
            }
            ins.executeBatch();
        }
        return n;
    }

    /** Coerces a SQLite value to the exact Postgres column type (SQLite is dynamically typed). */
    private static Object coerce(Object v, String pgType) {
        if (v == null || pgType == null) return v;
        return switch (pgType) {
            case "integer", "smallint" -> ((Number) num(v)).intValue();
            case "bigint" -> ((Number) num(v)).longValue();
            case "double precision", "real" -> ((Number) num(v)).doubleValue();
            case "numeric" -> v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
            case "timestamp with time zone" -> timestamp(v.toString());
            default -> v.toString(); // text / varchar / etc.
        };
    }

    private static java.time.OffsetDateTime timestamp(String raw) {
        try {
            return java.time.OffsetDateTime.parse(raw);
        } catch (java.time.format.DateTimeParseException noOffset) {
            return java.time.LocalDateTime.parse(raw).atOffset(java.time.ZoneOffset.UTC);
        }
    }

    private static Number num(Object v) {
        return v instanceof Number n ? n : new BigDecimal(v.toString());
    }

    private static void resetIdentity(Connection dst, String table) throws SQLException {
        // table comes from the fixed IDENTITY_TABLES set — safe to inline as an identifier.
        String sql = "SELECT setval(pg_get_serial_sequence('" + table + "','id'), "
                + "COALESCE((SELECT MAX(id) FROM " + table + "),1), "
                + "(SELECT MAX(id) FROM " + table + ") IS NOT NULL)";
        try (Statement st = dst.createStatement()) { st.execute(sql); }
    }

    // ---- verification ----

    private static void verifyLedgerInvariant(Db pg, List<String> checks, List<String> problems) {
        var rows = pg.query(
                "SELECT a.id AS aid, a.cash_cents AS cash, a.reserved_cents AS reserved, "
              + "l.cash_after_cents AS lcash, l.reserved_after_cents AS lres "
              + "FROM accounts a "
              + "LEFT JOIN LATERAL (SELECT cash_after_cents, reserved_after_cents FROM ledger "
              + "                   WHERE account_id = a.id ORDER BY id DESC LIMIT 1) l ON TRUE",
                r -> new long[]{r.lng("cash"), r.lng("reserved"), r.lngOrNull("lcash") == null ? Long.MIN_VALUE : r.lng("lcash"),
                        r.lngOrNull("lres") == null ? Long.MIN_VALUE : r.lng("lres")},
                new Object[0]);
        int accountsWithLedger = 0;
        for (long[] row : rows) {
            if (row[2] == Long.MIN_VALUE) continue; // account has no ledger rows
            accountsWithLedger++;
            if (row[0] != row[2]) problems.add("ledger invariant: account cash " + row[0] + " != last ledger cash_after " + row[2]);
            if (row[1] != row[3]) problems.add("ledger invariant: account reserved " + row[1] + " != last ledger reserved_after " + row[3]);
        }
        checks.add("ledger invariant checked on " + accountsWithLedger + " account(s) with ledger history");
    }

    // ---- introspection helpers ----

    private static boolean sqliteHasTable(Connection src, String table) throws SQLException {
        try (PreparedStatement ps = src.prepareStatement(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) > 0; }
        }
    }

    private static List<String> sqliteColumns(Connection src, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    private static Map<String, String> pgColumnTypes(Connection dst, String table) throws SQLException {
        Map<String, String> types = new LinkedHashMap<>();
        try (PreparedStatement ps = dst.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns "
              + "WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) types.put(rs.getString("column_name"), rs.getString("data_type"));
            }
        }
        return types;
    }

    private static int scalarInt(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
