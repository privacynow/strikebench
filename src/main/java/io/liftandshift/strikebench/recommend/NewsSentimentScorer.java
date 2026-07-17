package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.model.NewsItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * One deterministic, inspectable keyword scorer for every news consumer. The version is part of
 * the result contract because a sentiment-derived conclusion is not reproducible without the
 * exact vocabulary and aggregation policy that produced it.
 */
public final class NewsSentimentScorer {

    public static final String VERSION = "sentiment-keyword-v1";
    public static final String KEYWORD_BASIS = "KEYWORD_DERIVED";
    public static final String UNAVAILABLE_BASIS = "UNAVAILABLE";
    public static final String DEMO_BASIS = "DEMO_FABRICATED";

    static final List<String> POSITIVE_KEYWORDS = List.of(
            "beat", "beats", "record", "surge", "surges", "rally", "rallies", "rallied", "upgrade",
            "upgraded", "growth", "accelerat", "strong", "tops", "exceed", "profit", "gain", "jump",
            "soar", "bullish", "outperform", "cools", "eases", "optimis", "breakthrough");
    static final List<String> NEGATIVE_KEYWORDS = List.of(
            "miss", "misses", "downgrade", "probe", "investigation", "lawsuit", "recall", "cuts",
            "falls", "fall", "plunge", "drop", "drops", "weak", "warning", "bearish", "layoff",
            "fraud", "scrutiny", "slump", "fears", "concern", "halt", "underperform", "loss");

    private static final List<EventRule> EVENT_RULES = List.of(
            new EventRule(EventFlag.EARNINGS, List.of("earnings")),
            new EventRule(EventFlag.GUIDANCE, List.of("guidance")),
            new EventRule(EventFlag.RESULTS, List.of("results")),
            new EventRule(EventFlag.FDA, List.of("fda")),
            new EventRule(EventFlag.M_AND_A, List.of("merger", "acquisition")),
            new EventRule(EventFlag.SEC_FILING, List.of("8-k")),
            new EventRule(EventFlag.DIVIDEND, List.of("dividend date")));

    public enum Classification { POSITIVE, NEGATIVE, MIXED, NEUTRAL, UNAVAILABLE }
    public enum Trend { POSITIVE, NEGATIVE, MIXED, NEUTRAL, UNAVAILABLE }
    public enum EventFlag { EARNINGS, GUIDANCE, RESULTS, FDA, M_AND_A, SEC_FILING, DIVIDEND }

    /** Flattened wire-safe headline: existing clients retain the NewsItem fields at top level. */
    public record HeadlineSentiment(
            String symbol,
            String headline,
            String source,
            String url,
            long publishedEpochMs,
            Classification classification,
            Double score,
            String basis,
            List<String> positiveKeywords,
            List<String> negativeKeywords,
            boolean eventRisk,
            List<EventFlag> eventRiskFlags,
            String scorerVersion
    ) {
        public HeadlineSentiment {
            positiveKeywords = positiveKeywords == null ? List.of() : List.copyOf(positiveKeywords);
            negativeKeywords = negativeKeywords == null ? List.of() : List.copyOf(negativeKeywords);
            eventRiskFlags = eventRiskFlags == null ? List.of() : List.copyOf(eventRiskFlags);
        }
    }

    public record Aggregate(
            boolean available,
            Trend trend,
            Double score,
            int totalHeadlines,
            int scoredHeadlines,
            int positiveHeadlines,
            int negativeHeadlines,
            int mixedHeadlines,
            int neutralHeadlines,
            double coverageRatio,
            boolean eventRisk,
            int eventRiskHeadlines,
            List<EventFlag> eventRiskFlags,
            String basis,
            String scorerVersion,
            String note
    ) {
        public Aggregate {
            eventRiskFlags = eventRiskFlags == null ? List.of() : List.copyOf(eventRiskFlags);
        }
    }

    public record Result(List<HeadlineSentiment> headlines, Aggregate aggregate) {
        public Result {
            headlines = headlines == null ? List.of() : List.copyOf(headlines);
            if (aggregate == null) throw new IllegalArgumentException("sentiment aggregate is required");
        }
    }

    /** Score observed headlines. An empty input is unavailable, never silently neutral. */
    public static Result score(List<NewsItem> raw) {
        List<NewsItem> items = clean(raw);
        if (items.isEmpty()) {
            return unavailable(List.of(), UNAVAILABLE_BASIS,
                    "News sentiment unavailable — no observed headlines were available.");
        }

        List<HeadlineSentiment> headlines = items.stream().map(NewsSentimentScorer::scoreOne).toList();
        int positive = count(headlines, Classification.POSITIVE);
        int negative = count(headlines, Classification.NEGATIVE);
        int mixed = count(headlines, Classification.MIXED);
        int neutral = count(headlines, Classification.NEUTRAL);
        int scored = positive + negative + mixed;
        int directional = positive + negative;
        double aggregateScore = directional == 0 ? 0.0 : round2((positive - negative) / (double) directional);
        Trend trend = positive > negative ? Trend.POSITIVE
                : negative > positive ? Trend.NEGATIVE
                : scored > 0 ? Trend.MIXED : Trend.NEUTRAL;
        List<EventFlag> flags = headlines.stream().flatMap(h -> h.eventRiskFlags().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        int eventHeadlines = (int) headlines.stream().filter(HeadlineSentiment::eventRisk).count();
        String note = scored == 0
                ? "No configured positive or negative keyword matched; the available headlines remain neutral under this scorer."
                : "Keyword-derived headline classification; open the source before treating a headline as evidence.";
        return new Result(headlines, new Aggregate(true, trend, aggregateScore, headlines.size(), scored,
                positive, negative, mixed, neutral, round2(scored / (double) headlines.size()),
                eventHeadlines > 0, eventHeadlines, flags, KEYWORD_BASIS, VERSION, note));
    }

    /**
     * Preserve raw headlines while refusing to classify a lane that is not eligible evidence.
     * Demo callers use this to keep fabricated teaching prompts visible without letting their
     * wording influence sentiment, event risk, thesis, or confidence.
     */
    public static Result unavailable(List<NewsItem> raw, String basis, String note) {
        String resolvedBasis = basis == null || basis.isBlank() ? UNAVAILABLE_BASIS : basis;
        List<HeadlineSentiment> headlines = clean(raw).stream().map(item -> new HeadlineSentiment(
                item.symbol(), item.headline(), item.source(), item.url(), item.publishedEpochMs(),
                Classification.UNAVAILABLE, null, resolvedBasis, List.of(), List.of(), false, List.of(), VERSION))
                .toList();
        return new Result(headlines, new Aggregate(false, Trend.UNAVAILABLE, null, headlines.size(),
                0, 0, 0, 0, 0, 0.0, false, 0, List.of(), resolvedBasis, VERSION,
                note == null || note.isBlank() ? "News sentiment is unavailable." : note));
    }

    static int countHits(String haystack, List<String> stems) {
        return matches(haystack, stems).size();
    }

    private static HeadlineSentiment scoreOne(NewsItem item) {
        String normalized = item.headline() == null ? "" : item.headline().toLowerCase(Locale.ROOT);
        List<String> positive = matches(normalized, POSITIVE_KEYWORDS);
        List<String> negative = matches(normalized, NEGATIVE_KEYWORDS);
        int p = positive.size(), n = negative.size();
        Classification classification = p == 0 && n == 0 ? Classification.NEUTRAL
                : p > n ? Classification.POSITIVE
                : n > p ? Classification.NEGATIVE : Classification.MIXED;
        Double score = p + n == 0 ? 0.0 : round2((p - n) / (double) (p + n));
        List<EventFlag> flags = eventFlags(normalized);
        return new HeadlineSentiment(item.symbol(), item.headline(), item.source(), item.url(),
                item.publishedEpochMs(), classification, score, KEYWORD_BASIS,
                positive, negative, !flags.isEmpty(), flags, VERSION);
    }

    private static List<String> matches(String haystack, List<String> stems) {
        if (haystack == null || haystack.isBlank()) return List.of();
        List<String> hits = new ArrayList<>();
        for (String stem : stems) if (haystack.contains(stem)) hits.add(stem);
        return List.copyOf(hits);
    }

    private static List<EventFlag> eventFlags(String headline) {
        List<EventFlag> flags = new ArrayList<>();
        for (EventRule rule : EVENT_RULES) {
            if (!matches(headline, rule.stems()).isEmpty()) flags.add(rule.flag());
        }
        return List.copyOf(flags);
    }

    private static List<NewsItem> clean(List<NewsItem> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static int count(List<HeadlineSentiment> headlines, Classification classification) {
        return (int) headlines.stream().filter(h -> h.classification() == classification).count();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record EventRule(EventFlag flag, List<String> stems) {}

    private NewsSentimentScorer() {}
}
