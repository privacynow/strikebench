package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
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

/** Underlying-history backfill into underlying_bar — evidence-honest + idempotent. */
class UnderlyingBackfillTest {

    private Db db;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T14:00:00Z"), ZoneOffset.UTC);

    @AfterEach void closeDb() { if (db != null) db.close(); }

    private UnderlyingBackfill backfiller() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        FixtureProvider fixture = new FixtureProvider(clock);
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(fixture), List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        return new UnderlyingBackfill(market, db, clock);
    }

    private long rows(String symbol) {
        return db.query("SELECT count(*) c FROM underlying_bar WHERE symbol=?", r -> r.lng("c"), symbol).getFirst();
    }

    @Test
    void backfillsDailyBarsAndLabelsFixtureNotObserved() {
        UnderlyingBackfill bf = backfiller();
        var res = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"));
        assertThat(res.rows()).isGreaterThan(0);
        assertThat(res.observed()).isFalse();       // fixture history is never passed off as real
        assertThat(res.source()).isEqualTo("fixture");
        assertThat(rows("AAPL")).isEqualTo(res.rows());
        // The stored rows are labeled observed=0 (demo).
        long observedRows = db.query(
                "SELECT count(*) c FROM underlying_bar WHERE symbol='AAPL' AND observed=1",
                r -> r.lng("c")).getFirst();
        assertThat(observedRows).isZero();
    }

    @Test
    void backfillIsIdempotent() {
        UnderlyingBackfill bf = backfiller();
        var a = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"));
        long after1 = rows("AAPL");
        var b = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"));
        long after2 = rows("AAPL");
        assertThat(after2).isEqualTo(after1); // upsert on (symbol, d, source): no duplicate rows
        assertThat(b.rows()).isEqualTo(a.rows());
    }
}
