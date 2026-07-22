package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationsTest {

    @Test void emptyDatabaseMigratesToTheCurrentSchemaAndIsIdempotent() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Migrations.run(db);
            Migrations.run(db); // second run applies nothing

            // Flyway owns the history; the baseline landed successfully.
            assertThat(db.query(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                    r -> r.str("version"))).containsExactly("1", "2", "3", "4");

            // The baseline carries its seed rows and the current column shape.
            assertThat(db.query("SELECT id FROM users ORDER BY id", r -> r.str("id")))
                    .containsExactly("local", "system");
            assertThat(db.query("SELECT id FROM dataset ORDER BY id", r -> r.str("id")))
                    .containsExactly("demo-fixture", "observed");
            assertThat(db.query("SELECT COUNT(*) n FROM plan_candidate_warning", r -> r.lng("n")))
                    .containsExactly(0L);
            assertThat(db.query("SELECT COUNT(*) n FROM market_event_evidence", r -> r.lng("n")))
                    .containsExactly(0L);
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='portfolio_valuation' "
                            + "AND column_name='broker_buying_power_cents'",
                    r -> r.str("column_name"))).containsExactly("broker_buying_power_cents");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='account_objective_revision' "
                            + "AND column_name IN ('package_capacities','capacity_policy') ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly("capacity_policy", "package_capacities");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_strategy_run' "
                            + "AND column_name='sentiment_scorer_version'",
                    r -> r.str("column_name"))).containsExactly("sentiment_scorer_version");
        }
    }

    @Test void aLegacyNonFlywayDatabaseIsRejectedInsteadOfSilentlyAdopted() {
        // A database that predates Flyway (real tables, no history) must not be silently baselined —
        // pre-release databases are disposable, so the honest answer is "recreate it", not "guess".
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            db.exec("CREATE TABLE old_shape(id TEXT PRIMARY KEY)");
            assertThatThrownBy(() -> Migrations.run(db))
                    .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                    .hasMessageContaining("non-empty schema");
        }
    }

    @Test void anEditedAppliedMigrationFailsValidationInsteadOfDrifting() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Migrations.run(db);
            // Simulate an applied migration being edited after the fact: its recorded checksum no
            // longer matches the file. Flyway must refuse to migrate rather than drift silently.
            db.exec("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
            assertThatThrownBy(() -> Migrations.run(db))
                    .isInstanceOf(FlywayValidateException.class);
        }
    }
}
