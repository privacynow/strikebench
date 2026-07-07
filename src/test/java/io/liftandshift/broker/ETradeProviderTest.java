package io.liftandshift.broker;

import io.liftandshift.config.AppConfig;
import io.liftandshift.db.Db;
import io.liftandshift.db.Migrations;
import io.liftandshift.model.OptionChain;
import io.liftandshift.model.OptionType;
import io.liftandshift.model.Quote;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ETradeProviderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));

    @TempDir Path tmp;
    private MockWebServer server;
    private ETradeProvider provider;
    private SecretsStore secrets;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Db db = new Db(tmp.resolve("etrade.db").toString());
        Migrations.run(db);
        secrets = new SecretsStore(db, CLOCK);
        AppConfig cfg = new AppConfig(Map.of(
                "ETRADE_CONSUMER_KEY", "ck",
                "ETRADE_CONSUMER_SECRET", "cs",
                "ETRADE_BASE_URL", server.url("/").toString()));
        provider = new ETradeProvider(cfg, secrets, CLOCK);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void connect() throws Exception {
        server.enqueue(new MockResponse().setBody("oauth_token=rt&oauth_token_secret=rts"));
        provider.startConnect();
        server.takeRequest();
        server.enqueue(new MockResponse().setBody("oauth_token=at&oauth_token_secret=ats"));
        provider.verifyConnect("1234");
        server.takeRequest();
    }

    @Test
    void oauthConnectFlowSignsRequestsAndStoresTokens() throws Exception {
        assertThat(provider.configured()).isTrue();
        assertThat(provider.connected()).isFalse();

        server.enqueue(new MockResponse().setBody("oauth_token=rt&oauth_token_secret=rts"));
        String authorizeUrl = provider.startConnect();
        assertThat(authorizeUrl).startsWith("https://us.etrade.com/e/t/etws/authorize?key=ck&token=rt");

        RecordedRequest req1 = server.takeRequest();
        assertThat(req1.getPath()).isEqualTo("/oauth/request_token");
        String auth1 = req1.getHeader("Authorization");
        assertThat(auth1).startsWith("OAuth ")
                .contains("oauth_consumer_key=\"ck\"")
                .contains("oauth_callback=\"oob\"")
                .contains("oauth_signature_method=\"HMAC-SHA1\"")
                .containsPattern("oauth_signature=\"[^\"]+\"");

        server.enqueue(new MockResponse().setBody("oauth_token=at&oauth_token_secret=ats"));
        provider.verifyConnect("1234");
        RecordedRequest req2 = server.takeRequest();
        assertThat(req2.getPath()).isEqualTo("/oauth/access_token");
        assertThat(req2.getHeader("Authorization"))
                .contains("oauth_verifier=\"1234\"")
                .contains("oauth_token=\"rt\"");

        assertThat(provider.connected()).isTrue();
        assertThat(secrets.get(ETradeProvider.KEY_ACCESS_TOKEN)).contains("at");
        assertThat(secrets.get(ETradeProvider.KEY_ACCESS_DATE)).contains("2026-07-08");
    }

    @Test
    void accountsAndBalanceParse() throws Exception {
        connect();
        server.enqueue(new MockResponse().setBody("""
                {"AccountListResponse":{"Accounts":{"Account":[
                  {"accountId":"840104290","accountIdKey":"abcKey","accountName":"Individual Brokerage",
                   "accountType":"INDIVIDUAL","accountStatus":"ACTIVE"}]}}}"""));
        List<io.liftandshift.market.ports.BrokerageProvider.BrokerAccount> accounts = provider.accounts();
        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().accountIdKey()).isEqualTo("abcKey");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/accounts/list.json");
        assertThat(req.getHeader("Authorization")).contains("oauth_token=\"at\"");

        server.enqueue(new MockResponse().setBody("""
                {"BalanceResponse":{"accountId":"840104290","Computed":{
                  "cashBalance":5000.25,"cashBuyingPower":4800.50,
                  "RealTimeValues":{"totalAccountValue":10250.75}}}}"""));
        var balance = provider.balance("abcKey");
        assertThat(balance.cashCents()).isEqualTo(500025L);
        assertThat(balance.buyingPowerCents()).isEqualTo(480050L);
        assertThat(balance.netAccountValueCents()).isEqualTo(1025075L);
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/accounts/abcKey/balance.json?instType=BROKERAGE&realTimeNAV=true");
    }

    @Test
    void quoteAndChainParse() throws Exception {
        connect();
        server.enqueue(new MockResponse().setBody("""
                {"QuoteResponse":{"QuoteData":[{"dateTimeUTC":1624472491,"quoteStatus":"DELAYED",
                  "Product":{"symbol":"AAPL"},
                  "All":{"lastTrade":255.30,"bid":255.28,"ask":255.32,"previousClose":253.10,
                         "high":256.40,"low":252.80,"totalVolume":40123456,"companyName":"APPLE INC"}}]}}"""));
        Quote q = provider.quote("AAPL").orElseThrow();
        assertThat(q.last()).isEqualByComparingTo(new BigDecimal("255.30"));
        assertThat(q.freshness()).isEqualTo(io.liftandshift.model.Freshness.DELAYED);
        server.takeRequest();

        server.enqueue(new MockResponse().setBody("""
                {"OptionChainResponse":{"timeStamp":1624472491,"quoteType":"DELAYED","nearPrice":255.30,
                  "OptionPair":[
                    {"Call":{"strikePrice":255,"bid":8.10,"ask":8.40,"lastPrice":8.20,"volume":321,"openInterest":1234,
                             "osiKey":"AAPL--260821C00255000","OptionGreeks":{"iv":0.28,"delta":0.55,"gamma":0.02,"theta":-0.04,"vega":0.30}},
                     "Put":{"strikePrice":255,"bid":7.90,"ask":8.20,"lastPrice":8.00,"volume":222,"openInterest":999,
                            "osiKey":"AAPL--260821P00255000","OptionGreeks":{"iv":0.29,"delta":-0.45,"gamma":0.02,"theta":-0.04,"vega":0.30}}}]}}"""));
        OptionChain chain = provider.chain("AAPL", LocalDate.of(2026, 8, 21)).orElseThrow();
        assertThat(chain.calls()).hasSize(1);
        assertThat(chain.puts()).hasSize(1);
        assertThat(chain.find(OptionType.CALL, new BigDecimal("255")).orElseThrow().iv()).isEqualTo(0.28);
        assertThat(chain.underlyingPrice()).isEqualByComparingTo(new BigDecimal("255.30"));
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("symbol=AAPL").contains("expiryYear=2026").contains("expiryMonth=8").contains("expiryDay=21");
    }

    @Test
    void marketDataEmptyWhenNotConnected() {
        assertThat(provider.quote("AAPL")).isEmpty();
        assertThat(provider.expirations("AAPL")).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void tokensExpireAtMidnightEastern() throws Exception {
        connect();
        assertThat(provider.connected()).isTrue();
        // Same instant next day (ET): stored date no longer matches
        Clock nextDay = Clock.fixed(Instant.parse("2026-07-09T15:30:00Z"), ZoneId.of("America/New_York"));
        ETradeProvider later = new ETradeProvider(
                new AppConfig(Map.of("ETRADE_CONSUMER_KEY", "ck", "ETRADE_CONSUMER_SECRET", "cs",
                        "ETRADE_BASE_URL", server.url("/").toString())), secrets, nextDay);
        assertThat(later.connected()).isFalse();
    }

    @Test
    void previewAndPlaceOrderRoundTrip() throws Exception {
        connect();
        server.enqueue(new MockResponse().setBody("""
                {"PreviewOrderResponse":{"PreviewIds":[{"previewId":730469380}],
                  "Order":[{"estimatedCommission":0.65,"estimatedTotalAmount":-181.35,
                            "messages":{"Message":[{"description":"You are about to place a spread order"}]}}]}}"""));
        var preview = provider.previewOrder("abcKey", Map.of("orderType", "SPREADS"));
        assertThat(preview.previewId()).isEqualTo("730469380");
        assertThat(preview.estimatedTotalCents()).isEqualTo(-18135L);
        assertThat(preview.estimatedCommissionCents()).isEqualTo(65L);
        assertThat(preview.messages()).hasSize(1);
        RecordedRequest previewReq = server.takeRequest();
        assertThat(previewReq.getPath()).isEqualTo("/v1/accounts/abcKey/orders/preview.json");
        assertThat(previewReq.getBody().readUtf8()).contains("\"PreviewOrderRequest\"");

        server.enqueue(new MockResponse().setBody("""
                {"PlaceOrderResponse":{"OrderIds":[{"orderId":529}],"Order":[{}]}}"""));
        var result = provider.placeOrder("abcKey", Map.of("orderType", "SPREADS"), "730469380", "client-1");
        assertThat(result.brokerOrderId()).isEqualTo("529");
        assertThat(result.status()).isEqualTo("OPEN");
        String placeBody = server.takeRequest().getBody().readUtf8();
        assertThat(placeBody).contains("\"clientOrderId\":\"client-1\"").contains("\"previewId\":\"730469380\"");
    }

    @Test
    void httpErrorsPropagate() throws Exception {
        connect();
        server.enqueue(new MockResponse().setResponseCode(500).setBody("oops"));
        assertThatThrownBy(() -> provider.accounts()).hasMessageContaining("500");
    }
}
