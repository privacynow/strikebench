package io.liftandshift.strikebench.db;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schema migrations, run by Flyway from versioned SQL under classpath:db/migrations
 * (V1__baseline.sql, V2__..., ...). Flyway owns its own history table and applies pending
 * migrations in order; never edit a migration that has already been applied to a real database —
 * add a forward migration instead.
 *
 * <p>Strict by design: an empty database is built from V1 up, and a non-empty database that
 * predates Flyway (no history table) is rejected rather than silently adopted. Pre-release
 * databases are disposable — recreate them empty and let Flyway rebuild.
 */
public final class Migrations {

    private static final Logger log = LoggerFactory.getLogger(Migrations.class);

    private Migrations() {}

    public static void run(Db db) {
        var result = Flyway.configure()
                .dataSource(db.dataSource())
                .locations("classpath:db/migrations")
                .load()
                .migrate();
        if (result.migrationsExecuted == 0) {
            log.info("Data schema ready (no migrations pending)");
        } else {
            log.info("Data schema ready ({} migration(s) applied, now at {})",
                    result.migrationsExecuted, result.targetSchemaVersion);
        }
    }
}
