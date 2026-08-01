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
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.market.providers.Http;
import io.liftandshift.strikebench.market.providers.Http.ProviderHttpException;
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
import java.util.HashSet;
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
        Long cash = centsOrNull(computed.path("cashBalance"));
        Long bp = centsOrNull(computed.path("cashBuyingPower"));
        Long nav = centsOrNull(computed.path("RealTimeValues").path("totalAccountValue"));
        return new BrokerBalance(accountIdKey, cash, bp, nav, true,
                sourceEpochMillis(root.path("BalanceResponse"), computed).orElse(null));
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
                        centsOrNull(p.path("marketValue")),
                        centsOrNull(p.path("totalCost")),
                        sourceEpochMillis(p, acct).orElse(null)));
            }
        }
        return out;
    }

    @Override
    public OrderPreview previewOrder(String accountIdKey, OrderCommand command) {
        Map<String, Object> orderPayload = orderPayload(command);
        String body = Json.write(Map.of("PreviewOrderRequest", orderPayload));
        JsonNode root = Json.parse(signedSend("POST", base() + "/v1/accounts/" + accountIdKey + "/orders/preview.json", body));
        JsonNode res = root.path("PreviewOrderResponse");
        String previewId = res.path("PreviewIds").path(0).path("previewId").asText("");
        JsonNode order0 = res.path("Order").path(0);
        return new OrderPreview(previewId,
                centsOrNull(order0.path("estimatedTotalAmount")),
                centsOrNull(order0.path("estimatedCommission")),
                messages(order0), sourceEpochMillis(order0, res).orElse(null));
    }

    @Override
    public OrderResult placeOrder(String accountIdKey, OrderCommand command, String previewId,
                                  String clientOrderId) {
        Map<String, Object> payload;
        try {
            payload = new LinkedHashMap<>(orderPayload(command));
        } catch (RuntimeException rejectedBeforeRequest) {
            throw new OrderNotSubmittedException(
                    "The live order was rejected before any broker request was sent: "
                            + rejectedBeforeRequest.getMessage(), rejectedBeforeRequest);
        }
        payload.put("PreviewIds", List.of(Map.of("previewId", previewId)));
        payload.put("clientOrderId", clientOrderId);
        String body = Json.write(Map.of("PlaceOrderRequest", payload));
        String url = base() + "/v1/accounts/" + Http.pathSegment(accountIdKey)
                + "/orders/place.json";
        Map<String, String> headers;
        try {
            requireConnected();
            String auth = oauth.authorizationHeader("POST", url, null, accessCreds());
            headers = Map.of("Authorization", auth, "Content-Type", "application/json");
        } catch (RuntimeException rejectedBeforeRequest) {
            throw new OrderNotSubmittedException(
                    "The live order was rejected before any broker request was sent: "
                            + rejectedBeforeRequest.getMessage(), rejectedBeforeRequest);
        }
        JsonNode root;
        try {
            root = Json.parse(http.post(url, body, headers));
        } catch (ProviderHttpException response) {
            // E*TRADE documents HTTP 400 as a rejected placement. The broker responded without
            // accepting an order, so this is the one safe path out of UNKNOWN. Authentication,
            // throttling, transport, and server errors remain ambiguous and fail closed.
            if (response.statusCode() == 400) {
                throw new OrderNotSubmittedException(
                        "E*TRADE rejected the placement without accepting an order: "
                                + response.getMessage(), response);
            }
            throw response;
        }
        JsonNode res = root.path("PlaceOrderResponse");
        String orderId = res.path("OrderIds").path(0).path("orderId").asText("");
        return new OrderResult(orderId, orderId.isBlank() ? "UNKNOWN" : "OPEN", messages(res.path("Order").path(0)));
    }

    /**
     * Reconciliation deliberately reads the broker's order ledger instead of inferring success
     * from a transport exception. The same client id reserved before submission is the primary
     * identity; broker order id is an additive recovery key when the place response reached us.
     */
    @Override
    public OrderLookup findOrder(String accountIdKey, String clientOrderId,
                                 String brokerOrderId) {
        for (Map<String, Object> row : orders(accountIdKey)) {
            JsonNode order = Json.MAPPER.valueToTree(row);
            String rowClientId = firstText(order, "clientOrderId", "clientOrderID");
            String rowBrokerId = firstText(order, "orderId", "orderID");
            boolean clientMatch = clientOrderId != null && !clientOrderId.isBlank()
                    && clientOrderId.equals(rowClientId);
            boolean brokerMatch = brokerOrderId != null && !brokerOrderId.isBlank()
                    && brokerOrderId.equals(rowBrokerId);
            if (!clientMatch && !brokerMatch) continue;
            String status = firstText(order, "orderStatus", "status");
            if (status == null || status.isBlank()) status = "UNKNOWN";
            return OrderLookup.found(new OrderResult(
                    rowBrokerId == null ? "" : rowBrokerId,
                    status.toUpperCase(Locale.ROOT), messages(order)));
        }
        return OrderLookup.notFound();
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
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seenMarkers = new HashSet<>();
        String marker = null;
        while (true) {
            String url = base() + "/v1/accounts/" + Http.pathSegment(accountIdKey)
                    + "/orders.json?count=100"
                    + (marker == null ? "" : "&marker=" + Http.queryValue(marker));
            JsonNode root = Json.parse(signedGet(url));
            JsonNode response = root.path("OrdersResponse");
            for (JsonNode o : response.path("Order")) {
                out.add(Json.MAPPER.convertValue(o, Map.class));
            }
            String nextMarker = response.path("marker").asText("").trim();
            if (nextMarker.isEmpty()) break;
            if (!seenMarkers.add(nextMarker)) {
                throw new IllegalStateException(
                        "E*TRADE repeated an order-page marker; reconciliation is incomplete.");
            }
            marker = nextMarker;
        }
        return out;
    }

    /**
     * E*TRADE protocol translation only. Pricing, package sign, quantity and executability all
     * came from the normalized Practice result before this adapter is called.
     */
    private static Map<String, Object> orderPayload(OrderCommand command) {
        long stockLegs = command.legs().stream().filter(OrderLeg::stock).count();
        if (stockLegs > 0 && stockLegs != command.legs().size()) {
            throw new IllegalArgumentException(
                    "The E*TRADE live adapter does not submit mixed stock-and-option packages. "
                            + "Use held-share coverage for a covered option, or submit the stock "
                            + "and option orders separately at the broker.");
        }
        boolean stockOrder = stockLegs == command.legs().size();
        if (stockOrder && command.legs().size() != 1) {
            throw new IllegalArgumentException(
                    "The E*TRADE live adapter accepts one exact stock order at a time.");
        }
        if (!stockOrder && command.legs().stream().anyMatch(leg -> leg.multiplier() != 100)) {
            throw new IllegalArgumentException(
                    "Adjusted option deliverables are not supported by the E*TRADE live adapter.");
        }

        String orderType = stockOrder ? "EQ"
                : command.legs().size() == 1 ? "OPTN" : "SPREADS";
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("allOrNone", false);
        order.put("priceType", providerPriceType(command, stockOrder));
        order.put("orderTerm", "GOOD_FOR_DAY");
        order.put("marketSession", "REGULAR");
        if (command.orderInstruction().type()
                == io.liftandshift.strikebench.paper.OrderInstruction.Type.LIMIT) {
            order.put("limitPrice", providerLimitPrice(command, stockOrder));
        }

        List<Map<String, Object>> instruments = new ArrayList<>();
        for (OrderLeg leg : command.legs()) {
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("symbol", command.symbol());
            int quantity;
            String action;
            if (leg.stock()) {
                product.put("securityType", "EQ");
                quantity = Math.multiplyExact(command.quantity(),
                        Math.multiplyExact(leg.ratio(), leg.multiplier()));
                action = leg.action().name();
            } else {
                product.put("securityType", "OPTN");
                product.put("callPut", leg.type().name());
                product.put("expiryYear", leg.expiration().getYear());
                product.put("expiryMonth", leg.expiration().getMonthValue());
                product.put("expiryDay", leg.expiration().getDayOfMonth());
                product.put("strikePrice", leg.strike());
                quantity = Math.multiplyExact(command.quantity(), leg.ratio());
                action = leg.action().name() + "_OPEN";
            }
            instruments.add(Map.of(
                    "Product", product,
                    "orderAction", action,
                    "quantity", quantity));
        }
        order.put("Instrument", instruments);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderType", orderType);
        payload.put("Order", List.of(order));
        return payload;
    }

    private static String providerPriceType(OrderCommand command, boolean stockOrder) {
        if (command.orderInstruction().type()
                == io.liftandshift.strikebench.paper.OrderInstruction.Type.MARKET) {
            return "MARKET";
        }
        if (stockOrder || command.legs().size() == 1) return "LIMIT";
        long signedNet = command.orderInstruction().limitNetCents();
        if (signedNet > 0) return "NET_CREDIT";
        if (signedNet < 0) return "NET_DEBIT";
        return "NET_EVEN";
    }

    private static String providerLimitPrice(OrderCommand command, boolean stockOrder) {
        long signedNet = command.orderInstruction().limitNetCents();
        long units;
        if (stockOrder) {
            units = command.legs().stream().mapToLong(leg -> Math.multiplyExact(
                    (long) command.quantity(), Math.multiplyExact(
                            (long) leg.ratio(), (long) leg.multiplier()))).sum();
        } else {
            units = Math.multiplyExact((long) command.quantity(), 100L);
        }
        if (units <= 0) throw new IllegalArgumentException("live order has no priced units");
        io.liftandshift.strikebench.paper.PackageLimitTickPolicy.requireValid(
                command.orderInstruction(), stockOrder, units, command.quantity());
        return BigDecimal.valueOf(Math.abs(signedNet), 2)
                .divide(BigDecimal.valueOf(units), 4, java.math.RoundingMode.UNNECESSARY)
                .stripTrailingZeros().toPlainString();
    }

    private static String firstText(JsonNode root, String... names) {
        if (root == null || root.isMissingNode() || root.isNull()) return null;
        if (root.isObject()) {
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                for (String name : names) {
                    if (name.equalsIgnoreCase(field.getKey())) {
                        String value = field.getValue().asText("").trim();
                        if (!value.isEmpty()) return value;
                    }
                }
                String nested = firstText(field.getValue(), names);
                if (nested != null) return nested;
            }
        } else if (root.isArray()) {
            for (JsonNode child : root) {
                String nested = firstText(child, names);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static List<String> messages(JsonNode order) {
        List<String> out = new ArrayList<>();
        for (JsonNode m : order.path("messages").path("Message")) {
            String desc = m.path("description").asText("");
            if (!desc.isBlank()) out.add(desc);
        }
        return out;
    }

    private static Long centsOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) return null;
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
        String normalized = Symbol.normalize(symbol);
        JsonNode root = Json.parse(signedGet(base() + "/v1/market/quote/" + OAuth1.enc(normalized) + ".json"));
        JsonNode data = root.path("QuoteResponse").path("QuoteData").path(0);
        if (data.isMissingNode()) return Optional.empty();
        JsonNode all = data.path("All");
        Optional<Long> sourceTime = sourceEpochMillis(data, all);
        Freshness freshness = sourceTime.isEmpty() ? Freshness.STALE
                : "REALTIME".equalsIgnoreCase(data.path("quoteStatus").asText(""))
                        ? Freshness.REALTIME : Freshness.DELAYED;
        return Optional.of(new Quote(
                Symbol.normalize(data.path("Product").path("symbol").asText(normalized)),
                all.path("companyName").asText(""),
                dec(all.path("lastTrade")), dec(all.path("bid")), dec(all.path("ask")),
                dec(all.path("previousClose")), dec(all.path("high")), dec(all.path("low")),
                longOrNull(all.path("totalVolume")),
                true, sourceTime.orElse(0L), name(), freshness));
    }

    @Override
    public List<LocalDate> expirations(String symbol) {
        if (!connected()) return List.of();
        String normalized = Symbol.normalize(symbol);
        JsonNode root = Json.parse(signedGet(base() + "/v1/market/optionexpiredate.json?symbol=" + OAuth1.enc(normalized)));
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
        String sym = Symbol.normalize(symbol);
        String url = base() + "/v1/market/optionchains.json?symbol=" + OAuth1.enc(sym)
                + "&expiryYear=" + expiration.getYear() + "&expiryMonth=" + expiration.getMonthValue()
                + "&expiryDay=" + expiration.getDayOfMonth() + "&includeWeekly=true";
        JsonNode root = Json.parse(signedGet(url));
        JsonNode res = root.path("OptionChainResponse");
        if (res.isMissingNode()) return Optional.empty();
        Optional<Long> sourceTime = sourceEpochMillis(res);
        Freshness freshness = sourceTime.isEmpty() ? Freshness.STALE
                : "REALTIME".equalsIgnoreCase(res.path("quoteType").asText(""))
                        ? Freshness.REALTIME : Freshness.DELAYED;
        List<OptionQuote> calls = new ArrayList<>();
        List<OptionQuote> puts = new ArrayList<>();
        for (JsonNode pair : res.path("OptionPair")) {
            JsonNode call = pair.path("Call");
            JsonNode put = pair.path("Put");
            if (!call.isMissingNode() && call.has("strikePrice")) calls.add(toOptionQuote(
                    sym, call, OptionType.CALL, expiration, freshness, sourceTime.orElse(0L)));
            if (!put.isMissingNode() && put.has("strikePrice")) puts.add(toOptionQuote(
                    sym, put, OptionType.PUT, expiration, freshness, sourceTime.orElse(0L)));
        }
        if (calls.isEmpty() && puts.isEmpty()) return Optional.empty();
        return Optional.of(new OptionChain(sym, expiration, dec(res.path("nearPrice")), calls, puts,
                sourceTime.orElse(0L), name(), freshness));
    }

    private OptionQuote toOptionQuote(String symbol, JsonNode n, OptionType type, LocalDate expiration,
                                      Freshness freshness, long chainObservedAt) {
        JsonNode greeks = n.path("OptionGreeks");
        long observedAt = sourceEpochMillis(n, greeks).orElse(chainObservedAt);
        return new OptionQuote(symbol, n.path("osiKey").asText(""), type,
                dec(n.path("strikePrice")), expiration,
                dec(n.path("bid")), dec(n.path("ask")), dec(n.path("lastPrice")),
                longOrNull(n.path("volume")), longOrNull(n.path("openInterest")),
                dbl(greeks.path("iv")), dbl(greeks.path("delta")), dbl(greeks.path("gamma")),
                dbl(greeks.path("theta")), dbl(greeks.path("vega")),
                observedAt, name(), observedAt > 0 ? freshness : Freshness.STALE);
    }

    @Override
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        return List.of(); // E*TRADE has no candle endpoint in this API family
    }

    private static BigDecimal dec(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.decimalValue();
    }

    private static Double dbl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) return null;
        double value = node.doubleValue();
        return Double.isFinite(value) ? value : null;
    }

    private static Long longOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !node.isNumber()
                ? null : node.longValue();
    }

    /** Source timestamps only: receiving the HTTP response is not the same market observation. */
    private static Optional<Long> sourceEpochMillis(JsonNode... nodes) {
        String[] fields = {"dateTimeUTC", "quoteTime", "timeStamp", "timestamp", "asOf"};
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) continue;
            for (String field : fields) {
                JsonNode value = node.path(field);
                if (value.isNumber()) {
                    long raw = value.longValue();
                    if (raw <= 0) continue;
                    return Optional.of(raw < 10_000_000_000L ? raw * 1000L : raw);
                }
                String text = value.asText("").trim();
                if (text.isEmpty()) continue;
                try {
                    return Optional.of(java.time.Instant.parse(text).toEpochMilli());
                } catch (java.time.format.DateTimeParseException ignored) {
                    // Continue to the next broker-owned timestamp field; never invent one.
                }
            }
        }
        return Optional.empty();
    }
}
