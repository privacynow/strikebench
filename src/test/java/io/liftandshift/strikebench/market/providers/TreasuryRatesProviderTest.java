package io.liftandshift.strikebench.market.providers;

import io.liftandshift.strikebench.config.AppConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TreasuryRatesProviderTest {

    /** Fixed 2026-07-06 so the requested year is deterministic. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);

    private static final String TWO_ENTRY_FEED = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:d="http://schemas.microsoft.com/ado/2007/08/dataservices"
                  xmlns:m="http://schemas.microsoft.com/ado/2007/08/dataservices/metadata">
              <title type="text">DailyTreasuryYieldCurveRateData</title>
              <entry>
                <content type="application/xml">
                  <m:properties>
                    <d:NEW_DATE m:type="Edm.DateTime">2026-07-01T00:00:00</d:NEW_DATE>
                    <d:BC_1MONTH m:type="Edm.Double">5.10</d:BC_1MONTH>
                    <d:BC_3MONTH m:type="Edm.Double">5.05</d:BC_3MONTH>
                    <d:BC_6MONTH m:type="Edm.Double">5.00</d:BC_6MONTH>
                    <d:BC_1YEAR m:type="Edm.Double">4.80</d:BC_1YEAR>
                  </m:properties>
                </content>
              </entry>
              <entry>
                <content type="application/xml">
                  <m:properties>
                    <d:NEW_DATE m:type="Edm.DateTime">2026-07-02T00:00:00</d:NEW_DATE>
                    <d:BC_1MONTH m:type="Edm.Double">5.40</d:BC_1MONTH>
                    <d:BC_3MONTH m:type="Edm.Double">5.32</d:BC_3MONTH>
                    <d:BC_6MONTH m:type="Edm.Double">5.20</d:BC_6MONTH>
                    <d:BC_1YEAR m:type="Edm.Double">4.95</d:BC_1YEAR>
                  </m:properties>
                </content>
              </entry>
            </feed>
            """;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private TreasuryRatesProvider provider() {
        AppConfig cfg = new AppConfig(Map.of("TREASURY_BASE_URL", server.url("/").toString()));
        return new TreasuryRatesProvider(cfg, CLOCK);
    }

    @Test
    void lastEntryWinsAndTenorsMapByDays() {
        server.enqueue(new MockResponse().setBody(TWO_ENTRY_FEED));
        TreasuryRatesProvider provider = provider();

        // 30 days -> 1M tenor from the LAST entry (5.40%), not the first (5.10%)
        assertThat(provider.riskFreeRate(30).orElseThrow()).isEqualTo(0.0540, within(1e-9));
        assertThat(provider.riskFreeRate(100).orElseThrow()).isEqualTo(0.0532, within(1e-9)); // 3M
        assertThat(provider.riskFreeRate(200).orElseThrow()).isEqualTo(0.0520, within(1e-9)); // 6M
        assertThat(provider.riskFreeRate(365).orElseThrow()).isEqualTo(0.0495, within(1e-9)); // 1Y

        // Curve is cached: four lookups, one fetch
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void requestsDailyYieldCurveForCurrentYear() throws Exception {
        server.enqueue(new MockResponse().setBody(TWO_ENTRY_FEED));
        provider().riskFreeRate(30);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath())
                .startsWith("/resource-center/data-chart-center/interest-rates/pages/xml")
                .contains("data=daily_treasury_yield_curve")
                .contains("field_tdr_date_value=2026");
    }

    @Test
    void feedWithoutEntriesYieldsEmpty() {
        server.enqueue(new MockResponse().setBody(
                "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>"));
        assertThat(provider().riskFreeRate(30)).isEmpty();
    }

    @Test
    void missingTenorTagYieldsEmptyOnlyForThatTenor() {
        server.enqueue(new MockResponse().setBody("""
                <feed xmlns:d="d" xmlns:m="m">
                  <entry><content><m:properties>
                    <d:NEW_DATE>2026-07-02T00:00:00</d:NEW_DATE>
                    <d:BC_3MONTH>5.32</d:BC_3MONTH>
                  </m:properties></content></entry>
                </feed>
                """));
        TreasuryRatesProvider provider = provider();
        assertThat(provider.riskFreeRate(30)).isEmpty(); // 1M tag absent
        assertThat(provider.riskFreeRate(100).orElseThrow()).isEqualTo(0.0532, within(1e-9));
    }

    @Test
    void httpFailurePropagates() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertThatThrownBy(() -> provider().riskFreeRate(30))
                .isInstanceOf(Http.ProviderHttpException.class)
                .hasMessageContaining("500");
    }
}
