package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaTest {

    @Test void emptyDatabaseInitializesOnceFromTheCurrentSchema() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Schema.initialize(db);
            Schema.initialize(db);

            assertThat(db.query("SELECT schema_sha256 FROM strikebench_schema", r -> r.str("schema_sha256")))
                    .singleElement().asString().hasSize(64);
            assertThat(db.query("SELECT id FROM users ORDER BY id", r -> r.str("id")))
                    .containsExactly("local", "system");
            assertThat(db.query("SELECT id FROM dataset ORDER BY id", r -> r.str("id")))
                    .containsExactly("demo-fixture", "observed");
            assertThat(db.query("SELECT COUNT(*) n FROM plan_candidate_warning", r -> r.lng("n")))
                    .containsExactly(0L);
        }
    }

    @Test void anEarlierOrUnknownSchemaIsRejectedInsteadOfTranslated() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            db.exec("CREATE TABLE old_shape(id TEXT PRIMARY KEY)");
            assertThatThrownBy(() -> Schema.initialize(db))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Recreate the local database")
                    .hasMessageContaining("does not carry schema migrations or compatibility records");
        }
    }

    @Test void aChangedBaselineIsRejectedInsteadOfSilentlyAccepted() {
        var cfg = TestDb.emptyConfig();
        try (Db db = new Db(cfg.get("DB_URL"), cfg.get("DB_USER"), cfg.get("DB_PASSWORD"))) {
            Schema.initialize(db);
            db.exec("UPDATE strikebench_schema SET schema_sha256='obsolete'");
            assertThatThrownBy(() -> Schema.initialize(db))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schema fingerprint differs")
                    .hasMessageContaining("Recreate the local database");
        }
    }
}
