package io.liftandshift.market.providers;

import io.liftandshift.config.AppConfig;
import io.liftandshift.model.NewsItem;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgarProviderTest {

    private static final String USER_AGENT = "StrikeBench-Test/1.0 (test@example.com)";

    private static final String TICKER_MAP_JSON = """
            {
              "0": {"cik_str": 320193, "ticker": "AAPL", "title": "Apple Inc."},
              "1": {"cik_str": 789019, "ticker": "MSFT", "title": "Microsoft Corp"}
            }
            """;

    private static final String SUBMISSIONS_JSON = """
            {
              "name": "Apple Inc.",
              "filings": {
                "recent": {
                  "accessionNumber": ["0000320193-26-000010", "0000320193-26-000005"],
                  "form": ["10-Q", "8-K"],
                  "filingDate": ["2026-05-01", "2026-02-02"],
                  "primaryDocument": ["aapl-20260328.htm", "aapl-8k.htm"]
                }
              }
            }
            """;

    private MockWebServer server;
    private EdgarProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        AppConfig cfg = new AppConfig(Map.of(
                "EDGAR_BASE_URL", server.url("/").toString(),
                "EDGAR_DATA_BASE_URL", server.url("/").toString(),
                "EDGAR_USER_AGENT", USER_AGENT));
        provider = new EdgarProvider(cfg);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void newsParsesRecentFilingsWithUserAgentOnEveryRequest() throws Exception {
        server.enqueue(json(TICKER_MAP_JSON));
        server.enqueue(json(SUBMISSIONS_JSON));

        List<NewsItem> news = provider.news("aapl");

        assertThat(news).hasSize(2);

        NewsItem first = news.get(0);
        assertThat(first.symbol()).isEqualTo("AAPL");
        assertThat(first.headline()).isEqualTo("10-Q filing");
        assertThat(first.source()).isEqualTo("SEC EDGAR");
        assertThat(first.url()).isEqualTo(
                "https://www.sec.gov/Archives/edgar/data/320193/000032019326000010/aapl-20260328.htm");
        assertThat(first.publishedEpochMs())
                .isEqualTo(Instant.parse("2026-05-01T00:00:00Z").toEpochMilli());

        NewsItem second = news.get(1);
        assertThat(second.headline()).isEqualTo("8-K filing");
        assertThat(second.url()).isEqualTo(
                "https://www.sec.gov/Archives/edgar/data/320193/000032019326000005/aapl-8k.htm");
        assertThat(second.publishedEpochMs())
                .isEqualTo(Instant.parse("2026-02-02T00:00:00Z").toEpochMilli());

        RecordedRequest tickerReq = server.takeRequest();
        assertThat(tickerReq.getPath()).isEqualTo("/files/company_tickers.json");
        assertThat(tickerReq.getHeader("User-Agent")).isEqualTo(USER_AGENT);

        RecordedRequest submissionsReq = server.takeRequest();
        assertThat(submissionsReq.getPath()).isEqualTo("/submissions/CIK0000320193.json");
        assertThat(submissionsReq.getHeader("User-Agent")).isEqualTo(USER_AGENT);
    }

    @Test
    void unknownTickerReturnsEmptyWithoutSubmissionsRequest() {
        server.enqueue(json(TICKER_MAP_JSON));

        assertThat(provider.news("ZZZZ")).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1); // ticker map only
    }

    @Test
    void tickerMapIsCachedAcrossCalls() {
        server.enqueue(json(TICKER_MAP_JSON));
        server.enqueue(json(SUBMISSIONS_JSON));
        server.enqueue(json(SUBMISSIONS_JSON));

        assertThat(provider.news("AAPL")).hasSize(2);
        assertThat(provider.news("AAPL")).hasSize(2);
        assertThat(server.getRequestCount()).isEqualTo(3); // map fetched once, submissions twice
    }

    @Test
    void serverErrorOnSubmissionsThrows() {
        server.enqueue(json(TICKER_MAP_JSON));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> provider.news("AAPL"))
                .isInstanceOf(Http.ProviderHttpException.class)
                .hasMessageContaining("500");
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
