package io.liftandshift.strikebench.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical ticker identity.
 *
 * <p>Every backend boundary uses the same trim, locale-independent case fold and validation rule.
 * Provider spellings (for example Yahoo's {@code BRK-B}) are aliases at the provider boundary;
 * they never become a second identity in caches, persistence, Plans, or evaluation artifacts.</p>
 */
public record Symbol(String value) implements Comparable<Symbol> {

    private static final Pattern VALID =
            Pattern.compile("(?=.*[A-Z0-9])[A-Z0-9.^=_/-]{1,24}");

    public Symbol {
        value = canonical(value);
        if (value.isEmpty()) throw new IllegalArgumentException("symbol is required");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid symbol: " + value);
        }
    }

    public static Symbol of(String raw) {
        return new Symbol(raw);
    }

    /** Optional boundary: blank means absent; any present value is canonical and validated. */
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

    /** Canonical list hygiene: drop blanks, de-duplicate, preserve caller order. */
    public static List<String> list(List<String> rawSymbols) {
        List<String> normalized = new ArrayList<>();
        for (String raw : rawSymbols == null ? List.<String>of() : rawSymbols) {
            if (raw == null || raw.isBlank()) continue;
            String value = normalize(raw);
            if (!normalized.contains(value)) normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /** Provider spelling only; canonical identity remains {@link #value()}. */
    public String providerAlias(String provider) {
        if ("yahoo".equalsIgnoreCase(provider) && value.matches("[A-Z]{1,6}\\.[A-Z]")) {
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

    private static String canonical(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
}
