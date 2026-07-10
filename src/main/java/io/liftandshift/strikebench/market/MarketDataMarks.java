package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Leg;
// CandleSeries lives in the same package
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.paper.MarksSource;

import java.math.BigDecimal;
import java.util.Optional;

/** Bridges the paper core's MarksSource port onto the live MarketDataService chain. */
public final class MarketDataMarks implements MarksSource {

    private final MarketDataService market;
    private final boolean fixturesOnly;

    public MarketDataMarks(MarketDataService market) {
        this(market, true);
    }

    public MarketDataMarks(MarketDataService market, boolean fixturesOnly) {
        this.market = market;
        this.fixturesOnly = fixturesOnly;
    }

    @Override
    public Optional<BigDecimal> underlyingMark(String symbol) {
        return market.quote(symbol).map(Quote::mark).filter(m -> m != null && m.signum() > 0);
    }

    @Override
    public Optional<java.math.BigDecimal> underlyingMark(String symbol, String worldId) {
        if (worldId == null) return underlyingMark(symbol);
        return market.quote(symbol, worldId).map(io.liftandshift.strikebench.model.Quote::mark)
                .filter(m -> m != null && m.signum() > 0);
    }

    @Override
    public Optional<LegMark> legMark(String symbol, io.liftandshift.strikebench.model.Leg leg, String worldId) {
        if (worldId == null) return legMark(symbol, leg);
        if (leg.isStock()) {
            return market.quote(symbol, worldId).map(q ->
                    new LegMark(q.bid(), q.ask(), q.mark(), null, q.freshness(), 1.0, 0.0, 0.0, 0.0))
                    .filter(m -> m.mid() != null);
        }
        return market.chain(symbol, leg.expiration(), worldId)
                .flatMap(chain -> chain.find(leg.type(), leg.strike())
                        .filter(io.liftandshift.strikebench.model.OptionQuote::hasMark)
                        .map(q -> new LegMark(q.bid(), q.ask(), q.mid(), q.iv(), q.freshness(),
                                q.delta(), q.gamma(), q.theta(), q.vega())));
    }

    @Override
    public Optional<java.math.BigDecimal> closeOn(String symbol, java.time.LocalDate date, String worldId) {
        if (worldId == null) return closeOn(symbol, date);
        // Settlement INSIDE a simulated world uses that world's own closes — its account is the
        // only one allowed here, so a synthetic close can never mint real-lane paper cash.
        var series = market.candleSeries(symbol, date.minusDays(7), date, worldId, null);
        if (series.isEmpty()) return Optional.empty();
        var last = series.candles().getLast();
        return last.date().equals(date) ? Optional.of(last.close()) : Optional.empty();
    }

    @Override
    public Optional<Long> underlyingAsOfMs(String symbol) {
        return market.quote(symbol).map(io.liftandshift.strikebench.model.Quote::asOfEpochMs);
    }

    @Override
    public Optional<LegMark> legMark(String symbol, Leg leg) {
        if (leg.isStock()) {
            // A share behaves like a delta-1, greek-free contract
            return market.quote(symbol).map(q ->
                    new LegMark(q.bid(), q.ask(), q.mark(), null, q.freshness(), 1.0, 0.0, 0.0, 0.0))
                    .filter(m -> m.mid() != null);
        }
        return market.chain(symbol, leg.expiration())
                .flatMap(chain -> chain.find(leg.type(), leg.strike())
                        .filter(OptionQuote::hasMark)
                        // A mid standing in the LAST TRADE (no two-sided book) may be hours old:
                        // the display mark is labeled STALE so it can never read as a live price.
                        // Fills are unaffected — they use the executable sides, which don't exist here.
                        .map(q -> new LegMark(q.bid(), q.ask(), q.mid(), q.iv(),
                                q.midIsLastTradeFallback()
                                        ? io.liftandshift.strikebench.model.Freshness.worse(q.freshness(),
                                                io.liftandshift.strikebench.model.Freshness.STALE)
                                        : q.freshness(),
                                q.delta(), q.gamma(), q.theta(), q.vega())));
    }

    @Override
    public Optional<BigDecimal> closeOn(String symbol, java.time.LocalDate date) {
        CandleSeries series = market.candleSeries(symbol, date.minusDays(7), date);
        // In live mode a fixture close is fake — better to settle against the real current
        // quote (the caller's fallback) than a demo number that never happened.
        if (series.isEmpty() || (series.isFixture() && !fixturesOnly)) return Optional.empty();
        var last = series.candles().getLast();
        // Strictly the close OF that date — yesterday's close standing in for an expiry
        // settlement fabricates the overnight gap.
        if (!last.date().equals(date)) return Optional.empty();
        return Optional.of(last.close());
    }

    @Override
    public double riskFreeRate(int days) {
        return market.riskFreeRate(days);
    }
}
