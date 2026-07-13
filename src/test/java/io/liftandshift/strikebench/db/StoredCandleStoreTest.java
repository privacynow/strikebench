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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                LocalDate.parse("2026-04-02"), DatasetService.OBSERVED).orElseThrow().series();
        assertThat(series.candles()).allSatisfy(c -> {
            assertThat(c.close()).isEqualByComparingTo("200");
            assertThat(c.adjusted()).isTrue();
        });
        assertThat(series.source()).isEqualTo("stored:official-source");
        assertThat(series.priceBasis()).isEqualTo("ADJUSTED");
    }

    @Test
    void incompleteHighQualitySourceDoesNotGetStitchedIntoCompleteLowerSource() {
        db = TestDb.fresh();
        for (String d : List.of("2026-04-01", "2026-04-02", "2026-04-03", "2026-04-06", "2026-04-07")) {
            db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,quality_rank) VALUES ('AAPL',?,100,'complete',1,70)",
                    LocalDate.parse(d));
        }
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,quality_rank) VALUES ('AAPL','2026-04-07',999,'partial-premium',1,99)");
        var series = new StoredCandleStore(db).candles("AAPL", LocalDate.parse("2026-04-01"),
                LocalDate.parse("2026-04-07"), DatasetService.OBSERVED).orElseThrow().series();
        assertThat(series.source()).isEqualTo("stored:complete");
        assertThat(series.candles().getLast().close()).isEqualByComparingTo("100");
    }

    @Test
    void oneSourceWithMixedRawAndAdjustedRowsIsNotServedAsCoherentHistory() {
        db = TestDb.fresh();
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,adjusted) VALUES ('AAPL','2026-04-01',100,'mixed',1,0)");
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,adjusted) VALUES ('AAPL','2026-04-02',50,'mixed',1,1)");
        assertThat(new StoredCandleStore(db).candles("AAPL", LocalDate.parse("2026-04-01"),
                LocalDate.parse("2026-04-02"), DatasetService.OBSERVED)).isEmpty();
    }

    @Test
    void partialCoverageIsReturnedAsAnExplicitFallbackCandidate() {
        db = TestDb.fresh();
        // Two stray rows are useful only as an explicit partial fallback; they must not be marked
        // complete and thereby silence provider enrichment.
        db.exec("INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,1)",
                "AAPL", LocalDate.parse("2026-04-01"), new java.math.BigDecimal("250.00"), "yahoo");
        db.exec("INSERT INTO underlying_bar (symbol, d, close, source, observed) VALUES (?,?,?,?,1)",
                "AAPL", LocalDate.parse("2026-04-02"), new java.math.BigDecimal("252.00"), "yahoo");
        var store = new StoredCandleStore(db);
        var read = store.candles("AAPL", LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-04-30"), DatasetService.OBSERVED).orElseThrow();
        assertThat(read.series().candles()).hasSize(2);
        assertThat(read.coverage().complete()).isFalse();
        assertThat(read.coverage().availableFrom()).isEqualTo(LocalDate.parse("2026-04-01"));
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

    @Test
    void observedReadThroughSurvivesAServiceRestartWithoutAnotherProvider() {
        db = TestDb.fresh();
        var store = new StoredCandleStore(db);
        var observed = new io.liftandshift.strikebench.support.ObservedFixtureProvider(clock);
        MarketDataService first = new MarketDataService(List.<MarketDataProvider>of(observed),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), store);

        CandleSeries fetched = first.candleSeries("AAPL", from, to);
        assertThat(fetched.source()).isEqualTo("observed-test-feed");
        assertThat(fetched.candles()).hasSizeGreaterThan(20);

        MarketDataService restarted = new MarketDataService(List.<MarketDataProvider>of(),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), store);
        CandleSeries restored = restarted.candleSeries("AAPL", from, to);
        assertThat(restored.source()).isEqualTo("stored:observed-test-feed");
        assertThat(restored.candles()).hasSameSizeAs(fetched.candles());
        for (int i = 0; i < fetched.candles().size(); i++) {
            var expected = fetched.candles().get(i);
            var actual = restored.candles().get(i);
            assertThat(actual.date()).isEqualTo(expected.date());
            assertThat(actual.open()).isEqualByComparingTo(expected.open());
            assertThat(actual.high()).isEqualByComparingTo(expected.high());
            assertThat(actual.low()).isEqualByComparingTo(expected.low());
            assertThat(actual.close()).isEqualByComparingTo(expected.close());
            assertThat(actual.volume()).isEqualTo(expected.volume());
            assertThat(actual.adjusted()).isEqualTo(expected.adjusted());
        }
    }

    @Test
    void coherentPartialHistorySurvivesRestartWhenNoProviderIsAvailable() {
        db = TestDb.fresh();
        LocalDate d = LocalDate.parse("2026-04-01");
        int inserted = 0;
        while (!d.isAfter(LocalDate.parse("2026-04-30"))) {
            if (io.liftandshift.strikebench.market.MarketHours.isTradingDay(d)) {
                db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed) "
                        + "VALUES ('AAPL',?,100,'yahoo',1)", d);
                inserted++;
            }
            d = d.plusDays(1);
        }
        var store = new StoredCandleStore(db);
        MarketDataService restarted = new MarketDataService(List.<MarketDataProvider>of(),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), store);

        CandleSeries restored = restarted.candleSeries("AAPL", LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-04-30"));

        assertThat(restored.source()).isEqualTo("stored:yahoo");
        assertThat(restored.candles()).hasSize(inserted);
        assertThat(store.candles("AAPL", LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-04-30"), DatasetService.OBSERVED)
                .orElseThrow().coverage().complete()).isFalse();
    }

    @Test
    void partialStoreDoesNotSuppressProviderEnrichment() {
        db = TestDb.fresh();
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed) "
                + "VALUES ('AAPL','2026-04-01',250,'yahoo',1)");
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed) "
                + "VALUES ('AAPL','2026-04-02',252,'yahoo',1)");
        var store = new StoredCandleStore(db);
        var observed = new io.liftandshift.strikebench.support.ObservedFixtureProvider(clock);
        MarketDataService reader = new MarketDataService(List.<MarketDataProvider>of(observed),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), store);

        CandleSeries enriched = reader.candleSeries("AAPL", from, to);

        assertThat(enriched.source()).isEqualTo("observed-test-feed");
        assertThat(enriched.candles()).hasSizeGreaterThan(20);
        assertThat(store.candles("AAPL", from, to, DatasetService.OBSERVED)
                .orElseThrow().coverage().complete()).isTrue();
    }

    @Test
    void generatedReadThroughCanNeverEnterObservedStorage() {
        db = TestDb.fresh();
        var store = new StoredCandleStore(db);
        var fixture = new FixtureProvider(clock);
        MarketDataService demoBacked = new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of(), store);

        assertThat(demoBacked.candleSeries("AAPL", from, to).source()).isEqualTo("fixture");
        assertThat(db.query("SELECT count(*) n FROM underlying_bar", r -> r.lng("n"))).containsExactly(0L);
    }

    @Test
    void productionStoreRejectsGeneratedEvidenceEvenWhenCalledDirectly() {
        db = TestDb.fresh();
        var store = new StoredCandleStore(db);
        var fixture = new FixtureProvider(clock);
        MarketDataService generatedMarket = new MarketDataService(List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        CandleSeries generated = generatedMarket.candleSeries("AAPL", from, to);

        assertThatThrownBy(() -> store.persistObserved("AAPL", generated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only observed daily history");
        assertThat(db.query("SELECT count(*) n FROM underlying_bar", r -> r.lng("n"))).containsExactly(0L);
    }
}
