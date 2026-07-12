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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The forward chain-snapshot recorder — writes option_bar/underlying_bar with honest evidence. */
class SnapshotServiceTest {

    private Db db;

    @AfterEach void closeDb() { if (db != null) db.close(); }

    private SnapshotService service(Clock clock) { return service(clock, true, false); }

    private SnapshotService service(Clock clock, boolean observed) { return service(clock, observed, false); }

    private SnapshotService service(Clock clock, boolean observed, boolean previousCloseOnly) {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of());
        FixtureProvider fixture = new FixtureProvider(clock);
        MarketDataProvider provider = observed ? new MarketDataProvider() {
            @Override public String name() { return "observed-test-feed"; }
            @Override public Set<Domain> domains() { return fixture.domains(); }
            @Override public List<io.liftandshift.strikebench.model.SymbolMatch> lookup(String q) { return fixture.lookup(q); }
            @Override public Optional<io.liftandshift.strikebench.model.Quote> quote(String s) {
                return fixture.quote(s).map(q -> new io.liftandshift.strikebench.model.Quote(
                        q.symbol(), q.description(), previousCloseOnly ? null : q.last(),
                        previousCloseOnly ? null : q.bid(), previousCloseOnly ? null : q.ask(),
                        q.prevClose(), q.dayHigh(),
                        q.dayLow(), q.volume(), q.optionable(), q.asOfEpochMs(), name(),
                        io.liftandshift.strikebench.model.Freshness.DELAYED));
            }
            @Override public List<LocalDate> expirations(String s) { return fixture.expirations(s); }
            @Override public Optional<io.liftandshift.strikebench.model.OptionChain> chain(String s, LocalDate e) {
                return fixture.chain(s, e).map(c -> new io.liftandshift.strikebench.model.OptionChain(
                        c.underlying(), c.expiration(), c.underlyingPrice(),
                        c.calls().stream().map(this::observed).toList(),
                        c.puts().stream().map(this::observed).toList(),
                        c.asOfEpochMs(), name(), io.liftandshift.strikebench.model.Freshness.DELAYED));
            }
            private io.liftandshift.strikebench.model.OptionQuote observed(
                    io.liftandshift.strikebench.model.OptionQuote q) {
                return new io.liftandshift.strikebench.model.OptionQuote(q.underlying(), q.occSymbol(), q.type(),
                        q.strike(), q.expiration(), q.bid(), q.ask(), q.last(), q.volume(), q.openInterest(),
                        q.iv(), q.delta(), q.gamma(), q.theta(), q.vega(), q.asOfEpochMs(), name(),
                        io.liftandshift.strikebench.model.Freshness.DELAYED);
            }
            @Override public List<io.liftandshift.strikebench.model.Candle> candles(String s, LocalDate f, LocalDate t) {
                return fixture.candles(s, f, t);
            }
        } : fixture;
        MarketDataService market = new MarketDataService(
                List.of(provider), List.of(), List.of());
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

        assertThat(count("SELECT count(*) c FROM option_bar WHERE bid_ask_observed=1")).isGreaterThan(0);
        assertThat(count("SELECT count(*) c FROM option_bar WHERE iv_source='vendor'")).isGreaterThan(0);

        // Underlying bar is attributable observed data.
        var rows = db.query(
                "SELECT close, observed FROM underlying_bar WHERE symbol='AAPL' AND source='snapshot'",
                row -> row.str("close") + "|" + row.bool("observed"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).endsWith("|true");

        // Both option types present (calls and puts recorded).
        assertThat(count("SELECT count(*) c FROM option_bar WHERE opt_type='CALL'")).isGreaterThan(0);
        assertThat(count("SELECT count(*) c FROM option_bar WHERE opt_type='PUT'")).isGreaterThan(0);
    }

    @Test void demoSnapshotsNeverEnterObservedTables() {
        SnapshotService snap = service(Clock.systemUTC(), false);

        var r = snap.snapshot(List.of("AAPL"));

        assertThat(r.underlyingRows()).isZero();
        assertThat(r.optionRows()).isZero();
        assertThat(count("SELECT count(*) c FROM underlying_bar")).isZero();
        assertThat(count("SELECT count(*) c FROM option_bar")).isZero();
    }

    @Test void previousCloseFallbackIsNeverWrittenAsTodaysObservedBar() {
        SnapshotService snap = service(Clock.systemUTC(), true, true);

        var r = snap.snapshot(List.of("AAPL"));

        assertThat(r.underlyingRows()).isZero();
        assertThat(count("SELECT count(*) c FROM underlying_bar")).isZero();
        assertThat(r.optionRows()).isGreaterThan(0); // only the missing underlying observation is withheld
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
