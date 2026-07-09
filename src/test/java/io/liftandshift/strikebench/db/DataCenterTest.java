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
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true", "ENGINE_ENABLED", "false"));
        FixtureProvider fixture = new FixtureProvider(clock);
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(fixture), List.<NewsFilingsProvider>of(fixture), List.<RatesProvider>of(fixture));
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
        var job = c.jobs().start("backfill_underlying",
                Map.of("symbols", List.of("AAPL"), "from", "2026-04-01", "to", "2026-06-30"), null);
        var done = await(c.jobs(), job.id());
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.rowsWritten()).isGreaterThan(0);
        assertThat(underlyingRows("AAPL")).isGreaterThan(0);
        var view = c.jobs().get(job.id());
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().getFirst().status()).isEqualTo("DONE");
    }

    @Test
    void unknownJobKindIsRejected() {
        Ctx c = wire();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> c.jobs().start("delete_everything", Map.of(), null));
    }

    @Test
    void coverageReportsWhatWeHold() throws Exception {
        Ctx c = wire();
        c.backfill().backfill("AAPL", java.time.LocalDate.parse("2026-04-01"), java.time.LocalDate.parse("2026-06-30"));
        var cov = c.coverage().bySymbol();
        assertThat(cov).anySatisfy(s -> {
            assertThat(s.symbol()).isEqualTo("AAPL");
            assertThat(s.underlyingBars()).isGreaterThan(0);
            assertThat(s.underlyingObserved()).isFalse(); // fixture = demo, honestly labeled
        });
        assertThat(c.coverage().summary().underlyingSymbols()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void resetMarketDataClearsBarsButKeepsAccount() {
        Ctx c = wire();
        c.accounts().getOrCreateDefault();
        c.backfill().backfill("AAPL", java.time.LocalDate.parse("2026-04-01"), java.time.LocalDate.parse("2026-06-30"));
        assertThat(underlyingRows("AAPL")).isGreaterThan(0);

        var res = c.reset().reset(DataResetService.Tier.MARKET_DATA);
        assertThat(res.tablesCleared()).contains("underlying_bar", "option_bar");
        assertThat(underlyingRows("AAPL")).isZero();
        // the account is untouched by a market-data reset
        assertThat(db.query("SELECT count(*) c FROM accounts", r -> r.lng("c")).getFirst()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void resetEverythingWipesAndReseedsAFundedAccount() {
        Ctx c = wire();
        c.accounts().getOrCreateDefault();
        c.backfill().backfill("AAPL", java.time.LocalDate.parse("2026-04-01"), java.time.LocalDate.parse("2026-06-30"));

        var res = c.reset().reset(DataResetService.Tier.EVERYTHING);
        assertThat(res.reseededAccount()).isTrue();
        assertThat(underlyingRows("AAPL")).isZero();
        // exactly one fresh funded account exists after a full reset
        assertThat(db.query("SELECT count(*) c FROM accounts", r -> r.lng("c")).getFirst()).isEqualTo(1L);
    }
}
