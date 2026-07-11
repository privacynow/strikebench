package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataConnectorCatalogTest {
    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void yahooAutomationNeedsPermissionNotJustAnEnableFlag() {
        db = TestDb.fresh();
        var catalog = catalog(Map.of("YAHOO_ENABLED", "true"));
        var yahoo = catalog.all().stream().filter(c -> c.key().equals("yahoo")).findFirst().orElseThrow();
        assertThat(yahoo.configured()).isTrue();
        assertThat(yahoo.eligible()).isFalse();
        assertThatThrownBy(() -> catalog.requireAutomated("yahoo")).hasMessageContaining("not eligible");

        var permitted = catalog(Map.of("YAHOO_ENABLED", "true", "YAHOO_AUTOMATION_PERMISSION_CONFIRMED", "true"));
        assertThat(permitted.requireAutomated("yahoo").eligible()).isTrue();
    }

    @Test
    void keyedOfficialSourceWinsAutoAndCsvCannotBeScheduled() {
        db = TestDb.fresh();
        var catalog = catalog(Map.of("ALPHAVANTAGE_API_KEY", "secret"));
        assertThat(catalog.recommendedSource()).isEqualTo("alphavantage");
        assertThat(catalog.requireAutomated("auto").key()).isEqualTo("alphavantage");
        assertThatThrownBy(() -> catalog.requireAutomated("user_csv")).hasMessageContaining("manual import");
    }

    private DataConnectorCatalog catalog(Map<String, String> config) {
        return new DataConnectorCatalog(new AppConfig(config), new ProviderRequestBudget(db, Clock.systemUTC()));
    }
}
