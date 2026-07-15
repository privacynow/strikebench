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
}
