package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.EventService;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false", "STOOQ_ENABLED", "true"));
        MarketDataProvider provider = new ObservedFixtureProvider(clock, "stooq");
        MarketDataService market = new MarketDataService(
                List.of(provider), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        MarketDataEngine engine = new MarketDataEngine(market, universe, cfg, clock);
        SnapshotService snapshots = new SnapshotService(market, universe, db, clock);
        UnderlyingBackfill backfill = new UnderlyingBackfill(market, db, clock);
        AuditLog audit = new AuditLog(db, clock);
        AccountService accounts = new AccountService(db, cfg, audit, clock);
        DataJobService jobs = new DataJobService(db, clock, engine, snapshots, backfill, universe, cfg);
        DataResetService reset = new DataResetService(db, accounts, jobs, null);
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
    void historySyncJobRunsWritesBarsAndReportsDone() throws Exception {
        Ctx c = wire();
        var invalidations = new java.util.concurrent.atomic.AtomicInteger();
        c.jobs().setDataChangedHook(invalidations::incrementAndGet);
        var job = c.jobs().start("sync_underlying",
                Map.of("symbols", List.of("AAPL"), "from", "2026-04-01", "to", "2026-06-30",
                        "source", "stooq"), null);
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
    void marketResetClearsDerivedEventsButPreservesReviewedEvidence() {
        Ctx c = wire();
        var market = new MarketDataService(List.of(), List.of(), List.of());
        var events = new EventService(market, db, clock);
        events.earnings("ZZZZZZ"); // persists an honest UNAVAILABLE derived receipt
        events.importReviewed(new EventService.ReviewedEvent("AAPL",
                EventService.EvidenceStatus.CONFIRMED, LocalDate.of(2026, 7, 22),
                EventService.EventSession.AFTER_CLOSE, null, null,
                EventService.SourceKind.ISSUER_CONFIRMED, "Issuer IR", "https://issuer.example/events",
                null, "reviewed issuer evidence", "local", "issuer receipt"));

        c.reset().reset(DataResetService.Tier.MARKET_DATA);

        assertThat(db.query("SELECT source_kind FROM market_event_evidence ORDER BY source_kind",
                r -> r.str("source_kind"))).containsExactly("ISSUER_CONFIRMED");
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
        SettingsStore settings = new SettingsStore(db);
        settings.put("yahoo_cooldown_until", "9999999999999");
        settings.put("cboe_cooldown_until", "9999999999998");
        settings.put("ordinary.setting", "delete-me");
        c.backfill().backfill("AAPL", java.time.LocalDate.parse("2026-04-01"), java.time.LocalDate.parse("2026-06-30"));
        db.exec("INSERT INTO sim_session(id,name,config,status) VALUES (?,?,?::jsonb,?)",
                "world-reset-test", "Reset test", "{}", "FINISHED");

        var res = c.reset().reset(DataResetService.Tier.EVERYTHING);
        assertThat(res.reseededAccount()).isTrue();
        assertThat(underlyingRows("AAPL")).isZero();
        assertThat(db.query("SELECT count(*) c FROM sim_session", r -> r.lng("c")).getFirst()).isZero();
        assertThat(settings.get("yahoo_cooldown_until")).contains("9999999999999");
        assertThat(settings.get("cboe_cooldown_until")).contains("9999999999998");
        assertThat(settings.get("ordinary.setting")).isEmpty();
        // exactly one fresh funded account exists after a full reset
        assertThat(db.query("SELECT count(*) c FROM accounts", r -> r.lng("c")).getFirst()).isEqualTo(1L);
    }

    @Test
    void marketResetWaitsForInFlightProviderWriterBeforeDeletingRows() throws Exception {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false", "STOOQ_ENABLED", "true"));
        var delegate = new ObservedFixtureProvider(clock, "stooq");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        MarketDataProvider blocking = new MarketDataProvider() {
            @Override public String name() { return "stooq"; }
            @Override public Set<io.liftandshift.strikebench.market.Domain> domains() { return delegate.domains(); }
            @Override public List<io.liftandshift.strikebench.model.SymbolMatch> lookup(String query) {
                return delegate.lookup(query);
            }
            @Override public Optional<io.liftandshift.strikebench.model.Quote> quote(String symbol) {
                return delegate.quote(symbol);
            }
            @Override public List<java.time.LocalDate> expirations(String symbol) {
                return delegate.expirations(symbol);
            }
            @Override public Optional<io.liftandshift.strikebench.model.OptionChain> chain(
                    String symbol, java.time.LocalDate expiration) {
                return delegate.chain(symbol, expiration);
            }
            @Override public List<io.liftandshift.strikebench.model.Candle> candles(
                    String symbol, java.time.LocalDate from, java.time.LocalDate to) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test provider was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return delegate.candles(symbol, from, to);
            }
        };
        MarketDataService market = new MarketDataService(List.of(blocking), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        var jobs = new DataJobService(db, clock, new MarketDataEngine(market, universe, cfg, clock),
                new SnapshotService(market, universe, db, clock),
                new UnderlyingBackfill(market, db, clock), universe, cfg);
        var accounts = new AccountService(db, cfg, new AuditLog(db, clock), clock);
        var reset = new DataResetService(db, accounts, jobs, null);

        jobs.start("sync_underlying", Map.of("symbols", List.of("AAPL"),
                "from", "2026-04-01", "to", "2026-06-30", "source", "stooq"), null);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<DataResetService.ResetResult> pending = CompletableFuture.supplyAsync(
                () -> reset.reset(DataResetService.Tier.MARKET_DATA));
        Thread.sleep(100);
        assertThat(pending).isNotDone();

        release.countDown();
        assertThat(pending.get(5, TimeUnit.SECONDS).tier()).isEqualTo("MARKET_DATA");
        assertThat(underlyingRows("AAPL")).isZero();
        Thread.sleep(100);
        assertThat(underlyingRows("AAPL")).as("no cancelled writer can repopulate after reset").isZero();
        jobs.shutdown();
    }

    @Test
    void everythingResetRecreatesTheOwnerAuthorizedSystemSchedule() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of(
                "ENGINE_ENABLED", "false",
                "YAHOO_ENABLED", "true",
                "YAHOO_AUTOMATION_PERMISSION_CONFIRMED", "true",
                "YAHOO_HISTORY_SYNC_ENABLED", "true"));
        MarketDataProvider provider = new ObservedFixtureProvider(clock, "yahoo");
        MarketDataService market = new MarketDataService(List.of(provider), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        var jobs = new DataJobService(db, clock, new MarketDataEngine(market, universe, cfg, clock),
                new SnapshotService(market, universe, db, clock),
                new UnderlyingBackfill(market, db, clock), universe, cfg);
        var state = new DataSyncState(db, clock);
        var scheduler = new DataSyncScheduler(cfg, clock, state, jobs, universe);
        scheduler.configureDefaultYahooSchedule();
        var accounts = new AccountService(db, cfg, new AuditLog(db, clock), clock);

        new DataResetService(db, accounts, jobs, scheduler).reset(DataResetService.Tier.EVERYTHING);

        var restored = state.schedule(io.liftandshift.strikebench.util.OwnerScope.SYSTEM);
        assertThat(restored.enabled()).isTrue();
        assertThat(restored.source()).isEqualTo("yahoo");
        assertThat(restored.symbols()).contains("AMD", "NVDA", "SPY", "SMH").hasSizeGreaterThan(80);
        jobs.shutdown();
    }

    @Test
    void committedResetReturnsADegradedHealthReceiptWhenSchedulerRemountFails() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false"));
        var accounts = new AccountService(db, cfg, new AuditLog(db, clock), clock);
        var scheduler = new DataResetService.SchedulerControl() {
            @Override public boolean pauseForReset() { return true; }
            @Override public void resumeAfterReset(boolean wasRunning) {
                throw new IllegalStateException("test remount failure");
            }
        };
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed) "
                + "VALUES ('AAPL','2026-07-06',100,'yahoo',1)");

        var result = new DataResetService(db, accounts, null, scheduler,
                new MarketDataMaintenanceGate()).reset(DataResetService.Tier.MARKET_DATA);

        assertThat(underlyingRows("AAPL")).isZero();
        assertThat(result.maintenanceStatus()).isEqualTo("DEGRADED");
        assertThat(result.warnings()).singleElement()
                .asString().contains("could not be remounted");
    }
}
