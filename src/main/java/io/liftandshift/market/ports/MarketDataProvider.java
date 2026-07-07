package io.liftandshift.market.ports;

import io.liftandshift.market.Domain;
import io.liftandshift.model.Candle;
import io.liftandshift.model.NewsItem;
import io.liftandshift.model.OptionChain;
import io.liftandshift.model.Quote;
import io.liftandshift.model.SymbolMatch;

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
