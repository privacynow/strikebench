package io.liftandshift.market.providers;

import io.liftandshift.config.AppConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class FredProviderTest {

    private MockWebServer server;
    private FredProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new FredProvider(new AppConfig(Map.of(
                "FRED_BASE_URL", server.url("/").toString(),
                "FRED_API_KEY", "testkey123",
                "HTTP_TIMEOUT_MS", "2000")));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void skipsMissingDotObservationsAndParsesFirstRealValue() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("""
                {"observations":[
                  {"date":"2026-07-03","value":"."},
                  {"date":"2026-07-02","value":"5.38"},
                  {"date":"2026-07-01","value":"5.41"}
                ]}"""));

        OptionalDouble rate = provider.riskFreeRate(30);

        assertThat(rate).isPresent();
        assertThat(rate.getAsDouble()).isEqualTo(0.0538, offset(1e-12));

        String path = server.takeRequest().getPath();
        assertThat(path).startsWith("/fred/series/observations?");
        assertThat(path).contains("series_id=DGS1MO");
        assertThat(path).contains("api_key=testkey123");
        assertThat(path).contains("file_type=json");
        assertThat(path).contains("sort_order=desc");
        assertThat(path).contains("limit=10");
    }

    @Test
    void horizonPicksSeries() throws InterruptedException {
        server.enqueue(new MockResponse().setBody(
                "{\"observations\":[{\"date\":\"2026-07-02\",\"value\":\"4.10\"}]}"));
        server.enqueue(new MockResponse().setBody(
                "{\"observations\":[{\"date\":\"2026-07-02\",\"value\":\"4.20\"}]}"));

        assertThat(provider.riskFreeRate(90).getAsDouble()).isEqualTo(0.0410, offset(1e-12));
        assertThat(provider.riskFreeRate(200).getAsDouble()).isEqualTo(0.0420, offset(1e-12));

        assertThat(server.takeRequest().getPath()).contains("series_id=DGS3MO");
        assertThat(server.takeRequest().getPath()).contains("series_id=DGS1&");
    }

    @Test
    void allMissingOrEmptyObservationsIsEmpty() {
        server.enqueue(new MockResponse().setBody("""
                {"observations":[
                  {"date":"2026-07-03","value":"."},
                  {"date":"2026-07-02","value":"."}
                ]}"""));
        assertThat(provider.riskFreeRate(30)).isEmpty();

        server.enqueue(new MockResponse().setBody("{\"observations\":[]}"));
        assertThat(provider.riskFreeRate(30)).isEmpty();
    }

    @Test
    void blankApiKeyMakesNoHttpCalls() {
        FredProvider keyless = new FredProvider(new AppConfig(Map.of(
                "FRED_BASE_URL", server.url("/").toString(),
                "HTTP_TIMEOUT_MS", "2000")));

        assertThat(keyless.riskFreeRate(30)).isEmpty();
        assertThat(keyless.riskFreeRate(200)).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void serverErrorPropagates() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> provider.riskFreeRate(30))
                .isInstanceOf(Http.ProviderHttpException.class)
                .hasMessageContaining("500");
    }
}
