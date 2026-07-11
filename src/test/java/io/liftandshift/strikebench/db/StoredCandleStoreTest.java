package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
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
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The P1 fix: persisted underlying_bar (backfills/snapshots/CSV) is on the candle READ path. */
class StoredCandleStoreTest {

    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T14:00:00Z"), ZoneOffset.UTC);
    private final LocalDate from = LocalDate.parse("2026-04-01"), to = LocalDate.parse("2026-06-30");

    @AfterEach void closeDb() { if (db != null) db.close(); }

    private MarketDataService fixtureService() {
        FixtureProvider fixture = new FixtureProvider(clock);
        return new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
    }

    @Test
    void fixtureBackfillNeverEntersObservedStore() {
        db = TestDb.fresh();
        var result = new UnderlyingBackfill(fixtureService(), db, clock).backfill("AAPL", from, to);
        assertThat(result.rows()).isZero();
        assertThat(result.observed()).isFalse();

        // A reader WITH the store but NO candle providers must still return the STORED bars.
        MarketDataService reader = new MarketDataService(List.<MarketDataProvider>of(),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), new StoredCandleStore(db));
        CandleSeries s = reader.candleSeries("AAPL", from, to);
        assertThat(s.candles()).isEmpty();
    }

    @Test
    void observedStoredBarsAreLabelledEod() {
        db = TestDb.fresh();
        // Insert a couple of OBSERVED rows directly (as a real vendor backfill would).
        db.exec("INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,1)",
                "AAPL", LocalDate.parse("2026-04-01"), new java.math.BigDecimal("250.00"), "yahoo");
        db.exec("INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,1)",
                "AAPL", LocalDate.parse("2026-04-02"), new java.math.BigDecimal("252.00"), "yahoo");
        MarketDataService reader = new MarketDataService(List.<MarketDataProvider>of(),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), new StoredCandleStore(db));
        // The store answers only for the range it actually COVERS.
        CandleSeries s = reader.candleSeries("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-02"));
        assertThat(s.candles()).hasSize(2);
        assertThat(s.freshness()).isEqualTo(Freshness.EOD); // observed real history
    }

    @Test
    void higherQualitySourceWinsPerDateAndAdjustmentBasisSurvives() {
        db = TestDb.fresh();
        for (String d : List.of("2026-04-01", "2026-04-02")) {
            db.exec("INSERT INTO underlying_bar(symbol,d,open,high,low,close,source,observed,adjusted,quality_rank) "
                            + "VALUES ('AAPL',?,90,110,80,100,'low-source',1,0,40)", LocalDate.parse(d));
            db.exec("INSERT INTO underlying_bar(symbol,d,open,high,low,close,source,observed,adjusted,quality_rank) "
                            + "VALUES ('AAPL',?,180,220,160,200,'official-source',1,1,90)", LocalDate.parse(d));
        }
        var series = new StoredCandleStore(db).candles("AAPL", LocalDate.parse("2026-04-01"),
                LocalDate.parse("2026-04-02"), DatasetService.OBSERVED).orElseThrow();
        assertThat(series.candles()).allSatisfy(c -> {
            assertThat(c.close()).isEqualByComparingTo("200");
            assertThat(c.adjusted()).isTrue();
        });
    }

    @Test
    void partialCoverageFallsThroughToProviders() {
        db = TestDb.fresh();
        // Two stray rows must NOT satisfy a month-long request (that silenced the provider chain
        // and let an incomplete store 'backfill' only itself forever).
        db.exec("INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,1)",
                "AAPL", LocalDate.parse("2026-04-01"), new java.math.BigDecimal("250.00"), "yahoo");
        db.exec("INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,1)",
                "AAPL", LocalDate.parse("2026-04-02"), new java.math.BigDecimal("252.00"), "yahoo");
        var store = new StoredCandleStore(db);
        assertThat(store.candles("AAPL", LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-30"), DatasetService.OBSERVED)).isEmpty();
    }

    @Test
    void observedStoreExcludesDemoRowsRatherThanMixingThem() {
        db = TestDb.fresh();
        // One real bar must never launder a fabricated neighbor into an EOD series.
        db.exec("INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,1)",
                "AAPL", LocalDate.parse("2026-04-01"), new java.math.BigDecimal("250.00"), "yahoo");
        db.exec("INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,0)",
                "AAPL", LocalDate.parse("2026-04-02"), new java.math.BigDecimal("252.00"), "fixture");
        var store = new StoredCandleStore(db);
        assertThat(store.candles("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-02"),
                DatasetService.OBSERVED)).isEmpty();
    }

    @Test
    void withoutAStoreTheProviderChainIsUsed() {
        MarketDataService reader = fixtureService(); // no store
        CandleSeries s = reader.candleSeries("AAPL", from, to);
        assertThat(s.source()).isEqualTo("fixture"); // provider chain, not stored
    }
}
