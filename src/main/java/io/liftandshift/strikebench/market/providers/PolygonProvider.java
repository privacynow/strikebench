package io.liftandshift.strikebench.market.providers;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.ports.HistoricalOptionsProvider;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Polygon.io provider (requires POLYGON_API_KEY). Serves two domains only:
 * <ul>
 *   <li>{@link Domain#CANDLES} — adjusted daily aggregates.</li>
 *   <li>{@link Domain#HISTORICAL_OPTIONS} — expired/as-of option contract references plus
 *       per-contract daily closes, used by the backtester. Labeled {@link Freshness#EOD}.</li>
 * </ul>
 * Live quote/chain/lookup methods intentionally return empty: this provider is for
 * candles and backtests, never live trade decisions.
 */
public final class PolygonProvider implements MarketDataProvider, HistoricalOptionsProvider {

    public static final String NAME = "polygon";

    /** Max contracts priced per historical chain (each needs its own aggregates call). */
    private static final int CHAIN_CONTRACT_CAP = 60;

    private final Http http;
    private final String base;
    private final String apiKey;
    private final io.liftandshift.strikebench.db.ProviderRequestBudget budget;
    private final int dailyLimit;

    public PolygonProvider(AppConfig cfg) {
        this(cfg, null);
    }

    public PolygonProvider(AppConfig cfg, io.liftandshift.strikebench.db.ProviderRequestBudget budget) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.base = Http.normalizeBase(cfg.polygonBaseUrl());
        this.apiKey = cfg.polygonApiKey();
        this.budget = budget;
        this.dailyLimit = cfg.polygonDailyRequestLimit();
    }

    @Override public String name() { return NAME; }

    @Override public Set<Domain> domains() { return Set.of(Domain.CANDLES, Domain.HISTORICAL_OPTIONS); }

    // ---- Live MarketDataProvider methods: not served by Polygon in this app ----

    @Override public List<SymbolMatch> lookup(String query) { return List.of(); }

    @Override public Optional<Quote> quote(String symbol) { return Optional.empty(); }

    @Override public List<LocalDate> expirations(String symbol) { return List.of(); }

    @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) { return Optional.empty(); }

    // ---- Candles ----

    @Override
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        String sym = normalize(symbol);
        String url = base + "/v2/aggs/ticker/" + Http.pathSegment(sym) + "/range/1/day/" + from + "/" + to
                + "?adjusted=true&sort=asc&limit=5000&apiKey=" + apiKey;
        JsonNode results = Json.parse(get(url)).path("results");
        if (!results.isArray() || results.isEmpty()) return List.of();
        List<Candle> out = new ArrayList<>(results.size());
        for (JsonNode bar : results) {
            if (!bar.hasNonNull("t") || !bar.hasNonNull("c")) continue;
            LocalDate date = Instant.ofEpochMilli(bar.path("t").longValue()).atZone(ZoneOffset.UTC).toLocalDate();
            out.add(new Candle(
                    date,
                    bar.path("o").decimalValue(),
                    bar.path("h").decimalValue(),
                    bar.path("l").decimalValue(),
                    bar.path("c").decimalValue(),
                    bar.path("v").longValue(),
                    true));
        }
        return List.copyOf(out);
    }

    // ---- Historical options (backtesting only) ----

    @Override
    public List<LocalDate> historicalExpirations(String symbol, LocalDate asOf) {
        String url = base + "/v3/reference/options/contracts?underlying_ticker="
                + Http.queryValue(normalize(symbol))
                + "&as_of=" + asOf + "&limit=1000&apiKey=" + apiKey;
        TreeSet<LocalDate> expirations = new TreeSet<>();
        for (JsonNode contract : referenceResults(url, 25)) {
            String date = contract.path("expiration_date").asText("");
            if (!date.isBlank()) expirations.add(LocalDate.parse(date));
        }
        return List.copyOf(expirations);
    }

    @Override
    public Optional<OptionChain> historicalChain(String symbol, LocalDate asOf, LocalDate expiration) {
        String sym = normalize(symbol);
        String url = base + "/v3/reference/options/contracts?underlying_ticker=" + Http.queryValue(sym)
                + "&as_of=" + asOf + "&expiration_date=" + expiration + "&limit=250&apiKey=" + apiKey;
        List<JsonNode> contracts = referenceResults(url, 10);
        if (contracts.isEmpty()) return Optional.empty();

        long asOfEpochMs = io.liftandshift.strikebench.market.MarketHours
                .sessionClose(asOf).toEpochMilli();
        BigDecimal underlyingPrice = dayBar(sym, asOf).map(DayBar::close).orElse(null);
        // A zero anchor creates a numerically valid-looking but economically meaningless chain.
        if (underlyingPrice == null || underlyingPrice.signum() <= 0) return Optional.empty();

        List<OptionQuote> calls = new ArrayList<>();
        List<OptionQuote> puts = new ArrayList<>();
        List<JsonNode> selected = balancedNearestContracts(contracts, underlyingPrice);
        for (JsonNode contract : selected) {
            OptionType type = switch (contract.path("contract_type").asText("")) {
                case "call" -> OptionType.CALL;
                case "put" -> OptionType.PUT;
                default -> null;
            };
            String ticker = contract.path("ticker").asText("");
            if (type == null || ticker.isBlank()) continue;
            Optional<DayBar> bar = dayBar(ticker, asOf);
            if (bar.isEmpty()) continue; // contract had no aggregate that day — skip
            OptionQuote quote = new OptionQuote(
                    sym,
                    ticker,
                    type,
                    contract.path("strike_price").decimalValue(),
                    expiration,
                    null,               // bid unknown from daily aggregates
                    null,               // ask unknown
                    bar.get().close(),  // last = that day's close
                    bar.get().volume(),
                    null,               // open interest unknown
                    null, null, null, null, null, // iv/greeks unknown
                    asOfEpochMs,
                    NAME,
                    Freshness.EOD);
            (type == OptionType.CALL ? calls : puts).add(quote);
        }
        calls.sort(Comparator.comparing(OptionQuote::strike));
        puts.sort(Comparator.comparing(OptionQuote::strike));
        return Optional.of(new OptionChain(
                sym, expiration, underlyingPrice,
                List.copyOf(calls), List.copyOf(puts),
                asOfEpochMs, NAME, Freshness.EOD));
    }

    /** Follow Polygon's explicit cursor; the first page is not the whole reference catalog. */
    private List<JsonNode> referenceResults(String firstUrl, int maxPages) {
        List<JsonNode> out = new ArrayList<>();
        String url = firstUrl;
        for (int page = 0; page < maxPages && url != null && !url.isBlank(); page++) {
            JsonNode response = Json.parse(get(withApiKey(url)));
            JsonNode results = response.path("results");
            if (results.isArray()) results.forEach(out::add);
            String next = response.path("next_url").asText("");
            url = next.isBlank() ? null : next;
        }
        return List.copyOf(out);
    }

    private String withApiKey(String url) {
        if (url.contains("apiKey=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "apiKey=" + apiKey;
    }

    /**
     * Polygon orders reference rows by its own catalog key. Taking the first 60 can therefore
     * produce one option side or far-away strikes. Select equal call/put neighborhoods around the
     * observed underlying and fill only genuine spare capacity afterward.
     */
    private static List<JsonNode> balancedNearestContracts(List<JsonNode> contracts,
                                                            BigDecimal underlyingPrice) {
        double spot = underlyingPrice.doubleValue();
        Comparator<JsonNode> nearest = Comparator
                .comparingDouble((JsonNode node) ->
                        Math.abs(node.path("strike_price").asDouble(Double.NaN) - spot))
                .thenComparing(node -> node.path("ticker").asText(""));
        List<JsonNode> calls = contracts.stream()
                .filter(x -> "call".equals(x.path("contract_type").asText("")))
                .filter(PolygonProvider::validContractReference).sorted(nearest).toList();
        List<JsonNode> puts = contracts.stream()
                .filter(x -> "put".equals(x.path("contract_type").asText("")))
                .filter(PolygonProvider::validContractReference).sorted(nearest).toList();
        int sideCap = CHAIN_CONTRACT_CAP / 2;
        List<JsonNode> selected = new ArrayList<>(CHAIN_CONTRACT_CAP);
        selected.addAll(calls.subList(0, Math.min(sideCap, calls.size())));
        selected.addAll(puts.subList(0, Math.min(sideCap, puts.size())));
        if (selected.size() < CHAIN_CONTRACT_CAP) {
            java.util.Set<String> chosen = selected.stream()
                    .map(x -> x.path("ticker").asText()).collect(java.util.stream.Collectors.toSet());
            java.util.stream.Stream.concat(calls.stream(), puts.stream())
                    .filter(x -> !chosen.contains(x.path("ticker").asText()))
                    .sorted(nearest)
                    .limit(CHAIN_CONTRACT_CAP - selected.size())
                    .forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private static boolean validContractReference(JsonNode contract) {
        double strike = contract.path("strike_price").asDouble(Double.NaN);
        return Double.isFinite(strike) && strike > 0
                && !contract.path("ticker").asText("").isBlank();
    }

    /** Close (+ volume) for one ticker on one day via the aggregates endpoint; empty when no bar. */
    private Optional<DayBar> dayBar(String ticker, LocalDate day) {
        String url = base + "/v2/aggs/ticker/" + Http.pathSegment(ticker)
                + "/range/1/day/" + day + "/" + day + "?apiKey=" + apiKey;
        JsonNode results = Json.parse(get(url)).path("results");
        if (!results.isArray() || results.isEmpty()) return Optional.empty();
        JsonNode bar = results.get(0);
        if (!bar.hasNonNull("c")) return Optional.empty();
        BigDecimal close = bar.path("c").decimalValue();
        if (close == null || close.signum() <= 0) return Optional.empty();
        Long volume = bar.hasNonNull("v") ? bar.path("v").longValue() : null;
        return Optional.of(new DayBar(close, volume));
    }

    private record DayBar(BigDecimal close, Long volume) {}

    private String get(String url) {
        if (budget != null) budget.acquire(name(), dailyLimit);
        return http.get(url);
    }

    private static String normalize(String symbol) {
        return Symbol.normalize(symbol);
    }
}
