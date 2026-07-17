package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.model.NewsItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsSentimentScorerTest {

    @Test
    void classifiesEveryHeadlineWithAuditableKeywordsAndStableVersion() {
        List<NewsItem> news = List.of(
                item("Apple beats expectations as growth accelerates", 4),
                item("Regulators open probe after recall", 3),
                item("Strong growth warning", 2),
                item("Company schedules its annual meeting", 1));

        var first = NewsSentimentScorer.score(news);
        var second = NewsSentimentScorer.score(news);

        assertThat(first).isEqualTo(second);
        assertThat(first.aggregate().scorerVersion()).isEqualTo("sentiment-keyword-v1");
        assertThat(first.headlines()).extracting(NewsSentimentScorer.HeadlineSentiment::classification)
                .containsExactly(NewsSentimentScorer.Classification.POSITIVE,
                        NewsSentimentScorer.Classification.NEGATIVE,
                        NewsSentimentScorer.Classification.POSITIVE,
                        NewsSentimentScorer.Classification.NEUTRAL);
        assertThat(first.headlines().getFirst().positiveKeywords()).contains("beat", "beats", "growth", "accelerat");
        assertThat(first.headlines().get(1).negativeKeywords()).contains("probe", "recall");
        assertThat(first.headlines()).allSatisfy(headline -> {
            assertThat(headline.basis()).isEqualTo(NewsSentimentScorer.KEYWORD_BASIS);
            assertThat(headline.scorerVersion()).isEqualTo(NewsSentimentScorer.VERSION);
        });
    }

    @Test
    void aggregatePublishesTrendCoverageAndTypedEventRisk() {
        var scored = NewsSentimentScorer.score(List.of(
                item("Strong earnings results beat expectations", 3),
                item("Board approves merger after FDA guidance", 2),
                item("Company schedules annual meeting", 1)));

        assertThat(scored.aggregate().available()).isTrue();
        assertThat(scored.aggregate().trend()).isEqualTo(NewsSentimentScorer.Trend.POSITIVE);
        assertThat(scored.aggregate().totalHeadlines()).isEqualTo(3);
        assertThat(scored.aggregate().scoredHeadlines()).isEqualTo(1);
        assertThat(scored.aggregate().coverageRatio()).isEqualTo(0.33);
        assertThat(scored.aggregate().eventRisk()).isTrue();
        assertThat(scored.aggregate().eventRiskHeadlines()).isEqualTo(2);
        assertThat(scored.aggregate().eventRiskFlags()).containsExactly(
                NewsSentimentScorer.EventFlag.EARNINGS,
                NewsSentimentScorer.EventFlag.RESULTS,
                NewsSentimentScorer.EventFlag.GUIDANCE,
                NewsSentimentScorer.EventFlag.FDA,
                NewsSentimentScorer.EventFlag.M_AND_A);
        assertThat(scored.headlines().getFirst().eventRiskFlags()).containsExactly(
                NewsSentimentScorer.EventFlag.EARNINGS, NewsSentimentScorer.EventFlag.RESULTS);
    }

    @Test
    void missingAndDemoInputsStayUnavailableWithoutInventedClassificationOrEvents() {
        var missing = NewsSentimentScorer.score(List.of());
        assertThat(missing.headlines()).isEmpty();
        assertThat(missing.aggregate().available()).isFalse();
        assertThat(missing.aggregate().trend()).isEqualTo(NewsSentimentScorer.Trend.UNAVAILABLE);
        assertThat(missing.aggregate().score()).isNull();
        assertThat(missing.aggregate().basis()).isEqualTo(NewsSentimentScorer.UNAVAILABLE_BASIS);

        var demo = NewsSentimentScorer.unavailable(
                List.of(item("Strong earnings beat", 1)), NewsSentimentScorer.DEMO_BASIS,
                "Fabricated teaching prompt; not observed news.");
        assertThat(demo.aggregate().available()).isFalse();
        assertThat(demo.aggregate().eventRisk()).isFalse();
        assertThat(demo.headlines()).singleElement().satisfies(headline -> {
            assertThat(headline.classification()).isEqualTo(NewsSentimentScorer.Classification.UNAVAILABLE);
            assertThat(headline.score()).isNull();
            assertThat(headline.positiveKeywords()).isEmpty();
            assertThat(headline.eventRisk()).isFalse();
            assertThat(headline.eventRiskFlags()).isEmpty();
            assertThat(headline.basis()).isEqualTo(NewsSentimentScorer.DEMO_BASIS);
        });
    }

    private static NewsItem item(String headline, long day) {
        return new NewsItem("AAPL", headline, "Test Wire", "https://example.test/" + day, day * 1_000L);
    }
}
