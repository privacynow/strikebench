package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.CandleCoverage;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.ports.CandleStore;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.util.DataUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JointPathEnsembleTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-22T16:00:00Z"), ZoneOffset.UTC);
    private static final DataEvidence OBSERVED = DataEvidence.of("stored observed bars", Freshness.EOD);

    @Test
    void alignsBySessionDisclosesOmissionsAndMeasuresDependence() {
        var service = new PathEnsembleService(null, CLOCK);
        List<LocalDate> sessions = sessions(42);
        var a = history("AAA", sessions, 1);
        LocalDate removedOne = sessions.get(7), removedTwo = sessions.get(19);
        var b = new PathEnsembleService.HistoryInput("BBB", a.returns().stream()
                .filter(value -> !value.session().equals(removedOne)
                        && !value.session().equals(removedTwo)).toList(), 41, OBSERVED);
        var c = history("CCC", sessions, -1);

        var evidence = service.measureCorrelation(List.of(a, b, c));

        assertThat(evidence.available()).isTrue();
        assertThat(evidence.alignedSessions()).isEqualTo(40);
        assertThat(evidence.symbols()).filteredOn(row -> row.symbol().equals("BBB"))
                .singleElement().satisfies(row -> {
                    assertThat(row.returnSessions()).isEqualTo(40);
                    assertThat(row.omittedReturnSessions()).isZero();
                });
        assertThat(evidence.symbols()).filteredOn(row -> row.symbol().equals("AAA"))
                .singleElement().satisfies(row -> assertThat(row.omittedReturnSessions()).isEqualTo(2));
        assertThat(evidence.correlation("AAA", "BBB")).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(evidence.correlation("AAA", "CCC")).isCloseTo(-1.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(evidence.basis()).contains("intersection").contains("no fill-forward");
    }

    @Test
    void jointPathsShareAPathIndexAndAreExactlyReproducible() {
        var service = new PathEnsembleService(null, CLOCK);
        List<LocalDate> dates = sessions(64);
        List<PathEnsembleService.Scope> scopes = List.of(
                new PathEnsembleService.Scope("AAA", "observed", AnalysisContext.OBSERVED),
                new PathEnsembleService.Scope("BBB", "observed", AnalysisContext.OBSERVED),
                new PathEnsembleService.Scope("CCC", "observed", AnalysisContext.OBSERVED));
        Map<String, Double> spots = new LinkedHashMap<>();
        spots.put("AAA", 100.0); spots.put("BBB", 200.0); spots.put("CCC", 80.0);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.BLOCK_BOOTSTRAP,
                ScenarioSpec.Shape.CHOP, 12, 1, 0, .25,
                0, 0, 0, 6, ScenarioSpec.Heston.fromVol(.25), 919L, 180);
        List<PathEnsembleService.HistoryInput> histories = List.of(
                history("AAA", dates, 1), history("BBB", dates, 1), history("CCC", dates, -1));

        var first = service.buildJointFromEvidence(scopes, spec, spots, histories,
                LocalDate.parse("2026-07-22"));
        var second = service.buildJointFromEvidence(scopes, spec, spots, histories,
                LocalDate.parse("2026-07-22"));

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.modelVersion()).isEqualTo(PathEnsembleService.JOINT_MODEL_VERSION);
        assertThat(first.member("AAA").paths()).isDeepEqualTo(second.member("AAA").paths());
        assertThat(first.member("AAA").paths().length)
                .isEqualTo(first.member("BBB").paths().length);
        for (int path = 0; path < first.member("AAA").paths().length; path++) {
            double[] a = first.member("AAA").paths()[path];
            double[] b = first.member("BBB").paths()[path];
            for (int step = 0; step < a.length; step++) {
                assertThat(a[step] / 100.0).isCloseTo(b[step] / 200.0,
                        org.assertj.core.data.Offset.offset(1e-12));
            }
        }
        assertThat(first.interpretation()).contains("same sampled market history")
                .contains("never summed");
    }

    @Test
    void refusesToManufactureCorrelationFromTooLittleCommonHistory() {
        var service = new PathEnsembleService(null, CLOCK);
        List<LocalDate> dates = sessions(20);
        var evidence = service.measureCorrelation(List.of(
                history("AAA", dates, 1), history("BBB", dates, -1)));
        assertThat(evidence.available()).isFalse();
        assertThat(evidence.unavailableReason()).contains("20 aligned").contains("at least 30");

        var spec = new ScenarioSpec(ScenarioSpec.PathModel.BLOCK_BOOTSTRAP,
                ScenarioSpec.Shape.CHOP, 5, 1, 0, .25,
                0, 0, 0, 6, ScenarioSpec.Heston.fromVol(.25), 1L, 20);
        assertThatThrownBy(() -> service.buildJointFromEvidence(List.of(
                        new PathEnsembleService.Scope("AAA", "observed", AnalysisContext.OBSERVED),
                        new PathEnsembleService.Scope("BBB", "observed", AnalysisContext.OBSERVED)),
                spec, Map.of("AAA", 100.0, "BBB", 100.0), List.of(
                        history("AAA", dates, 1), history("BBB", dates, -1)),
                LocalDate.parse("2026-07-22")))
                .isInstanceOf(DataUnavailableException.class)
                .hasMessageContaining("at least 30");
    }

    @Test
    void automaticBookBuildUsesLatestSharedLocalCloseWithoutProviderFallback() {
        List<LocalDate> dates = sessions(48);
        List<Candle> aaa = candles(dates, 100, 1);
        List<Candle> bbb = candles(dates.subList(0, dates.size() - 1), 200, 1);
        CandleStore store = (symbol, from, to, dataset) -> {
            List<Candle> values = "AAA".equals(symbol) ? aaa : "BBB".equals(symbol) ? bbb : List.of();
            if (values.isEmpty()) return Optional.empty();
            CandleSeries series = new CandleSeries(values, "stored:test-feed", Freshness.EOD);
            return Optional.of(new CandleStore.Read(series,
                    CandleCoverage.assess(values, from, to)));
        };
        MarketDataService market = new MarketDataService(List.<MarketDataProvider>of(),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), store);
        var service = new PathEnsembleService(market, CLOCK);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.BLOCK_BOOTSTRAP,
                ScenarioSpec.Shape.CHOP, 8, 1, 0, .25,
                0, 0, 0, 6, ScenarioSpec.Heston.fromVol(.25), 778L, 80);

        var joint = service.buildJointFromLocalHistory(List.of(
                new PathEnsembleService.Scope("AAA", "observed", AnalysisContext.OBSERVED),
                new PathEnsembleService.Scope("BBB", "observed", AnalysisContext.OBSERVED)), spec);

        LocalDate shared = dates.get(dates.size() - 2);
        assertThat(joint.anchorDate()).isEqualTo(shared);
        assertThat(joint.member("AAA").spot())
                .isEqualTo(aaa.stream().filter(c -> c.date().equals(shared)).findFirst().orElseThrow()
                        .close().doubleValue());
        assertThat(joint.member("BBB").spot()).isEqualTo(bbb.getLast().close().doubleValue());
        assertThat(joint.correlation().alignedSessions()).isGreaterThanOrEqualTo(30);
        assertThat(joint.interpretation()).contains("already resident")
                .contains("no external provider acquisition");
    }

    private static PathEnsembleService.HistoryInput history(String symbol,
                                                             List<LocalDate> sessions,
                                                             int direction) {
        List<PathEnsembleService.DatedReturn> values = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            double magnitude = i % 5 == 0 ? .025 : i % 2 == 0 ? .012 : -.009;
            values.add(new PathEnsembleService.DatedReturn(sessions.get(i), direction * magnitude));
        }
        return new PathEnsembleService.HistoryInput(symbol, values, sessions.size() + 1, OBSERVED);
    }

    private static List<LocalDate> sessions(int count) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate date = LocalDate.parse("2026-01-02");
        while (out.size() < count) {
            if (MarketHours.isTradingDay(date)) out.add(date);
            date = date.plusDays(1);
        }
        return out;
    }

    private static List<Candle> candles(List<LocalDate> dates, double start, int direction) {
        List<Candle> out = new ArrayList<>();
        double price = start;
        for (int i = 0; i < dates.size(); i++) {
            double move = i == 0 ? 0 : direction * (i % 5 == 0 ? .021 : i % 2 == 0 ? .011 : -.008);
            price *= Math.exp(move);
            BigDecimal close = BigDecimal.valueOf(price);
            out.add(new Candle(dates.get(i), close, close, close, close, 1_000, true));
        }
        return out;
    }
}
