package io.liftandshift.strikebench.market.ports;

import io.liftandshift.strikebench.model.OptionChain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A read-through store of the last-known observed option chains (the {@code option_bar} table),
 * mirroring {@link CandleStore} for options. Injected into
 * {@link io.liftandshift.strikebench.market.MarketDataService} so a scan can fall back to a warm
 * chain when a live provider is exhausted, rate-limited, or absent — serving the last-known chain
 * (labeled EOD) instead of an empty "no listed options". Absent (null) in pure unit tests, which
 * then use the provider chain exactly as before.
 *
 * <p>The store serves ONLY the canonical observed dataset; simulated and Demo lanes keep their own
 * in-world sources and never read here.
 */
public interface WarmOptionStore {

    /** One stored chain plus the calendar date it was captured (its {@code asof}). */
    record Read(OptionChain chain, LocalDate asOf) {}

    /** The most recently captured stored chain for this symbol/expiration, or empty when none. */
    Optional<Read> latestChain(String symbol, LocalDate expiration);

    /** The expirations present in the symbol's most recent stored capture, or empty when none. */
    List<LocalDate> latestExpirations(String symbol);
}
