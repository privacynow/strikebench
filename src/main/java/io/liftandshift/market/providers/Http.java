package io.liftandshift.market.providers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal HTTP GET helper for providers. Non-2xx and transport failures throw —
 * MarketDataService catches, records ERROR status, and falls through the chain.
 */
public final class Http {

    private final HttpClient client;
    private final Duration timeout;

    public Http(long timeoutMs) {
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String get(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
            headers.forEach(builder::header);
            HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new ProviderHttpException(url, res.statusCode(), truncate(res.body()));
            }
            return res.body();
        } catch (IOException e) {
            throw new ProviderHttpException(url, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderHttpException(url, -1, "interrupted");
        }
    }

    public String get(String url) {
        return get(url, Map.of());
    }

    public String post(String url, String body, Map<String, String> headers) {
        return send("POST", url, body, headers);
    }

    public String put(String url, String body, Map<String, String> headers) {
        return send("PUT", url, body, headers);
    }

    private String send(String method, String url, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            headers.forEach(builder::header);
            HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new ProviderHttpException(url, res.statusCode(), truncate(res.body()));
            }
            return res.body();
        } catch (IOException e) {
            throw new ProviderHttpException(url, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderHttpException(url, -1, "interrupted");
        }
    }

    /** Strips any trailing slash so base + "/path" concatenation is uniform. */
    public static String normalizeBase(String base) {
        return base != null && base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    public static final class ProviderHttpException extends RuntimeException {
        public ProviderHttpException(String url, int status, String detail) {
            super("HTTP " + (status > 0 ? status : "error") + " from " + url + (detail == null || detail.isBlank() ? "" : ": " + detail));
        }
    }
}
