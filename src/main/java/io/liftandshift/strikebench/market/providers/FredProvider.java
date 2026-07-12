package io.liftandshift.strikebench.market.providers;

import com.fasterxml.jackson.databind.JsonNode;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.util.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;

/**
 * FRED (Federal Reserve Economic Data) risk-free rate provider. Requires an API key;
 * without one it stays silent (empty result, no network calls) so the service chain
 * can fall through to the keyless Treasury provider.
 *
 * <p>Series are picked by horizon: DGS1MO (&le;45 days), DGS3MO (&le;135 days), DGS1 otherwise.
 * FRED reports missing observations as {@code "."}; those are skipped, newest first.
 */
public final class FredProvider implements RatesProvider {

    private final Http http;
    private final String baseUrl;
    private final String apiKey;

    public FredProvider(AppConfig cfg) {
        this.http = new Http(cfg.httpTimeoutMs());
        this.baseUrl = Http.normalizeBase(cfg.fredBaseUrl());
        this.apiKey = cfg.fredApiKey();
    }

    @Override
    public String name() {
        return "fred";
    }

    @Override
    public OptionalDouble riskFreeRate(int days) {
        if (apiKey == null || apiKey.isBlank()) return OptionalDouble.empty();
        String series = seriesFor(days);
        String url = baseUrl + "/fred/series/observations"
                + "?series_id=" + series
                + "&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&file_type=json&sort_order=desc&limit=10";
        JsonNode root = Json.parse(http.get(url));
        JsonNode observations = root.path("observations");
        if (!observations.isArray()) return OptionalDouble.empty();
        for (JsonNode obs : observations) {
            String value = obs.path("value").asText("");
            if (value.isBlank() || ".".equals(value)) continue; // FRED's missing-data marker
            try {
                return OptionalDouble.of(Double.parseDouble(value) / 100.0);
            } catch (NumberFormatException ignored) {
                // malformed observation: keep scanning older ones
            }
        }
        return OptionalDouble.empty();
    }

    /** DGS1MO for ~1 month horizons, DGS3MO for ~1 quarter, DGS1 beyond that. */
    static String seriesFor(int days) {
        if (days <= 45) return "DGS1MO";
        if (days <= 135) return "DGS3MO";
        return "DGS1";
    }
}
