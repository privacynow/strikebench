package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.market.ports.WarmOptionStore;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Serves the last-known observed option chain from {@code option_bar} for the live read path,
 * reusing {@link StoredHistoricalOptionsProvider}'s row-to-chain mapping at the symbol's most
 * recent capture date. No SQL is duplicated: the latest {@code asof} is resolved here and the
 * chain assembly is delegated. Only the canonical observed dataset is served.
 */
public final class StoredOptionChainStore implements WarmOptionStore {

    private final Db db;
    private final StoredHistoricalOptionsProvider historical;

    public StoredOptionChainStore(Db db) {
        this.db = db;
        this.historical = new StoredHistoricalOptionsProvider(db);
    }

    @Override
    public Optional<Read> latestChain(String symbol, LocalDate expiration) {
        return latestAsof(symbol).flatMap(asof ->
                historical.historicalChain(symbol, asof, expiration).map(c -> new Read(c, asof)));
    }

    @Override
    public List<LocalDate> latestExpirations(String symbol) {
        return latestAsof(symbol)
                .map(asof -> historical.historicalExpirations(symbol, asof))
                .orElseGet(List::of);
    }

    private Optional<LocalDate> latestAsof(String symbol) {
        return db.query("SELECT max(asof) a FROM option_bar WHERE symbol=? AND dataset_id='observed'",
                        r -> r.date("a"), norm(symbol))
                .stream().filter(Objects::nonNull).findFirst();
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
}
