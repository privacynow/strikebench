package io.liftandshift.strikebench.market.providers;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.util.Json;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SEC EDGAR filings as "news" for a symbol. Keyless, but the SEC requires a
 * contact User-Agent header on EVERY request ({@link AppConfig#edgarUserAgent()}).
 *
 * Two hosts:
 * - {@link AppConfig#edgarBaseUrl()} (www.sec.gov) serves the ticker->CIK map,
 * - {@link AppConfig#edgarDataBaseUrl()} (data.sec.gov) serves the submissions JSON.
 *
 * The ticker map is fetched once and cached for the lifetime of this provider.
 * HTTP/transport failures propagate as {@link Http.ProviderHttpException} so the
 * service layer can fall through the provider chain.
 */
public final class EdgarProvider implements NewsFilingsProvider {

    private static final int MAX_ITEMS = 10;
    /** Filing links shown to the user always point at the real SEC archive host. */
    private static final String ARCHIVES_HOST = "https://www.sec.gov";

    private final Http http;
    private final String baseUrl;
    private final String dataBaseUrl;
    private final String userAgent;

    /** ticker (upper-case) -> CIK. Cached after the first successful fetch. */
    private volatile Map<String, Long> tickerToCik;

    public EdgarProvider(AppConfig cfg) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.edgarBaseUrl());
        this.dataBaseUrl = Http.normalizeBase(cfg.edgarDataBaseUrl());
        this.userAgent = cfg.edgarUserAgent();
        if (userAgent.isBlank()) {
            throw new IllegalStateException("EDGAR_USER_AGENT must identify this installation and include a contact email");
        }
    }

    @Override
    public String name() {
        return "edgar";
    }

    @Override
    public List<NewsItem> news(String symbol) {
        String ticker = Symbol.normalizeOptional(symbol);
        if (ticker == null) return List.of();

        Long cik = tickerMap().get(ticker);
        if (cik == null) return List.of();

        String url = dataBaseUrl + "/submissions/CIK" + String.format("%010d", cik) + ".json";
        JsonNode root = Json.parse(http.get(url, headers()));

        JsonNode recent = root.path("filings").path("recent");
        JsonNode accessions = recent.path("accessionNumber");
        JsonNode forms = recent.path("form");
        JsonNode dates = recent.path("filingDate");
        JsonNode docs = recent.path("primaryDocument");
        if (!accessions.isArray() || accessions.isEmpty()) return List.of();

        int n = Math.min(MAX_ITEMS, Math.min(
                Math.min(accessions.size(), forms.size()),
                Math.min(dates.size(), docs.size())));

        List<NewsItem> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            try {
                String accession = accessions.get(i).asText("");
                String accessionNoDashes = accession.replace("-", "");
                String document = docs.get(i).asText("");
                String form = forms.get(i).asText("");
                if (!accession.matches("[0-9-]+") || accessionNoDashes.isBlank()
                        || !document.matches("[A-Za-z0-9._-]+") || form.isBlank()) {
                    continue;
                }
                String filingUrl = ARCHIVES_HOST + "/Archives/edgar/data/" + cik + "/"
                        + accessionNoDashes + "/" + Http.pathSegment(document);
                long publishedEpochMs = LocalDate.parse(dates.get(i).asText())
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                items.add(new NewsItem(
                        ticker,
                        form + " filing",
                        "SEC EDGAR",
                        filingUrl,
                        publishedEpochMs));
            } catch (RuntimeException malformedProviderRow) {
                // One malformed filing row is unavailable; the remaining issuer filings are valid.
            }
        }
        return List.copyOf(items);
    }

    /** Fetches (once) and caches the SEC ticker->CIK map. */
    private Map<String, Long> tickerMap() {
        Map<String, Long> cached = tickerToCik;
        if (cached != null) return cached;

        JsonNode root = Json.parse(http.get(baseUrl + "/files/company_tickers.json", headers()));
        Map<String, Long> map = new HashMap<>();
        java.util.Set<String> ambiguous = new HashSet<>();
        root.forEach(entry -> {
            String ticker = entry.path("ticker").asText("");
            long cik = entry.path("cik_str").asLong(-1);
            if (!ticker.isEmpty() && cik > 0) {
                try {
                    String canonical = Symbol.normalize(ticker);
                    if (ambiguous.contains(canonical)) return;
                    Long previous = map.putIfAbsent(canonical, cik);
                    if (previous != null && previous != cik) {
                        map.remove(canonical);
                        ambiguous.add(canonical);
                    }
                } catch (IllegalArgumentException malformedProviderRow) {
                    // External collections are row-tolerant: a bad SEC ticker cannot erase the map.
                }
            }
        });
        Map<String, Long> frozen = Map.copyOf(map);
        tickerToCik = frozen; // cache only after a successful fetch + parse
        return frozen;
    }

    private Map<String, String> headers() {
        return Map.of("User-Agent", userAgent);
    }
}
