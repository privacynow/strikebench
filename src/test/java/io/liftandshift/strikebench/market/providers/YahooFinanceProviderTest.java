package io.liftandshift.strikebench.market.providers;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.model.Candle;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Yahoo chart-API daily candle parsing (keyless, personal-mode equity backfill). */
class YahooFinanceProviderTest {

    // Two real bars + one holiday row where Yahoo emits nulls (must be skipped, never a $0 bar).
    // Timestamps: 2026-07-01, 2026-07-02, 2026-07-03 (UTC midnight).
    private static final String JSON = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL"},
              "timestamp":[1782950400,1783036800,1783123200],
              "indicators":{"quote":[{
                "open":[250.0,254.1,null],
                "high":[252.5,256.2,null],
                "low":[249.1,253.8,null],
                "close":[251.2,255.3,null],
                "volume":[38000000,41231234,null]
              }]}}],"error":null}}
            """;

    private MockWebServer server;
    private YahooFinanceProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AppConfig cfg = new AppConfig(Map.of("YAHOO_BASE_URL", server.url("/").toString()));
        provider = new YahooFinanceProvider(cfg);
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void parsesDailyCandlesAndSkipsNullHolidayRows() throws Exception {
        server.enqueue(new MockResponse().setBody(JSON).addHeader("Content-Type", "application/json"));
        List<Candle> candles = provider.candles("AAPL", LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-31"));
        assertThat(candles).hasSize(2); // the null (holiday) row is dropped, not booked at $0
        assertThat(candles.get(1).date()).isAfter(candles.get(0).date()); // ascending, real dates
        assertThat(candles.get(0).close()).isEqualByComparingTo("251.2");
        assertThat(candles.get(0).open()).isEqualByComparingTo("250.0");
        assertThat(candles.get(1).close()).isEqualByComparingTo("255.3");
        assertThat(candles.get(1).volume()).isEqualTo(41231234L);
    }

    @Test
    void onlyServesCandles() {
        assertThat(provider.domains()).containsExactly(Domain.CANDLES);
        assertThat(provider.quote("AAPL")).isEmpty();
        assertThat(provider.expirations("AAPL")).isEmpty();
    }

    @Test
    void http200InterstitialMalformedJsonAndChartErrorTripAfterThreeFailures() {
        server.enqueue(ok("<html><title>Will be right back</title></html>"));
        server.enqueue(ok("{\"chart\":"));
        server.enqueue(ok("""
                {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}
                """));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> provider.candles("AAPL",
                    LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-31")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(provider.coolingDown()).isEqualTo(i == 2);
        }

        // The fourth call is answered by the cooldown fallback and never reaches the wire.
        assertThat(provider.candles("AAPL",
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-31"))).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void emptyHttp200ChartResultsAreFailuresAndStopTheUniverseSweep() {
        server.enqueue(ok("{\"chart\":{\"result\":[],\"error\":null}}"));
        server.enqueue(ok("""
                {"chart":{"result":[{"timestamp":[],"indicators":{"quote":[]}}],"error":null}}
                """));
        server.enqueue(ok("""
                {"chart":{"result":[{"timestamp":[1782950400],
                  "indicators":{"quote":[{"close":[null]}]}}],"error":null}}
                """));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> provider.candles("AAPL",
                    LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-31")))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(provider.coolingDown()).isTrue();
        assertThat(provider.candles("MSFT",
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-31"))).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    private static MockResponse ok(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }
}
