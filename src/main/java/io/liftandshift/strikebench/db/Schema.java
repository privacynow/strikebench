package io.liftandshift.strikebench.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;

/** Initializes an empty database from the one current schema and rejects every other shape. */
public final class Schema {

    private static final Logger log = LoggerFactory.getLogger(Schema.class);
    private static final String RESOURCE = "db/schema.sql";

    private Schema() {}

    public static void initialize(Db db) {
        byte[] bytes = readSchema();
        String fingerprint = sha256(bytes);
        db.tx(c -> {
            boolean initialized;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT to_regclass('public.strikebench_schema') IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                initialized = rs.getBoolean(1);
            }
            if (initialized) {
                String stored;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT schema_sha256 FROM public.strikebench_schema");
                     ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw incompatible("schema marker is empty");
                    stored = rs.getString(1);
                    if (rs.next()) throw incompatible("schema marker has multiple rows");
                }
                if (!fingerprint.equals(stored)) {
                    throw incompatible("schema fingerprint differs from this build");
                }
                return null;
            }

            long existingTables;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE schemaname='public'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                existingTables = rs.getLong(1);
            }
            if (existingTables != 0) {
                throw incompatible("database contains an earlier development schema");
            }

            try (Statement st = c.createStatement()) {
                st.execute(new String(bytes, StandardCharsets.UTF_8));
                st.execute("CREATE TABLE public.strikebench_schema ("
                        + "schema_sha256 TEXT PRIMARY KEY, initialized_at TIMESTAMPTZ NOT NULL)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO public.strikebench_schema(schema_sha256,initialized_at) "
                            + "VALUES(?,CURRENT_TIMESTAMP)")) {
                ps.setString(1, fingerprint);
                ps.executeUpdate();
            }
            log.info("Current local data schema initialized");
            return null;
        });
    }

    private static IllegalStateException incompatible(String detail) {
        return new IllegalStateException("StrikeBench database is not the current development schema ("
                + detail + "). Recreate the local database; this pre-release build does not carry schema "
                + "migrations or compatibility records.");
    }

    private static byte[] readSchema() {
        try (var in = Schema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing current schema resource: " + RESOURCE);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read current schema resource", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
