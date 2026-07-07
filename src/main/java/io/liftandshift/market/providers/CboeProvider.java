package io.liftandshift.market.providers;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.config.AppConfig;
import io.liftandshift.market.Domain;
import io.liftandshift.market.ports.MarketDataProvider;
import io.liftandshift.model.Candle;
import io.liftandshift.model.Freshness;
import io.liftandshift.model.OptionChain;
import io.liftandshift.model.OptionQuote;
import io.liftandshift.model.OptionType;
import io.liftandshift.model.Quote;
import io.liftandshift.model.SymbolMatch;
import io.liftandshift.util.Json;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Keyless delayed option chains from the Cboe CDN:
 * {@code GET {base}/api/global/delayed_quotes/options/{SYMBOL}.json}
 *
 * The payload carries the underlying quote plus every listed contract with
 * bid/ask/IV/greeks/open interest. Data is ~15 min delayed — everything is
 * labeled {@link Freshness#DELAYED}. A 404 means Cboe does not know the
 * symbol (definitively no data); any other HTTP failure propagates so the
 * service records an error and falls through the provider chain.
 */
public final class CboeProvider implements MarketDataProvider {

    private static final DateTimeFormatter OCC_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final Http http;
    private final String baseUrl;

    /**
     * One Cboe download carries the quote AND every contract for a symbol (often megabytes).
     * Cache the parsed payload briefly so quote/expirations/chain calls within a screen —
     * and across the auto-scout's universe scan — pay for one download, not one per call.
     * Failures are never cached; a definitive 404 is (as empty) so unknown symbols don't hammer.
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Optional<JsonNode>> payloadCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofSeconds(60))
                    .maximumSize(50)
                    .build();

    public CboeProvider(AppConfig cfg) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.cboeBaseUrl());
    }

    @Override
    public String name() {
        return "cboe";
    }

    @Override
    public Set<Domain> domains() {
        return Set.of(Domain.OPTIONS, Domain.QUOTES);
    }

    @Override
    public List<SymbolMatch> lookup(String query) {
        return List.of();
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        String sym = normalize(symbol);
        JsonNode data = fetchData(sym);
        if (data == null) return Optional.empty();

        BigDecimal last = decimal(data, "current_price");
        if (last == null) last = decimal(data, "close");
        JsonNode options = data.path("options");
        boolean optionable = options.isArray() && !options.isEmpty();

        return Optional.of(new Quote(
                sym,
                null,
                last,
                decimal(data, "bid"),
                decimal(data, "ask"),
                decimal(data, "prev_day_close"),
                null,
                null,
                longVal(data, "volume"),
                optionable,
                System.currentTimeMillis(),
                "cboe",
                Freshness.DELAYED));
    }

    @Override
    public List<LocalDate> expirations(String symbol) {
        String sym = normalize(symbol);
        JsonNode data = fetchData(sym);
        if (data == null) return List.of();

        TreeSet<LocalDate> dates = new TreeSet<>();
        for (JsonNode opt : data.path("options")) {
            ParsedOcc occ = parseOcc(opt.path("option").asText(null), sym);
            if (occ != null) dates.add(occ.expiration());
        }
        return List.copyOf(dates);
    }

    @Override
    public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
        String sym = normalize(symbol);
        JsonNode data = fetchData(sym);
        if (data == null) return Optional.empty();

        long asOf = System.currentTimeMillis();
        List<OptionQuote> calls = new ArrayList<>();
        List<OptionQuote> puts = new ArrayList<>();
        for (JsonNode opt : data.path("options")) {
            ParsedOcc occ = parseOcc(opt.path("option").asText(null), sym);
            if (occ == null || !occ.expiration().equals(expiration)) continue;
            OptionQuote q = new OptionQuote(
                    sym,
                    occ.raw(),
                    occ.type(),
                    occ.strike(),
                    occ.expiration(),
                    decimal(opt, "bid"),
                    decimal(opt, "ask"),
                    decimal(opt, "last_trade_price"),
                    longVal(opt, "volume"),
                    longVal(opt, "open_interest"),
                    doubleVal(opt, "iv"),
                    doubleVal(opt, "delta"),
                    doubleVal(opt, "gamma"),
                    doubleVal(opt, "theta"),
                    doubleVal(opt, "vega"),
                    asOf,
                    "cboe",
                    Freshness.DELAYED);
            (occ.type() == OptionType.CALL ? calls : puts).add(q);
        }
        if (calls.isEmpty() && puts.isEmpty()) return Optional.empty();

        calls.sort(Comparator.comparing(OptionQuote::strike));
        puts.sort(Comparator.comparing(OptionQuote::strike));

        BigDecimal underlyingPrice = decimal(data, "current_price");
        if (underlyingPrice == null) underlyingPrice = decimal(data, "close");

        return Optional.of(new OptionChain(
                sym, expiration, underlyingPrice,
                List.copyOf(calls), List.copyOf(puts),
                asOf, "cboe", Freshness.DELAYED));
    }

    @Override
    public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        return List.of();
    }

    // ---- internals ----

    /**
     * Fetches and parses the delayed-quotes payload. Returns the {@code data}
     * node, or null when Cboe definitively has nothing for the symbol
     * (HTTP 404, or a body without a data object). Other failures propagate.
     */
    private JsonNode fetchData(String symbol) {
        Optional<JsonNode> cached = payloadCache.get(symbol, this::fetchDataUncached);
        return cached == null ? null : cached.orElse(null);
    }

    private Optional<JsonNode> fetchDataUncached(String symbol) {
        String url = baseUrl + "/api/global/delayed_quotes/options/" + symbol + ".json";
        String body;
        try {
            body = http.get(url);
        } catch (Http.ProviderHttpException e) {
            // Cboe's CDN answers 404 OR S3-style "403 AccessDenied" for symbols it does not
            // know — both mean "definitively no data", not a provider failure.
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("HTTP 404") || (msg.contains("HTTP 403") && msg.contains("AccessDenied"))) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode data = Json.parse(body).path("data");
        return data.isObject() ? Optional.of(data) : Optional.empty();
    }

    private static String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Parses an OCC-style contract id: ROOT + yyMMdd + C|P + 8 digits of strike*1000.
     * The root length varies (weekly/index roots may differ from the underlying
     * ticker), so we strip the known symbol from the front when it matches and
     * otherwise anchor on the fixed-width 15-char tail. Returns null when the
     * id is malformed — the contract is skipped, never a hard failure.
     */
    private static ParsedOcc parseOcc(String raw, String symbol) {
        if (raw == null) return null;
        String tail;
        if (raw.startsWith(symbol) && raw.length() - symbol.length() >= 15) {
            tail = raw.substring(symbol.length());
        } else if (raw.length() >= 15) {
            tail = raw.substring(raw.length() - 15);
        } else {
            return null;
        }
        // Root longer than the requested symbol (e.g. SPXW for SPX): keep the fixed-width tail.
        if (tail.length() > 15) tail = tail.substring(tail.length() - 15);
        try {
            LocalDate expiration = LocalDate.parse(tail.substring(0, 6), OCC_DATE);
            OptionType type = switch (tail.charAt(6)) {
                case 'C' -> OptionType.CALL;
                case 'P' -> OptionType.PUT;
                default -> null;
            };
            if (type == null) return null;
            String strikeDigits = tail.substring(7);
            if (!strikeDigits.chars().allMatch(Character::isDigit)) return null;
            BigDecimal strike = new BigDecimal(strikeDigits).movePointLeft(3);
            return new ParsedOcc(raw, type, strike, expiration);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.decimalValue() : null;
    }

    private static Long longVal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.longValue() : null;
    }

    private static Double doubleVal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.doubleValue() : null;
    }

    private record ParsedOcc(String raw, OptionType type, BigDecimal strike, LocalDate expiration) {}
}
