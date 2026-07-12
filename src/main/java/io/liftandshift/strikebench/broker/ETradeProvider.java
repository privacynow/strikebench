package io.liftandshift.strikebench.broker;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.ports.BrokerageProvider;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.market.providers.Http;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * E*TRADE adapter: OAuth 1.0a (server-side only, tokens in the local secrets table,
 * expiring at midnight US/Eastern per E*TRADE policy) + market data + account/order calls.
 * Market data methods return empty until connected, so the provider chain skips ahead.
 */
public final class ETradeProvider implements BrokerageProvider, MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(ETradeProvider.class);
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    static final String KEY_ACCESS_TOKEN = "etrade.access_token";
    static final String KEY_ACCESS_SECRET = "etrade.access_secret";
    static final String KEY_ACCESS_DATE = "etrade.access_date";

    private final AppConfig cfg;
    private final Http http;
    private final OAuth1 oauth;
    private final SecretsStore secrets;
    private final Clock clock;

    // Pending request token during the interactive connect flow
    private volatile String requestToken;
    private volatile String requestTokenSecret;

    public ETradeProvider(AppConfig cfg, SecretsStore secrets, Clock clock) {
        this(cfg, secrets, clock, new OAuth1());
    }

    public ETradeProvider(AppConfig cfg, SecretsStore secrets, Clock clock, OAuth1 oauth) {
        this.cfg = cfg;
        this.secrets = secrets;
        this.clock = clock;
        this.oauth = oauth;
        this.http = new Http(cfg.httpTimeoutMs());
    }

    private String base() {
        String override = cfg.etradeBaseUrlOverride();
        if (!override.isBlank()) return Http.normalizeBase(override);
        return cfg.etradeSandbox() ? "https://apisb.etrade.com" : "https://api.etrade.com";
    }

    // ---- BrokerageProvider: connection ----

    @Override public String name() { return "etrade"; }

    @Override
    public boolean configured() {
        return !cfg.etradeConsumerKey().isBlank() && !cfg.etradeConsumerSecret().isBlank();
    }

    @Override
    public boolean connected() {
        if (!configured()) return false;
        Optional<String> token = secrets.get(KEY_ACCESS_TOKEN);
        Optional<String> date = secrets.get(KEY_ACCESS_DATE);
        if (token.isEmpty() || date.isEmpty()) return false;
        // Tokens die at midnight US/Eastern
        return LocalDate.now(clock.withZone(EASTERN)).toString().equals(date.get());
    }

    @Override
    public String startConnect() {
        requireConfigured();
        String url = base() + "/oauth/request_token";
        String auth = oauth.authorizationHeader("GET", url, Map.of("oauth_callback", "oob"),
                new OAuth1.Creds(cfg.etradeConsumerKey(), cfg.etradeConsumerSecret(), null, null));
        Map<String, String> form = OAuth1.parseForm(http.get(url, Map.of("Authorization", auth)));
        this.requestToken = form.get("oauth_token");
        this.requestTokenSecret = form.get("oauth_token_secret");
        if (requestToken == null || requestTokenSecret == null) {
            throw new IllegalStateException("E*TRADE did not return a request token");
        }
        return "https://us.etrade.com/e/t/etws/authorize?key=" + OAuth1.enc(cfg.etradeConsumerKey())
                + "&token=" + OAuth1.enc(requestToken);
    }

    @Override
    public void verifyConnect(String verifierCode) {
        requireConfigured();
        if (requestToken == null) throw new IllegalStateException("start the connect flow first");
        if (verifierCode == null || verifierCode.isBlank()) throw new IllegalArgumentException("verifier code is required");
        String url = base() + "/oauth/access_token";
        String auth = oauth.authorizationHeader("GET", url, Map.of("oauth_verifier", verifierCode.trim()),
                new OAuth1.Creds(cfg.etradeConsumerKey(), cfg.etradeConsumerSecret(), requestToken, requestTokenSecret));
        Map<String, String> form = OAuth1.parseForm(http.get(url, Map.of("Authorization", auth)));
        String token = form.get("oauth_token");
        String secret = form.get("oauth_token_secret");
        if (token == null || secret == null) throw new IllegalStateException("E*TRADE did not return an access token");
        secrets.put(KEY_ACCESS_TOKEN, token);
        secrets.put(KEY_ACCESS_SECRET, secret);
        secrets.put(KEY_ACCESS_DATE, LocalDate.now(clock.withZone(EASTERN)).toString());
        this.requestToken = null;
        this.requestTokenSecret = null;
        log.info("E*TRADE connected (sandbox={})", cfg.etradeSandbox());
    }

    private void requireConfigured() {
        if (!configured()) throw new IllegalStateException("E*TRADE consumer key/secret are not configured");
    }

    private void requireConnected() {
        if (!connected()) throw new IllegalStateException("Not connected to E*TRADE (tokens expire at midnight ET; reconnect)");
    }

    private OAuth1.Creds accessCreds() {
        return new OAuth1.Creds(cfg.etradeConsumerKey(), cfg.etradeConsumerSecret(),
                secrets.get(KEY_ACCESS_TOKEN).orElse(null), secrets.get(KEY_ACCESS_SECRET).orElse(null));
    }

    private String signedGet(String url) {
        requireConnected();
        String auth = oauth.authorizationHeader("GET", url, null, accessCreds());
        return http.get(url, Map.of("Authorization", auth));
    }

    private String signedSend(String method, String url, String jsonBody) {
        requireConnected();
        String auth = oauth.authorizationHeader(method, url, null, accessCreds());
        Map<String, String> headers = Map.of("Authorization", auth, "Content-Type", "application/json");
        return method.equals("PUT") ? http.put(url, jsonBody, headers) : http.post(url, jsonBody, headers);
    }

    // ---- BrokerageProvider: accounts & orders ----

    @Override
    public List<BrokerAccount> accounts() {
        JsonNode root = Json.parse(signedGet(base() + "/v1/accounts/list.json"));
        List<BrokerAccount> out = new ArrayList<>();
        for (JsonNode a : root.path("AccountListResponse").path("Accounts").path("Account")) {
            out.add(new BrokerAccount(a.path("accountIdKey").asText(), a.path("accountId").asText(),
                    a.path("accountName").asText(a.path("accountDesc").asText("")),
                    a.path("accountType").asText(""), a.path("accountStatus").asText("")));
        }
        return out;
    }

    @Override
    public BrokerBalance balance(String accountIdKey) {
        JsonNode root = Json.parse(signedGet(base() + "/v1/accounts/" + accountIdKey
                + "/balance.json?instType=BROKERAGE&realTimeNAV=true"));
        JsonNode computed = root.path("BalanceResponse").path("Computed");
        long cash = cents(computed.path("cashBalance"));
        long bp = cents(computed.path("cashBuyingPower"));
        long nav = cents(computed.path("RealTimeValues").path("totalAccountValue"));
        return new BrokerBalance(accountIdKey, cash, bp, nav, true);
    }

    @Override
    public List<BrokerPosition> positions(String accountIdKey) {
        JsonNode root = Json.parse(signedGet(base() + "/v1/accounts/" + accountIdKey + "/portfolio.json"));
        List<BrokerPosition> out = new ArrayList<>();
        for (JsonNode acct : root.path("PortfolioResponse").path("AccountPortfolio")) {
            for (JsonNode p : acct.path("Position")) {
                out.add(new BrokerPosition(
                        p.path("Product").path("symbol").asText(p.path("symbolDescription").asText("")),
                        p.path("symbolDescription").asText(""),
                        p.path("positionType").asText(""),
                        p.path("quantity").asDouble(),
                        cents(p.path("marketValue")),
                        cents(p.path("totalCost"))));
            }
        }
        return out;
    }

    @Override
    public OrderPreview previewOrder(String accountIdKey, Map<String, Object> orderPayload) {
        String body = Json.write(Map.of("PreviewOrderRequest", orderPayload));
        JsonNode root = Json.parse(signedSend("POST", base() + "/v1/accounts/" + accountIdKey + "/orders/preview.json", body));
        JsonNode res = root.path("PreviewOrderResponse");
        String previewId = res.path("PreviewIds").path(0).path("previewId").asText("");
        JsonNode order0 = res.path("Order").path(0);
        return new OrderPreview(previewId,
                cents(order0.path("estimatedTotalAmount")),
                cents(order0.path("estimatedCommission")),
                messages(order0));
    }

    @Override
    public OrderResult placeOrder(String accountIdKey, Map<String, Object> orderPayload, String previewId, String clientOrderId) {
        Map<String, Object> payload = new LinkedHashMap<>(orderPayload);
        payload.put("PreviewIds", List.of(Map.of("previewId", previewId)));
        payload.put("clientOrderId", clientOrderId);
        String body = Json.write(Map.of("PlaceOrderRequest", payload));
        JsonNode root = Json.parse(signedSend("POST", base() + "/v1/accounts/" + accountIdKey + "/orders/place.json", body));
        JsonNode res = root.path("PlaceOrderResponse");
        String orderId = res.path("OrderIds").path(0).path("orderId").asText("");
        return new OrderResult(orderId, orderId.isBlank() ? "UNKNOWN" : "OPEN", messages(res.path("Order").path(0)));
    }

    @Override
    public void cancelOrder(String accountIdKey, String brokerOrderId) {
        Map<String, Object> payload;
        try {
            payload = Map.of("CancelOrderRequest", Map.of("orderId", Long.parseLong(brokerOrderId)));
        } catch (NumberFormatException e) {
            payload = Map.of("CancelOrderRequest", Map.of("orderId", brokerOrderId));
        }
        signedSend("PUT", base() + "/v1/accounts/" + accountIdKey + "/orders/cancel.json", Json.write(payload));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> orders(String accountIdKey) {
        JsonNode root = Json.parse(signedGet(base() + "/v1/accounts/" + accountIdKey + "/orders.json"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode o : root.path("OrdersResponse").path("Order")) {
            out.add(Json.MAPPER.convertValue(o, Map.class));
        }
        return out;
    }

    private static List<String> messages(JsonNode order) {
        List<String> out = new ArrayList<>();
        for (JsonNode m : order.path("messages").path("Message")) {
            String desc = m.path("description").asText("");
            if (!desc.isBlank()) out.add(desc);
        }
        return out;
    }

    private static long cents(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        return Money.toCents(node.decimalValue());
    }

    // ---- MarketDataProvider (only useful once connected) ----

    @Override public Set<Domain> domains() { return Set.of(Domain.QUOTES, Domain.OPTIONS); }

    @Override
    public List<SymbolMatch> lookup(String query) {
        if (!connected()) return List.of();
        JsonNode root = Json.parse(signedGet(base() + "/v1/market/lookup/" + OAuth1.enc(query) + ".json"));
        List<SymbolMatch> out = new ArrayList<>();
        for (JsonNode d : root.path("LookupResponse").path("Data")) {
            out.add(new SymbolMatch(d.path("symbol").asText(), d.path("description").asText(""), true));
        }
        return out;
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        if (!connected()) return Optional.empty();
        JsonNode root = Json.parse(signedGet(base() + "/v1/market/quote/" + OAuth1.enc(symbol) + ".json"));
        JsonNode data = root.path("QuoteResponse").path("QuoteData").path(0);
        if (data.isMissingNode()) return Optional.empty();
        JsonNode all = data.path("All");
        Freshness freshness = "REALTIME".equalsIgnoreCase(data.path("quoteStatus").asText("")) ? Freshness.REALTIME : Freshness.DELAYED;
        return Optional.of(new Quote(
                data.path("Product").path("symbol").asText(symbol.toUpperCase(Locale.ROOT)),
                all.path("companyName").asText(""),
                dec(all.path("lastTrade")), dec(all.path("bid")), dec(all.path("ask")),
                dec(all.path("previousClose")), dec(all.path("high")), dec(all.path("low")),
                all.path("totalVolume").isMissingNode() ? null : all.path("totalVolume").asLong(),
                true, System.currentTimeMillis(), name(), freshness));
    }

    @Override
    public List<LocalDate> expirations(String symbol) {
        if (!connected()) return List.of();
        JsonNode root = Json.parse(signedGet(base() + "/v1/market/optionexpiredate.json?symbol=" + OAuth1.enc(symbol)));
        List<LocalDate> out = new ArrayList<>();
        for (JsonNode d : root.path("OptionExpireDateResponse").path("ExpirationDate")) {
            out.add(LocalDate.of(d.path("year").asInt(), d.path("month").asInt(), d.path("day").asInt()));
        }
        out.sort(LocalDate::compareTo);
        return out;
    }

    @Override
    public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
        if (!connected()) return Optional.empty();
        String url = base() + "/v1/market/optionchains.json?symbol=" + OAuth1.enc(symbol)
                + "&expiryYear=" + expiration.getYear() + "&expiryMonth=" + expiration.getMonthValue()
                + "&expiryDay=" + expiration.getDayOfMonth() + "&includeWeekly=true";
        JsonNode root = Json.parse(signedGet(url));
        JsonNode res = root.path("OptionChainResponse");
        if (res.isMissingNode()) return Optional.empty();
        Freshness freshness = "REALTIME".equalsIgnoreCase(res.path("quoteType").asText("")) ? Freshness.REALTIME : Freshness.DELAYED;
        List<OptionQuote> calls = new ArrayList<>();
        List<OptionQuote> puts = new ArrayList<>();
        String sym = symbol.toUpperCase(Locale.ROOT);
        for (JsonNode pair : res.path("OptionPair")) {
            JsonNode call = pair.path("Call");
            JsonNode put = pair.path("Put");
            if (!call.isMissingNode() && call.has("strikePrice")) calls.add(toOptionQuote(sym, call, OptionType.CALL, expiration, freshness));
            if (!put.isMissingNode() && put.has("strikePrice")) puts.add(toOptionQuote(sym, put, OptionType.PUT, expiration, freshness));
        }
        if (calls.isEmpty() && puts.isEmpty()) return Optional.empty();
        return Optional.of(new OptionChain(sym, expiration, dec(res.path("nearPrice")), calls, puts,
                System.currentTimeMillis(), name(), freshness));
    }

    private OptionQuote toOptionQuote(String symbol, JsonNode n, OptionType type, LocalDate expiration, Freshness freshness) {
        JsonNode greeks = n.path("OptionGreeks");
        return new OptionQuote(symbol, n.path("osiKey").asText(""), type,
                dec(n.path("strikePrice")), expiration,
                dec(n.path("bid")), dec(n.path("ask")), dec(n.path("lastPrice")),
                n.path("volume").isMissingNode() ? null : n.path("volume").asLong(),
                n.path("openInterest").isMissingNode() ? null : n.path("openInterest").asLong(),
                dbl(greeks.path("iv")), dbl(greeks.path("delta")), dbl(greeks.path("gamma")),
                dbl(greeks.path("theta")), dbl(greeks.path("vega")),
                System.currentTimeMillis(), name(), freshness);
    }

    @Override
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        return List.of(); // E*TRADE has no candle endpoint in this API family
    }

    private static BigDecimal dec(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.decimalValue();
    }

    private static Double dbl(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asDouble();
    }
}
