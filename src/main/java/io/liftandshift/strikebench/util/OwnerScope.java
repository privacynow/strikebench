package io.liftandshift.strikebench.util;

import io.liftandshift.strikebench.db.Db;

import java.sql.Connection;
import java.sql.SQLException;

/** Canonical persistence identity for local and authenticated owners. */
public final class OwnerScope {
    public static final String LOCAL = "local";
    public static final String SYSTEM = "system";

    private OwnerScope() {}

    public static String id(String raw) {
        if (raw == null) return LOCAL;
        String value = raw.trim();
        if (value.isEmpty() || "__local__".equals(value)) return LOCAL;
        return value.startsWith("user:") ? id(value.substring(5)) : value;
    }

    /**
     * Service tests and local integrations may use an explicit owner without running OIDC first.
     * Production OIDC users already exist; this only creates a minimally identified local owner.
     */
    public static String ensure(Connection connection, String raw) throws SQLException {
        String owner = id(raw);
        Db.execOn(connection, "INSERT INTO users(id,name,created_at,updated_at) "
                        + "VALUES(?,?,now(),now()) ON CONFLICT(id) DO NOTHING",
                owner, LOCAL.equals(owner) ? "Local user" : owner);
        return owner;
    }

    /**
     * Transaction mutex for mutations whose rows may not exist yet.
     *
     * <p>The workspace and active-world selectors are both optional rows. Row-locking either table
     * alone therefore cannot serialize the first write with a concurrent world transition. The
     * durable owner row always exists after {@link #ensure}; locking it gives both owners one
     * stable lock before either touches its optional row.
     */
    public static String lock(Connection connection, String raw) throws SQLException {
        String owner = ensure(connection, raw);
        Db.queryOn(connection, "SELECT id FROM users WHERE id=? FOR UPDATE",
                row -> row.str("id"), owner);
        return owner;
    }
}
