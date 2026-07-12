package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.SnapshotService;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Data Center backend: cancellable/idempotent jobs, coverage matrix, tiered reset. */
class DataCenterTest {

    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T14:00:00Z"), ZoneOffset.UTC);

    @AfterEach void closeDb() { if (db != null) db.close(); }

    private record Ctx(DataJobService jobs, DataResetService reset, DataCoverage coverage,
                       AccountService accounts, UnderlyingBackfill backfill) {}

    private Ctx wire() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false"));
        MarketDataProvider provider = new ObservedFixtureProvider(clock);
        MarketDataService market = new MarketDataService(
                List.of(provider), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        MarketDataEngine engine = new MarketDataEngine(market, universe, cfg, clock);
        SnapshotService snapshots = new SnapshotService(market, universe, db, clock);
        UnderlyingBackfill backfill = new UnderlyingBackfill(market, db, clock);
        AuditLog audit = new AuditLog(db, clock);
        AccountService accounts = new AccountService(db, cfg, audit, clock);
        DataJobService jobs = new DataJobService(db, clock, engine, snapshots, backfill, universe, cfg);
        DataResetService reset = new DataResetService(db, accounts);
        return new Ctx(jobs, reset, new DataCoverage(db), accounts, backfill);
    }

    private DataJobService.DataJob await(DataJobService jobs, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var v = jobs.get(id);
            if (v.job() != null && (v.job().status().equals("DONE") || v.job().status().equals("FAILED")
                    || v.job().status().equals("CANCELLED"))) return v.job();
            Thread.sleep(50);
        }
        return jobs.get(id).job();
    }

    private long underlyingRows(String symbol) {
        return db.query("SELECT count(*) c FROM underlying_bar WHERE symbol=?", r -> r.lng("c"), symbol).getFirst();
    }

    @Test
    void backfillJobRunsWritesBarsAndReportsDone() throws Exception {
        Ctx c = wire();
        var invalidations = new java.util.concurrent.atomic.AtomicInteger();
        c.jobs().setDataChangedHook(invalidations::incrementAndGet);
        var job = c.jobs().start("backfill_underlying",
                Map.of("symbols", List.of("AAPL"), "from", "2026-04-01", "to", "2026-06-30"), null);
        var done = await(c.jobs(), job.id());
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.rowsWritten()).isGreaterThan(0);
        assertThat(underlyingRows("AAPL")).isGreaterThan(0);
        var view = c.jobs().get(job.id());
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().getFirst().status()).isEqualTo("DONE");
        assertThat(invalidations.get()).isEqualTo(1);
    }

    @Test
    void unknownJobKindIsRejected() {
        Ctx c = wire();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> c.jobs().start("delete_everything", Map.of(), null));
    }

    @Test
    void aJobWhereEveryItemFailsIsReportedFAILEDNotDone() throws Exception {
        // Regression for the review finding: the FAILED branch was unreachable, so a fully-failed job
        // masqueraded as DONE. A CSV import of a nonexistent path fails its only item → status FAILED.
        Ctx c = wire();
        var job = c.jobs().start("import_options_csv",
                Map.of("path", "/nonexistent/strikebench-does-not-exist.csv", "source", "test"), null);
        var done = await(c.jobs(), job.id());
        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(c.jobs().get(job.id()).items().getFirst().status()).isEqualTo("FAILED");
    }

    @Test
    void coverageReportsWhatWeHold() throws Exception {
        Ctx c = wire();
        c.backfill().backfill("AAPL", java.time.LocalDate.parse("2026-04-01"), java.time.LocalDate.parse("2026-06-30"));
        var cov = c.coverage().bySymbol();
        assertThat(cov).anySatisfy(s -> {
            assertThat(s.symbol()).isEqualTo("AAPL");
            assertThat(s.underlyingBars()).isGreaterThan(0);
            assertThat(s.underlyingObserved()).isTrue();
        });
        assertThat(c.coverage().summary().underlyingSymbols()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void coverageCountsDistinctTradingDatesNotDuplicateProviderRows() {
        Ctx c = wire();
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,bar_kind) VALUES ('AAPL','2026-06-01',100,'source-a',1,'OHLCV')");
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,bar_kind) VALUES ('AAPL','2026-06-01',101,'source-b',1,'CLOSE_ONLY')");
        var row = c.coverage().bySymbol().stream().filter(x -> x.symbol().equals("AAPL")).findFirst().orElseThrow();
        assertThat(row.underlyingBars()).isEqualTo(1);
        assertThat(row.underlyingSources()).contains("source-a", "source-b");
        assertThat(row.underlyingBasis()).contains("OHLCV", "CLOSE_ONLY");
        assertThat(c.coverage().summary().underlyingBars()).isEqualTo(1);
    }

    @Test
    void resetMarketDataClearsBarsButKeepsAccount() {
        Ctx c = wire();
        c.accounts().getOrCreateDefault();
        c.backfill().backfill("AAPL", java.time.LocalDate.parse("2026-04-01"), java.time.LocalDate.parse("2026-06-30"));
        assertThat(underlyingRows("AAPL")).isGreaterThan(0);

        var res = c.reset().reset(DataResetService.Tier.MARKET_DATA);
        assertThat(res.areasCleared()).contains("Market history and snapshots", "Generated datasets");
        assertThat(res.areasCleared()).noneMatch(a -> a.contains("_") || a.contains(" WHERE "));
        assertThat(underlyingRows("AAPL")).isZero();
        // the account is untouched by a market-data reset
        assertThat(db.query("SELECT count(*) c FROM accounts", r -> r.lng("c")).getFirst()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void resetPaperAlsoClearsSimulationPracticeSessions() {
        Ctx c = wire();
        c.accounts().getOrCreateDefault();
        db.exec("INSERT INTO sim_session(id,name,config,status) VALUES (?,?,?::jsonb,?)",
                "world-paper-reset", "Practice world", "{}", "FINISHED");

        var res = c.reset().reset(DataResetService.Tier.PAPER);
        assertThat(res.areasCleared()).contains("Simulation practice sessions");
        assertThat(db.query("SELECT count(*) c FROM sim_session", r -> r.lng("c")).getFirst()).isZero();
        assertThat(db.query("SELECT count(*) c FROM accounts", r -> r.lng("c")).getFirst()).isEqualTo(1L);
    }

    @Test
    void resetEverythingWipesAndReseedsAFundedAccount() {
        Ctx c = wire();
        c.accounts().getOrCreateDefault();
        c.backfill().backfill("AAPL", java.time.LocalDate.parse("2026-04-01"), java.time.LocalDate.parse("2026-06-30"));
        db.exec("INSERT INTO sim_session(id,name,config,status) VALUES (?,?,?::jsonb,?)",
                "world-reset-test", "Reset test", "{}", "FINISHED");

        var res = c.reset().reset(DataResetService.Tier.EVERYTHING);
        assertThat(res.reseededAccount()).isTrue();
        assertThat(underlyingRows("AAPL")).isZero();
        assertThat(db.query("SELECT count(*) c FROM sim_session", r -> r.lng("c")).getFirst()).isZero();
        // exactly one fresh funded account exists after a full reset
        assertThat(db.query("SELECT count(*) c FROM accounts", r -> r.lng("c")).getFirst()).isEqualTo(1L);
    }
}
