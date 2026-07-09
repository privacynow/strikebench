package io.liftandshift.strikebench.market.providers;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.model.Candle;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlphaVantageProviderTest {

    // Newest-first like the real API; includes days outside the requested window on both sides.
    private static final String HAPPY_BODY = """
            {
              "Meta Data": {
                "1. Information": "Daily Time Series with Splits and Dividend Events",
                "2. Symbol": "AAPL"
              },
              "Time Series (Daily)": {
                "2026-07-03": {"1. open": "255.5", "2. high": "257.0", "3. low": "254.9", "4. close": "256.1", "5. adjusted close": "256.4", "6. volume": "35000000"},
                "2026-07-02": {"1. open": "253.2", "2. high": "256.4", "3. low": "252.8", "4. close": "255.0", "5. adjusted close": "255.3", "6. volume": "40123456"},
                "2026-07-01": {"1. open": "250.0", "2. high": "254.1", "3. low": "249.5", "4. close": "253.1", "5. adjusted close": "253.4", "6. volume": "38000000"},
                "2026-06-15": {"1. open": "240.0", "2. high": "241.0", "3. low": "239.0", "4. close": "240.5", "5. adjusted close": "240.7", "6. volume": "10000000"}
              }
            }
            """;

    private MockWebServer server;
    private AlphaVantageProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AppConfig cfg = new AppConfig(Map.of(
                "ALPHAVANTAGE_BASE_URL", server.url("/").toString(),
                "ALPHAVANTAGE_API_KEY", "test-key-123"));
        provider = new AlphaVantageProvider(cfg);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesAdjustedDailyCandlesAscendingAndFilteredToWindow() {
        server.enqueue(new MockResponse().setBody(HAPPY_BODY));

        List<Candle> candles = provider.candles("AAPL",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));

        assertThat(candles).hasSize(2); // 2026-06-15 and 2026-07-03 filtered out
        Candle first = candles.get(0);
        Candle second = candles.get(1);

        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 7, 1)); // ascending despite newest-first payload
        assertThat(second.date()).isEqualTo(LocalDate.of(2026, 7, 2));

        assertThat(second.open()).isEqualByComparingTo(new BigDecimal("253.2"));
        assertThat(second.high()).isEqualByComparingTo(new BigDecimal("256.4"));
        assertThat(second.low()).isEqualByComparingTo(new BigDecimal("252.8"));
        assertThat(second.close()).isEqualByComparingTo(new BigDecimal("255.3")); // adjusted close, not "4. close"
        assertThat(second.volume()).isEqualTo(40123456L);
        assertThat(second.adjusted()).isTrue();

        assertThat(first.close()).isEqualByComparingTo(new BigDecimal("253.4"));
        assertThat(first.volume()).isEqualTo(38000000L);
        assertThat(first.adjusted()).isTrue();
    }

    @Test
    void requestsDailyAdjustedFunctionWithSymbolAndKey() throws InterruptedException {
        server.enqueue(new MockResponse().setBody(HAPPY_BODY));

        provider.candles("aapl", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath())
                .startsWith("/query?")
                .contains("function=TIME_SERIES_DAILY_ADJUSTED")
                .contains("symbol=AAPL") // uppercased
                .contains("outputsize=full")
                .contains("apikey=test-key-123");
    }

    @Test
    void rateLimitNoteThrowsSoChainFallsThrough() {
        server.enqueue(new MockResponse().setBody(
                "{\"Note\": \"Thank you for using Alpha Vantage! Our standard API rate limit is 25 requests per day.\"}"));

        assertThatThrownBy(() -> provider.candles("AAPL",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("rate limit is 25 requests per day");
    }

    @Test
    void errorMessageBodyThrows() {
        server.enqueue(new MockResponse().setBody(
                "{\"Error Message\": \"Invalid API call. Please retry or visit the documentation.\"}"));

        assertThatThrownBy(() -> provider.candles("AAPL",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid API call");
    }

    @Test
    void httpErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> provider.candles("AAPL",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)))
                .isInstanceOf(Http.ProviderHttpException.class)
                .hasMessageContaining("500");
    }

    @Test
    void noSeriesAndNoErrorKeysMeansNoData() {
        server.enqueue(new MockResponse().setBody("{\"Meta Data\": {\"2. Symbol\": \"NOPE\"}}"));

        assertThat(provider.candles("NOPE",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2))).isEmpty();
    }

    @Test
    void servesCandlesDomainOnlyAndOtherCallsAreEmpty() {
        assertThat(provider.name()).isEqualTo("alphavantage");
        assertThat(provider.domains()).containsExactly(Domain.CANDLES);
        assertThat(provider.lookup("AAPL")).isEmpty();
        assertThat(provider.quote("AAPL")).isEmpty();
        assertThat(provider.expirations("AAPL")).isEmpty();
        assertThat(provider.chain("AAPL", LocalDate.of(2026, 7, 17))).isEmpty();
        assertThat(provider.news("AAPL")).isEmpty();
    }
}
