package io.liftandshift.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Minimal ordered migration runner. Migrations are classpath resources under /db/migrations
 * listed explicitly so this works identically inside the shaded jar.
 */
public final class Migrations {

    private static final Logger log = LoggerFactory.getLogger(Migrations.class);

    /** Ordered list of migrations. Append only; never edit an applied migration. */
    static final List<String> MIGRATIONS = List.of(
            "V1__init.sql",
            "V2__positions_intent.sql",
            "V3__settings.sql"
    );

    private Migrations() {}

    public static void run(Db db) {
        db.tx(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)");
            }
            long applied = Db.queryOn(c, "SELECT COUNT(*) AS n FROM schema_migrations", r -> r.lng("n")).getFirst();
            for (int i = (int) applied; i < MIGRATIONS.size(); i++) {
                String name = MIGRATIONS.get(i);
                log.info("Applying migration {} ({})", i + 1, name);
                applySql(c, load(name));
                Db.execOn(c, "INSERT INTO schema_migrations(version, name, applied_at) VALUES (?,?,datetime('now'))", i + 1, name);
            }
            return null;
        });
    }

    private static void applySql(Connection c, String sql) throws SQLException {
        // Strip -- comments per line BEFORE splitting on ';' (comments may contain semicolons,
        // and a leading file comment must not swallow the first statement). Our migrations keep
        // string literals free of both '--' and ';', so this stays a simple splitter.
        StringBuilder cleaned = new StringBuilder(sql.length());
        for (String line : sql.split("\n", -1)) {
            int idx = line.indexOf("--");
            cleaned.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        for (String stmt : cleaned.toString().split(";")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            try (Statement st = c.createStatement()) {
                st.execute(trimmed);
            }
        }
    }

    private static String load(String name) {
        String path = "/db/migrations/" + name;
        try (InputStream in = Migrations.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing migration resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load migration " + path, e);
        }
    }
}
