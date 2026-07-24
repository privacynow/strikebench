package io.liftandshift.strikebench.market;

/**
 * The KIND of read a provider was asked for, orthogonal to which {@link Domain} it lives in and to
 * provider health. One CANDLES bucket used to collapse a recent underlying-bar read, a long
 * historical-range backfill, and a budget denial into a single ok/empty/error verdict, so a green
 * latest read masked an incomplete history or a local allowance denial. Splitting the read into a
 * typed condition lets {@code /api/status} report each independently.
 *
 * <ul>
 *   <li>{@code QUOTE} — the latest-quote read (QUOTES domain).</li>
 *   <li>{@code CHAIN} — an option expirations / chain read (OPTIONS domain).</li>
 *   <li>{@code UNDERLYING_BAR} — a recent daily bar used to anchor pricing.</li>
 *   <li>{@code HISTORICAL_RANGE} — a dated candle range read (history charts, backfills).</li>
 *   <li>{@code BUDGET} — a LOCAL allowance decision (durable per-provider request budget), never a
 *       network read; a denial here is not a provider outage.</li>
 * </ul>
 */
public enum ReadCondition {
    QUOTE, CHAIN, UNDERLYING_BAR, HISTORICAL_RANGE, BUDGET;

    /** The condition a domain read defaults to when the caller does not name a finer one. */
    public static ReadCondition forDomain(Domain domain) {
        if (domain == null) return null;
        return switch (domain) {
            case QUOTES -> QUOTE;
            case OPTIONS -> CHAIN;
            case CANDLES -> HISTORICAL_RANGE;
            default -> null; // NEWS / RATES / HISTORICAL_OPTIONS / BROKERAGE report at domain grain
        };
    }

    public static String nameOrNull(ReadCondition condition) {
        return condition == null ? null : condition.name();
    }
}
