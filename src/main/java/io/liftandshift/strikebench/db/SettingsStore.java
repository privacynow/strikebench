package io.liftandshift.strikebench.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Small typed boundary for process-wide operational settings — the one home for the settings SQL. */
public final class SettingsStore {

    public static final String SELECT_V_SQL = "SELECT v FROM settings WHERE k=?";
    static final String UPSERT_SQL =
            "INSERT INTO settings(k,v,updated_at) VALUES (?,?,?) "
          + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at";
    static final String CAS_SQL = "UPDATE settings SET v=?,updated_at=? WHERE k=? AND v=?";

    private final Db db;

    public SettingsStore(Db db) {
        this.db = db;
    }

    public Optional<String> get(String key) {
        requireKey(key);
        return read(db, key);
    }

    public void put(String key, String value) {
        requireKey(key);
        if (value == null) throw new IllegalArgumentException("setting value is required");
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES (?,?,now()) "
                        + "ON CONFLICT (k) DO UPDATE SET v=excluded.v, updated_at=excluded.updated_at",
                key, value);
    }

    /**
     * Raw first-or-null read. Intentionally NO requireKey — reproduces the raw {@code SELECT v}
     * sites byte-for-byte (all routed keys are provably non-blank).
     */
    public static Optional<String> read(Db db, String key) {
        var values = db.query(SELECT_V_SQL, r -> r.str("v"), key);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    /** Pooled/autocommit upsert with a CALLER-SUPPLIED timestamp (sim or JVM clock, never now()). */
    public static void upsert(Db db, String key, String value, Instant updatedAt) {
        db.exec(UPSERT_SQL, key, value, updatedAt.toString());
    }

    /** Connection-scoped upsert; enlists in the caller's transaction. */
    public static int upsertOn(Connection c, String key, String value, Instant updatedAt) throws SQLException {
        return Db.execOn(c, UPSERT_SQL, key, value, updatedAt.toString());
    }

    /** Connection-scoped optimistic compare-and-swap; returns affected rowcount (0 = lost race). */
    public static int casOn(Connection c, String key, String expected, String value, Instant updatedAt)
            throws SQLException {
        return Db.execOn(c, CAS_SQL, value, updatedAt.toString(), key, expected); // bind: v, ts, k, expected
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("setting key is required");
    }
}
