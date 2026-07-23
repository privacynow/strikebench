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
    void yahooIsOwnerAuthorizedByDefaultAndEitherFlagCanRevokeIt() {
        db = TestDb.fresh();
        var defaults = catalog(Map.of());
        var yahoo = defaults.all().stream().filter(c -> c.key().equals("yahoo")).findFirst().orElseThrow();
        assertThat(yahoo.configured()).isTrue();
        assertThat(yahoo.eligible()).isTrue();
        assertThat(defaults.requireAutomated("auto").key()).isEqualTo("yahoo");

        var disabled = catalog(Map.of("YAHOO_ENABLED", "false"));
        assertThatThrownBy(() -> disabled.requireAutomated("yahoo")).hasMessageContaining("not eligible");

        var revoked = catalog(Map.of("YAHOO_ENABLED", "true",
                "YAHOO_AUTOMATION_PERMISSION_CONFIRMED", "false"));
        assertThatThrownBy(() -> revoked.requireAutomated("yahoo")).hasMessageContaining("not eligible");
    }

    @Test
    void keyedOfficialSourceWinsAutoAndCsvCannotBeScheduled() {
        db = TestDb.fresh();
        var catalog = catalog(Map.of("ALPHAVANTAGE_API_KEY", "secret"));
        assertThat(catalog.recommendedSource()).isEqualTo("alphavantage");
        assertThat(catalog.requireAutomated("auto").key()).isEqualTo("alphavantage");
        assertThatThrownBy(() -> catalog.requireAutomated("user_csv")).hasMessageContaining("manual import");
    }

    @Test
    void fixturesOnlyNeverAdvertisesAnObservedProviderAsRunnable() {
        db = TestDb.fresh();
        var catalog = catalog(Map.of(
                "FIXTURES_ONLY", "true",
                "YAHOO_ENABLED", "true",
                "YAHOO_AUTOMATION_PERMISSION_CONFIRMED", "true",
                "ALPHAVANTAGE_API_KEY", "configured-but-not-mounted"));

        assertThat(catalog.all().stream().filter(DataConnectorCatalog.Connector::automated))
                .allSatisfy(connector -> assertThat(connector.eligible()).isFalse());
        var yahoo = catalog.all().stream().filter(c -> c.key().equals("yahoo")).findFirst().orElseThrow();
        assertThat(yahoo.configured()).isTrue();
        assertThat(yahoo.setup()).contains("Fixtures-only Demo mode");
        assertThat(catalog.recommendedSource()).isEqualTo("none");
        assertThatThrownBy(() -> catalog.requireAutomated("auto")).hasMessageContaining("not eligible");
    }

    private DataConnectorCatalog catalog(Map<String, String> config) {
        return new DataConnectorCatalog(new AppConfig(config), new ProviderRequestBudget(db, Clock.systemUTC()));
    }
}
