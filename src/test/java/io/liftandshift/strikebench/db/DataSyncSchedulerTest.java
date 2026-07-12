package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.SnapshotService;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
        MarketDataProvider feed = new ObservedFixtureProvider(clock);
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
        assertThat(state.schedule(null).lastRunDate().toString()).isEqualTo("2026-07-10");
        long deadline = System.currentTimeMillis() + 5_000;
        while (jobs.hasActive("sync_underlying") && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }
}
