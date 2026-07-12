package io.liftandshift.strikebench.market.providers;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolygonProviderTest {

    private MockWebServer server;
    private PolygonProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new PolygonProvider(new AppConfig(Map.of(
                "POLYGON_BASE_URL", server.url("/").toString(),
                "POLYGON_API_KEY", "test-key",
                "HTTP_TIMEOUT_MS", "3000")));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    @Test
    void candlesParseHappyPath() throws Exception {
        // 1719878400000 ms = 2024-07-02T00:00:00Z
        server.enqueue(json("""
                {"ticker":"AAPL","resultsCount":2,"results":[
                  {"t":1719878400000,"o":253.2,"h":256.4,"l":252.8,"c":255.3,"v":40123456},
                  {"t":1719964800000,"o":255.5,"h":258.0,"l":254.9,"c":257.15,"v":38000000}
                ]}"""));

        List<Candle> candles = provider.candles("aapl", LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 5));

        assertThat(candles).hasSize(2);
        Candle first = candles.getFirst();
        assertThat(first.date()).isEqualTo(LocalDate.of(2024, 7, 2));
        assertThat(first.open()).isEqualByComparingTo(new BigDecimal("253.2"));
        assertThat(first.high()).isEqualByComparingTo(new BigDecimal("256.4"));
        assertThat(first.low()).isEqualByComparingTo(new BigDecimal("252.8"));
        assertThat(first.close()).isEqualByComparingTo(new BigDecimal("255.3"));
        assertThat(first.volume()).isEqualTo(40123456L);
        assertThat(first.adjusted()).isTrue();
        assertThat(candles.getLast().date()).isEqualTo(LocalDate.of(2024, 7, 3));
        assertThat(candles.getLast().close()).isEqualByComparingTo(new BigDecimal("257.15"));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo(
                "/v2/aggs/ticker/AAPL/range/1/day/2024-07-01/2024-07-05?adjusted=true&sort=asc&limit=5000&apiKey=test-key");
    }

    @Test
    void candlesEmptyResultsReturnsEmptyList() {
        server.enqueue(json("{\"ticker\":\"NOPE\",\"resultsCount\":0}"));
        assertThat(provider.candles("NOPE", LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 5))).isEmpty();
    }

    @Test
    void historicalExpirationsAreDistinctAndSorted() throws Exception {
        server.enqueue(json("""
                {"results":[
                  {"ticker":"O:AAPL260918C00260000","strike_price":260,"contract_type":"call","expiration_date":"2026-09-18"},
                  {"ticker":"O:AAPL260821C00255000","strike_price":255,"contract_type":"call","expiration_date":"2026-08-21"},
                  {"ticker":"O:AAPL260821P00250000","strike_price":250,"contract_type":"put","expiration_date":"2026-08-21"}
                ]}"""));

        List<LocalDate> expirations = provider.historicalExpirations("AAPL", LocalDate.of(2026, 7, 1));

        assertThat(expirations).containsExactly(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 9, 18));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath())
                .startsWith("/v3/reference/options/contracts?")
                .contains("underlying_ticker=AAPL")
                .contains("as_of=2026-07-01")
                .contains("limit=1000")
                .contains("apiKey=test-key");
    }

    @Test
    void historicalChainBuildsEodQuotesFromContractCloses() throws Exception {
        LocalDate asOf = LocalDate.of(2026, 7, 1);
        LocalDate expiration = LocalDate.of(2026, 8, 21);
        // Request order: contracts reference, underlying agg, then one agg per contract.
        server.enqueue(json("""
                {"results":[
                  {"ticker":"O:AAPL260821C00255000","strike_price":255,"contract_type":"call","expiration_date":"2026-08-21"},
                  {"ticker":"O:AAPL260821P00250000","strike_price":250.0,"contract_type":"put","expiration_date":"2026-08-21"}
                ]}"""));
        server.enqueue(json("{\"results\":[{\"t\":1782777600000,\"o\":253.2,\"h\":256.4,\"l\":252.8,\"c\":254.1,\"v\":40123456}]}"));
        server.enqueue(json("{\"results\":[{\"c\":12.35,\"v\":150}]}"));
        server.enqueue(json("{\"results\":[{\"c\":7.8,\"v\":90}]}"));

        OptionChain chain = provider.historicalChain("aapl", asOf, expiration).orElseThrow();

        assertThat(chain.underlying()).isEqualTo("AAPL");
        assertThat(chain.expiration()).isEqualTo(expiration);
        assertThat(chain.underlyingPrice()).isEqualByComparingTo(new BigDecimal("254.1"));
        assertThat(chain.freshness()).isEqualTo(Freshness.EOD);
        assertThat(chain.source()).isEqualTo("polygon");

        assertThat(chain.calls()).hasSize(1);
        OptionQuote call = chain.calls().getFirst();
        assertThat(call.type()).isEqualTo(OptionType.CALL);
        assertThat(call.occSymbol()).isEqualTo("O:AAPL260821C00255000");
        assertThat(call.strike()).isEqualByComparingTo(new BigDecimal("255"));
        assertThat(call.last()).isEqualByComparingTo(new BigDecimal("12.35"));
        assertThat(call.volume()).isEqualTo(150L);
        assertThat(call.bid()).isNull();
        assertThat(call.ask()).isNull();
        assertThat(call.openInterest()).isNull();
        assertThat(call.iv()).isNull();
        assertThat(call.delta()).isNull();
        assertThat(call.freshness()).isEqualTo(Freshness.EOD);
        assertThat(call.source()).isEqualTo("polygon");

        assertThat(chain.puts()).hasSize(1);
        OptionQuote put = chain.puts().getFirst();
        assertThat(put.type()).isEqualTo(OptionType.PUT);
        assertThat(put.strike()).isEqualByComparingTo(new BigDecimal("250"));
        assertThat(put.last()).isEqualByComparingTo(new BigDecimal("7.8"));
        assertThat(put.volume()).isEqualTo(90L);

        // Verify request order and params (colon in the contract ticker is a legal raw path char,
        // but assert on the colon-free tail to stay encoding-agnostic).
        assertThat(server.getRequestCount()).isEqualTo(4);
        RecordedRequest contractsReq = server.takeRequest();
        assertThat(contractsReq.getPath())
                .startsWith("/v3/reference/options/contracts?")
                .contains("underlying_ticker=AAPL")
                .contains("as_of=2026-07-01")
                .contains("expiration_date=2026-08-21")
                .contains("limit=250")
                .contains("apiKey=test-key");
        RecordedRequest underlyingReq = server.takeRequest();
        assertThat(underlyingReq.getPath())
                .startsWith("/v2/aggs/ticker/AAPL/range/1/day/2026-07-01/2026-07-01")
                .contains("apiKey=test-key");
        assertThat(server.takeRequest().getPath()).contains("AAPL260821C00255000");
        assertThat(server.takeRequest().getPath()).contains("AAPL260821P00250000");
    }

    @Test
    void historicalChainSkipsContractsWithoutAggregates() {
        server.enqueue(json("""
                {"results":[
                  {"ticker":"O:AAPL260821C00255000","strike_price":255,"contract_type":"call","expiration_date":"2026-08-21"},
                  {"ticker":"O:AAPL260821P00250000","strike_price":250,"contract_type":"put","expiration_date":"2026-08-21"}
                ]}"""));
        server.enqueue(json("{\"results\":[{\"c\":254.1,\"v\":100}]}")); // underlying
        server.enqueue(json("{\"resultsCount\":0}"));                     // call: no bar that day
        server.enqueue(json("{\"results\":[{\"c\":7.8,\"v\":90}]}"));     // put

        OptionChain chain = provider
                .historicalChain("AAPL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 21))
                .orElseThrow();

        assertThat(chain.calls()).isEmpty();
        assertThat(chain.puts()).hasSize(1);
        assertThat(chain.puts().getFirst().last()).isEqualByComparingTo(new BigDecimal("7.8"));
    }

    @Test
    void historicalChainWithNoContractsIsEmpty() {
        server.enqueue(json("{\"status\":\"OK\",\"results\":[]}"));

        Optional<OptionChain> chain = provider
                .historicalChain("AAPL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 21));

        assertThat(chain).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1); // no aggregate calls attempted
    }

    @Test
    void serverErrorPropagatesAsProviderHttpException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertThatThrownBy(() -> provider.candles("AAPL", LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 5)))
                .isInstanceOf(Http.ProviderHttpException.class)
                .hasMessageContaining("500");
    }

    @Test
    void liveMarketDataMethodsAreIntentionallyEmpty() {
        assertThat(provider.name()).isEqualTo("polygon");
        assertThat(provider.domains()).containsExactlyInAnyOrder(
                io.liftandshift.strikebench.market.Domain.CANDLES, io.liftandshift.strikebench.market.Domain.HISTORICAL_OPTIONS);
        assertThat(provider.lookup("AAPL")).isEmpty();
        assertThat(provider.quote("AAPL")).isEmpty();
        assertThat(provider.expirations("AAPL")).isEmpty();
        assertThat(provider.chain("AAPL", LocalDate.of(2026, 8, 21))).isEmpty();
        assertThat(server.getRequestCount()).isZero(); // none of the above touch the network
    }
}
