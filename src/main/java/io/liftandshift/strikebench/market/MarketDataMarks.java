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
        return current.quote(symbol).map(MarketDataEngine.MarketSnapshot::toQuote);
    }

    @Override
    public Optional<Quote> underlyingQuote(String symbol, String worldId) {
        return observed(worldId) ? observedQuote(symbol) : market.quote(symbol, worldId);
    }

    @Override
    public Map<String, BigDecimal> underlyingMarks(List<String> symbols, String worldId) {
        if (!observed(worldId) || engine == null) return MarksSource.super.underlyingMarks(symbols, worldId);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (MarketDataEngine.MarketSnapshot snapshot : engine.quotes(symbols == null ? List.of() : symbols)) {
            BigDecimal mark = snapshot.toQuote().mark();
            if (mark != null && mark.signum() > 0) out.put(snapshot.symbol(), mark);
        }
        return out;
    }

    @Override
    public Optional<LegMark> legMark(String symbol, io.liftandshift.strikebench.model.Leg leg, String worldId) {
        if (observed(worldId)) return observedLegMark(symbol, leg);
        if (leg.isStock()) {
            return market.quote(symbol, worldId).map(MarketDataMarks::stockMark)
                    .filter(m -> m.mid() != null);
        }
        // The public chain identifies standard listed contracts only. Multiplier alone cannot
        // identify an adjusted deliverable, so using the same strike's x100 quote for an x10 lot
        // would falsely label the tracked value OBSERVED. Keep it unavailable until a broker or
        // vendor supplies the adjusted contract identity and quote.
        if (leg.multiplier() != Leg.SHARES_PER_CONTRACT) return Optional.empty();
        return market.chain(symbol, leg.expiration(), worldId)
                .flatMap(chain -> chain.find(leg.type(), leg.strike())
                        .filter(io.liftandshift.strikebench.model.OptionQuote::hasMark)
                        .map(MarketDataMarks::optionMark));
    }

    @Override
    public Optional<java.math.BigDecimal> closeOn(String symbol, java.time.LocalDate date, String worldId) {
        if (observed(worldId)) return observedCloseOn(symbol, date);
        // Settlement INSIDE a simulated world uses that world's own closes — its account is the
        // only one allowed here, so a synthetic close can never mint real-mode paper cash.
        var series = market.candleSeries(symbol, date.minusDays(7), date, worldId, null);
        if (series.isEmpty()) return Optional.empty();
        var last = series.candles().getLast();
        return last.date().equals(date) ? Optional.of(last.close()) : Optional.empty();
    }

    @Override
    public Optional<java.time.Instant> simNow(String worldId) {
        return worldId == null ? Optional.empty() : market.simInstant(worldId);
    }

    private Optional<LegMark> observedLegMark(String symbol, Leg leg) {
        if (leg.isStock()) {
            // A share behaves like a delta-1, greek-free contract
            return observedQuote(symbol).map(MarketDataMarks::stockMark)
                    .filter(m -> m.mid() != null);
        }
        if (leg.multiplier() != Leg.SHARES_PER_CONTRACT) return Optional.empty();
        return market.chain(symbol, leg.expiration())
                .flatMap(chain -> chain.find(leg.type(), leg.strike())
                        .filter(OptionQuote::hasMark)
                        .map(MarketDataMarks::optionMark));
    }

    private static LegMark stockMark(Quote q) {
        return LegMark.fromUnderlying(q);
    }

    private static LegMark optionMark(OptionQuote q) {
        return new LegMark(q.bid(), q.ask(), q.mid(), q.iv(), q.markFreshness(),
                q.delta(), q.gamma(), q.theta(), q.vega(), q.evidence(), q.asOfEpochMs());
    }

    private Optional<BigDecimal> observedCloseOn(String symbol, java.time.LocalDate date) {
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
    public double riskFreeRate(int days, String worldId) {
        return market.riskFreeRateQuote(days, worldId).annualRate();
    }

    @Override
    public io.liftandshift.strikebench.model.DataEvidence riskFreeRateEvidence(int days, String worldId) {
        return market.riskFreeRateQuote(days, worldId).evidence();
    }
}
