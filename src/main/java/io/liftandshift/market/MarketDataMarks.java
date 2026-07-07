package io.liftandshift.market;

import io.liftandshift.model.Leg;
// CandleSeries lives in the same package
import io.liftandshift.model.OptionQuote;
import io.liftandshift.model.Quote;
import io.liftandshift.paper.MarksSource;

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
                        .map(q -> new LegMark(q.bid(), q.ask(), q.mid(), q.iv(), q.freshness(),
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
