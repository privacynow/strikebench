package io.liftandshift.strikebench.eval;

import java.util.Locale;

/**
 * The user's DECLARED side of the coherence diagnostic: what they said this position is for.
 * Sources: a Plan's context (intent + thesis + horizon) or an account objective revision.
 * The implied side comes from the stance vector — the diagnostic compares the two and never
 * rewrites either.
 */
public record DeclaredObjective(
        String objective,          // INCOME | ACCUMULATE | HEDGE | DIRECTIONAL | CAPITAL_PRESERVATION (or a plan intent)
        String thesis,             // bullish | bearish | neutral | volatile (nullable — income can be shares-agnostic)
        Integer horizonTradingDays,
        String assignmentPreference, // AVOID | ACCEPT | PREFER_BELOW_BASIS | SEEK (nullable)
        String source              // plain-language provenance, e.g. "this Plan's declared view"
) {
    public DeclaredObjective {
        objective = normalize(objective);
        thesis = normalize(thesis);
        assignmentPreference = normalize(assignmentPreference);
    }

    public boolean declaresAnything() {
        return thesis != null || objective != null || horizonTradingDays != null;
    }

    public Integer horizonCalendarDays() {
        // Trading sessions → calendar span (5 sessions ≈ 7 days); duration on the stance
        // vector is calendar-denominated.
        return horizonTradingDays == null ? null : Math.max(1, Math.round(horizonTradingDays * 7f / 5f));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
