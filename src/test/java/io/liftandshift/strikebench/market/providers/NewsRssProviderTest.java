package io.liftandshift.strikebench.market.providers;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.model.NewsItem;
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

class NewsRssProviderTest {

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>"AAPL" stock - Google News</title>
              <item>
                <title>Apple beats earnings, shares jump &amp; guidance raised - Reuters</title>
                <link>https://news.google.com/rss/articles/abc123</link>
                <pubDate>Mon, 06 Jul 2026 14:00:00 GMT</pubDate>
                <source url="https://www.reuters.com">Reuters</source>
              </item>
              <item>
                <title>Regulators open probe into Apple - Bloomberg</title>
                <link>https://news.google.com/rss/articles/def456</link>
                <pubDate>Sun, 05 Jul 2026 09:30:00 GMT</pubDate>
                <source url="https://www.bloomberg.com">Bloomberg</source>
              </item>
            </channel></rss>
            """;

    private MockWebServer server;
    private NewsRssProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new NewsRssProvider(new AppConfig(Map.of("NEWS_RSS_BASE_URL", server.url("/rss/search").toString())));
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void parsesHeadlinesStripsSourceSuffixAndUnescapes() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/xml").setBody(RSS));

        List<NewsItem> items = provider.news("aapl");

        assertThat(items).hasSize(2);
        NewsItem first = items.getFirst();
        assertThat(first.symbol()).isEqualTo("AAPL");
        assertThat(first.headline()).isEqualTo("Apple beats earnings, shares jump & guidance raised"); // suffix stripped, &amp; unescaped
        assertThat(first.source()).isEqualTo("Reuters");
        assertThat(first.url()).isEqualTo("https://news.google.com/rss/articles/abc123");
        assertThat(first.publishedEpochMs()).isEqualTo(Instant.parse("2026-07-06T14:00:00Z").toEpochMilli());
        assertThat(items.get(1).source()).isEqualTo("Bloomberg");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("q=").contains("AAPL");
    }

    @Test
    void emptySymbolMakesNoRequest() {
        assertThat(provider.news("  ")).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void serverErrorThrowsSoTheServiceCanOmitThisSource() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("nope"));
        assertThatThrownBy(() -> provider.news("AAPL")).isInstanceOf(Http.ProviderHttpException.class);
    }
}
