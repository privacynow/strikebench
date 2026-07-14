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
}
