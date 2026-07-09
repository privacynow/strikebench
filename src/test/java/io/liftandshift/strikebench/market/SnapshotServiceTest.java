package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.market.ports.NewsFilingsProvider;
import io.liftandshift.strikebench.market.ports.RatesProvider;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The forward chain-snapshot recorder — writes option_bar/underlying_bar with honest evidence. */
class SnapshotServiceTest {

    private Db db;

    @AfterEach void closeDb() { if (db != null) db.close(); }

    private SnapshotService service(Clock clock) {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        FixtureProvider fixture = new FixtureProvider(clock);
        MarketDataService market = new MarketDataService(
                List.<MarketDataProvider>of(fixture),
                List.<NewsFilingsProvider>of(fixture),
                List.<RatesProvider>of(fixture));
        UniverseService universe = new UniverseService(db, cfg, clock);
        return new SnapshotService(market, universe, db, clock);
    }

    private long count(String sql) {
        return db.query(sql, r -> r.lng("c")).getFirst();
    }

    @Test void recordsChainsAndUnderlyingWithHonestEvidence() {
        SnapshotService snap = service(Clock.systemUTC());

        var r = snap.snapshot(List.of("AAPL"));

        assertThat(r.symbols()).isEqualTo(1);
        assertThat(r.optionRows()).isGreaterThan(0);
        assertThat(r.underlyingRows()).isEqualTo(1);
        assertThat(r.errors()).isEmpty();

        // option_bar rows are all tagged as our forward snapshots.
        assertThat(count("SELECT count(*) c FROM option_bar WHERE symbol='AAPL' AND source='snapshot'"))
                .isEqualTo(r.optionRows());

        // Honesty: fixture data can NEVER masquerade as observed market data.
        assertThat(count("SELECT count(*) c FROM option_bar WHERE bid_ask_observed=1")).isZero();
        assertThat(count("SELECT count(*) c FROM option_bar WHERE iv_source='model'")).isGreaterThan(0);
        assertThat(count("SELECT count(*) c FROM option_bar WHERE iv_source='vendor'")).isZero();

        // Underlying bar: today's close for AAPL, flagged not-observed (demo).
        var rows = db.query(
                "SELECT close, observed FROM underlying_bar WHERE symbol='AAPL' AND source='snapshot'",
                row -> row.str("close") + "|" + row.bool("observed"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).endsWith("|false");

        // Both option types present (calls and puts recorded).
        assertThat(count("SELECT count(*) c FROM option_bar WHERE opt_type='CALL'")).isGreaterThan(0);
        assertThat(count("SELECT count(*) c FROM option_bar WHERE opt_type='PUT'")).isGreaterThan(0);
    }

    @Test void isIdempotentPerDay() {
        SnapshotService snap = service(Clock.systemUTC());

        var first = snap.snapshot(List.of("AAPL"));
        long afterFirst = count("SELECT count(*) c FROM option_bar");
        assertThat(afterFirst).isEqualTo(first.optionRows());

        // Re-running the same day upserts in place — no duplicate rows.
        snap.snapshot(List.of("AAPL"));
        assertThat(count("SELECT count(*) c FROM option_bar")).isEqualTo(afterFirst);
        assertThat(count("SELECT count(*) c FROM underlying_bar")).isEqualTo(1);
    }

    @Test void oneBadSymbolNeverAbortsTheRest() {
        SnapshotService snap = service(Clock.systemUTC());

        // NOPE has no fixture data; AAPL does. The run records AAPL and reports NOPE as an error.
        var r = snap.snapshot(List.of("NOPE", "AAPL"));

        assertThat(r.symbols()).isEqualTo(2);
        assertThat(r.optionRows()).isGreaterThan(0);
        assertThat(r.errors()).anyMatch(e -> e.startsWith("NOPE"));
        assertThat(count("SELECT count(*) c FROM option_bar WHERE symbol='AAPL'")).isGreaterThan(0);
        assertThat(count("SELECT count(*) c FROM option_bar WHERE symbol='NOPE'")).isZero();
    }
}
