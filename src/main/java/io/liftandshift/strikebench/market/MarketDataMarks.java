package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.Leg;
// CandleSeries lives in the same package
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.paper.MarksSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bridges the paper core's MarksSource port onto the live MarketDataService chain. */
public final class MarketDataMarks implements MarksSource {

    private final MarketDataService market;
    private final boolean fixturesOnly;
    private volatile MarketDataEngine engine;

    public MarketDataMarks(MarketDataService market) {
        this(market, true);
    }

    public MarketDataMarks(MarketDataService market, boolean fixturesOnly) {
        this.market = market;
        this.fixturesOnly = fixturesOnly;
    }

    /** Late-wired because ApiServer owns engine lifecycle; all request paths run after this hook. */
    public void setEngine(MarketDataEngine engine) { this.engine = engine; }

    private static boolean observed(String worldId) {
        return worldId == null || worldId.isBlank() || "observed".equalsIgnoreCase(worldId);
    }

    private Optional<Quote> observedQuote(String symbol) {
        MarketDataEngine current = engine;
        if (current == null) return market.quote(symbol);
        return current.quote(symbol).map(MarketDataMarks::snapshotQuote);
    }

    private static Quote snapshotQuote(MarketDataEngine.MarketSnapshot snapshot) {
        return new Quote(snapshot.symbol(), snapshot.description(), snapshot.last(), snapshot.bid(), snapshot.ask(),
                snapshot.prevClose(), null, null, null, snapshot.optionable(), snapshot.asOfEpochMs(),
                snapshot.source(), snapshot.freshness());
    }

    @Override
    public Optional<BigDecimal> underlyingMark(String symbol) {
        return observedQuote(symbol).map(Quote::mark).filter(m -> m != null && m.signum() > 0);
    }

    @Override
    public Optional<java.math.BigDecimal> underlyingMark(String symbol, String worldId) {
        if (observed(worldId)) return underlyingMark(symbol);
        return market.quote(symbol, worldId).map(io.liftandshift.strikebench.model.Quote::mark)
                .filter(m -> m != null && m.signum() > 0);
    }

    @Override
    public Map<String, BigDecimal> underlyingMarks(List<String> symbols, String worldId) {
        if (!observed(worldId) || engine == null) return MarksSource.super.underlyingMarks(symbols, worldId);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (MarketDataEngine.MarketSnapshot snapshot : engine.quotes(symbols == null ? List.of() : symbols)) {
            BigDecimal mark = snapshotQuote(snapshot).mark();
            if (mark != null && mark.signum() > 0) out.put(snapshot.symbol(), mark);
        }
        return out;
    }

    @Override
    public Optional<io.liftandshift.strikebench.model.DataEvidence> underlyingEvidence(String symbol, String worldId) {
        return (observed(worldId) ? observedQuote(symbol) : market.quote(symbol, worldId))
                .map(io.liftandshift.strikebench.model.Quote::evidence);
    }

    @Override
    public Optional<LegMark> legMark(String symbol, io.liftandshift.strikebench.model.Leg leg, String worldId) {
        if (observed(worldId)) return legMark(symbol, leg);
        if (leg.isStock()) {
            return market.quote(symbol, worldId).map(MarketDataMarks::stockMark)
                    .filter(m -> m.mid() != null);
        }
        return market.chain(symbol, leg.expiration(), worldId)
                .flatMap(chain -> chain.find(leg.type(), leg.strike())
                        .filter(io.liftandshift.strikebench.model.OptionQuote::hasMark)
                        .map(MarketDataMarks::optionMark));
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
        return observedQuote(symbol).map(io.liftandshift.strikebench.model.Quote::asOfEpochMs);
    }

    @Override
    public Optional<Long> underlyingAsOfMs(String symbol, String worldId) {
        if (observed(worldId)) return underlyingAsOfMs(symbol);
        return market.quote(symbol, worldId).map(io.liftandshift.strikebench.model.Quote::asOfEpochMs);
    }

    @Override
    public Optional<java.time.Instant> simNow(String worldId) {
        return worldId == null ? Optional.empty() : market.simInstant(worldId);
    }

    @Override
    public Optional<LegMark> legMark(String symbol, Leg leg) {
        if (leg.isStock()) {
            // A share behaves like a delta-1, greek-free contract
            return observedQuote(symbol).map(MarketDataMarks::stockMark)
                    .filter(m -> m.mid() != null);
        }
        return market.chain(symbol, leg.expiration())
                .flatMap(chain -> chain.find(leg.type(), leg.strike())
                        .filter(OptionQuote::hasMark)
                        .map(MarketDataMarks::optionMark));
    }

    private static LegMark stockMark(Quote q) {
        return new LegMark(q.bid(), q.ask(), q.mark(), null, q.markFreshness(),
                1.0, 0.0, 0.0, 0.0, q.evidence());
    }

    private static LegMark optionMark(OptionQuote q) {
        return new LegMark(q.bid(), q.ask(), q.mid(), q.iv(), q.markFreshness(),
                q.delta(), q.gamma(), q.theta(), q.vega(), q.evidence());
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

    @Override
    public double riskFreeRate(int days, String worldId) {
        return market.riskFreeRateQuote(days, worldId).annualRate();
    }

    @Override
    public io.liftandshift.strikebench.model.DataEvidence riskFreeRateEvidence(int days, String worldId) {
        return market.riskFreeRateQuote(days, worldId).evidence();
    }
}
