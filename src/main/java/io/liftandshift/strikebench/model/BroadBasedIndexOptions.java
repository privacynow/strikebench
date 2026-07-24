package io.liftandshift.strikebench.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared identity for the broad-based Cboe index-option roots StrikeBench can identify without a
 * user override. This is deliberately narrower than a blind prefix match: {@code VIXY} is an ETF,
 * not a VIX index-option series, while listed weekly and p.m.-settled roots retain the tax character
 * of their canonical broad-based index.
 */
public final class BroadBasedIndexOptions {

    public static final List<String> ROOTS = List.of(
            "SPX", "XSP", "NDX", "VIX", "RUT", "DJX", "OEX", "XEO");

    public static final List<String> AUTOMATIC_SYMBOLS = List.of(
            "SPX", "SPXW", "SPXpm", "XSP", "NDX", "NDXP", "VIX", "VIXW",
            "RUT", "RUTW", "DJX", "OEX", "XEO");

    private static final Map<String, Set<String>> SERIES_SUFFIXES = Map.of(
            "SPX", Set.of("W", "PM"),
            "NDX", Set.of("P"),
            "VIX", Set.of("W"),
            "RUT", Set.of("W"));

    private BroadBasedIndexOptions() {}

    /** Returns the canonical index root for a known root or exchange series alias. */
    public static Optional<String> canonicalRoot(String symbol) {
        String normalized = normalize(symbol);
        if (ROOTS.contains(normalized)) return Optional.of(normalized);

        for (var entry : SERIES_SUFFIXES.entrySet()) {
            String root = entry.getKey();
            if (!normalized.startsWith(root)) continue;
            String suffix = normalized.substring(root.length());
            if (entry.getValue().contains(suffix)) return Optional.of(root);
        }
        return Optional.empty();
    }

    public static boolean isKnownRoot(String symbol) {
        return canonicalRoot(symbol).isPresent();
    }

    private static String normalize(String symbol) {
        if (symbol == null) return "";
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("_") ? normalized.substring(1) : normalized;
    }
}
