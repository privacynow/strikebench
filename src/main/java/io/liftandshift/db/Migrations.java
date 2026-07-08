package io.liftandshift.db;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schema migrations, run by Flyway from versioned SQL under classpath:db/migrations
 * (V1__init.sql, V2__..., ...). Flyway owns its own history table and runs pending
 * migrations in order; never edit a migration that has been applied to a real database.
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
        log.info("Flyway: schema at version {} ({} migration(s) applied this run)",
                result.targetSchemaVersion, result.migrationsExecuted);
    }
}
