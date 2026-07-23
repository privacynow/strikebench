package io.liftandshift.strikebench.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Symbol-list hygiene: trim, upper-case, drop blanks, de-duplicate, preserve input order. */
public final class Symbols {

    private Symbols() {}

    public static List<String> normalize(List<String> symbols) {
        List<String> normalized = new ArrayList<>();
        for (String raw : symbols == null ? List.<String>of() : symbols) {
            String symbol = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (!symbol.isEmpty() && !normalized.contains(symbol)) normalized.add(symbol);
        }
        return List.copyOf(normalized);
    }
}
