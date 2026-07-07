package io.liftandshift.strategy;

/**
 * WHY a trader reaches for a strategy — orthogonal to the directional thesis.
 * DIRECTIONAL is the classic "I have a view" flow; the other four exist because
 * options are also tools for income, protection, and trading the shares themselves
 * (selling a covered call to exit a winner at a target price, selling a
 * cash-secured put to buy a name at a discount).
 */
public enum StrategyIntent {
    DIRECTIONAL("Trade a view", "Express a bullish, bearish, neutral or volatile opinion with defined risk."),
    INCOME("Earn income", "Collect option premium against cash or shares; assignment is the main trade-off."),
    HEDGE("Protect a position", "Cap the downside of shares you hold; costs premium or capped upside."),
    ACQUIRE("Buy at a discount", "Get paid to wait for your price: sell a put at the level you would happily buy."),
    EXIT("Sell at a target", "Get paid to be patient: sell a call at the price you would happily sell your shares.");

    private final String display;
    private final String blurb;

    StrategyIntent(String display, String blurb) {
        this.display = display;
        this.blurb = blurb;
    }

    public String display() { return display; }
    public String blurb() { return blurb; }

    /** Lenient parse for API input; null/unknown -> DIRECTIONAL (the historical default). */
    public static StrategyIntent parse(String raw) {
        if (raw == null || raw.isBlank()) return DIRECTIONAL;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown intent '" + raw + "' — one of "
                    + java.util.Arrays.toString(values()));
        }
    }
}
