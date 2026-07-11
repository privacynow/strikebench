package io.liftandshift.strikebench.market.sim;

import io.liftandshift.strikebench.paper.AuditLog;
import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketDataMarks;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountService;
import io.liftandshift.strikebench.paper.PositionsService;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lane-level release gates: the SATURDAY reviewer session actually works end-to-end (no false
 * closed-market warnings, sim-clock DTE analytics), an accelerated world can settle at ITS
 * expiry, shares fill at WORLD prices, hostile users can't touch foreign sessions, and a JVM
 * restart resumes the exact world by replay.
 */
class SimLaneGateTest {

    // A SATURDAY, on purpose: the headline use case is weekend reviewer sessions.
    private static final Clock SATURDAY = Clock.fixed(Instant.parse("2026-07-11T18:00:00Z"),
            ZoneId.of("America/New_York"));

    private static Db db;
    private static AppConfig cfg;
    private static MarketDataService market;
    private static SimulationSessions sessions;
    private static AccountService accounts;
    private static TradeService trades;
    private static PositionsService positions;
    private static AuditLog audit;

    @BeforeAll
    static void up() {
        db = TestDb.fresh();
        cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        FixtureProvider fixture = new FixtureProvider(SATURDAY);
        market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        sessions = new SimulationSessions(db, new EventBus());
        market.setWorldResolver(id -> sessions.resolveForData(id));
        audit = new AuditLog(db, SATURDAY);
        accounts = new AccountService(db, cfg, audit, SATURDAY);
        MarketDataMarks marks = new MarketDataMarks(market, true);
        trades = new TradeService(db, cfg, marks, audit, SATURDAY);
        positions = new PositionsService(db, marks, audit, SATURDAY);
    }

    @AfterAll
    static void down() { db.close(); }

    private static SimulatedWorld createWorld(String userId) {
        return sessions.create(new SimulatedWorld.Config(null, "Weekend review",
                Map.of("ACME", 1.0), Map.of("ACME", 100.0), "CHOP", 0.30, 99L,
                "2026-07-13T09:30:00", 10), userId);
    }

    @Test
    void saturdaySimTradingWorksEndToEnd_noFalseClosedMarketWarnings() {
        SimulatedWorld w = createWorld("saturday-user");
        for (int i = 0; i < 20; i++) w.tick();
        Account sim = accounts.getOrCreateForWorld(w.worldId(), "Sim account");

        LocalDate exp = w.expirations().get(2);
        var chain = w.chain("ACME", exp).orElseThrow();
        double spot = chain.underlyingPrice().doubleValue();
        BigDecimal shortK = chain.puts().stream().map(q -> q.strike())
                .filter(k -> k.doubleValue() <= spot - 4).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal longK = shortK.subtract(new BigDecimal("2.5"));

        TradePreview p = trades.preview(new TradeService.OpenRequest(sim.id(), "ACME",
                "CREDIT_PUT_SPREAD", 1,
                List.of(Leg.option(LegAction.SELL, OptionType.PUT, shortK, exp, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.BUY, OptionType.PUT, longK, exp, 1, BigDecimal.ZERO)),
                "bullish", "week", "balanced"));
        assertThat(p.ok()).as("a weekend sim trade must preview clean: %s", p.blockReasons()).isTrue();
        // The lane's clock is the SIM clock: no 'market is closed / leftovers' warning on Saturday.
        assertThat(p.warnings()).noneMatch(x -> x.contains("Market is closed"));
        // Analytics DTE runs on the sim calendar, not the JVM's.
        @SuppressWarnings("unchecked")
        Map<String, Object> prob = (Map<String, Object>) p.analytics().get("probabilityMap");
        assertThat(String.valueOf(prob.get("timeBasis"))).contains("calendar days");

        var t = trades.create(new TradeService.OpenRequest(sim.id(), "ACME", "CREDIT_PUT_SPREAD", 1,
                List.of(Leg.option(LegAction.SELL, OptionType.PUT, shortK, exp, 1, BigDecimal.ZERO),
                        Leg.option(LegAction.BUY, OptionType.PUT, longK, exp, 1, BigDecimal.ZERO)),
                "bullish", "week", "balanced"));
        assertThat(t.status()).isEqualTo("ACTIVE");
    }

    @Test
    void acceleratedExpiryIsSettleableTheMomentTheSimCalendarPassesIt() {
        SimulatedWorld w = createWorld("expiry-user");
        Account sim = accounts.getOrCreateForWorld(w.worldId(), "Sim account");
        LocalDate exp = w.expirations().getFirst();
        var chain = w.chain("ACME", exp).orElseThrow();
        double spot = chain.underlyingPrice().doubleValue();
        BigDecimal k = chain.calls().stream().map(q -> q.strike())
                .filter(x -> x.doubleValue() >= spot + 2).min(BigDecimal::compareTo).orElseThrow();

        var t = trades.create(new TradeService.OpenRequest(sim.id(), "ACME", "LONG_CALL", 1,
                List.of(Leg.option(LegAction.BUY, OptionType.CALL, k, exp, 1, BigDecimal.ZERO)),
                "bullish", "week", "balanced"));

        // Fast-forward the WORLD past the expiration bell (real clock is still Saturday).
        while (!w.simTime().toLocalDate().isAfter(exp)) w.tick();
        // Settle succeeds NOW, at the world's own expiration-day close — no wait for the
        // real calendar (the wedged-position defect), no 'legs are still alive' on the JVM clock.
        var closed = trades.settle(t.id(), true);
        assertThat(closed.trade().status()).isEqualTo("EXPIRED");
        // ...and the account's cash identity still holds in the sim lane.
        Account after = accounts.get(sim.id());
        assertThat(after.cashCents()).isGreaterThan(0);
    }

    @Test
    void sharesFillAtWorldPricesInsideAWorld() {
        SimulatedWorld w = createWorld("shares-user");
        for (int i = 0; i < 10; i++) w.tick();
        Account sim = accounts.getOrCreateForWorld(w.worldId(), "Sim account");
        double worldSpot = w.quote("ACME").orElseThrow().last().doubleValue();

        var res = positions.buy(sim.id(), "ACME", 100);
        // ACME exists ONLY in the world — the fill can only have come from the world's book,
        // and it must be within its (tiny) spread of the world spot.
        assertThat(res.pricePerShareCents()).isBetween(Math.round(worldSpot * 100) - 20, Math.round(worldSpot * 100) + 20);
        // No false 'market is closed' warning on a Saturday inside a running world.
        assertThat(res.warnings()).noneMatch(x -> x.contains("Market is closed"));
    }

    @Test
    void hostileUsersCannotSeeOperateOrRestoreForeignSessions() {
        SimulatedWorld w = createWorld("owner-a");
        String id = w.worldId();
        // Memory-resident world: user B is checked exactly like a restore (the old bypass).
        assertThat(sessions.get(id, "user-b")).isEmpty();
        assertThat(sessions.getOrRestore(id, "user-b")).isEmpty();
        assertThatThrownBy(() -> sessions.start(id, "user-b"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> sessions.injectMove(id, "user-b", "ACME", -0.5))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(sessions.list("user-b")).noneMatch(m -> id.equals(m.get("id")));
        // The owner still owns it.
        assertThat(sessions.get(id, "owner-a")).isPresent();
    }

    @Test
    void aRestartResumesTheExactWorldByReplay() {
        SimulatedWorld w = createWorld("restart-user");
        String id = w.worldId();
        for (int i = 0; i < 30; i++) sessions.step(id, "restart-user");
        sessions.injectMove(id, "restart-user", "ACME", -0.04);
        for (int i = 0; i < 30; i++) sessions.step(id, "restart-user");
        BigDecimal before = w.quote("ACME").orElseThrow().last();
        var beforeTime = w.simTime();

        // Simulate a JVM restart: a brand-new registry over the same database.
        SimulationSessions restarted = new SimulationSessions(db, new EventBus());
        SimulatedWorld restored = restarted.getOrRestore(id, "restart-user").orElseThrow();
        assertThat(restored.simTime()).isEqualTo(beforeTime);
        assertThat(restored.ticks()).isEqualTo(w.ticks());
        assertThat(restored.quote("ACME").orElseThrow().last()).isEqualByComparingTo(before);
    }

    @Test
    void finishedSessionsAreTerminal() {
        SimulatedWorld w = createWorld("finish-user");
        String id = w.worldId();
        sessions.finish(id, "finish-user");
        // No zombie resurrection: FINISHED never restores, not even for the owner.
        assertThat(sessions.getOrRestore(id, "finish-user")).isEmpty();
        // ...but the row (and its report data) is still listed for the owner.
        assertThat(sessions.list("finish-user")).anyMatch(m ->
                id.equals(m.get("id")) && "FINISHED".equals(m.get("status")));
    }
}
