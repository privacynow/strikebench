package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.SnapshotService;
import io.liftandshift.strikebench.market.Universes;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DataSyncSchedulerTest {
    private Db db;
    private DataJobService jobs;

    @AfterEach void close() { if (jobs != null) jobs.shutdown(); if (db != null) db.close(); }

    @Test
    void completedSessionChangesOnlyAfterCloseGrace() {
        Clock before = Clock.fixed(Instant.parse("2026-07-10T19:59:00Z"), ZoneOffset.UTC); // 15:59 ET
        Clock after = Clock.fixed(Instant.parse("2026-07-10T20:21:00Z"), ZoneOffset.UTC);  // 16:21 ET
        assertThat(DataSyncScheduler.latestCompletedSession(before).toString()).isEqualTo("2026-07-09");
        assertThat(DataSyncScheduler.latestCompletedSession(after).toString()).isEqualTo("2026-07-10");
    }

    @Test
    void oneScheduleQueuesAtMostOnceForTheCompletedSession() throws Exception {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T20:21:00Z"), ZoneOffset.UTC);
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false", "STOOQ_ENABLED", "true"));
        MarketDataProvider feed = new ObservedFixtureProvider(clock, "stooq");
        MarketDataService market = new MarketDataService(List.of(feed), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        MarketDataEngine engine = new MarketDataEngine(market, universe, cfg, clock);
        var state = new DataSyncState(db, clock);
        var catalog = new DataConnectorCatalog(cfg, new ProviderRequestBudget(db, clock));
        jobs = new DataJobService(db, clock, engine, new SnapshotService(market, universe, db, clock),
                new UnderlyingBackfill(market, db, clock), universe, cfg, catalog);
        state.saveSchedule(null, true, "stooq", List.of("AAPL"), 1);
        var scheduler = new DataSyncScheduler(cfg, clock, state, jobs);
        scheduler.tick();
        scheduler.tick();
        assertThat(db.query("SELECT count(*) c FROM data_job WHERE kind='sync_underlying'", r -> r.lng("c")).getFirst())
                .isEqualTo(1);
        assertThat(state.schedule(null).lastRunDate()).isNull(); // queued is not complete
        awaitJobs();
        scheduler.tick();
        assertThat(state.schedule(null).lastRunDate().toString()).isEqualTo("2026-07-10");
        assertThat(state.schedule(null).lastStatus()).isEqualTo("COMPLETE");
        assertThat(state.schedule(null).completedCoverageHash())
                .isEqualTo(state.schedule(null).coverageHash());
        scheduler.tick();
        assertThat(db.query("SELECT count(*) c FROM data_job WHERE kind='sync_underlying'", r -> r.lng("c")).getFirst())
                .isEqualTo(1);
    }

    @Test
    void partialCoverageDoesNotAdvanceAndRetryRequestsOnlyMissingRanges() throws Exception {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T20:21:00Z"), ZoneOffset.UTC);
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false", "YAHOO_COOLDOWN_MINUTES", "5"));
        AtomicBoolean qqqComplete = new AtomicBoolean();
        AtomicInteger aaplCalls = new AtomicInteger();
        AtomicInteger qqqCalls = new AtomicInteger();
        MarketDataProvider feed = selectiveYahoo(clock, qqqComplete, aaplCalls, qqqCalls);
        MarketDataService market = new MarketDataService(List.of(feed), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        MarketDataEngine engine = new MarketDataEngine(market, universe, cfg, clock);
        var state = new DataSyncState(db, clock);
        jobs = new DataJobService(db, clock, engine, new SnapshotService(market, universe, db, clock),
                new UnderlyingBackfill(market, db, clock), universe, cfg,
                new DataConnectorCatalog(cfg, new ProviderRequestBudget(db, clock)));
        state.saveSchedule(null, true, "yahoo", List.of("AAPL", "QQQ", "SPY", "TSLA"), 1);
        var scheduler = new DataSyncScheduler(cfg, clock, state, jobs);

        scheduler.tick();
        awaitJobs();
        String firstJob = state.schedule(null).lastJobId();
        assertThat(jobs.get(firstJob).items()).extracting(DataJobService.DataJobItem::status)
                .containsExactly("DONE", "PARTIAL", "SKIPPED", "FAILED");
        scheduler.tick();
        assertThat(state.schedule(null).lastRunDate()).isNull();
        assertThat(state.schedule(null).lastStatus()).isEqualTo("RETRY_WAIT_PARTIAL");
        scheduler.tick();
        assertThat(jobCount()).isEqualTo(1); // provider-friendly cooldown survives scheduler ticks

        qqqComplete.set(true);
        db.exec("UPDATE data_job SET updated_at=? WHERE id=?",
                OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofMinutes(6)), ZoneOffset.UTC), firstJob);
        scheduler.tick();
        assertThat(jobCount()).isEqualTo(2);
        awaitJobs();
        String retryJob = state.schedule(null).lastJobId();
        assertThat(jobs.get(retryJob).items()).extracting(DataJobService.DataJobItem::status)
                .containsExactly("DONE", "DONE", "DONE", "DONE");
        scheduler.tick();

        assertThat(state.schedule(null).covers(LocalDate.parse("2026-07-10"))).isTrue();
        assertThat(aaplCalls).hasValue(1); // already-complete storage avoided a second provider call
        assertThat(qqqCalls).hasValue(2);
    }

    @Test
    void sameDayCoverageExpansionInvalidatesPriorCompletionImmediately() throws Exception {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T20:21:00Z"), ZoneOffset.UTC);
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false"));
        MarketDataProvider feed = new ObservedFixtureProvider(clock, "yahoo");
        MarketDataService market = new MarketDataService(List.of(feed), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        MarketDataEngine engine = new MarketDataEngine(market, universe, cfg, clock);
        var state = new DataSyncState(db, clock);
        jobs = new DataJobService(db, clock, engine, new SnapshotService(market, universe, db, clock),
                new UnderlyingBackfill(market, db, clock), universe, cfg,
                new DataConnectorCatalog(cfg, new ProviderRequestBudget(db, clock)));
        state.saveSchedule(null, true, "yahoo", List.of("AAPL"), 1);
        var scheduler = new DataSyncScheduler(cfg, clock, state, jobs);

        scheduler.tick();
        awaitJobs();
        scheduler.tick();
        var original = state.schedule(null);
        assertThat(original.covers(LocalDate.parse("2026-07-10"))).isTrue();

        var expanded = state.saveSchedule(null, true, "yahoo", List.of("AAPL", "QQQ"), 2);
        assertThat(expanded.coverageHash()).isNotEqualTo(original.coverageHash());
        assertThat(expanded.completedCoverageHash()).isEqualTo(original.completedCoverageHash());
        assertThat(expanded.covers(LocalDate.parse("2026-07-10"))).isFalse();
        assertThat(expanded.lastStatus()).isEqualTo("CONFIG_CHANGED");

        scheduler.tick(); // old successful job has a stale hash, so no retry cooldown applies
        assertThat(jobCount()).isEqualTo(2);
        awaitJobs();
        scheduler.tick();
        var completed = state.schedule(null);
        assertThat(completed.covers(LocalDate.parse("2026-07-10"))).isTrue();
        assertThat(completed.completedCoverageHash()).isEqualTo(completed.coverageHash());
    }

    @Test
    void marketDataResetPreservesScheduleButInvalidatesCompletionAndQueuesRebuild() throws Exception {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T20:21:00Z"), ZoneOffset.UTC);
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false"));
        MarketDataProvider feed = new ObservedFixtureProvider(clock, "yahoo");
        MarketDataService market = new MarketDataService(List.of(feed), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        MarketDataEngine engine = new MarketDataEngine(market, universe, cfg, clock);
        var state = new DataSyncState(db, clock);
        jobs = new DataJobService(db, clock, engine, new SnapshotService(market, universe, db, clock),
                new UnderlyingBackfill(market, db, clock), universe, cfg,
                new DataConnectorCatalog(cfg, new ProviderRequestBudget(db, clock)));
        state.saveSchedule(null, true, "yahoo", List.of("AAPL", "QQQ"), 2);
        var scheduler = new DataSyncScheduler(cfg, clock, state, jobs);

        scheduler.tick();
        awaitJobs();
        scheduler.tick();
        var completed = state.schedule(null);
        assertThat(completed.covers(LocalDate.parse("2026-07-10"))).isTrue();
        assertThat(jobCount()).isEqualTo(1);

        var accounts = new io.liftandshift.strikebench.paper.AccountService(db, cfg,
                new io.liftandshift.strikebench.paper.AuditLog(db, clock), clock);
        new DataResetService(db, accounts, jobs, scheduler).reset(DataResetService.Tier.MARKET_DATA);

        var invalidated = state.schedule(null);
        assertThat(invalidated.enabled()).isTrue();
        assertThat(invalidated.source()).isEqualTo("yahoo");
        assertThat(invalidated.symbols()).containsExactly("AAPL", "QQQ");
        assertThat(invalidated.years()).isEqualTo(2);
        assertThat(invalidated.coverageHash()).isEqualTo(completed.coverageHash());
        assertThat(invalidated.lastRunDate()).isNull();
        assertThat(invalidated.lastJobId()).isNull();
        assertThat(invalidated.completedCoverageHash()).isNull();
        assertThat(invalidated.lastStatus()).isEqualTo("MARKET_DATA_RESET");
        assertThat(jobCount()).isZero();

        scheduler.tick();
        assertThat(jobCount()).isEqualTo(1);
        assertThat(state.schedule(null).lastStatus()).isEqualTo("QUEUED");
    }

    @Test
    void ownerAuthorizedYahooMaintainsASeparateCanonicalUniverseScheduleUntilRevoked() {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T20:21:00Z"), ZoneOffset.UTC);
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false"));
        MarketDataProvider feed = new ObservedFixtureProvider(clock);
        MarketDataService market = new MarketDataService(List.of(feed), List.of(), List.of());
        UniverseService universe = new UniverseService(db, cfg, clock);
        MarketDataEngine engine = new MarketDataEngine(market, universe, cfg, clock);
        var state = new DataSyncState(db, clock);
        jobs = new DataJobService(db, clock, engine, new SnapshotService(market, universe, db, clock),
                new UnderlyingBackfill(market, db, clock), universe, cfg,
                new DataConnectorCatalog(cfg, new ProviderRequestBudget(db, clock)));

        var scheduler = new DataSyncScheduler(cfg, clock, state, jobs, universe);
        scheduler.configureDefaultYahooSchedule();
        scheduler.configureDefaultYahooSchedule(); // boot/restart reconciliation is an idempotent upsert

        var system = state.schedule(io.liftandshift.strikebench.util.OwnerScope.SYSTEM);
        assertThat(system.enabled()).isTrue();
        assertThat(system.source()).isEqualTo("yahoo");
        assertThat(system.years()).isEqualTo(2);
        assertThat(system.symbols()).contains("SPY", "AMD", "SMH", "TLT", "MU", "STX", "WDC", "SNDK")
                .hasSizeGreaterThan(80);
        assertThat(db.query("SELECT count(*) c FROM data_sync_schedule WHERE user_id='system'",
                r -> r.lng("c")).getFirst()).isEqualTo(1);
        assertThat(state.schedule(null).enabled()).isFalse(); // never overwrites the user's schedule

        AppConfig revoked = new AppConfig(Map.of("ENGINE_ENABLED", "false", "YAHOO_ENABLED", "false"));
        new DataSyncScheduler(revoked, clock, state, jobs,
                new UniverseService(db, revoked, clock)).configureDefaultYahooSchedule();
        assertThat(state.schedule(io.liftandshift.strikebench.util.OwnerScope.SYSTEM).enabled()).isFalse();
    }

    @Test
    void systemYahooScheduleKeepsEveryCanonicalSymbolAlongsideMaximumCustomUniverse() {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T20:21:00Z"), ZoneOffset.UTC);
        AppConfig cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false"));
        UniverseService universe = new UniverseService(db, cfg, clock);
        List<String> custom = IntStream.range(0, UniverseService.MAX_CUSTOM)
                .mapToObj(i -> "ZX" + (100 + i))
                .toList();
        universe.selectCustom(custom);

        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        Universes.SECTORS.values().stream()
                .filter(sector -> !"DEMO".equals(sector.key()))
                .forEach(sector -> canonical.addAll(sector.symbols()));

        var state = new DataSyncState(db, clock);
        new DataSyncScheduler(cfg, clock, state, null, universe).configureDefaultYahooSchedule();

        var system = state.schedule(io.liftandshift.strikebench.util.OwnerScope.SYSTEM);
        assertThat(system.symbols())
                .containsAll(canonical)
                .containsAll(custom)
                .hasSize(canonical.size() + custom.size());
        assertThat(system.symbols()).hasSizeLessThanOrEqualTo(DataSyncState.MAX_SCHEDULE_SYMBOLS);
    }

    private long jobCount() {
        return db.query("SELECT count(*) c FROM data_job WHERE kind='sync_underlying'", r -> r.lng("c")).getFirst();
    }

    private void awaitJobs() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (jobs.hasActive("sync_underlying") && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(jobs.hasActive("sync_underlying")).isFalse();
    }

    private static MarketDataProvider selectiveYahoo(Clock clock, AtomicBoolean qqqComplete,
                                                       AtomicInteger aaplCalls, AtomicInteger qqqCalls) {
        ObservedFixtureProvider delegate = new ObservedFixtureProvider(clock, "yahoo");
        return new MarketDataProvider() {
            @Override public String name() { return "yahoo"; }
            @Override public Set<io.liftandshift.strikebench.market.Domain> domains() { return delegate.domains(); }
            @Override public List<SymbolMatch> lookup(String query) { return delegate.lookup(query); }
            @Override public Optional<Quote> quote(String symbol) { return delegate.quote(symbol); }
            @Override public List<LocalDate> expirations(String symbol) { return delegate.expirations(symbol); }
            @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
                return delegate.chain(symbol, expiration);
            }
            @Override public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
                List<Candle> all = delegate.candles(symbol, from, to);
                if ("AAPL".equals(symbol)) aaplCalls.incrementAndGet();
                if ("QQQ".equals(symbol)) {
                    qqqCalls.incrementAndGet();
                    return qqqComplete.get() || all.isEmpty() ? all : List.of(all.getLast());
                }
                if (!qqqComplete.get() && "SPY".equals(symbol)) return List.of();
                if (!qqqComplete.get() && "TSLA".equals(symbol)) {
                    throw new IllegalStateException("test provider interruption");
                }
                return all;
            }
        };
    }
}
