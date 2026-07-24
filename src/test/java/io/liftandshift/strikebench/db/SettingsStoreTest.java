package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsStoreTest {

    @Test
    void operationalSettingRoundTripsAndUpdates() {
        try (Db db = TestDb.fresh()) {
            SettingsStore store = new SettingsStore(db);
            assertThat(store.get("review.setting")).isEmpty();

            store.put("review.setting", "one");
            store.put("review.setting", "two");

            assertThat(store.get("review.setting")).contains("two");
            assertThatThrownBy(() -> store.get(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.put("review.setting", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void upsertPersistsTheCallerSuppliedTimestampNotDbNow() {
        // The headline regression guard: the sim/JVM-clock callers (DatasetService, UniverseService,
        // WorldTransitionService) route through upsert/upsertOn, which MUST write the caller's Instant
        // into updated_at — never DB now(). A silent swap would rewrite a simulated timestamp.
        try (Db db = TestDb.fresh()) {
            java.time.Instant when = java.time.Instant.parse("2011-11-11T11:11:11Z");
            SettingsStore.upsert(db, "review.ts", "v1", when);
            String storedIso = db.query("SELECT updated_at FROM settings WHERE k=?",
                    r -> r.odt("updated_at").toInstant().toString(), "review.ts").getFirst();
            assertThat(java.time.Instant.parse(storedIso)).isEqualTo(when);
            assertThat(SettingsStore.read(db, "review.ts")).contains("v1");
        }
    }
}
