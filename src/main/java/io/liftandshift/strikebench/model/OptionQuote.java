package io.liftandshift.strikebench.model;

import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Normalized single option contract quote.
 * Prices/strike: BigDecimal (money). IV/greeks: double ratios (not money).
 */
public record OptionQuote(
        String underlying,
        String occSymbol,
        OptionType type,
        BigDecimal strike,
        LocalDate expiration,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal last,
        Long volume,
        Long openInterest,
        Double iv,
        Double delta,
        Double gamma,
        Double theta,
        Double vega,
        long asOfEpochMs,
        String source,
        Freshness freshness
) {
    public DataEvidence rawEvidence() { return DataEvidence.of(source, freshness); }

    /** The displayed last-trade fallback is stale even when the surrounding chain is current. */
    public DataEvidence evidence() {
        DataEvidence raw = rawEvidence();
        return midIsLastTradeFallback()
                ? new DataEvidence(raw.provenance(), DataAge.STALE, source + " (last-trade fallback)")
                : raw;
    }

    public Freshness markFreshness() {
        return midIsLastTradeFallback() ? Freshness.STALE : freshness;
    }

    /** Mid price when both sides exist and are sane, else last. Null if unpriceable. */
    public BigDecimal mid() {
        if (bid != null && ask != null && ask.signum() > 0 && bid.signum() >= 0 && ask.compareTo(bid) >= 0) {
            return bid.add(ask).divide(BigDecimal.valueOf(2), Money.PRICE_SCALE, RoundingMode.HALF_UP);
        }
        if (last != null && last.signum() > 0) return last;
        return null;
    }

    /** True when mid() is standing in the LAST TRADE (no two-sided book) — possibly hours old. */
    public boolean midIsLastTradeFallback() {
        boolean twoSided = bid != null && ask != null && ask.signum() > 0 && bid.signum() >= 0 && ask.compareTo(bid) >= 0;
        return !twoSided && last != null && last.signum() > 0;
    }

    /** Bid-ask spread as a fraction of mid (liquidity quality). NaN when unpriceable. */
    public double spreadPct() {
        BigDecimal m = mid();
        if (m == null || m.signum() <= 0 || bid == null || ask == null) return Double.NaN;
        return ask.subtract(bid).doubleValue() / m.doubleValue();
    }

    public boolean hasMark() { return mid() != null; }
}
