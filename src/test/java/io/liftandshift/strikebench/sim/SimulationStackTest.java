package io.liftandshift.strikebench.sim;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.DatasetService;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.db.StoredCandleStore;
import io.liftandshift.strikebench.db.StoredHistoricalOptionsProvider;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** IV path, strategy-under-scenario distribution, dataset persistence + the active-dataset switch. */
class SimulationStackTest {

    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T14:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate PATH_DATE = LocalDate.parse("2026-07-06");

    @AfterEach void closeDb() { if (db != null) db.close(); }

    private ScenarioSpec spec(ScenarioSpec.Shape shape, double drift, long seed) {
        return new ScenarioSpec(ScenarioSpec.PathModel.GBM, shape, 20, 1, drift, 0.25,
                0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.25), seed, 300);
    }

    private ScenarioSimulator.SimResult run(ScenarioSimulator simulator, double spot,
                                              PathPosition position, int qty,
                                              ScenarioSpec raw, IvSpec iv, double rate,
                                              double[] historicalReturns) {
        ScenarioSpec spec = raw.sane();
        double[][] paths = new PathGenerator().generate(spec, spot, historicalReturns);
        return simulator.runOnPaths(paths, position, qty, spec, iv, rate, null, null, 0);
    }

    private static PathPosition position(Leg... legs) {
        return new PathPosition(PATH_DATE, List.of(legs));
    }

    private static Leg option(LegAction action, OptionType type, double strike, int expiryDay) {
        return Leg.option(action, type, BigDecimal.valueOf(strike),
                io.liftandshift.strikebench.market.MarketHours.tradingDateAfter(PATH_DATE, expiryDay),
                1, BigDecimal.ZERO);
    }

    @Test
    void ivPathCrushesAtTheEventAndStaysBounded() {
        IvSpec iv = new IvSpec(0.40, 0, 0, 0.40, 5, -0.5, 0.03, 4.0);
        double[] path = iv.path(20, 1.0 / 252, 1);
        assertThat(path[0]).isEqualTo(0.40);
        assertThat(path[5]).isCloseTo(0.40, org.assertj.core.data.Offset.offset(0.02)); // pre-event
        assertThat(path[6]).isLessThan(0.25);  // crushed after the event day's close
        for (double v : path) assertThat(v).isBetween(0.03, 4.0);
    }

    @Test
    void longCallWinsMoreInAGrindUpThanASelloff() {
        var sim = new ScenarioSimulator();
        var legs = position(option(LegAction.BUY, OptionType.CALL, 100, 20));
        var up = run(sim, 100, legs, 1, spec(ScenarioSpec.Shape.GRIND_UP, 0.6, 7), IvSpec.flat(0.25), 0.04, null);
        var down = run(sim, 100, legs, 1, spec(ScenarioSpec.Shape.SELLOFF_REBOUND, -0.6, 7), IvSpec.flat(0.25), 0.04, null);
        assertThat(up.winRatePct()).isGreaterThan(down.winRatePct());
        assertThat(up.expectedPnlCents()).isGreaterThan(down.expectedPnlCents());
        // the report is a real distribution: ordered percentiles, bands, histogram, example path
        assertThat(up.p5Cents()).isLessThanOrEqualTo(up.p50Cents());
        assertThat(up.p50Cents()).isLessThanOrEqualTo(up.p95Cents());
        assertThat(up.bands()).isNotEmpty();
        assertThat(up.distribution()).isNotEmpty();
        assertThat(up.examplePath()).isNotEmpty();
        assertThat(up.notes()).anyMatch(n -> n.contains("MODELED"));
    }

    @Test
    void ivCrushHurtsALongStraddleEvenWhenPriceMoves() {
        var sim = new ScenarioSimulator();
        // Legs expire AFTER the 20-day horizon (day 45), so the exit is a BSM value at the then-current
        // IV — which is exactly where a crush bites. (At expiry, value is intrinsic and IV is moot.)
        var straddle = position(option(LegAction.BUY, OptionType.CALL, 100, 45),
                option(LegAction.BUY, OptionType.PUT, 100, 45));
        var flatIv = run(sim, 100, straddle, 1, spec(ScenarioSpec.Shape.CHOP, 0, 9), IvSpec.flat(0.40), 0.04, null);
        var crushed = run(sim, 100, straddle, 1, spec(ScenarioSpec.Shape.CHOP, 0, 9),
                new IvSpec(0.40, 0, 0, 0.40, 2, -0.5, 0.03, 4.0), 0.04, null);
        // Same underlying paths (same seed) — the crush strictly lowers the long-vol P&L.
        assertThat(crushed.expectedPnlCents()).isLessThan(flatIv.expectedPnlCents());
    }

    @Test
    void expiredLegsSettleOnceAndStayCash() {
        // A leg expiring INSIDE the horizon settles at its expiration-day price and stays that
        // cash amount — the P&L fan must be FLAT after expiry (it used to keep re-valuing the
        // dead option against the post-expiration stock price).
        var sim = new ScenarioSimulator();
        var legs = position(option(LegAction.BUY, OptionType.CALL, 100, 5));
        var spec = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP, 20, 1, 0, 0.4,
                0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.4), 77, 1); // ONE path -> bands ARE that path
        var r = run(sim, 100, legs, 1, spec, IvSpec.flat(0.4), 0.04, null);
        long settled = r.bands().get(5).p50Cents();
        for (int d = 6; d <= 20; d++) {
            assertThat(r.bands().get(d).p50Cents()).as("day " + d + " stays at the settled value").isEqualTo(settled);
        }
    }

    @Test
    void zeroDteOptionKeepsTimeValueUntilTheFirstSimulatedClose() {
        var sim = new ScenarioSimulator();
        var sameDay = position(option(LegAction.BUY, OptionType.CALL, 100, 0));
        assertThat(sameDay.expiryDay(sameDay.legs().getFirst())).isZero();
        var oneSession = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                1, 4, 0, 0.25, 0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.25), 88, 80);

        var result = run(sim, 100, sameDay, 1, oneSession, IvSpec.flat(0.25), 0.04, null);

        assertThat(result.entryCostCents()).isGreaterThan(0); // ATM intrinsic is zero; this is time value
        assertThat(result.horizonDays()).isEqualTo(1);
        assertThat(result.bands()).hasSize(2); // entry and first simulated closing bell
    }

    @Test
    void seedReproducesTheExactDistribution() {
        var sim = new ScenarioSimulator();
        var legs = position(option(LegAction.BUY, OptionType.CALL, 100, 20));
        var a = run(sim, 100, legs, 1, spec(ScenarioSpec.Shape.CHOP, 0, 42), IvSpec.flat(0.3), 0.04, null);
        var b = run(sim, 100, legs, 1, spec(ScenarioSpec.Shape.CHOP, 0, 42), IvSpec.flat(0.3), 0.04, null);
        assertThat(a.p50Cents()).isEqualTo(b.p50Cents());
        assertThat(a.expectedPnlCents()).isEqualTo(b.expectedPnlCents());
    }

    @Test
    void configuredRoundTripFeesShiftTheWholeOutcomeDistribution() {
        var simulator = new ScenarioSimulator();
        var legs = position(option(LegAction.BUY, OptionType.CALL, 100, 20));
        ScenarioSpec sane = spec(ScenarioSpec.Shape.CHOP, 0, 55).sane();
        double[][] paths = new PathGenerator().generate(sane, 100, null);

        var gross = simulator.runOnPaths(paths, legs, 1, sane, IvSpec.flat(0.3), 0.04,
                null, null, 0);
        var net = simulator.runOnPaths(paths, legs, 1, sane, IvSpec.flat(0.3), 0.04,
                null, null, 500);

        assertThat(net.expectedPnlCents()).isEqualTo(gross.expectedPnlCents() - 500);
        assertThat(net.p5Cents()).isEqualTo(gross.p5Cents() - 500);
        assertThat(net.p50Cents()).isEqualTo(gross.p50Cents() - 500);
        assertThat(net.p95Cents()).isEqualTo(gross.p95Cents() - 500);
        assertThat(net.bands().getLast().p50Cents()).isEqualTo(gross.bands().getLast().p50Cents() - 500);
        assertThat(net.winRatePct()).isLessThanOrEqualTo(gross.winRatePct());
        assertThat(net.notes()).anyMatch(n -> n.contains("$5.00") && n.contains("round-trip"));
    }

    @Test
    void exactEnsembleComparisonReusesTheStoredMatrixAndEachProposalQuantity() {
        var simulator = new ScenarioSimulator();
        ScenarioSpec sane = spec(ScenarioSpec.Shape.GRIND_UP, 0.15, 909).sane();
        double[][] paths = new PathGenerator().generate(sane, 100, null);
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "demo",
                        io.liftandshift.strikebench.db.AnalysisContext.OBSERVED),
                100, sane, paths, null, PathGenerator.MODEL_VERSION);
        var call = position(option(LegAction.BUY, OptionType.CALL, 100, 20));

        var compared = simulator.compare(ensemble, List.of(
                new ScenarioSimulator.CompareItem("one", call, null, "stored entry", 0, 1),
                new ScenarioSimulator.CompareItem("three", call, null, "stored entry", 0, 3)),
                9, IvSpec.flat(0.25), 0.04);

        assertThat(compared.ensemble()).isSameAs(ensemble);
        assertThat(compared.report().refused()).isEmpty();
        assertThat(compared.report().results()).extracting(ScenarioSimulator.CompareOutcome::key)
                .containsExactly("one", "three");
        var one = compared.report().results().get(0).result();
        var three = compared.report().results().get(1).result();
        assertThat(three.entryCostCents()).isCloseTo(one.entryCostCents() * 3,
                org.assertj.core.data.Offset.offset(1L));
        assertThat(three.expectedPnlCents()).isCloseTo(one.expectedPnlCents() * 3,
                org.assertj.core.data.Offset.offset(1L));
        assertThat(three.p5Cents()).isCloseTo(one.p5Cents() * 3,
                org.assertj.core.data.Offset.offset(1L));
    }

    @Test
    void datasetRunPersistsCoexistsAndDrivesTheReadPathWhenSelected() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        FixtureProvider fixture = new FixtureProvider(clock);
        DatasetService datasets = new DatasetService(db, clock);
        MarketDataService market = new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), new StoredCandleStore(db));
        var ensembles = new PathEnsembleService(market, clock);
        SimulationEngine engine = new SimulationEngine(market, datasets, db, clock, ensembles);

        var run = engine.runAndPersist("AAPL", spec(ScenarioSpec.Shape.SELLOFF_REBOUND, 0, 3), null,
                "observed", io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
        assertThat(run.bars()).isGreaterThan(10);
        assertThat(run.pathModelVersion()).isEqualTo(PathGenerator.MODEL_VERSION);
        DatasetService.DatasetRow saved = datasets.list(null).stream()
                .filter(d -> d.id().equals(run.datasetId())).findFirst().orElseThrow();
        assertThat(io.liftandshift.strikebench.util.Json.parse(saved.spec()).get("pathModelVersion").asText())
                .isEqualTo(PathGenerator.MODEL_VERSION);
        // Observed rows are untouched: the synthetic bars live ONLY under the new dataset_id.
        long observedRows = db.query("SELECT count(*) c FROM underlying_bar WHERE dataset_id='observed'",
                r -> r.lng("c")).getFirst();
        assertThat(observedRows).isZero();

        // Synthetic bars are the RECENT PAST ending today — the exact window production screens
        // request (Research history, HV, backtests), so an ACTIVE dataset genuinely drives them.
        LocalDate to = LocalDate.now(clock), from = to.minusDays(40);
        assertThat(market.candleSeries("AAPL", from, to).source()).isEqualTo("fixture"); // observed default

        // Select the synthetic dataset and read WITH the explicit analysis context — the store
        // has no ambient state; a caller without a context always reads observed.
        datasets.setActive(run.datasetId(), null);
        var actx = new io.liftandshift.strikebench.db.AnalysisContext(null, datasets.activeId(null));
        CandleSeries s = market.candleSeries("AAPL", from, to, actx);
        assertThat(s.source()).isEqualTo("synthetic");
        assertThat(s.freshness()).isEqualTo(Freshness.MODELED); // scenario data never masquerades as real
        // The SAME read WITHOUT a context (background machinery, another user) stays observed —
        // one user's scenario world can never become ambient.
        CandleSeries plain = market.candleSeries("AAPL", from, to);
        assertThat(plain.source()).isNotEqualTo("synthetic");

        // Deleting the active dataset falls back to observed (and cascades its bars).
        datasets.delete(run.datasetId(), null);
        assertThat(datasets.activeId(null)).isEqualTo(DatasetService.OBSERVED);
        long orphans = db.query("SELECT count(*) c FROM underlying_bar WHERE dataset_id=?",
                r -> r.lng("c"), run.datasetId()).getFirst();
        assertThat(orphans).isZero();
    }

    @Test
    void datasetsAreIsolatedBetweenUsers() {
        db = TestDb.fresh();
        DatasetService datasets = new DatasetService(db, clock);
        String mine = datasets.create("mine", "SYNTHETIC_PURE", "AAPL", 1, Map.of(), "user-a");
        String theirs = datasets.create("theirs", "SYNTHETIC_PURE", "AAPL", 2, Map.of(), "user-b");
        // Listing shows my runs + observed — never someone else's.
        assertThat(datasets.list("user-a")).extracting(DatasetService.DatasetRow::id)
                .contains(mine, "observed").doesNotContain(theirs);
        // Activating or deleting another user's dataset reads as "no such dataset".
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> datasets.setActive(theirs, "user-a"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> datasets.delete(theirs, "user-a"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        // Owner semantics still allow the owner everything.
        datasets.setActive(mine, "user-a");
        assertThat(datasets.activeId("user-a")).isEqualTo(mine);
        // …and the selection is PERSONAL: user-b's read path is untouched by user-a's switch.
        assertThat(datasets.activeId("user-b")).isEqualTo(DatasetService.OBSERVED);
        // Retention pruning is per owner AND spares the active run: user-a's churn must never
        // evict user-b's dataset, and never the one the app is actively analyzing.
        for (int i = 0; i < 30; i++) datasets.create("run" + i, "SYNTHETIC_PURE", "AAPL", 100 + i, Map.of(), "user-a");
        assertThat(datasets.ownedBy(theirs, "user-b")).isTrue();
        assertThat(datasets.ownedBy(mine, "user-a")).isTrue(); // survived its owner's churn (it is active)
        datasets.delete(mine, "user-a");
        assertThat(datasets.activeId("user-a")).isEqualTo(DatasetService.OBSERVED); // fell back, no ghost
    }

    @Test
    void previewProducesFanBandsAndSamples() {
        db = TestDb.fresh();
        FixtureProvider fixture = new FixtureProvider(clock);
        DatasetService datasets = new DatasetService(db, clock);
        MarketDataService market = new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        var ensembles = new PathEnsembleService(market, clock);
        SimulationEngine engine = new SimulationEngine(market, datasets, db, clock, ensembles);
        var p = engine.preview("AAPL", spec(ScenarioSpec.Shape.CHOP, 0, 11), "observed",
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED,
                List.of(new SimulationEngine.DecisionLevel("target", 270),
                        new SimulationEngine.DecisionLevel("floor", 240)),
                new SimulationEngine.MarketVolInput(0.30, LocalDate.parse("2026-08-07"), 32), 0.04);
        assertThat(p.bands()).hasSize(21); // day 0..20
        assertThat(p.stepBands()).hasSize(21); // every stored step, not a terminal triangle
        assertThat(p.samples()).hasSize(48);
        assertThat(p.samples()).allSatisfy(path -> assertThat(path).hasSize(21));
        assertThat(p.stepBands()).allSatisfy(band -> {
            assertThat(band.p10()).isLessThanOrEqualTo(band.p25());
            assertThat(band.p25()).isLessThanOrEqualTo(band.p50());
            assertThat(band.p50()).isLessThanOrEqualTo(band.p75());
            assertThat(band.p75()).isLessThanOrEqualTo(band.p90());
        });
        assertThat(p.endP10()).isLessThanOrEqualTo(p.endP50());
        assertThat(p.endP50()).isLessThanOrEqualTo(p.endP90());
        assertThat(p.decisionMap().terminal().p5()).isLessThanOrEqualTo(p.decisionMap().terminal().p50());
        assertThat(p.decisionMap().terminal().p50()).isLessThanOrEqualTo(p.decisionMap().terminal().p95());
        assertThat(p.decisionMap().levels()).hasSize(2);
        assertThat(p.decisionMap().levels()).allSatisfy(level -> {
            assertThat(level.touchProbability()).isBetween(0.0, 1.0);
            assertThat(level.endBeyondProbability()).isBetween(0.0, 1.0);
            assertThat(level.touchCiLow()).isLessThanOrEqualTo(level.touchProbability());
            assertThat(level.touchCiHigh()).isGreaterThanOrEqualTo(level.touchProbability());
        });
        assertThat(p.receipt().fingerprint()).hasSize(24);
        assertThat(p.receipt().anchorSpot()).isEqualTo(p.spot());
        assertThat(p.receipt().worldId()).isEqualTo("observed");
        assertThat(p.receipt().anchorExecutable()).isFalse();
        assertThat(p.receipt().anchorLimitation()).contains("analysis only").contains("executable quote");
        assertThat(p.marketImplied().p16()).isLessThan(p.marketImplied().p50());
        assertThat(p.marketImplied().p50()).isLessThan(p.marketImplied().p84());
        assertThat(p.marketImplied().basis()).contains("Risk-neutral").contains("not a forecast");
        assertThat(p.marketImplied().expiration()).isEqualTo("2026-08-07");
        assertThat(p.marketImplied().horizonSessions()).isEqualTo(20);
    }

    @Test
    void maximumResolutionStoredPreviewKeepsFullStatisticsButBoundsSerializedSeries() {
        var max = new ScenarioSpec(ScenarioSpec.PathModel.GBM, ScenarioSpec.Shape.CHOP,
                756, 96, 0, .25, 0, 0, 0, 6,
                ScenarioSpec.Heston.fromVol(.25), 501L, 5_000).sane();
        int steps = max.totalSteps();
        double[][] paths = new double[max.paths()][steps + 1];
        for (int step = 0; step <= steps; step++) {
            for (int path = 0; path < paths.length; path++) {
                paths[path][step] = 100 + (path - paths.length / 2.0) * step / 1_000_000.0;
            }
        }
        var ensemble = new PathEnsembleService.Ensemble(PathEnsembleService.Basis.PARAMETRIC,
                new PathEnsembleService.Scope("AAPL", "observed",
                        io.liftandshift.strikebench.db.AnalysisContext.OBSERVED),
                100, max, paths, null, PathGenerator.MODEL_VERSION, PATH_DATE);
        var stored = new io.liftandshift.strikebench.plan.PlanOutcomeService.StoredEnsemble(
                "pen_max", "fingerprint", "PARAMETRIC", 1, null, "CURRENT", ensemble,
                IvSpec.flat(.25), null, .04, 23_400.0 / 96, "test", "MODELED",
                clock.instant().toString());

        var preview = new SimulationEngine(null, null, null, clock, null)
                .previewFromStored(stored, List.of(), null, .04);

        assertThat((long) max.paths() * (steps + 1)).isLessThanOrEqualTo(ScenarioSpec.MAX_TOTAL_POINTS);
        assertThat(preview.paths()).isEqualTo(max.paths());
        assertThat(preview.bands()).hasSize(757); // full daily statistical surface
        assertThat(preview.stepBands()).hasSize(PathEnsembleService.MAX_DISPLAY_POINTS_PER_SERIES);
        assertThat(preview.stepBands().getFirst().step()).isZero();
        assertThat(preview.stepBands().getLast().step()).isEqualTo(steps);
        assertThat(preview.samples()).hasSize(max.paths()).allSatisfy(path ->
                assertThat(path).hasSize(PathEnsembleService.MAX_DISPLAY_POINTS_PER_SERIES));
        assertThat(preview.samples()).allSatisfy(path -> assertThat(path.getFirst()).isEqualTo(100));
        assertThat(io.liftandshift.strikebench.util.Json.canonical(
                io.liftandshift.strikebench.util.Json.MAPPER.valueToTree(preview)).length())
                .isLessThan(2_000_000);
        assertThat(preview.notes()).anyMatch(note -> note.contains("deterministic checkpoints")
                && note.contains("Full stored paths"));
    }

    @Test
    void pathAnchorsAndBootstrapHistoryAreExplicitPerLaneWithNoSharedWorldState() {
        FixtureProvider demo = new FixtureProvider(clock);
        MarketDataProvider observed = new MarketDataProvider() {
            public String name() { return "observed-lane-test"; }
            public Set<io.liftandshift.strikebench.market.Domain> domains() {
                return Set.of(io.liftandshift.strikebench.market.Domain.QUOTES,
                        io.liftandshift.strikebench.market.Domain.CANDLES);
            }
            public List<SymbolMatch> lookup(String q) { return List.of(); }
            public Optional<Quote> quote(String s) {
                var px = new java.math.BigDecimal("100.00");
                return Optional.of(new Quote(s, s, px, px, px, px, px, px, 1L, true,
                        clock.millis(), name(), Freshness.DELAYED));
            }
            public List<LocalDate> expirations(String s) { return List.of(); }
            public Optional<OptionChain> chain(String s, LocalDate e) { return Optional.empty(); }
            public List<io.liftandshift.strikebench.model.Candle> candles(String s, LocalDate from, LocalDate to) {
                List<io.liftandshift.strikebench.model.Candle> out = new java.util.ArrayList<>();
                LocalDate d = from;
                double px = 50;
                while (!d.isAfter(to)) {
                    px *= 1.001;
                    var p = java.math.BigDecimal.valueOf(px);
                    out.add(new io.liftandshift.strikebench.model.Candle(d, p, p, p, p, 1, false));
                    d = d.plusDays(1);
                }
                return out;
            }
        };
        MarketDataService market = new MarketDataService(List.of(observed), List.of(), List.of());
        market.setDemoSources(demo, demo, demo);
        var paths = new PathEnsembleService(market, clock);
        var observedScope = new PathEnsembleService.Scope("AAPL", "observed",
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);
        var demoScope = new PathEnsembleService.Scope("AAPL", "demo",
                io.liftandshift.strikebench.db.AnalysisContext.OBSERVED);

        assertThat(paths.anchorSpot(observedScope)).isEqualTo(100.0);
        assertThat(paths.anchorSpot(demoScope)).isEqualTo(255.30);
        assertThat(paths.historicalLogReturns(observedScope)).isNotNull();
        assertThat(paths.historicalLogReturns(demoScope)).isNotNull();
        assertThat(paths.historicalLogReturns(observedScope)[0])
                .isNotEqualTo(paths.historicalLogReturns(demoScope)[0]);
        assertThat(java.util.Arrays.stream(SimulationEngine.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("anchorWorld");
    }

    @Test
    void storedOptionHistoryServesTheSingleBacktesterChain() {
        db = TestDb.fresh();
        LocalDate asOf = LocalDate.parse("2026-06-01"), exp = LocalDate.parse("2026-06-19");
        db.exec("INSERT INTO option_bar (symbol, asof, expiration, strike, opt_type, bid, ask, iv, underlying, "
              + "source, bid_ask_observed, iv_source) VALUES (?,?,?,?,?,?,?,?,?,?,1,'vendor')",
                "AAPL", asOf, exp, new java.math.BigDecimal("250"), "CALL",
                new java.math.BigDecimal("5.10"), new java.math.BigDecimal("5.30"), 0.31,
                new java.math.BigDecimal("251.20"), "csv");
        var provider = new StoredHistoricalOptionsProvider(db);
        assertThat(provider.historicalExpirations("AAPL", asOf)).containsExactly(exp);
        var chain = provider.historicalChain("AAPL", asOf, exp);
        assertThat(chain).isPresent();
        assertThat(chain.get().calls()).hasSize(1);
        assertThat(chain.get().freshness()).isEqualTo(Freshness.EOD); // observed bid/ask
        assertThat(chain.get().calls().getFirst().bid()).isEqualByComparingTo("5.10");
    }
}
