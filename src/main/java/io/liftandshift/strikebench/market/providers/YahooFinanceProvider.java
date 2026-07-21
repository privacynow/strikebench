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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Keyless daily OHLCV candles from Yahoo Finance's public chart endpoint:
 * {@code GET {base}/v8/finance/chart/{SYMBOL}?period1=..&period2=..&interval=1d}, which returns JSON
 * with a parallel {@code timestamp[]} and {@code indicators.quote[0].{open,high,low,close,volume}}.
 *
 * <p>This is an attributable <em>underlying</em> history source, enabled under the product owner's
 * standing authorization until {@code YAHOO_ENABLED=false}. Covers EQUITY/ETF/index prices only,
 * <b>not options</b>. Candles only; every other domain returns empty so the provider chain falls
 * through cleanly. Successful observed reads are persisted by the canonical candle store rather
 * than repeatedly downloaded.
 */
public final class YahooFinanceProvider implements MarketDataProvider {

    private final Http http;
    private final String baseUrl;
    private final io.liftandshift.strikebench.db.ProviderRequestBudget budget;
    private final int dailyLimit;
    // The backfill job walks whole universes through this endpoint — same politeness discipline
    // as Cboe: capped concurrency, spaced starts, and a provider-wide breaker on rate limits
    // (Yahoo answers 429 or its legacy 999). Repeated ordinary upstream failures also trip it,
    // so an outage cannot be retried across the rest of the universe. While cooling, candles()
    // returns empty and the provider chain falls through to other sources.
    private final io.liftandshift.strikebench.market.ProviderPoliteness politeness;

    public YahooFinanceProvider(AppConfig cfg) {
        this(cfg, null);
    }

    public YahooFinanceProvider(AppConfig cfg, io.liftandshift.strikebench.db.ProviderRequestBudget budget) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.yahooBaseUrl());
        this.budget = budget;
        this.dailyLimit = cfg.yahooDailyRequestLimit();
        this.politeness = new io.liftandshift.strikebench.market.ProviderPoliteness(
                "yahoo", cfg.yahooMaxConcurrency(), cfg.yahooMinSpacingMs(),
                cfg.yahooCooldownMinutes() * 60_000L);
    }

    public void setEvents(io.liftandshift.strikebench.util.EventBus events) { politeness.setEvents(events); }
    public boolean coolingDown() { return politeness.coolingDown(); }
    /** A restart must not erase an active upstream cooldown. */
    public void seedCooldown(long untilMs) { politeness.seedCooldown(untilMs); }

    @Override public String name() { return "yahoo"; }

    @Override public Set<Domain> domains() { return Set.of(Domain.CANDLES); }

    @Override
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        long p1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long p2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        // URL-encode the symbol so index/class tickers (^GSPC, BRK-B) reach the right path segment.
        String enc = java.net.URLEncoder.encode(symbol.trim().toUpperCase(Locale.ROOT), java.nio.charset.StandardCharsets.UTF_8);
        String url = baseUrl + "/v8/finance/chart/" + enc
                + "?period1=" + p1 + "&period2=" + p2 + "&interval=1d&events=div%2Csplit";
        // A browser-like User-Agent avoids the occasional bot interstitial; still a plain GET.
        // The politeness gate spaces/limits requests and short-circuits during a rate-limit cooldown.
        JsonNode root = politeness.call(() -> {
            if (budget != null) budget.acquire(name(), dailyLimit);
            String body = http.get(url, Map.of("User-Agent",
                    "Mozilla/5.0 (compatible; StrikeBench/1.0; +https://strikebench.com)"));
            // Validation belongs inside the breaker call. Yahoo and intervening anti-bot layers can
            // answer HTTP 200 with HTML, malformed JSON, chart.error, or an empty chart. Treating
            // those as successful calls reset the failure counter and let a universe job keep
            // sweeping an unhealthy endpoint.
            return requireUsableChart(body);
        }, null);
        if (root == null) return List.of();

        JsonNode result = root.path("chart").path("result");
        JsonNode r0 = result.get(0);
        JsonNode ts = r0.path("timestamp");
        JsonNode quote = r0.path("indicators").path("quote");
        if (!ts.isArray() || ts.isEmpty() || !quote.isArray() || quote.isEmpty()) return List.of();
        JsonNode q = quote.get(0);
        JsonNode opens = q.path("open"), highs = q.path("high"), lows = q.path("low"),
                 closes = q.path("close"), vols = q.path("volume");

        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            JsonNode cNode = closes.path(i);
            if (cNode.isMissingNode() || cNode.isNull()) continue; // Yahoo emits nulls for holidays/halts
            LocalDate date = Instant.ofEpochSecond(ts.get(i).asLong()).atZone(ZoneOffset.UTC).toLocalDate();
            if (date.isBefore(from) || date.isAfter(to)) continue;
            BigDecimal close = dec(cNode);
            if (close == null || close.signum() <= 0) continue;
            out.add(new Candle(date,
                    orElse(dec(opens.path(i)), close),
                    orElse(dec(highs.path(i)), close),
                    orElse(dec(lows.path(i)), close),
                    close,
                    vols.path(i).isNumber() ? vols.path(i).asLong() : 0L,
                    false));
        }
        out.sort(Comparator.comparing(Candle::date));
        return out;
    }

    private static JsonNode requireUsableChart(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Yahoo chart response was empty");
        }
        JsonNode root = Json.parse(body);
        JsonNode chart = root.path("chart");
        JsonNode error = chart.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String code = error.path("code").asText("upstream error");
            String description = error.path("description").asText("");
            throw new IllegalStateException("Yahoo chart error: " + code
                    + (description.isBlank() ? "" : " — " + description));
        }
        JsonNode result = chart.path("result");
        if (!result.isArray() || result.isEmpty()) {
            throw new IllegalStateException("Yahoo chart returned no result");
        }
        JsonNode r0 = result.get(0);
        JsonNode timestamps = r0.path("timestamp");
        JsonNode quotes = r0.path("indicators").path("quote");
        JsonNode closes = quotes.isArray() && !quotes.isEmpty()
                ? quotes.get(0).path("close") : null;
        if (!timestamps.isArray() || timestamps.isEmpty()
                || !quotes.isArray() || quotes.isEmpty()
                || closes == null || !closes.isArray() || closes.isEmpty()) {
            throw new IllegalStateException("Yahoo chart result contained no usable daily prices");
        }
        boolean usable = false;
        for (int i = 0; i < Math.min(timestamps.size(), closes.size()); i++) {
            if (timestamps.path(i).canConvertToLong()
                    && closes.path(i).isNumber()
                    && closes.path(i).decimalValue().signum() > 0) {
                usable = true;
                break;
            }
        }
        if (!usable) {
            throw new IllegalStateException("Yahoo chart result contained no usable daily prices");
        }
        return root;
    }

    private static BigDecimal dec(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || !n.isNumber()) return null;
        return new BigDecimal(n.asText());
    }

    private static BigDecimal orElse(BigDecimal v, BigDecimal fallback) { return v == null ? fallback : v; }

    // ---- Domains Yahoo does not serve here ----
    @Override public List<SymbolMatch> lookup(String query) { return List.of(); }
    @Override public Optional<Quote> quote(String symbol) { return Optional.empty(); }
    @Override public List<LocalDate> expirations(String symbol) { return List.of(); }
    @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) { return Optional.empty(); }
}
