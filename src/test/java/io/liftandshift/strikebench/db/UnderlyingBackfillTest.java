package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.support.ObservedFixtureProvider;
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

    private UnderlyingBackfill backfiller(boolean observed) {
        db = TestDb.fresh();
        FixtureProvider fixture = new FixtureProvider(clock);
        MarketDataProvider provider = observed ? new ObservedFixtureProvider(clock) : fixture;
        MarketDataService market = new MarketDataService(
                List.of(provider), List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        return new UnderlyingBackfill(market, db, clock);
    }

    private long rows(String symbol) {
        return db.query("SELECT count(*) c FROM underlying_bar WHERE symbol=?", r -> r.lng("c"), symbol).getFirst();
    }

    @Test
    void demoBackfillIsRefusedInsteadOfEnteringObservedStorage() {
        UnderlyingBackfill bf = backfiller(false);
        var res = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"));
        assertThat(res.rows()).isZero();
        assertThat(res.observed()).isFalse();
        assertThat(res.source()).isEqualTo("fixture");
        assertThat(rows("AAPL")).isZero();
        assertThat(res.note()).contains("refused");
    }

    @Test
    void backfillIsIdempotent() {
        UnderlyingBackfill bf = backfiller(true);
        var a = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"));
        assertThat(a.observed()).isTrue();
        assertThat(a.rows()).isGreaterThan(0);
        long after1 = rows("AAPL");
        var b = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"));
        long after2 = rows("AAPL");
        assertThat(after2).isEqualTo(after1); // upsert on (symbol, d, source): no duplicate rows
        assertThat(b.rows()).isEqualTo(a.rows());
        assertThat(db.query("SELECT count(*) c FROM underlying_bar WHERE observed=1", r -> r.lng("c")).getFirst())
                .isEqualTo(after2);
    }
}
