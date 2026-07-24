package io.liftandshift.strikebench.market.providers;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.BroadBasedIndexOptions;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.util.Json;

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
    /** A cached payload remembers WHEN it was fetched AND the data's OWN stamp — readers must
     *  never restamp either as fresh. asOf() prefers the source's own time when Cboe provides it. */
    record CachedPayload(JsonNode data, long fetchedAtMs, Long sourceAsOfMs) {
        long asOf() { return sourceAsOfMs != null ? sourceAsOfMs : fetchedAtMs; }
    }

    private final com.github.benmanes.caffeine.cache.Cache<String, Optional<CachedPayload>> payloadCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofSeconds(120))
                    // WEIGHT-bounded, not count-bounded: each entry is a full multi-MB option-chain
                    // tree, so 300 of them was a heap risk. ~64MB budget, weighed by contract count
                    // (a cheap proxy for tree size: ~200 bytes/contract + fixed overhead).
                    .maximumWeight(64L * 1024 * 1024)
                    .weigher((String k, Optional<CachedPayload> v) ->
                            v.map(cp -> 1024 + cp.data().path("options").size() * 200).orElse(64))
                    .build();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CboeProvider.class);
    // ONE shared politeness governor (concurrency cap, request spacing, circuit breaker) for this
    // HEAVY keyless source, replacing the inline Cboe-only copy — which diverged in two ways that
    // this fixes: its seedCooldown could REGRESS a live breaker, and ordinary consecutive failures
    // never tripped it (only HTTP 429 did). Cached (warmed) symbols skip the gate entirely.
    private final io.liftandshift.strikebench.market.ProviderPoliteness politeness;

    public CboeProvider(AppConfig cfg) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.cboeBaseUrl());
        this.politeness = new io.liftandshift.strikebench.market.ProviderPoliteness("cboe",
                cfg.cboeMaxConcurrency(), cfg.cboeMinSpacingMs(),
                Math.max(1, cfg.cboeCooldownMinutes()) * 60_000L);
    }

    /** True while Cboe is in a rate-limit cooldown — surfaced to the Data Center. */
    public boolean coolingDown() { return politeness.coolingDown(); }
    public long cooldownUntilMs() { return politeness.cooldownUntilMs(); }

    public void setEvents(io.liftandshift.strikebench.util.EventBus events) { politeness.setEvents(events); }

    /** Restore a persisted breaker state at boot — a restart must not forget an active Cboe ban. */
    public void seedCooldown(long untilMs) { politeness.seedCooldown(untilMs); }

    /**
     * Whether this heavy provider has budget for SPECULATIVE work right now. Prefetch is denied
     * while cooling down or while every concurrency permit is busy — a guess must never queue
     * behind (or ahead of) something the user actually asked for.
     */
    public boolean prefetchBudget() { return politeness.prefetchBudget(); }

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
        CachedPayload payload = fetchData(sym);
        if (payload == null) return Optional.empty();
        JsonNode data = payload.data();

        BigDecimal last = decimal(data, "current_price");
        if (last == null) last = decimal(data, "close");
        JsonNode options = data.path("options");
        boolean optionable = false;
        if (options.isArray()) {
            for (JsonNode opt : options) {
                if (parseOcc(opt.path("option").asText(null), sym) != null) {
                    optionable = true;
                    break;
                }
            }
        }

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
                payload.asOf(), // the DATA's own stamp (or fetch time) — a cache read must not restamp it
                "cboe",
                Freshness.DELAYED));
    }

    @Override
    public List<LocalDate> expirations(String symbol) {
        String sym = normalize(symbol);
        CachedPayload payload = fetchData(sym);
        if (payload == null) return List.of();
        JsonNode data = payload.data();

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
        CachedPayload payload = fetchData(sym);
        if (payload == null) return Optional.empty();
        JsonNode data = payload.data();

        long asOf = payload.asOf();
        List<ParsedOption> matching = new ArrayList<>();
        for (JsonNode opt : data.path("options")) {
            ParsedOcc occ = parseOcc(opt.path("option").asText(null), sym);
            if (occ != null && occ.expiration().equals(expiration)) matching.add(new ParsedOption(opt, occ));
        }
        if (matching.isEmpty()) return Optional.empty();
        String selectedSeries = matching.stream().anyMatch(x -> x.occ().root().equals(sym))
                ? sym : matching.getFirst().occ().root();

        List<OptionQuote> calls = new ArrayList<>();
        List<OptionQuote> puts = new ArrayList<>();
        for (ParsedOption parsed : matching) {
            if (!parsed.occ().root().equals(selectedSeries)) continue;
            JsonNode opt = parsed.node();
            ParsedOcc occ = parsed.occ();
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
    private CachedPayload fetchData(String symbol) {
        // Circuit breaker: while cooling down from a 429, make NO Cboe request (for any symbol) — this
        // is what stops the retry storm and the ongoing hammering. Callers fall through the chain.
        if (coolingDown()) return null;
        String cacheKey = BroadBasedIndexOptions.canonicalRoot(symbol)
                .orElseGet(() -> symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT));
        Optional<CachedPayload> cached = payloadCache.get(cacheKey, this::fetchDataUncached);
        return cached == null ? null : cached.orElse(null);
    }

    private Optional<CachedPayload> fetchDataUncached(String symbol) {
        // Cboe serves these index chains under underscore roots. Series aliases such as SPXW
        // share the canonical SPX payload, but retain their requested symbol everywhere else.
        String cboeSymbol = BroadBasedIndexOptions.canonicalRoot(symbol)
                .map(root -> "_" + root)
                .orElse(symbol);
        String url = baseUrl + "/api/global/delayed_quotes/options/" + cboeSymbol + ".json";
        // ONE governor owns concurrency, spacing, and the circuit breaker (HTTP 403/429/999 denial or
        // three consecutive failures trip it, announced as provider.cooldown). Cboe's own 404 and
        // S3-style "403 AccessDenied" mean "definitively no data" — caught inside and returned empty
        // so they never count as a provider failure.
        return politeness.call(() -> {
            String body;
            try {
                body = http.get(url);
            } catch (Http.ProviderHttpException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("HTTP 404") || (msg.contains("HTTP 403") && msg.contains("AccessDenied"))) {
                    return Optional.<CachedPayload>empty();
                }
                throw e; // 429 / real denial / other → the shared breaker decides
            }
            JsonNode root = Json.parse(body);
            JsonNode data = root.path("data");
            if (!data.isObject()) return Optional.<CachedPayload>empty();
            Long sourceAsOf = null;
            try {
                String ts = root.path("timestamp").asText(null); // "yyyy-MM-dd HH:mm:ss" (UTC)
                if (ts != null && !ts.isBlank()) {
                    sourceAsOf = java.time.LocalDateTime.parse(ts.replace(' ', 'T'))
                            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                }
            } catch (RuntimeException ignored) { /* fall back to fetch time */ }
            return Optional.of(new CachedPayload(data, System.currentTimeMillis(), sourceAsOf));
        }, Optional.empty());
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
        int rootEnd = raw.length() - 15;
        if (rootEnd <= 0) return null;
        String contractRoot = normalize(raw.substring(0, rootEnd));
        Optional<String> requestedCanonical = BroadBasedIndexOptions.canonicalRoot(symbol);
        if (requestedCanonical.isPresent()) {
            String requested = normalize(symbol);
            boolean canonicalRequest = requested.equals(requestedCanonical.get());
            boolean sameSeries = canonicalRequest
                    ? BroadBasedIndexOptions.canonicalRoot(contractRoot)
                            .filter(requestedCanonical.get()::equals).isPresent()
                    : requested.equals(contractRoot);
            if (!sameSeries) return null;
        }
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
            return new ParsedOcc(raw, contractRoot, type, strike, expiration);
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

    private record ParsedOcc(String raw, String root, OptionType type, BigDecimal strike, LocalDate expiration) {}
    private record ParsedOption(JsonNode node, ParsedOcc occ) {}
}
