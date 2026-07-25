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
                    r -> r.str("version"))).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                    "11", "12");

            // The baseline carries its seed rows and the current column shape.
            assertThat(db.query("SELECT id FROM users ORDER BY id", r -> r.str("id")))
                    .containsExactly("local", "system");
            assertThat(db.query("SELECT id FROM dataset ORDER BY id", r -> r.str("id")))
                    .containsExactly("demo-fixture", "observed");
            assertThat(db.query("SELECT COUNT(*) n FROM plan_candidate_warning", r -> r.lng("n")))
                    .containsExactly(0L);
            // V10: the persisted candidate carries the whole §7.2 price receipt, not two amounts.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_candidate' "
                            + "AND column_name IN ('stock_cash_flow_cents','after_fee_net_cents',"
                            + "'valuation_basis','price_observed_at_epoch_ms','price_fingerprint') "
                            + "ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly("after_fee_net_cents",
                    "price_fingerprint", "price_observed_at_epoch_ms", "stock_cash_flow_cents",
                    "valuation_basis");
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
            // V11 renames the frozen decision's review horizon to the unit it actually holds.
            // It is a FORWARD migration on purpose: V1 is applied to every existing database and
            // editing it there renames nothing, it only breaks checksum validation.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_decision' "
                            + "AND column_name LIKE 'review_horizon%'",
                    r -> r.str("column_name"))).containsExactly("review_horizon_sessions");
            assertThat(db.query("SELECT conname FROM pg_constraint "
                            + "WHERE conname LIKE 'plan_decision_review_horizon%'",
                    r -> r.str("conname"))).containsExactly("plan_decision_review_horizon_sessions_check");
            assertThat(db.query("SELECT COUNT(*) n FROM position_lifecycle_decision_receipt",
                    r -> r.lng("n"))).containsExactly(0L);
            assertThat(db.query("SELECT COUNT(*) n FROM position_lifecycle_user_decision",
                    r -> r.lng("n"))).containsExactly(0L);
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_strategy_run' "
                            + "AND column_name='sentiment_scorer_version'",
                    r -> r.str("column_name"))).containsExactly("sentiment_scorer_version");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='data_sync_cursor' "
                            + "AND column_name='earliest_available'",
                    r -> r.str("column_name"))).containsExactly("earliest_available");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_candidate' "
                            + "AND column_name='option_net_cents'",
                    r -> r.str("column_name"))).containsExactly("option_net_cents");
            // V9: the managed backtest stores its exit knobs in the ONE management-policy
            // vocabulary, so the old max-profit/max-loss/calendar-DTE column names are gone.
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='backtests' "
                            + "AND column_name IN ('take_profit_fraction','stop_multiple',"
                            + "'time_rule_sessions','profit_target_pct','stop_fraction','roll_dte') "
                            + "ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly(
                            "stop_multiple", "take_profit_fraction", "time_rule_sessions");
            assertThat(db.query("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='plan_candidate_leg' "
                            + "AND column_name IN ('quote_bid','quote_ask','quote_as_of_epoch_ms',"
                            + "'quote_source','quote_freshness') ORDER BY column_name",
                    r -> r.str("column_name"))).containsExactly(
                            "quote_as_of_epoch_ms",
                            "quote_ask",
                            "quote_bid",
                            "quote_freshness",
                            "quote_source");
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
