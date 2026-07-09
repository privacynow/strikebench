package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.pricing.HistoricalVol;
import io.liftandshift.strikebench.strategy.StrategyFamily;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic, explainable market signals per symbol: price momentum, IV-vs-HV
 * volatility edge, and keyword-scored news sentiment. Deliberately simple — every
 * signal is a rule a beginner can audit, never a black box, and the output always
 * carries the evidence it was derived from.
 */
public final class SignalEngine {

    /** Naive keyword sentiment. Stems, matched case-insensitively against headlines. */
    static final List<String> POSITIVE = List.of(
            "beat", "beats", "record", "surge", "surges", "rally", "rallies", "rallied", "upgrade",
            "upgraded", "growth", "accelerat", "strong", "tops", "exceed", "profit", "gain", "jump",
            "soar", "bullish", "outperform", "cools", "eases", "optimis", "breakthrough");
    static final List<String> NEGATIVE = List.of(
            "miss", "misses", "downgrade", "probe", "investigation", "lawsuit", "recall", "cuts",
            "falls", "fall", "plunge", "drop", "drops", "weak", "warning", "bearish", "layoff",
            "fraud", "scrutiny", "slump", "fears", "concern", "halt", "underperform", "loss");
    static final List<String> EVENT = List.of(
            "earnings", "guidance", "results", "fda", "merger", "acquisition", "8-k", "dividend date");

    public record Signals(
            String symbol,
            boolean optionable,
            Double ret5d,               // 5-trading-day return, ratio
            Double ret20d,              // 20-trading-day return, ratio
            Double ivAtm,
            Double hv30,
            Double ivHvRatio,
            String volSignal,           // RICH | CHEAP | FAIR | UNKNOWN
            double sentimentScore,      // -1..1
            List<String> positiveHeadlines,
            List<String> negativeHeadlines,
            boolean eventRisk,
            Double liquidityScore,      // 0..1 from ATM spread
            String thesis,              // BULLISH | BEARISH | NEUTRAL | VOLATILE
            double confidence,          // 0..1
            List<String> rationale      // human-readable evidence, in order of weight
    ) {}

    private final MarketDataService market;
    private final Clock clock;
    private final boolean fixturesOnly;

    public SignalEngine(MarketDataService market, Clock clock) {
        this(market, clock, true);
    }

    public SignalEngine(MarketDataService market, Clock clock, boolean fixturesOnly) {
        this.market = market;
        this.clock = clock;
        this.fixturesOnly = fixturesOnly;
    }

    public Optional<Signals> analyze(String symbol) {
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        Quote quote = market.quote(sym).orElse(null);
        if (quote == null) return Optional.empty();
        boolean optionable = quote.optionable() && !market.expirations(sym).isEmpty();

        LocalDate today = LocalDate.now(clock);
        io.liftandshift.strikebench.market.CandleSeries series = market.candleSeries(sym, today.minusDays(120), today);
        List<Candle> candles = series.candles();
        boolean demoHistory = series.isFixture() && !fixturesOnly;
        Double ret5 = trailingReturn(candles, 5);
        Double ret20 = trailingReturn(candles, 20);
        double hv = HistoricalVol.annualized(candles, 30);
        Double hv30 = Double.isNaN(hv) ? null : hv;

        Double ivAtm = null;
        Double liquidity = null;
        if (optionable) {
            LocalDate exp = market.expirations(sym).stream()
                    .min(Comparator.comparingLong(d -> Math.abs(ChronoUnit.DAYS.between(today, d) - 30)))
                    .orElse(null);
            OptionChain chain = exp == null ? null : market.chain(sym, exp).orElse(null);
            if (chain != null && !chain.isEmpty()) {
                OptionQuote atm = chain.calls().stream()
                        .filter(q -> q.iv() != null && q.hasMark())
                        .min(Comparator.comparingDouble(q -> Math.abs(q.strike().doubleValue() - chain.underlyingPrice().doubleValue())))
                        .orElse(null);
                if (atm != null) {
                    ivAtm = atm.iv();
                    double spread = atm.spreadPct();
                    liquidity = Double.isNaN(spread) ? null : Math.clamp(1.0 - spread / 0.15, 0, 1);
                }
            }
        }
        Double ivHv = ivAtm != null && hv30 != null && hv30 > 0 ? ivAtm / hv30 : null;
        String volSignal = ivHv == null ? "UNKNOWN" : ivHv > 1.25 ? "RICH" : ivHv < 0.85 ? "CHEAP" : "FAIR";

        List<NewsItem> news = market.news(sym);
        List<String> posHits = new ArrayList<>();
        List<String> negHits = new ArrayList<>();
        boolean eventRisk = false;
        for (NewsItem item : news) {
            String h = item.headline() == null ? "" : item.headline().toLowerCase(Locale.ROOT);
            int p = countHits(h, POSITIVE), n = countHits(h, NEGATIVE);
            if (p > n) posHits.add(item.headline());
            else if (n > p) negHits.add(item.headline());
            if (containsAny(h, EVENT)) eventRisk = true;
        }
        int scored = posHits.size() + negHits.size();
        double sentiment = scored == 0 ? 0 : (posHits.size() - negHits.size()) / (double) scored;

        // Direction blends price action (heavier) with sentiment
        double momentum = ret20 == null ? 0 : Math.clamp(ret20 / 0.05, -1, 1); // +-5% over 20d saturates
        double direction = 0.6 * momentum + 0.4 * sentiment;

        String thesis;
        if (Math.abs(direction) >= 0.25) {
            thesis = direction > 0 ? StrategyFamily.Thesis.BULLISH.name() : StrategyFamily.Thesis.BEARISH.name();
        } else if ("CHEAP".equals(volSignal) && (eventRisk || Math.abs(sentiment) >= 0.5)) {
            thesis = StrategyFamily.Thesis.VOLATILE.name();
        } else {
            thesis = StrategyFamily.Thesis.NEUTRAL.name();
        }

        double dataQuality = 0.4 * (ret20 != null && !demoHistory ? 1 : 0.4)
                + 0.35 * (ivHv != null ? 1 : 0.3)
                + 0.25 * (scored > 0 ? 1 : 0.5);
        double strength = Math.abs(direction) >= 0.25 ? Math.abs(direction)
                : "NEUTRAL".equals(thesis) && "RICH".equals(volSignal) ? 0.6
                : "VOLATILE".equals(thesis) ? 0.5 : 0.35;
        double confidence = Math.clamp(0.65 * strength + 0.35 * dataQuality, 0, 1);

        List<String> rationale = new ArrayList<>();
        if (ret20 != null) {
            rationale.add(String.format("20-day move %+.1f%% (%s momentum)%s", ret20 * 100,
                    momentum > 0.2 ? "upward" : momentum < -0.2 ? "downward" : "flat",
                    demoHistory ? " — DEMO price history, no live candle source configured" : ""));
        }
        if (scored > 0) {
            rationale.add(String.format("News sentiment %+.2f (%d positive vs %d negative headline%s)",
                    sentiment, posHits.size(), negHits.size(), scored == 1 ? "" : "s"));
        } else {
            rationale.add("No clearly positive or negative headlines — sentiment neutral");
        }
        if (ivHv != null) {
            rationale.add(String.format("IV %.0f%% vs realized %.0f%% — options look %s%s",
                    ivAtm * 100, hv30 * 100, volSignal.toLowerCase(Locale.ROOT),
                    "RICH".equals(volSignal) ? " (favors collecting premium)"
                            : "CHEAP".equals(volSignal) ? " (favors buying premium)" : ""));
        }
        if (eventRisk) rationale.add("Event risk: an earnings/guidance-type headline is in the news window");

        return Optional.of(new Signals(sym, optionable, ret5, ret20, ivAtm, hv30, ivHv, volSignal,
                round2(sentiment), List.copyOf(posHits), List.copyOf(negHits), eventRisk,
                liquidity, thesis, round2(confidence), List.copyOf(rationale)));
    }

    private static Double trailingReturn(List<Candle> candles, int days) {
        if (candles == null || candles.size() <= days) return null;
        double now = candles.getLast().close().doubleValue();
        double then = candles.get(candles.size() - 1 - days).close().doubleValue();
        return then <= 0 ? null : now / then - 1;
    }

    static int countHits(String haystack, List<String> stems) {
        int hits = 0;
        for (String stem : stems) {
            if (haystack.contains(stem)) hits++;
        }
        return hits;
    }

    private static boolean containsAny(String haystack, List<String> stems) {
        for (String stem : stems) if (haystack.contains(stem)) return true;
        return false;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
