package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.DataProvenance;

import java.util.List;

/**
 * Candles plus where they came from. Explicit Demo, Simulated, and Scenario modes may return
 * generated history; the Observed provider chain never substitutes it.
 */
public record CandleSeries(List<Candle> candles, DataEvidence evidence,
                           String barBasis, String priceBasis) {

    public CandleSeries {
        candles = candles == null ? List.of() : List.copyOf(candles);
        evidence = evidence == null ? DataEvidence.missing("no candle evidence") : evidence;
    }

    public static final CandleSeries EMPTY = new CandleSeries(List.of(), DataEvidence.missing("none"), "NONE", "NONE");

    /**
     * An empty result that still NAMES the provider which produced it. Range-absence and local
     * allowance denials return no candles, but the reporting provider is itself evidence: a backfill
     * must persist the learned coverage boundary under the provider that reported it, never under the
     * generic "auto" request. Returning bare EMPTY here loses that identity.
     */
    public static CandleSeries emptyFrom(String provider) {
        return provider == null || provider.isBlank()
                ? EMPTY
                : new CandleSeries(List.of(), DataEvidence.missing(provider), "NONE", "NONE");
    }

    public boolean isEmpty() { return candles.isEmpty(); }

    /** Fabricated teaching history, eligible only in the explicit Demo mode. */
    public boolean isFixture() { return evidence.provenance() == DataProvenance.DEMO; }

    public String source() { return evidence.source(); }
    public String freshness() { return evidence.label(); }

    /** True only when every row carries a genuine open/high/low/close observation. */
    public boolean hasFullOhlc() {
        return "OHLC".equalsIgnoreCase(barBasis) || "OHLCV".equalsIgnoreCase(barBasis);
    }

    public static String priceBasisOf(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return "NONE";
        boolean anyAdjusted = candles.stream().anyMatch(Candle::adjusted);
        boolean anyRaw = candles.stream().anyMatch(c -> !c.adjusted());
        return anyAdjusted && anyRaw ? "MIXED" : anyAdjusted ? "ADJUSTED" : "RAW";
    }
}
