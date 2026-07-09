package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;

import java.util.List;

/**
 * Candles plus where they came from. The provider chain may fall back to deterministic
 * fixture data — consumers must be able to tell (and label) real history vs demo history.
 */
public record CandleSeries(List<Candle> candles, String source, Freshness freshness) {

    public static final CandleSeries EMPTY = new CandleSeries(List.of(), null, Freshness.MISSING);

    public boolean isEmpty() { return candles.isEmpty(); }

    /** Demo data standing in for real history (fine in fixture mode, must be labeled in live mode). */
    public boolean isFixture() { return freshness == Freshness.FIXTURE; }
}
