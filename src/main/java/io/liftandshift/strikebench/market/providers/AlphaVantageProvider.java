package io.liftandshift.strikebench.market.providers;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.util.Json;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Alpha Vantage daily-adjusted candles (key required: {@link AppConfig#alphaVantageApiKey()}).
 * Serves the CANDLES domain only. Rate-limit / error payloads (HTTP 200 with a
 * "Note", "Error Message" or "Information" field instead of a series) throw so
 * MarketDataService records the failure and falls through the provider chain.
 */
public final class AlphaVantageProvider implements MarketDataProvider {

    private static final String SERIES_KEY = "Time Series (Daily)";
    private static final List<String> ERROR_KEYS = List.of("Note", "Error Message", "Information");

    private final Http http;
    private final String baseUrl;
    private final String apiKey;
    private final boolean fullHistory;
    private final io.liftandshift.strikebench.db.ProviderRequestBudget budget;
    private final int dailyLimit;

    public AlphaVantageProvider(AppConfig cfg) {
        this(cfg, null);
    }

    public AlphaVantageProvider(AppConfig cfg, io.liftandshift.strikebench.db.ProviderRequestBudget budget) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.alphaVantageBaseUrl());
        this.apiKey = cfg.alphaVantageApiKey();
        this.fullHistory = cfg.alphaVantageFullHistoryEnabled();
        this.budget = budget;
        this.dailyLimit = cfg.alphaVantageDailyRequestLimit();
    }

    @Override
    public String name() {
        return "alphavantage";
    }

    @Override
    public Set<Domain> domains() {
        return Set.of(Domain.CANDLES);
    }

    @Override
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        String url = baseUrl + "/query?function=TIME_SERIES_DAILY_ADJUSTED"
                + "&symbol=" + URLEncoder.encode(sym, StandardCharsets.UTF_8)
                + "&outputsize=" + (fullHistory ? "full" : "compact")
                + "&apikey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        if (budget != null) budget.acquire(name(), dailyLimit);
        JsonNode root = Json.parse(http.get(url));
        JsonNode series = root.get(SERIES_KEY);
        if (series == null || !series.isObject()) {
            for (String key : ERROR_KEYS) {
                JsonNode msg = root.get(key);
                if (msg != null && !msg.asText("").isBlank()) {
                    throw new RuntimeException("Alpha Vantage " + key + ": " + msg.asText());
                }
            }
            return List.of(); // parsed fine, provider simply has nothing for this symbol
        }

        List<Candle> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> day : series.properties()) {
            LocalDate date = LocalDate.parse(day.getKey());
            if (date.isBefore(from) || date.isAfter(to)) continue;
            JsonNode bar = day.getValue();
            BigDecimal rawClose = money(bar, "4. close");
            BigDecimal adjustedClose = money(bar, "5. adjusted close");
            if (rawClose.signum() <= 0 || adjustedClose.signum() <= 0) continue;
            // DAILY_ADJUSTED reports raw O/H/L and adjusted close. Scale the whole OHLC tuple by
            // the same corporate-action factor; mixing raw highs with an adjusted close creates
            // impossible candles and corrupts volatility/range studies.
            BigDecimal factor = adjustedClose.divide(rawClose, MathContext.DECIMAL64);
            out.add(new Candle(date,
                    money(bar, "1. open").multiply(factor, MathContext.DECIMAL64),
                    money(bar, "2. high").multiply(factor, MathContext.DECIMAL64),
                    money(bar, "3. low").multiply(factor, MathContext.DECIMAL64),
                    adjustedClose, bar.path("6. volume").asLong(0L), true));
        }
        out.sort(Comparator.comparing(Candle::date));
        return List.copyOf(out);
    }

    @Override
    public List<SymbolMatch> lookup(String query) {
        return List.of();
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        return Optional.empty();
    }

    @Override
    public List<LocalDate> expirations(String symbol) {
        return List.of();
    }

    @Override
    public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
        return Optional.empty();
    }

    /** Alpha Vantage sends prices as JSON strings; numeric nodes use decimalValue() directly. */
    private static BigDecimal money(JsonNode bar, String field) {
        JsonNode n = bar.get(field);
        if (n == null || n.isNull() || n.asText("").isBlank()) {
            throw new IllegalArgumentException("Alpha Vantage bar missing field '" + field + "'");
        }
        return n.isNumber() ? n.decimalValue() : new BigDecimal(n.asText().trim());
    }
}
