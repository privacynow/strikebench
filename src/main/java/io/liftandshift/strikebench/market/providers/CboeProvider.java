package io.liftandshift.strikebench.market.providers;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
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

    private final long cooldownMs;
    /** After a 429/1015, ALL Cboe requests short-circuit until this time (a shared circuit breaker). */
    private volatile long cooldownUntilMs = 0;
    // Politeness governor for this HEAVY keyless source: cap concurrency and space requests so the
    // background warm + interactive loads never burst the CDN. Cached (warmed) symbols skip both.
    private final java.util.concurrent.Semaphore concurrency;
    private final long spacingMs;
    private long nextAllowedMs = 0;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CboeProvider.class);

    public CboeProvider(AppConfig cfg) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.cboeBaseUrl());
        this.cooldownMs = Math.max(1, cfg.cboeCooldownMinutes()) * 60_000L;
        this.concurrency = new java.util.concurrent.Semaphore(Math.max(1, cfg.cboeMaxConcurrency()), true);
        this.spacingMs = Math.max(0, cfg.cboeMinSpacingMs());
    }

    /** Serialize a minimum gap between network requests (rate cap), shared across all threads. */
    private synchronized void pace() {
        long now = System.currentTimeMillis();
        long wait = nextAllowedMs - now;
        if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        nextAllowedMs = Math.max(now, nextAllowedMs) + spacingMs;
    }

    /** True while Cboe is in a rate-limit cooldown — surfaced to the Data Center. */
    public boolean coolingDown() { return System.currentTimeMillis() < cooldownUntilMs; }
    public long cooldownUntilMs() { return cooldownUntilMs; }

    private io.liftandshift.strikebench.util.EventBus events; // optional: announce breaker trips
    public void setEvents(io.liftandshift.strikebench.util.EventBus events) { this.events = events; }

    /** Restore a persisted breaker state at boot — a restart must not forget an active Cboe ban. */
    public void seedCooldown(long untilMs) {
        if (untilMs > System.currentTimeMillis()) {
            cooldownUntilMs = untilMs;
            log.warn("Cboe cooldown restored from disk — cooling until {}", java.time.Instant.ofEpochMilli(untilMs));
        }
    }

    /**
     * Whether this heavy provider has budget for SPECULATIVE work right now. Prefetch is
     * denied while cooling down or while every concurrency permit is busy with real requests —
     * a guess must never queue behind (or ahead of) something the user actually asked for.
     */
    public boolean prefetchBudget() {
        return !coolingDown() && concurrency.availablePermits() > 0;
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
        CachedPayload payload = fetchData(sym);
        if (payload == null) return Optional.empty();
        JsonNode data = payload.data();

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
    private CachedPayload fetchData(String symbol) {
        // Circuit breaker: while cooling down from a 429, make NO Cboe request (for any symbol) — this
        // is what stops the retry storm and the ongoing hammering. Callers fall through the chain.
        if (coolingDown()) return null;
        Optional<CachedPayload> cached = payloadCache.get(symbol, this::fetchDataUncached);
        return cached == null ? null : cached.orElse(null);
    }

    /** Cboe serves index option chains under underscore roots (SPX -> _SPX etc.). */
    private static final java.util.Set<String> INDEX_ROOTS = java.util.Set.of(
            "SPX", "XSP", "NDX", "VIX", "RUT", "DJX", "OEX", "XEO");

    private Optional<CachedPayload> fetchDataUncached(String symbol) {
        String cboeSymbol = INDEX_ROOTS.contains(symbol) ? "_" + symbol : symbol;
        String url = baseUrl + "/api/global/delayed_quotes/options/" + cboeSymbol + ".json";
        String body;
        boolean acquired = false;
        try {
            concurrency.acquire();      // cap concurrent heavy downloads
            acquired = true;
            pace();                     // and space them out so we never burst the CDN
            if (coolingDown()) return Optional.empty(); // a 429 may have tripped while we waited
            body = http.get(url);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Http.ProviderHttpException e) {
            // Cboe's CDN answers 404 OR S3-style "403 AccessDenied" for symbols it does not
            // know — both mean "definitively no data", not a provider failure.
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("HTTP 404") || (msg.contains("HTTP 403") && msg.contains("AccessDenied"))) {
                return Optional.empty();
            }
            // 429 (Cloudflare 1015) = rate limited. Trip the breaker so we stop asking Cboe app-wide.
            if (msg.contains("HTTP 429")) {
                cooldownUntilMs = System.currentTimeMillis() + cooldownMs;
                log.warn("Cboe rate-limited (429/1015) — cooling down for {} min; serving stale/other sources",
                        cooldownMs / 60000);
                if (events != null) events.publish("provider.cooldown",
                        java.util.Map.of("provider", "cboe", "untilMs", cooldownUntilMs));
            }
            throw e;
        } finally {
            if (acquired) concurrency.release();
        }
        JsonNode root = Json.parse(body);
        JsonNode data = root.path("data");
        if (!data.isObject()) return Optional.empty();
        Long sourceAsOf = null;
        try {
            String ts = root.path("timestamp").asText(null); // "yyyy-MM-dd HH:mm:ss" (UTC)
            if (ts != null && !ts.isBlank()) {
                sourceAsOf = java.time.LocalDateTime.parse(ts.replace(' ', 'T'))
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            }
        } catch (RuntimeException ignored) { /* fall back to fetch time */ }
        return Optional.of(new CachedPayload(data, System.currentTimeMillis(), sourceAsOf));
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
