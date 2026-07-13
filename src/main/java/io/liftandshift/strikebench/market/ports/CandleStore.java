package io.liftandshift.strikebench.market.ports;

import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.CandleCoverage;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A read-through store of persisted daily candles (the {@code underlying_bar} table). Injected into
 * {@link io.liftandshift.strikebench.market.MarketDataService} so a Data Center backfill actually
 * feeds the read path. Complete ranges are served immediately; coherent partial observed ranges
 * remain available after the provider chain gets a chance to enrich them. Absent (null) in pure
 * unit tests, which then use the provider chain exactly as before.
 *
 * The dataset id is an EXPLICIT parameter (from the caller's {@code AnalysisContext}) — the store
 * holds no ambient per-request state, so virtual-thread fan-outs and background jobs cannot read
 * the wrong world by accident.
 */
public interface CandleStore {

    /** One coherent stored source/basis plus whether it covers the complete requested range. */
    record Read(CandleSeries series, CandleCoverage coverage) {}

    /** Stored daily candles for the symbol/range in the given dataset, or empty when none are useful. */
    Optional<Read> candles(String symbol, LocalDate from, LocalDate to, String datasetId);

    /**
     * Persist an observed provider response for reuse after restart. Read-only test stores keep the
     * default no-op; the production store rejects any non-observed evidence before writing.
     */
    default int persistObserved(String symbol, CandleSeries series) { return 0; }
}
