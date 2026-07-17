package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.NewsItem;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCanvasTest {

    @Test void ivNodesInterpolateThenEvolveStrikeAndTermSurface() {
        var canvas = new ScenarioCanvasSpec("NYSE", 0.012, "entered dividend yield", -0.30, 0.08,
                ScenarioCanvasSpec.SurfaceDynamics.STICKY_MONEYNESS,
                ScenarioCanvasSpec.SettlementPolicy.CASH_INTRINSIC,
                ScenarioCanvasSpec.ExercisePolicy.EXPIRATION_ONLY,
                List.of(new ScenarioCanvasSpec.IvNode(0, .40),
                        new ScenarioCanvasSpec.IvNode(4, .20)), null).sane(8);

        assertThat(canvas.atmIv(2, 8, .99)).isCloseTo(.30,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(canvas.surfaceIv(2, 8, .99, 100, 100, 110, 90.0 / 365.0))
                .isNotEqualTo(.30)
                .isBetween(.20, .40);
        assertThat(canvas.dividendYieldForPricing()).isEqualTo(.012);
    }

    @Test void calendarClockCarriesWeekendAndExchangeHolidayTime() {
        var spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 2, .25, 7, 20);
        LocalDate thursdayBeforeIndependenceDay = LocalDate.of(2026, 7, 2);
        List<LocalDate> sessions = ScenarioSpec.sessionDates(thursdayBeforeIndependenceDay, 2);
        double[] dt = spec.calendarStepYears(thursdayBeforeIndependenceDay);

        assertThat(sessions).containsExactly(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7));
        assertThat(dt[0]).isEqualTo(4.0 / 365.0);
        assertThat(dt[1]).isEqualTo(1.0 / 365.0);
    }

    @Test void symbolScopeRepricesMultiplePositionsAndTransformsFrontExpiry() {
        LocalDate anchor = LocalDate.of(2026, 7, 2);
        LocalDate front = MarketHours.tradingDateAfter(anchor, 2);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                4, 1, 0, .25, 0, 0, 0, 6, null, 42, 3);
        double[][] paths = {
                {100, 104, 108, 112, 116},
                {100, 102, 106, 109, 113},
                {100,  98, 101, 103, 105}
        };
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, anchor);
        var call = new PathPosition(anchor, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), front, 1, BigDecimal.ZERO)));
        var shares = new PathPosition(anchor, List.of(Leg.stockShares(LegAction.BUY, 100, new BigDecimal("100"))));
        var canvas = new ScenarioCanvasSpec("NYSE", null, "dividend unavailable", 0, 0,
                ScenarioCanvasSpec.SurfaceDynamics.STICKY_MONEYNESS,
                ScenarioCanvasSpec.SettlementPolicy.PHYSICAL_IF_ITM,
                ScenarioCanvasSpec.ExercisePolicy.EXPIRATION_ONLY,
                List.of(new ScenarioCanvasSpec.IvNode(0, .30),
                        new ScenarioCanvasSpec.IvNode(2, .20)), null);

        var report = new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.30), canvas, .04,
                List.of(new ScenarioCanvasValuator.PositionInput("ours", "Calendar front", "REAL",
                                "TRACKED_STRUCTURE", call, 1, 500L, false),
                        new ScenarioCanvasValuator.PositionInput("stock", "100 shares", "BASELINE",
                                "STOCK_BASELINE", shares, 1, 1_000_000L, false)));

        assertThat(report.positions()).extracting(ScenarioCanvasValuator.PositionPath::key)
                .containsExactly("ours", "stock");
        assertThat(report.positions().getFirst().transformations()).singleElement()
                .satisfies(t -> assertThat(t.day()).isEqualTo(2));
        assertThat(report.positions().getFirst().legs().getFirst().days().get(3).state())
                .isEqualTo("PHYSICAL_SETTLEMENT_TRANSFORMED");
        assertThat(report.comparison()).extracting(ScenarioCanvasValuator.ComparisonRow::key)
                .containsExactlyInAnyOrder("ours", "stock");
        assertThat(report.underlying()).hasSize(5);
    }

    @Test void storedEnsembleAnchorDrivesBothCanvasDistributionAndDailyValuationClock() {
        LocalDate ensembleAnchor = LocalDate.of(2026, 7, 1);
        LocalDate positionAsOf = LocalDate.of(2026, 7, 6);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                4, 1, 0, .25, 0, 0, 0, 6, null, 79, 3);
        double[][] paths = {
                {100, 101, 102, 103, 104},
                {100, 100, 101, 102, 103},
                {100,  99, 100, 101, 102}
        };
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, paths, null, PathGenerator.MODEL_VERSION, ensembleAnchor);
        var position = new PathPosition(positionAsOf, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), MarketHours.tradingDateAfter(positionAsOf, 30), 1, BigDecimal.ZERO)));
        var iv = new IvSpec(.80, -.40, 12, .20, -1, 0, .03, 4);
        var canvas = ScenarioCanvasSpec.defaults();

        var distribution = new ScenarioSimulator().compare(ensemble,
                List.of(new ScenarioSimulator.CompareItem("call", position, null, null, 0, 1)),
                1, iv, canvas, .04).report().results().getFirst().result();
        var daily = new ScenarioCanvasValuator().value(ensemble, iv, canvas, .04,
                List.of(new ScenarioCanvasValuator.PositionInput("call", "Call", "PLAN",
                        "PROPOSAL", position, 1, null, true)));

        assertThat(distribution.p50Cents()).isCloseTo(daily.comparison().getFirst().horizonP50Cents(),
                org.assertj.core.data.Offset.offset(1L));
        assertThat(daily.notes()).anyMatch(note -> note.contains("Daily Greeks")
                && note.contains("representative path"));
    }

    @Test void optionExpiringAfterCanvasHorizonRetainsTimeValueAndLiveState() {
        LocalDate anchor = LocalDate.of(2026, 7, 1);
        var position = new PathPosition(anchor, List.of(Leg.option(LegAction.BUY, OptionType.CALL,
                new BigDecimal("100"), MarketHours.tradingDateAfter(anchor, 30), 1, BigDecimal.ZERO)));
        int steps = 4;
        double[] path = {100, 100, 100, 100, 100};
        var spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, steps, .30, 91, 1);
        double[] elapsed = PathValuationKernel.elapsed(spec.calendarStepYears(anchor));
        double[] iv = IvSpec.flat(.30).path(steps, elapsed[steps] / steps, 1);
        var canvas = ScenarioCanvasSpec.defaults();
        int[] transformations = PathValuationKernel.transformationSteps(position, path, steps, 1,
                elapsed, iv, canvas, .04);

        var horizon = PathValuationKernel.legPoint(position, position.legs().getFirst(), path,
                steps, steps, 1, elapsed, iv, canvas, .04, transformations[0]);

        assertThat(transformations[0]).isGreaterThan(steps);
        assertThat(horizon.state()).isEqualTo("LIVE_MODELED");
        assertThat(horizon.optionPrice()).isGreaterThan(0);
        // LegPoint reports contract delta in share-equivalents (100 multiplier), not raw 0..1 delta.
        assertThat(horizon.deltaShares()).isBetween(40.0, 70.0);
    }

    @Test void symbolScopeSharesTheProcessBudgetAndRefusesPathologicalWork() {
        LocalDate anchor = LocalDate.of(2026, 7, 1);
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                756, 4, 0, .25, 0, 0, 0, 6, null, 93, 20);
        double[][] deliberatelyOversizedPathCount = new double[2_000][1];
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("MU", "observed", AnalysisContext.OBSERVED),
                100, spec, deliberatelyOversizedPathCount, null, PathGenerator.MODEL_VERSION, anchor);
        var shares = new PathPosition(anchor,
                List.of(Leg.stockShares(LegAction.BUY, 100, new BigDecimal("100"))));
        List<ScenarioCanvasValuator.PositionInput> positions = java.util.stream.IntStream.range(0, 32)
                .mapToObj(index -> new ScenarioCanvasValuator.PositionInput("p" + index,
                        "Position " + index, "REAL", "TRACKED_STRUCTURE", shares,
                        1, 1_000_000L, false))
                .toList();

        assertThatThrownBy(() -> new ScenarioCanvasValuator().value(ensemble, IvSpec.flat(.30),
                ScenarioCanvasSpec.defaults(), .04, positions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Canvas comparison is too large")
                .hasMessageContaining("leg-steps");
    }

    @Test void templateReceiptDetectsDrift() {
        var unsigned = new ScenarioCanvasSpec.TemplateReceipt(
                ScenarioCanvasSpec.TemplateKind.HISTORICAL_REPLAY, "owned CSV", "OBSERVED",
                LocalDate.of(2026, 7, 1), LocalDate.of(2025, 1, 2), LocalDate.of(2025, 2, 2),
                21, true, true, "observed underlying; modeled options", "no look-ahead", "");
        var signed = unsigned.signed();
        assertThat(signed.validFingerprint()).isTrue();
        assertThat(new ScenarioCanvasSpec.TemplateReceipt(signed.kind(), signed.source(), signed.provenance(),
                signed.inputAsOf(), signed.windowFrom(), signed.windowTo(), signed.observations() + 1,
                signed.observed(), signed.noHindsight(), signed.legDayProvenance(), signed.note(),
                signed.fingerprint()).validFingerprint()).isFalse();
    }

    @Test void earningsTemplateUsesCanonicalSecFilingWindowAnalogsNotOrdinaryGaps() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T15:30:00Z"), ZoneOffset.UTC);
        FilingAnalogProvider provider = new FilingAnalogProvider(LocalDate.of(2026, 7, 10));
        MarketDataService market = new MarketDataService(List.of(provider), List.of(provider), List.of());
        EventService events = new EventService(market, clock);
        ScenarioCanvasTemplateService templates = new ScenarioCanvasTemplateService(market, events, clock);
        ScenarioSpec spec = ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 30, .30, 92, 40);

        var seeded = templates.apply("AAPL", "observed", AnalysisContext.OBSERVED, 100, .40,
                spec, ScenarioCanvasSpec.defaults(),
                new ScenarioCanvasTemplateService.Request(
                        ScenarioCanvasSpec.TemplateKind.EARNINGS_GAP_UP, null, null, null, null));

        assertThat(events.quarterlyReportDates("AAPL")).containsExactly(
                LocalDate.of(2026, 4, 30), LocalDate.of(2026, 1, 29), LocalDate.of(2025, 10, 30));
        assertThat(seeded.spec().waypoints()).singleElement().satisfies(pin ->
                assertThat(pin.priceRatio()).isCloseTo(1.07,
                        org.assertj.core.data.Offset.offset(.0001)));
        assertThat(seeded.canvas().template().observed()).isTrue();
        assertThat(seeded.canvas().template().source()).contains("SEC EDGAR filing dates");
        assertThat(seeded.canvas().template().observations()).isEqualTo(3);
        assertThat(seeded.canvas().template().note()).contains("filing-window analogs")
                .contains("proxies").doesNotContain("ordinary trading-day gaps");
    }

    @Test void shorterHistoricalReplayNamesWhereObservedClosesEndAndModelResumes() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T15:30:00Z"), ZoneOffset.UTC);
        FilingAnalogProvider provider = new FilingAnalogProvider(LocalDate.of(2026, 7, 10));
        MarketDataService market = new MarketDataService(List.of(provider), List.of(provider), List.of());
        var templates = new ScenarioCanvasTemplateService(market, new EventService(market, clock), clock);

        var seeded = templates.apply("AAPL", "observed", AnalysisContext.OBSERVED, 100, .35,
                ScenarioSpec.preset(ScenarioSpec.Shape.CHOP, 10, .30, 23, 40),
                ScenarioCanvasSpec.defaults(), new ScenarioCanvasTemplateService.Request(
                        ScenarioCanvasSpec.TemplateKind.HISTORICAL_REPLAY, null, null,
                        "2026-06-01", "2026-06-05"));

        assertThat(seeded.canvas().template().note())
                .contains("later canvas sessions resume the stored conditional path model")
                .contains("not historical observations");
    }

    private static final class FilingAnalogProvider implements MarketDataProvider, NewsFilingsProvider {
        private final LocalDate anchor;
        private final List<LocalDate> filings = List.of(LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 1, 29), LocalDate.of(2025, 10, 30));

        FilingAnalogProvider(LocalDate anchor) { this.anchor = anchor; }
        @Override public String name() { return "observed-test"; }
        @Override public Set<Domain> domains() { return Set.of(Domain.CANDLES); }
        @Override public List<SymbolMatch> lookup(String query) { return List.of(); }
        @Override public Optional<Quote> quote(String symbol) { return Optional.empty(); }
        @Override public List<LocalDate> expirations(String symbol) { return List.of(); }
        @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) { return Optional.empty(); }
        @Override public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
            java.util.ArrayList<Candle> out = new java.util.ArrayList<>();
            double priorClose = 100;
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                if (!MarketHours.isTradingDay(date)) continue;
                double gap = date.equals(LocalDate.of(2025, 10, 28)) ? .04
                        : date.equals(LocalDate.of(2026, 1, 27)) ? .07
                        : date.equals(LocalDate.of(2026, 4, 28)) ? .10 : .001;
                BigDecimal open = BigDecimal.valueOf(priorClose * (1 + gap));
                BigDecimal close = open.multiply(BigDecimal.valueOf(1.0002));
                out.add(new Candle(date, open, close.max(open), open.min(close), close, 1_000_000, false));
                priorClose = close.doubleValue();
            }
            return out;
        }
        @Override public List<NewsItem> news(String symbol) {
            return filings.stream().map(date -> new NewsItem("AAPL", "10-Q quarterly report", "SEC EDGAR",
                    "https://example.invalid/" + date,
                    date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())).toList();
        }
    }
}
