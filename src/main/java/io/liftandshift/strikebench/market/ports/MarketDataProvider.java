package io.liftandshift.strikebench.market.ports;

import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A source of live-ish market data. Implementations either return data, return
 * empty (definitively nothing for this symbol), or throw (provider failure) —
 * MarketDataService records status and falls through the provider chain on
 * empty or thrown results.
 */
public interface MarketDataProvider {

    String name();

    /** Domains this provider can serve; the service only routes matching calls. */
    Set<Domain> domains();

    List<SymbolMatch> lookup(String query);

    Optional<Quote> quote(String symbol);

    List<LocalDate> expirations(String symbol);

    Optional<OptionChain> chain(String symbol, LocalDate expiration);

    /** Daily candles in [from, to], ascending by date. */
    List<Candle> candles(String symbol, LocalDate from, LocalDate to);

    default List<NewsItem> news(String symbol) { return List.of(); }
}
