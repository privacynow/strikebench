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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StooqProviderTest {

    private static final String CSV = """
            Date,Open,High,Low,Close,Volume
            2026-07-01,250.0,252.5,249.1,251.2,38000000
            2026-07-02,254.1,256.2,253.8,255.3,41231234
            2026-07-03,255.5,257.0,254.9,256.4,
            """;

    private MockWebServer server;
    private StooqProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AppConfig cfg = new AppConfig(Map.of("STOOQ_BASE_URL", server.url("/").toString()));
        provider = new StooqProvider(cfg);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesCandlesAndFiltersToWindow() {
        server.enqueue(new MockResponse().setBody(CSV));

        List<Candle> candles = provider.candles("AAPL",
                LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3));

        assertThat(candles).hasSize(2); // 2026-07-01 excluded by the window
        Candle first = candles.get(0);
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(first.open()).isEqualByComparingTo(new BigDecimal("254.1"));
        assertThat(first.high()).isEqualByComparingTo(new BigDecimal("256.2"));
        assertThat(first.low()).isEqualByComparingTo(new BigDecimal("253.8"));
        assertThat(first.close()).isEqualByComparingTo(new BigDecimal("255.3"));
        assertThat(first.volume()).isEqualTo(41231234L);
        assertThat(first.adjusted()).isFalse();

        Candle second = candles.get(1);
        assertThat(second.date()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(second.close()).isEqualByComparingTo(new BigDecimal("256.4"));
        assertThat(second.volume()).as("missing volume defaults to 0").isZero();

        assertThat(candles).isSortedAccordingTo(java.util.Comparator.comparing(Candle::date));
    }

    @Test
    void requestsStooqCsvPathWithLowercaseUsSymbol() throws InterruptedException {
        server.enqueue(new MockResponse().setBody(CSV));

        provider.candles("AAPL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3));

        String path = server.takeRequest().getPath();
        assertThat(path).contains("/q/d/l/?s=aapl.us");
        assertThat(path).contains("i=d");
    }

    @Test
    void noDataBodyReturnsEmptyList() {
        server.enqueue(new MockResponse().setBody("No data"));
        assertThat(provider.candles("NOPE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .isEmpty();
    }

    @Test
    void headerOnlyBodyReturnsEmptyList() {
        server.enqueue(new MockResponse().setBody("Date,Open,High,Low,Close,Volume\n"));
        assertThat(provider.candles("AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .isEmpty();
    }

    @Test
    void serverErrorPropagatesAsException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertThatThrownBy(() ->
                provider.candles("AAPL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3)))
                .isInstanceOf(Http.ProviderHttpException.class)
                .hasMessageContaining("500");
    }

    @Test
    void nameAndDomains() {
        assertThat(provider.name()).isEqualTo("stooq");
        assertThat(provider.domains()).containsExactly(Domain.CANDLES);
        assertThat(provider.quote("AAPL")).isEmpty();
        assertThat(provider.lookup("AAPL")).isEmpty();
        assertThat(provider.expirations("AAPL")).isEmpty();
        assertThat(provider.chain("AAPL", LocalDate.of(2026, 7, 17))).isEmpty();
    }
}
