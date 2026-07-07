package io.liftandshift.market.providers;

import io.liftandshift.config.AppConfig;
import io.liftandshift.market.Domain;
import io.liftandshift.model.Freshness;
import io.liftandshift.model.OptionChain;
import io.liftandshift.model.OptionQuote;
import io.liftandshift.model.OptionType;
import io.liftandshift.model.Quote;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CboeProviderTest {

    private static final String CHAIN_BODY = """
            {
              "timestamp": "2026-07-06 12:00:00",
              "data": {
                "symbol": "AAPL",
                "current_price": 255.30,
                "close": 254.90,
                "bid": 255.28,
                "ask": 255.32,
                "prev_day_close": 253.10,
                "volume": 123456,
                "options": [
                  {"option":"AAPL260821C00255000","bid":8.10,"ask":8.40,"iv":0.28,"open_interest":1234,"volume":321,"delta":0.55,"gamma":0.02,"theta":-0.04,"vega":0.30,"last_trade_price":8.20},
                  {"option":"AAPL260821P00250000","bid":5.05,"ask":5.35,"iv":0.30,"open_interest":900,"volume":150,"delta":-0.40,"gamma":0.021,"theta":-0.05,"vega":0.29,"last_trade_price":5.15},
                  {"option":"AAPL260918C00260000","bid":9.00,"ask":9.50,"iv":0.27,"open_interest":400,"volume":25,"delta":0.48,"gamma":0.015,"theta":-0.03,"vega":0.41,"last_trade_price":9.10},
                  {"option":"AAPL260918P00245000","bid":6.20,"ask":6.60,"iv":0.31,"open_interest":700,"volume":60,"delta":-0.33,"gamma":0.014,"theta":-0.035,"vega":0.40,"last_trade_price":6.35}
                ]
              }
            }
            """;

    private MockWebServer server;
    private CboeProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        AppConfig cfg = new AppConfig(Map.of("CBOE_BASE_URL", server.url("/").toString()));
        provider = new CboeProvider(cfg);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void identity() {
        assertThat(provider.name()).isEqualTo("cboe");
        assertThat(provider.domains()).containsExactlyInAnyOrder(Domain.OPTIONS, Domain.QUOTES);
    }

    @Test
    void quoteParsesUnderlyingAndRequestsExpectedPath() throws Exception {
        server.enqueue(new MockResponse().setBody(CHAIN_BODY));

        Quote q = provider.quote("aapl").orElseThrow();

        assertThat(q.symbol()).isEqualTo("AAPL");
        assertThat(q.last()).isEqualByComparingTo(new BigDecimal("255.30"));
        assertThat(q.bid()).isEqualByComparingTo(new BigDecimal("255.28"));
        assertThat(q.ask()).isEqualByComparingTo(new BigDecimal("255.32"));
        assertThat(q.prevClose()).isEqualByComparingTo(new BigDecimal("253.10"));
        assertThat(q.volume()).isEqualTo(123456L);
        assertThat(q.optionable()).isTrue();
        assertThat(q.freshness()).isEqualTo(Freshness.DELAYED);
        assertThat(q.source()).isEqualTo("cboe");

        assertThat(server.takeRequest().getPath())
                .isEqualTo("/api/global/delayed_quotes/options/AAPL.json");
    }

    @Test
    void quoteFallsBackToCloseWhenCurrentPriceMissing() {
        server.enqueue(new MockResponse().setBody("""
                {"data":{"symbol":"AAPL","close":254.90,"options":[]}}
                """));

        Quote q = provider.quote("AAPL").orElseThrow();

        assertThat(q.last()).isEqualByComparingTo(new BigDecimal("254.90"));
        assertThat(q.bid()).isNull();
        assertThat(q.optionable()).isFalse();
    }

    @Test
    void expirationsAreDistinctAndSorted() {
        server.enqueue(new MockResponse().setBody(CHAIN_BODY));

        assertThat(provider.expirations("AAPL")).containsExactly(
                LocalDate.of(2026, 8, 21),
                LocalDate.of(2026, 9, 18));
    }

    @Test
    void chainParsesOccSymbolsIntoCallsAndPuts() {
        server.enqueue(new MockResponse().setBody(CHAIN_BODY));

        OptionChain chain = provider.chain("AAPL", LocalDate.of(2026, 8, 21)).orElseThrow();

        assertThat(chain.underlying()).isEqualTo("AAPL");
        assertThat(chain.expiration()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(chain.underlyingPrice()).isEqualByComparingTo(new BigDecimal("255.30"));
        assertThat(chain.freshness()).isEqualTo(Freshness.DELAYED);
        assertThat(chain.source()).isEqualTo("cboe");
        assertThat(chain.calls()).hasSize(1);
        assertThat(chain.puts()).hasSize(1);

        OptionQuote call = chain.calls().getFirst();
        assertThat(call.occSymbol()).isEqualTo("AAPL260821C00255000");
        assertThat(call.type()).isEqualTo(OptionType.CALL);
        assertThat(call.strike()).isEqualByComparingTo(new BigDecimal("255"));
        assertThat(call.expiration()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(call.bid()).isEqualByComparingTo(new BigDecimal("8.10"));
        assertThat(call.ask()).isEqualByComparingTo(new BigDecimal("8.40"));
        assertThat(call.last()).isEqualByComparingTo(new BigDecimal("8.20"));
        assertThat(call.mid()).isEqualByComparingTo(new BigDecimal("8.25"));
        assertThat(call.iv()).isEqualTo(0.28);
        assertThat(call.delta()).isEqualTo(0.55);
        assertThat(call.gamma()).isEqualTo(0.02);
        assertThat(call.theta()).isEqualTo(-0.04);
        assertThat(call.vega()).isEqualTo(0.30);
        assertThat(call.openInterest()).isEqualTo(1234L);
        assertThat(call.volume()).isEqualTo(321L);
        assertThat(call.freshness()).isEqualTo(Freshness.DELAYED);

        OptionQuote put = chain.puts().getFirst();
        assertThat(put.occSymbol()).isEqualTo("AAPL260821P00250000");
        assertThat(put.type()).isEqualTo(OptionType.PUT);
        assertThat(put.strike()).isEqualByComparingTo(new BigDecimal("250"));
        assertThat(put.iv()).isEqualTo(0.30);
    }

    @Test
    void chainForUnlistedExpirationIsEmpty() {
        server.enqueue(new MockResponse().setBody(CHAIN_BODY));

        assertThat(provider.chain("AAPL", LocalDate.of(2027, 1, 15))).isEmpty();
    }

    @Test
    void notFoundMeansUnknownSymbolNotFailure() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));

        assertThat(provider.quote("NOPE")).isEmpty();
        assertThat(provider.expirations("NOPE")).isEmpty();
    }

    @Test
    void accessDenied403MeansUnknownSymbolNotFailure() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody(
                "<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>"));
        assertThat(provider.quote("ZZZZ")).isEmpty();
    }

    @Test
    void serverErrorPropagates() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> provider.quote("AAPL"))
                .isInstanceOf(Http.ProviderHttpException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void lookupCandlesAndNewsAreEmpty() {
        assertThat(provider.lookup("apple")).isEmpty();
        assertThat(provider.candles("AAPL", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1))).isEmpty();
        assertThat(provider.news("AAPL")).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void oneDownloadServesQuoteExpirationsAndChains() throws Exception {
        server.enqueue(new MockResponse().setBody(CHAIN_BODY));
        provider.quote("AAPL");
        provider.expirations("AAPL");
        java.util.List<java.time.LocalDate> exps = provider.expirations("AAPL");
        for (java.time.LocalDate exp : exps) provider.chain("AAPL", exp);
        org.assertj.core.api.Assertions.assertThat(server.getRequestCount())
                .as("payload cached per symbol: many calls, one HTTP download")
                .isEqualTo(1);
    }
}
