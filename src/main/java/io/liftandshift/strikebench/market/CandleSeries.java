package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;

import java.util.List;

/**
 * Candles plus where they came from. Explicit Demo, Simulated, and Scenario modes may return
 * generated history; the Observed provider chain never substitutes it.
 */
public record CandleSeries(List<Candle> candles, String source, Freshness freshness,
                           String barBasis, String priceBasis) {

    public CandleSeries(List<Candle> candles, String source, Freshness freshness) {
        this(candles, source, freshness, "OHLCV", inferPriceBasis(candles));
    }

    public CandleSeries(List<Candle> candles, String source, Freshness freshness, String barBasis) {
        this(candles, source, freshness, barBasis, inferPriceBasis(candles));
    }

    public static final CandleSeries EMPTY = new CandleSeries(List.of(), null, Freshness.MISSING, "NONE", "NONE");

    /**
     * An empty result that still NAMES the provider which produced it. Range-absence and local
     * allowance denials return no candles, but the reporting provider is itself evidence: a backfill
     * must persist the learned coverage boundary under the provider that reported it, never under the
     * generic "auto" request. Returning bare EMPTY here loses that identity.
     */
    public static CandleSeries emptyFrom(String provider) {
        return provider == null || provider.isBlank()
                ? EMPTY
                : new CandleSeries(List.of(), provider, Freshness.MISSING, "NONE", "NONE");
    }

    public boolean isEmpty() { return candles.isEmpty(); }

    /** Fabricated teaching history, eligible only in the explicit Demo mode. */
    public boolean isFixture() { return freshness == Freshness.FIXTURE; }

    /** True only when every row carries a genuine open/high/low/close observation. */
    public boolean hasFullOhlc() {
        return "OHLC".equalsIgnoreCase(barBasis) || "OHLCV".equalsIgnoreCase(barBasis);
    }

    public io.liftandshift.strikebench.model.DataEvidence evidence() {
        return io.liftandshift.strikebench.model.DataEvidence.of(source, freshness);
    }

    private static String inferPriceBasis(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return "NONE";
        boolean anyAdjusted = candles.stream().anyMatch(Candle::adjusted);
        boolean anyRaw = candles.stream().anyMatch(c -> !c.adjusted());
        return anyAdjusted && anyRaw ? "MIXED" : anyAdjusted ? "ADJUSTED" : "RAW";
    }
}
