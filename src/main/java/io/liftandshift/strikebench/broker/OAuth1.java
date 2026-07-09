package io.liftandshift.strikebench.broker;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * OAuth 1.0a HMAC-SHA1 request signing (RFC 5849), server-side only.
 * Nonce and timestamp are injectable so tests can assert exact signatures.
 */
public final class OAuth1 {

    public record Creds(String consumerKey, String consumerSecret, String token, String tokenSecret) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Supplier<String> nonceSupplier;
    private final LongSupplier timestampSupplier;

    public OAuth1() {
        this(() -> Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong()),
                () -> System.currentTimeMillis() / 1000L);
    }

    public OAuth1(Supplier<String> nonceSupplier, LongSupplier timestampSupplier) {
        this.nonceSupplier = nonceSupplier;
        this.timestampSupplier = timestampSupplier;
    }

    /**
     * Builds the Authorization header for a request. The url may carry a query string;
     * its parameters participate in the signature base string per the spec.
     */
    public String authorizationHeader(String method, String url, Map<String, String> extraOAuthParams, Creds creds) {
        Map<String, String> oauth = new LinkedHashMap<>();
        oauth.put("oauth_consumer_key", creds.consumerKey());
        oauth.put("oauth_nonce", nonceSupplier.get());
        oauth.put("oauth_signature_method", "HMAC-SHA1");
        oauth.put("oauth_timestamp", Long.toString(timestampSupplier.getAsLong()));
        oauth.put("oauth_version", "1.0");
        if (creds.token() != null && !creds.token().isBlank()) oauth.put("oauth_token", creds.token());
        if (extraOAuthParams != null) oauth.putAll(extraOAuthParams);

        String signature = sign(method, url, oauth, creds);
        oauth.put("oauth_signature", signature);

        StringBuilder header = new StringBuilder("OAuth ");
        boolean first = true;
        for (Map.Entry<String, String> e : oauth.entrySet()) {
            if (!first) header.append(", ");
            header.append(enc(e.getKey())).append("=\"").append(enc(e.getValue())).append('"');
            first = false;
        }
        return header.toString();
    }

    String sign(String method, String url, Map<String, String> oauthParams, Creds creds) {
        String baseString = baseString(method, url, oauthParams);
        String key = enc(creds.consumerSecret()) + "&" + enc(creds.tokenSecret() == null ? "" : creds.tokenSecret());
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    /** Signature base string: METHOD & enc(baseUrl) & enc(sorted normalized params). */
    static String baseString(String method, String url, Map<String, String> oauthParams) {
        URI uri = URI.create(url);
        String baseUrl = uri.getScheme() + "://" + uri.getAuthority() + uri.getRawPath();

        // Collect oauth params + query params, percent-encoded, sorted by key then value
        TreeMap<String, List<String>> params = new TreeMap<>();
        oauthParams.forEach((k, v) -> params.computeIfAbsent(enc(k), x -> new ArrayList<>()).add(enc(v)));
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isBlank()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                // Re-encode from raw to normalize (%-case, '+' etc.)
                params.computeIfAbsent(enc(urlDecode(k)), x -> new ArrayList<>()).add(enc(urlDecode(v)));
            }
        }
        StringBuilder norm = new StringBuilder();
        params.forEach((k, values) -> {
            values.sort(String::compareTo);
            for (String v : values) {
                if (!norm.isEmpty()) norm.append('&');
                norm.append(k).append('=').append(v);
            }
        });
        return method.toUpperCase(java.util.Locale.ROOT) + "&" + enc(baseUrl) + "&" + enc(norm.toString());
    }

    /** RFC 3986 percent-encoding (OAuth flavor: '~' untouched, space as %20). */
    static String enc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** Parses a form-encoded body like oauth_token=...&oauth_token_secret=... */
    public static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        for (String pair : body.trim().split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return out;
    }
}
