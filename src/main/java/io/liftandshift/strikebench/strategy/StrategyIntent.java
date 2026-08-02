package io.liftandshift.strikebench.strategy;

/**
 * WHY a trader reaches for a strategy — orthogonal to the directional thesis.
 * DIRECTIONAL is the classic "I have a view" flow; the other four exist because
 * options are also tools for income, protection, and trading the shares themselves
 * (selling a covered call to exit a winner at a target price, selling a
 * cash-secured put to buy a name at a discount).
 */
public enum StrategyIntent {
    DIRECTIONAL("Trade a view", "Express a bullish, bearish, neutral or volatile opinion with defined risk."),
    INCOME("Income", "Compare collateral-backed opening premium, defined-risk credits, and managed "
            + "time-spread campaigns; carry, after-cost economics, events, assignment, and capital "
            + "remain separate facts."),
    HEDGE("Protect a position", "Cap the downside of shares you hold; costs premium or capped upside."),
    ACQUIRE("Plan an acquisition", "Compare funded share-delivery obligations with capped-risk ways "
            + "to express or protect a desired entry price; only exact deliverables can acquire shares."),
    EXIT("Plan an exit", "Compare a covered call at a declared sale price with holding or selling "
            + "the shares; opening premium never makes the exit automatically favorable.");

    private final String display;
    private final String blurb;

    StrategyIntent(String display, String blurb) {
        this.display = display;
        this.blurb = blurb;
    }

    public String display() { return display; }
    public String blurb() { return blurb; }

    /** Parse one explicit intent. Product boundaries may not substitute a goal. */
    public static StrategyIntent parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("intent is required");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown intent '" + raw + "' — one of "
                    + java.util.Arrays.toString(values()));
        }
    }
}
