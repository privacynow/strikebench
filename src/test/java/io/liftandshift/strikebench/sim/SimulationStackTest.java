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
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** IV path, strategy-under-scenario distribution, dataset persistence + the active-dataset switch. */
class SimulationStackTest {

    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T14:00:00Z"), ZoneOffset.UTC);

    @AfterEach void closeDb() { if (db != null) db.close(); }

    private ScenarioSpec spec(ScenarioSpec.Shape shape, double drift, long seed) {
        return new ScenarioSpec(ScenarioSpec.PathModel.GBM, shape, 20, 1, drift, 0.25,
                0, 0, 0, 6, ScenarioSpec.Heston.fromVol(0.25), seed, 300);
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
        var legs = List.of(new ScenarioSimulator.SimLeg("BUY", "CALL", 100, 20, 1));
        var up = sim.run(100, legs, 1, spec(ScenarioSpec.Shape.GRIND_UP, 0.6, 7), IvSpec.flat(0.25), 0.04, null);
        var down = sim.run(100, legs, 1, spec(ScenarioSpec.Shape.SELLOFF_REBOUND, -0.6, 7), IvSpec.flat(0.25), 0.04, null);
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
        var straddle = List.of(new ScenarioSimulator.SimLeg("BUY", "CALL", 100, 45, 1),
                new ScenarioSimulator.SimLeg("BUY", "PUT", 100, 45, 1));
        var flatIv = sim.run(100, straddle, 1, spec(ScenarioSpec.Shape.CHOP, 0, 9), IvSpec.flat(0.40), 0.04, null);
        var crushed = sim.run(100, straddle, 1, spec(ScenarioSpec.Shape.CHOP, 0, 9),
                new IvSpec(0.40, 0, 0, 0.40, 2, -0.5, 0.03, 4.0), 0.04, null);
        // Same underlying paths (same seed) — the crush strictly lowers the long-vol P&L.
        assertThat(crushed.expectedPnlCents()).isLessThan(flatIv.expectedPnlCents());
    }

    @Test
    void seedReproducesTheExactDistribution() {
        var sim = new ScenarioSimulator();
        var legs = List.of(new ScenarioSimulator.SimLeg("BUY", "CALL", 100, 20, 1));
        var a = sim.run(100, legs, 1, spec(ScenarioSpec.Shape.CHOP, 0, 42), IvSpec.flat(0.3), 0.04, null);
        var b = sim.run(100, legs, 1, spec(ScenarioSpec.Shape.CHOP, 0, 42), IvSpec.flat(0.3), 0.04, null);
        assertThat(a.p50Cents()).isEqualTo(b.p50Cents());
        assertThat(a.expectedPnlCents()).isEqualTo(b.expectedPnlCents());
    }

    @Test
    void datasetRunPersistsCoexistsAndDrivesTheReadPathWhenSelected() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        FixtureProvider fixture = new FixtureProvider(clock);
        DatasetService datasets = new DatasetService(db, clock);
        MarketDataService market = new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), new StoredCandleStore(db, datasets));
        SimulationEngine engine = new SimulationEngine(market, datasets, db, clock);

        var run = engine.runAndPersist("AAPL", spec(ScenarioSpec.Shape.SELLOFF_REBOUND, 0, 3), null);
        assertThat(run.bars()).isGreaterThan(10);
        assertThat(datasets.list()).anySatisfy(d -> assertThat(d.id()).isEqualTo(run.datasetId()));
        // Observed rows are untouched: the synthetic bars live ONLY under the new dataset_id.
        long observedRows = db.query("SELECT count(*) c FROM underlying_bar WHERE dataset_id='observed'",
                r -> r.lng("c")).getFirst();
        assertThat(observedRows).isZero();

        // Default read path = observed → the fixture provider serves (no stored observed bars).
        LocalDate from = LocalDate.now(clock), to = from.plusDays(40);
        assertThat(market.candleSeries("AAPL", from, to).source()).isEqualTo("fixture");

        // Select the synthetic dataset → the read path serves it, labeled MODELED ('synthetic').
        datasets.setActive(run.datasetId());
        CandleSeries s = market.candleSeries("AAPL", from, to);
        assertThat(s.source()).isEqualTo("synthetic");
        assertThat(s.freshness()).isEqualTo(Freshness.MODELED); // scenario data never masquerades as real

        // Deleting the active dataset falls back to observed (and cascades its bars).
        datasets.delete(run.datasetId());
        assertThat(datasets.activeId()).isEqualTo(DatasetService.OBSERVED);
        long orphans = db.query("SELECT count(*) c FROM underlying_bar WHERE dataset_id=?",
                r -> r.lng("c"), run.datasetId()).getFirst();
        assertThat(orphans).isZero();
    }

    @Test
    void previewProducesFanBandsAndSamples() {
        db = TestDb.fresh();
        FixtureProvider fixture = new FixtureProvider(clock);
        DatasetService datasets = new DatasetService(db, clock);
        MarketDataService market = new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        SimulationEngine engine = new SimulationEngine(market, datasets, db, clock);
        var p = engine.preview("AAPL", spec(ScenarioSpec.Shape.CHOP, 0, 11));
        assertThat(p.bands()).hasSize(21); // day 0..20
        assertThat(p.samples()).isNotEmpty();
        assertThat(p.endP10()).isLessThanOrEqualTo(p.endP50());
        assertThat(p.endP50()).isLessThanOrEqualTo(p.endP90());
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
