package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.CandleSeries;
import io.liftandshift.strikebench.market.MarketDataEngine;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.SnapshotService;
import io.liftandshift.strikebench.market.UniverseService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataMaintenanceGateTest {

    private Db db;
    private ExecutorService pool;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T14:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void close() {
        if (pool != null) pool.shutdownNow();
        if (db != null) db.close();
    }

    @Test
    void resetAdmissionContainsReadThroughQuotesAndDirectSnapshotsUntilCompletion() throws Exception {
        db = TestDb.fresh();
        var maintenance = new MarketDataMaintenanceGate();
        var candles = new StoredCandleStore(db, maintenance);
        var quotes = new MarketSnapshotStore(db, maintenance);
        var cfg = new AppConfig(Map.of("ENGINE_ENABLED", "false"));
        Clock snapshotClock = Clock.systemUTC();
        MarketDataProvider provider = new ObservedFixtureProvider(snapshotClock, "observed-test-feed");
        var market = new MarketDataService(List.of(provider), List.of(), List.of());
        var universe = new UniverseService(db, cfg, snapshotClock);
        var snapshots = new SnapshotService(market, universe, db, snapshotClock, maintenance);
        pool = Executors.newFixedThreadPool(3);

        MarketDataMaintenanceGate.ResetLease reset = maintenance.pauseWrites(Duration.ofSeconds(1));
        CompletableFuture<Integer> candleWrite = CompletableFuture.supplyAsync(
                () -> candles.persistObserved("GATE", observedSeries()), pool);
        CompletableFuture<Void> quoteWrite = CompletableFuture.runAsync(
                () -> quotes.save(observedQuote()), pool);
        CompletableFuture<SnapshotService.SnapshotResult> snapshotWrite = CompletableFuture.supplyAsync(
                () -> snapshots.snapshot(List.of("AAPL")), pool);

        Thread.sleep(150);
        assertThat(candleWrite).isNotDone();
        assertThat(quoteWrite).isNotDone();
        assertThat(snapshotWrite).isNotDone();
        assertThat(count("SELECT count(*) c FROM underlying_bar")).isZero();
        assertThat(count("SELECT count(*) c FROM option_bar")).isZero();
        assertThat(count("SELECT count(*) c FROM market_snapshot")).isZero();

        reset.close();
        assertThat(candleWrite.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        quoteWrite.get(5, TimeUnit.SECONDS);
        assertThat(snapshotWrite.get(5, TimeUnit.SECONDS).optionRows()).isGreaterThan(0);
        assertThat(count("SELECT count(*) c FROM underlying_bar")).isGreaterThanOrEqualTo(3);
        assertThat(count("SELECT count(*) c FROM option_bar")).isGreaterThan(0);
        assertThat(count("SELECT count(*) c FROM market_snapshot")).isEqualTo(1);
    }

    private CandleSeries observedSeries() {
        return new CandleSeries(List.of(
                candle("2026-07-02", "100"), candle("2026-07-06", "101")),
                "yahoo", Freshness.EOD);
    }

    private static Candle candle(String date, String close) {
        BigDecimal value = new BigDecimal(close);
        return new Candle(LocalDate.parse(date), value, value, value, value, 1_000, true);
    }

    private MarketDataEngine.MarketSnapshot observedQuote() {
        return new MarketDataEngine.MarketSnapshot("GATE", "Gate quote", new BigDecimal("101"),
                new BigDecimal("100.90"), new BigDecimal("101.10"), new BigDecimal("100"),
                true, Freshness.DELAYED, "cboe", clock.millis(), clock.millis(), false, null);
    }

    private long count(String sql) {
        return db.query(sql, r -> r.lng("c")).getFirst();
    }
}
