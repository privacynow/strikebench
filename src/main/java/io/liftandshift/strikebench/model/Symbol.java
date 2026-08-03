package io.liftandshift.strikebench.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalized ticker identity.
 *
 * <p>Every backend boundary uses the same trim, locale-independent case fold and validation rule.
 * Provider spellings (for example Yahoo's {@code BRK-B}) are aliases at the provider boundary;
 * they never become a second identity in caches, persistence, Plans, or evaluation artifacts.</p>
 */
public record Symbol(String value) implements Comparable<Symbol> {

    /*
     * A normalized symbol is deliberately safe to use as an identity and as input to a provider
     * adapter. Separators may not lead, trail, repeat, or form a path. A slash is accepted only as
     * an inbound US share-class spelling (BRK/B) and is normalized to BRK.B before validation.
     */
    private static final Pattern VALID =
            Pattern.compile("\\^?[A-Z0-9_]+(?:[.=-][A-Z0-9]+)*");
    private static final Pattern SHARE_CLASS_ALIAS =
            Pattern.compile("([A-Z0-9]{1,10})[-/]([A-Z])");

    public Symbol {
        value = normalized(value);
        if (value.isEmpty()) throw new IllegalArgumentException("symbol is required");
        if (value.length() > 24 || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid symbol: " + value);
        }
    }

    public static Symbol of(String raw) {
        return new Symbol(raw);
    }

    /** Optional boundary: blank means absent; any present value is normalized and validated. */
    public static Symbol optional(String raw) {
        return raw == null || raw.isBlank() ? null : of(raw);
    }

    /** String projection for existing domain records that still carry their symbol as text. */
    public static String normalize(String raw) {
        return of(raw).value;
    }

    /** Optional string projection: blank stays null rather than becoming an invented ticker. */
    public static String normalizeOptional(String raw) {
        Symbol symbol = optional(raw);
        return symbol == null ? null : symbol.value;
    }

    /** Normalized list hygiene: drop blanks, de-duplicate, preserve caller order. */
    public static List<String> list(List<String> rawSymbols) {
        List<String> normalized = new ArrayList<>();
        for (String raw : rawSymbols == null ? List.<String>of() : rawSymbols) {
            if (raw == null || raw.isBlank()) continue;
            String value = normalize(raw);
            if (!normalized.contains(value)) normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /**
     * Normalizes a symbol-keyed map without silently resolving aliases by last-write-wins.
     * A caller supplying both {@code BRK.B} and {@code BRK-B} has supplied two values for one
     * instrument; rejecting that ambiguity is safer than changing a world or risk input invisibly.
     */
    public static <V> Map<String, V> map(Map<String, V> raw, String fieldName) {
        if (raw == null) return null;
        Map<String, V> normalized = new LinkedHashMap<>();
        Map<String, String> origins = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            String normalizedSymbol = normalize(entry.getKey());
            String previous = origins.putIfAbsent(normalizedSymbol, entry.getKey());
            if (previous != null) {
                throw new IllegalArgumentException((fieldName == null ? "symbol map" : fieldName)
                        + " contains more than one spelling for " + normalizedSymbol + ": "
                        + previous + " and " + entry.getKey());
            }
            normalized.put(normalizedSymbol, entry.getValue());
        }
        return Collections.unmodifiableMap(normalized);
    }

    /** Provider spelling only; normalized identity remains {@link #value()}. */
    public String providerAlias(String provider) {
        // Yahoo documents share classes with a dash. Other providers retain the normalized dot
        // spelling unless their own adapter grows an independently verified mapping.
        if ("yahoo".equalsIgnoreCase(provider)
                && value.matches("[A-Z0-9]{1,10}\\.[A-Z]")) {
            return value.replace('.', '-');
        }
        return value;
    }

    @Override public int compareTo(Symbol other) {
        return value.compareTo(other.value);
    }

    @Override public String toString() {
        return value;
    }

    private static String normalized(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        var shareClass = SHARE_CLASS_ALIAS.matcher(normalized);
        if (shareClass.matches()) {
            normalized = shareClass.group(1) + "." + shareClass.group(2);
        }
        return normalized;
    }
}
