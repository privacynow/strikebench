package io.liftandshift.strikebench.recommend;
import static io.liftandshift.strikebench.util.Numbers.round2;

import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.model.Candle;
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

    /** Compatibility aliases; {@link NewsSentimentScorer} is the one scorer and vocabulary owner. */
    static final List<String> POSITIVE = NewsSentimentScorer.POSITIVE_KEYWORDS;
    static final List<String> NEGATIVE = NewsSentimentScorer.NEGATIVE_KEYWORDS;
    public static final String SENTIMENT_SCORER_VERSION = NewsSentimentScorer.VERSION;

    /** Machine-readable provenance for the volatility comparison used by Universe Scout. */
    public record VolatilityEvidence(
            boolean impliedAvailable,
            String impliedSource,
            String impliedFreshness,
            Long impliedAsOfEpochMs,
            boolean realizedAvailable,
            String realizedSource,
            String realizedFreshness,
            int realizedObservations,
            String realizedFrom,
            String realizedTo,
            List<String> unavailableReasons
    ) {
        public VolatilityEvidence {
            unavailableReasons = unavailableReasons == null ? List.of() : List.copyOf(unavailableReasons);
        }
    }

    /** Event context stays source-backed and explicitly unavailable when no eligible news exists. */
    public record EventEvidence(
            boolean available,
            boolean eventRisk,
            List<NewsSentimentScorer.EventFlag> flags,
            int headlineCount,
            List<String> sources,
            Long latestPublishedEpochMs,
            String basis,
            String scorerVersion,
            String note
    ) {
        public EventEvidence {
            flags = flags == null ? List.of() : List.copyOf(flags);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

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
            List<String> rationale,     // human-readable evidence, in order of weight
            String sentimentScorerVersion,
            NewsSentimentScorer.Aggregate sentimentAggregate,
            List<NewsSentimentScorer.HeadlineSentiment> headlineSentiment,
            VolatilityEvidence volatilityEvidence,
            EventEvidence eventEvidence
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

    public Optional<Signals> analyze(String symbol) { return analyze(symbol, null); }

    /** World-aware: a simulated session's scout reads THAT world's market. null = observed. */
    public Optional<Signals> analyze(String symbol, String worldId) {
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        Quote quote = market.quote(sym, worldId).orElse(null);
        if (quote == null) return Optional.empty();
        var lane = market.lane(worldId);
        if (!quote.evidence().usableIn(lane)) return Optional.empty();
        boolean optionable = quote.optionable() && !market.expirations(sym, worldId).isEmpty();

        LocalDate today = market.simInstant(worldId)
                .map(i -> LocalDate.ofInstant(i, io.liftandshift.strikebench.market.MarketHours.EASTERN))
                .orElseGet(() -> LocalDate.now(clock));
        io.liftandshift.strikebench.market.CandleSeries series = market.candleSeries(sym, today.minusDays(120), today, worldId, null);
        if (!series.isEmpty() && !series.evidence().usableIn(lane)) {
            series = io.liftandshift.strikebench.market.CandleSeries.EMPTY;
        }
        List<Candle> candles = series.candles();
        boolean demoHistory = series.evidence().provenance()
                == io.liftandshift.strikebench.model.DataProvenance.DEMO;
        Double ret5 = trailingReturn(candles, 5);
        Double ret20 = trailingReturn(candles, 20);
        double hv = HistoricalVol.annualized(candles, 30);
        Double hv30 = Double.isNaN(hv) ? null : hv;

        Double ivAtm = null;
        Double liquidity = null;
        OptionChain volatilityChain = null;
        if (optionable) {
            LocalDate exp = market.expirations(sym, worldId).stream()
                    .min(Comparator.comparingLong(d -> Math.abs(ChronoUnit.DAYS.between(today, d) - 30)))
                    .orElse(null);
            OptionChain chain = exp == null ? null : market.chain(sym, exp, worldId).orElse(null);
            if (chain != null && !chain.isEmpty() && chain.evidence().usableIn(lane)) {
                volatilityChain = chain;
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

        // Demo headlines are explicitly fabricated practice prompts and simulated worlds have
        // no real-company news. They may be displayed as teaching catalysts, but must never
        // become sentiment, thesis, confidence, or event-risk evidence.
        NewsSentimentScorer.Result newsSentiment = lane == MarketLane.OBSERVED
                ? NewsSentimentScorer.score(market.news(sym, worldId))
                : NewsSentimentScorer.unavailable(List.of(),
                        lane == MarketLane.DEMO ? NewsSentimentScorer.DEMO_BASIS
                                : NewsSentimentScorer.UNAVAILABLE_BASIS,
                        lane == MarketLane.DEMO
                                ? "News sentiment unavailable — Demo catalysts are fabricated teaching prompts."
                                : "News sentiment unavailable — this generated market has no issuer-news feed.");
        List<String> posHits = newsSentiment.headlines().stream()
                .filter(item -> item.classification() == NewsSentimentScorer.Classification.POSITIVE)
                .map(NewsSentimentScorer.HeadlineSentiment::headline).toList();
        List<String> negHits = newsSentiment.headlines().stream()
                .filter(item -> item.classification() == NewsSentimentScorer.Classification.NEGATIVE)
                .map(NewsSentimentScorer.HeadlineSentiment::headline).toList();
        int scored = posHits.size() + negHits.size();
        double sentiment = newsSentiment.aggregate().score() == null ? 0.0
                : newsSentiment.aggregate().score();
        boolean eventRisk = newsSentiment.aggregate().available()
                && newsSentiment.aggregate().eventRisk();

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
        if (!newsSentiment.aggregate().available()) {
            rationale.add(newsSentiment.aggregate().note());
        } else if (scored > 0) {
            rationale.add(String.format("News sentiment %+.2f (%d positive vs %d negative headline%s)",
                    sentiment, posHits.size(), negHits.size(), scored == 1 ? "" : "s"));
        } else {
            rationale.add("No clearly positive or negative headlines — sentiment neutral");
        }
        if (ivHv != null) {
            // The realized-vol side comes from the candle history — if that's demo, say so (the IV
            // side is live from the chain, but the comparison is only as real as the weaker input).
            rationale.add(String.format("IV %.0f%% vs realized %.0f%% — options look %s%s%s",
                    ivAtm * 100, hv30 * 100, volSignal.toLowerCase(Locale.ROOT),
                    "RICH".equals(volSignal) ? " (favors collecting premium)"
                            : "CHEAP".equals(volSignal) ? " (favors buying premium)" : "",
                    demoHistory ? " — realized vol is from DEMO price history" : ""));
        }
        if (eventRisk) rationale.add("Event risk: an earnings/guidance-type headline is in the news window");

        List<String> volatilityGaps = new ArrayList<>();
        if (ivAtm == null) {
            volatilityGaps.add(optionable
                    ? "ATM implied volatility is unavailable from the eligible option chain."
                    : "No listed option chain is available for this symbol.");
        }
        if (hv30 == null) {
            volatilityGaps.add("At least 31 eligible daily closes are required for 30-day realized volatility.");
        }
        VolatilityEvidence volatilityEvidence = new VolatilityEvidence(
                ivAtm != null,
                volatilityChain == null ? null : volatilityChain.source(),
                volatilityChain == null ? "MISSING" : volatilityChain.freshness().name(),
                volatilityChain == null ? null : volatilityChain.asOfEpochMs(),
                hv30 != null,
                series.source(),
                series.freshness().name(),
                candles.size(),
                candles.isEmpty() ? null : candles.getFirst().date().toString(),
                candles.isEmpty() ? null : candles.getLast().date().toString(),
                volatilityGaps);
        List<String> newsSources = newsSentiment.headlines().stream()
                .map(NewsSentimentScorer.HeadlineSentiment::source)
                .filter(java.util.Objects::nonNull)
                .filter(source -> !source.isBlank())
                .distinct().toList();
        Long latestPublished = newsSentiment.headlines().stream()
                .map(NewsSentimentScorer.HeadlineSentiment::publishedEpochMs)
                .filter(published -> published > 0)
                .max(Long::compareTo).orElse(null);
        EventEvidence eventEvidence = new EventEvidence(
                newsSentiment.aggregate().available(),
                eventRisk,
                newsSentiment.aggregate().eventRiskFlags(),
                newsSentiment.aggregate().totalHeadlines(),
                newsSources,
                latestPublished,
                newsSentiment.aggregate().basis(),
                newsSentiment.aggregate().scorerVersion(),
                newsSentiment.aggregate().note());

        return Optional.of(new Signals(sym, optionable, ret5, ret20, ivAtm, hv30, ivHv, volSignal,
                round2(sentiment), List.copyOf(posHits), List.copyOf(negHits), eventRisk,
                liquidity, thesis, round2(confidence), List.copyOf(rationale), SENTIMENT_SCORER_VERSION,
                newsSentiment.aggregate(), newsSentiment.headlines(), volatilityEvidence, eventEvidence));
    }

    private static Double trailingReturn(List<Candle> candles, int days) {
        if (candles == null || candles.size() <= days) return null;
        double now = candles.getLast().close().doubleValue();
        double then = candles.get(candles.size() - 1 - days).close().doubleValue();
        return then <= 0 ? null : now / then - 1;
    }

    static int countHits(String haystack, List<String> stems) {
        return NewsSentimentScorer.countHits(haystack, stems);
    }

}
