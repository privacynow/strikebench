package io.liftandshift.strikebench.market.ports;

import io.liftandshift.strikebench.market.CandleSeries;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A read-through store of persisted daily candles (the {@code underlying_bar} table). Injected into
 * {@link io.liftandshift.strikebench.market.MarketDataService} so a Data Center backfill actually
 * feeds the read path — Research and the backtesters get stored history instead of silently
 * re-calling providers or falling to fixtures. Absent (null) in pure unit tests, which then use the
 * provider chain exactly as before.
 */
public interface CandleStore {

    /** Stored daily candles for the symbol/range, or empty to fall through to the provider chain. */
    Optional<CandleSeries> candles(String symbol, LocalDate from, LocalDate to);
}
