package io.liftandshift.strikebench.support;

import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic test feed with observed provenance. It reuses fixture values solely to avoid
 * network I/O; unlike FixtureProvider, every returned market object identifies this connector
 * and carries delayed/EOD observed semantics. Tests must choose this or FixtureProvider explicitly.
 */
public final class ObservedFixtureProvider implements MarketDataProvider {
    private final FixtureProvider fixture;
    private final String sourceName;

    public ObservedFixtureProvider(Clock clock) { this(clock, "observed-test-feed"); }

    public ObservedFixtureProvider(Clock clock, String sourceName) {
        this.fixture = new FixtureProvider(clock);
        this.sourceName = sourceName;
    }

    @Override public String name() { return sourceName; }
    @Override public Set<Domain> domains() { return fixture.domains(); }
    @Override public List<SymbolMatch> lookup(String query) { return fixture.lookup(query); }

    @Override public Optional<Quote> quote(String symbol) {
        return fixture.quote(symbol).map(q -> new Quote(q.symbol(), q.description(), q.last(), q.bid(), q.ask(),
                q.prevClose(), q.dayHigh(), q.dayLow(), q.volume(), q.optionable(), q.asOfEpochMs(), name(),
                Freshness.DELAYED));
    }

    @Override public List<LocalDate> expirations(String symbol) { return fixture.expirations(symbol); }

    @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
        return fixture.chain(symbol, expiration).map(c -> new OptionChain(c.underlying(), c.expiration(),
                c.underlyingPrice(), c.calls().stream().map(this::observed).toList(),
                c.puts().stream().map(this::observed).toList(), c.asOfEpochMs(), name(), Freshness.DELAYED));
    }

    @Override public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
        return fixture.candles(symbol, from, to);
    }

    private OptionQuote observed(OptionQuote q) {
        return new OptionQuote(q.underlying(), q.occSymbol(), q.type(), q.strike(), q.expiration(), q.bid(),
                q.ask(), q.last(), q.volume(), q.openInterest(), q.iv(), q.delta(), q.gamma(), q.theta(),
                q.vega(), q.asOfEpochMs(), name(), Freshness.DELAYED);
    }
}
