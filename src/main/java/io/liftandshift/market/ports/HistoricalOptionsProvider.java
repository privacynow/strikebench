package io.liftandshift.market.ports;

import io.liftandshift.model.OptionChain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Historical option data for backtesting. Never used for live trade decisions. */
public interface HistoricalOptionsProvider {

    String name();

    /** Option chain as it stood on asOf (freshness HISTORICAL sources) or MODELED reconstruction. */
    Optional<OptionChain> historicalChain(String symbol, LocalDate asOf, LocalDate expiration);

    /** Expirations that were listed as of the given date. */
    List<LocalDate> historicalExpirations(String symbol, LocalDate asOf);
}
