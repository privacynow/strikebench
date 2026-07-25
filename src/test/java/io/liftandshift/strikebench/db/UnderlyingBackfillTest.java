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

    /** A provider whose whole requested range predates coverage: it throws typed range-absence. */
    private static final class PreHistoryProvider implements MarketDataProvider {
        private final LocalDate coverageStart;
        PreHistoryProvider(LocalDate coverageStart) { this.coverageStart = coverageStart; }
        @Override public String name() { return "yahoo"; }
        @Override public java.util.Set<io.liftandshift.strikebench.market.Domain> domains() {
            return java.util.Set.of(io.liftandshift.strikebench.market.Domain.CANDLES);
        }
        @Override public List<io.liftandshift.strikebench.model.SymbolMatch> lookup(String q) { return List.of(); }
        @Override public java.util.Optional<io.liftandshift.strikebench.model.Quote> quote(String s) {
            return java.util.Optional.empty();
        }
        @Override public List<LocalDate> expirations(String s) { return List.of(); }
        @Override public java.util.Optional<io.liftandshift.strikebench.model.OptionChain> chain(String s, LocalDate e) {
            return java.util.Optional.empty();
        }
        @Override public List<io.liftandshift.strikebench.model.Candle> candles(String s, LocalDate f, LocalDate t) {
            throw new io.liftandshift.strikebench.market.providers.Http.RangeUnavailableException(
                    "http://test", "Data doesn't exist for startDate", coverageStart);
        }
    }

    /** A provider whose candle read is denied by the local request budget (no network call). */
    private static final class BudgetExhaustedProvider implements MarketDataProvider {
        @Override public String name() { return "yahoo"; }
        @Override public java.util.Set<io.liftandshift.strikebench.market.Domain> domains() {
            return java.util.Set.of(io.liftandshift.strikebench.market.Domain.CANDLES);
        }
        @Override public List<io.liftandshift.strikebench.model.SymbolMatch> lookup(String q) { return List.of(); }
        @Override public java.util.Optional<io.liftandshift.strikebench.model.Quote> quote(String s) {
            return java.util.Optional.empty();
        }
        @Override public List<LocalDate> expirations(String s) { return List.of(); }
        @Override public java.util.Optional<io.liftandshift.strikebench.model.OptionChain> chain(String s, LocalDate e) {
            return java.util.Optional.empty();
        }
        @Override public List<io.liftandshift.strikebench.model.Candle> candles(String s, LocalDate f, LocalDate t) {
            throw new ProviderRequestBudget.Exhausted("yahoo", 160,
                    java.time.Instant.parse("2026-07-07T00:00:00Z"));
        }
    }

    @Test
    void budgetExhaustedBackfillDefersToResetInsteadOfFailing() {
        db = TestDb.fresh();
        MarketDataService market = new MarketDataService(
                List.of(new BudgetExhaustedProvider()),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        UnderlyingBackfill bf = new UnderlyingBackfill(market, db, clock);

        var res = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"),
                "yahoo", null, null);
        assertThat(res.rows()).isZero();
        assertThat(res.note()).contains("allowance is exhausted");

        // The cursor is DEFERRED with next_allowed_at at the reset — not FAILED (failure_count stays 0).
        var row = db.query("SELECT status,failure_count,"
                        + "to_char(next_allowed_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI') na "
                        + "FROM data_sync_cursor WHERE symbol='AAPL'",
                r -> r.str("status") + "|" + r.intv("failure_count") + "|" + r.str("na"));
        assertThat(row).isNotEmpty();
        assertThat(row.getFirst()).isEqualTo("DEFERRED|0|2026-07-07T00:00");
    }

    @Test
    void aLiveBudgetDeferralIsHonoredSoTheNextTickSkipsTheExhaustedAllowance() {
        db = TestDb.fresh();
        MarketDataService market = new MarketDataService(
                List.of(new BudgetExhaustedProvider()),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        UnderlyingBackfill bf = new UnderlyingBackfill(market, db, clock);
        LocalDate from = LocalDate.parse("2026-04-01"), to = LocalDate.parse("2026-06-30");

        // First tick: allowance exhausted -> DEFERRED cursor with next_allowed_at at the reset.
        assertThat(bf.backfill("AAPL", from, to, "yahoo", null, null).note()).contains("allowance is exhausted");

        // Next tick BEFORE the reset: the deferral is honored -> NO new provider request; the gate
        // short-circuits with the resume note instead of re-spending the exhausted allowance.
        var second = bf.backfill("AAPL", from, to, "yahoo", null, null);
        assertThat(second.rows()).isZero();
        assertThat(second.note()).contains("still exhausted").contains("resuming after");

        // After the reset instant passes, the same backfill is allowed to try the provider again.
        Clock afterReset = Clock.fixed(Instant.parse("2026-07-07T00:01:00Z"), ZoneOffset.UTC);
        var resumed = new UnderlyingBackfill(new MarketDataService(
                List.of(new BudgetExhaustedProvider()), List.<NewsFilingsProvider>of(), List.<RatesProvider>of()),
                db, afterReset);
        assertThat(resumed.backfill("AAPL", from, to, "yahoo", null, null).note())
                .contains("allowance is exhausted"); // re-attempted (not skipped), and re-defers
    }

    @Test
    void preHistoryBackfillPersistsTheBoundaryAndClampsFuturePlansInsteadOfFailing() {
        db = TestDb.fresh();
        MarketDataService market = new MarketDataService(
                List.of(new PreHistoryProvider(LocalDate.parse("2026-06-15"))),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        UnderlyingBackfill bf = new UnderlyingBackfill(market, db, clock);

        // The backfill returns a normal (non-failed) result — range-absence is not an outage.
        var res = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"), "yahoo", null, null);
        assertThat(res.rows()).isZero();

        // The learned boundary is persisted durably.
        assertThat(db.query("SELECT earliest_available::text ea FROM data_sync_cursor "
                        + "WHERE symbol='AAPL' AND earliest_available IS NOT NULL",
                r -> r.str("ea")).getFirst()).isEqualTo("2026-06-15");

        // A future plan whose whole span predates coverage never re-spends the allowance.
        MissingRangePlanner planner = new MissingRangePlanner(db);
        assertThat(planner.plan("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-14"), "yahoo")
                .complete()).isTrue();
    }

    @Test
    void anAutomaticPreHistoryBackfillPersistsTheBoundaryUnderTheReportingProviderNotAuto() {
        // Range-absence returns NO candles, so the reporting provider used to be lost and the learned
        // boundary was persisted under the generic "auto" request. That mislabels one provider's
        // coverage as every provider's, and the planner then clamps by a key no provider owns.
        db = TestDb.fresh();
        MarketDataService market = new MarketDataService(
                List.of(new PreHistoryProvider(LocalDate.parse("2026-06-15"))),
                List.<NewsFilingsProvider>of(), List.<RatesProvider>of());
        UnderlyingBackfill bf = new UnderlyingBackfill(market, db, clock);

        // Automatic acquisition (no explicit source) — the provider chain resolves it.
        var res = bf.backfill("AAPL", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"));
        assertThat(res.rows()).isZero();

        var persisted = db.query("SELECT source_key, earliest_available::text ea FROM data_sync_cursor "
                        + "WHERE symbol='AAPL' AND earliest_available IS NOT NULL",
                r -> r.str("source_key") + "|" + r.str("ea"));
        assertThat(persisted)
                .as("the learned boundary must name the provider that reported the absence")
                .containsExactly("yahoo|2026-06-15");
        assertThat(persisted).noneMatch(row -> row.startsWith("auto|"));

        // Provider-scoped in memory too: only the reporting provider carries the boundary.
        assertThat(market.preHistoryBoundary("yahoo", "AAPL")).contains(LocalDate.parse("2026-06-15"));
        assertThat(market.preHistoryBoundary("auto", "AAPL")).isEmpty();
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
        assertThat(after2).isEqualTo(after1); // no duplicate rows
        assertThat(b.rows()).isZero(); // durable coverage planner makes no second provider request
        assertThat(b.complete()).isTrue();
        assertThat(b.note()).contains("no provider request");
        assertThat(db.query("SELECT count(*) c FROM underlying_bar WHERE observed=1", r -> r.lng("c")).getFirst())
                .isEqualTo(after2);
    }
}
