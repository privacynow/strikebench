package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alert center contract (spec 10.1): every alert kind appears from REAL seeded state with the
 * right severity and plain-language wording; heuristics say they are heuristics; earnings say
 * "estimated"; ex-dividend says "unavailable"; the needs-attention ordering is severity-first;
 * material changes publish an {@code alerts.updated} hint on the existing event bus.
 */
class AlertCenterServiceTest {

    // A Wednesday. Fixture EXP below is a Friday 44 days out (no time-rule noise by default).
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final LocalDate EXP = LocalDate.of(2026, 8, 21);

    private Db db;
    private AuditLog audit;
    private AccountService accounts;
    private TradeService trades;
    private PositionsService positions;
    private PortfolioAccountingService books;
    private PaperCoreTest.StubMarks marks;
    private EventBus events;
    private Optional<EventService.EarningsEstimate> earnings = Optional.empty();
    private AlertCenterService alerts;
    private Account account;

    @BeforeEach
    void setUp() {
        db = TestDb.fresh();
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        audit = new AuditLog(db, CLOCK);
        accounts = new AccountService(db, cfg, audit, CLOCK);
        marks = new PaperCoreTest.StubMarks();
        trades = new TradeService(db, cfg, marks, audit, CLOCK);
        positions = new PositionsService(db, marks, audit, CLOCK);
        books = new PortfolioAccountingService(db, CLOCK, marks);
        events = new EventBus();
        alerts = new AlertCenterService(db, CLOCK, trades, marks, symbol -> earnings, events,
                cfg.feePerContractCents());
        account = accounts.getOrCreateDefault();
    }

    @AfterEach
    void tearDown() {
        if (alerts != null) alerts.close();
        if (db != null) db.close();
    }

    private static Leg put(LegAction a, String strike, String prem, LocalDate exp) {
        return Leg.option(a, OptionType.PUT, new BigDecimal(strike), exp, 1, new BigDecimal(prem));
    }

    private static Leg call(LegAction a, String strike, String prem, LocalDate exp) {
        return Leg.option(a, OptionType.CALL, new BigDecimal(strike), exp, 1, new BigDecimal(prem));
    }

    private TradeRecord creditPutSpread(LocalDate exp) {
        return trades.create(new TradeService.OpenRequest(account.id(), "AAPL", "CREDIT_PUT_SPREAD", 1,
                List.of(put(LegAction.SELL, "100", "3.00", exp), put(LegAction.BUY, "95", "1.20", exp)),
                "bullish", "month", "balanced", null, null, null, null, null, "PROPOSED"));
    }

    private List<AlertCenterService.Alert> ofKind(AlertCenterService.AlertSet set, String kind) {
        return set.alerts().stream().filter(a -> kind.equals(a.kind())).toList();
    }

    @Test
    void quietBookProducesNoAlertsAndTheExDividendNoteSaysUnavailable() {
        AlertCenterService.AlertSet set = alerts.compute("local");
        assertThat(set.alerts()).isEmpty();
        assertThat(set.counts().total()).isZero();
        assertThat(set.exDividend().available()).isFalse();
        assertThat(set.exDividend().note()).contains("unavailable").contains("never guesses");
    }

    @Test
    void stopBreachOnACreditTradeIsUrgentWithThePlanDeepLink() {
        TradeRecord t = creditPutSpread(EXP);
        db.exec("INSERT INTO plans(id,user_id,symbol,market_kind,status) "
                + "VALUES ('plan-alert-1','local','AAPL','OBSERVED','POSITION_OPEN')");
        db.exec("INSERT INTO plan_link(id,plan_id,role,trade_id,created_at) "
                + "VALUES ('plink-alert-1','plan-alert-1','ENTRY',?,now())", t.id());
        marks.mids.put("PUT100", new BigDecimal("6.80"));
        marks.mids.put("PUT95", new BigDecimal("1.00"));
        trades.refresh(t.id());

        AlertCenterService.AlertSet set = alerts.compute("local");
        List<AlertCenterService.Alert> breaches = ofKind(set, "PROTOCOL_BREACH");
        assertThat(breaches).hasSize(1);
        AlertCenterService.Alert a = breaches.getFirst();
        assertThat(a.severity()).isEqualTo("URGENT");
        assertThat(a.severityLabel()).isEqualTo("Act today");
        assertThat(a.headline()).contains("AAPL").contains("past your loss line")
                .contains("your protocol says close it");
        assertThat(a.detail()).contains("collected $180.00").contains("~2x the credit")
                .contains("not a prediction");
        assertThat(a.deepLink()).isEqualTo("#/plan/plan-alert-1/manage-review");
        assertThat(a.planId()).isEqualTo("plan-alert-1");
        assertThat(set.counts().urgent()).isEqualTo(1);
    }

    @Test
    void takeProfitOnACreditTradeNeedsALookAndFallsBackToTheTradeDeepLink() {
        TradeRecord t = creditPutSpread(EXP);
        marks.mids.put("PUT100", new BigDecimal("0.60"));
        marks.mids.put("PUT95", new BigDecimal("0.10"));
        trades.refresh(t.id());

        AlertCenterService.Alert a = ofKind(alerts.compute("local"), "PROTOCOL_BREACH").getFirst();
        assertThat(a.severity()).isEqualTo("ATTENTION");
        assertThat(a.severityLabel()).isEqualTo("Needs a look");
        assertThat(a.headline()).contains("most of the profit is in hand")
                .contains("your protocol says take it");
        assertThat(a.deepLink()).isEqualTo("#/portfolio/trade/" + t.id());
    }

    @Test
    void expiryTodayIsUrgentAndCarriesStrikeNotionalWithItsUnit() {
        creditPutSpread(TODAY); // 0DTE: expires the day of the fixed clock
        AlertCenterService.AlertSet set = alerts.compute("local");
        List<AlertCenterService.Alert> expiries = ofKind(set, "EXPIRY");
        assertThat(expiries).hasSize(1);
        AlertCenterService.Alert a = expiries.getFirst();
        assertThat(a.severity()).isEqualTo("URGENT");
        assertThat(a.headline()).contains("expires today").contains("decide before the close");
        // $100 and $95 strikes x 100 shares x 1 contract = $19,500 of strike value, unit named.
        assertThat(a.detail()).contains("$19,500.00 of strike value")
                .contains("strike × contracts × multiplier");
        assertThat(a.meta().get("strikeNotionalCents")).isEqualTo(1_950_000L);
        // The urgent expiry outranks the same trade's informational roll-window line.
        assertThat(set.alerts().getFirst().severity()).isEqualTo("URGENT");
    }

    @Test
    void expiryThisWeekNeedsALookAndTheTimeRuleStaysQuietInsideTheExpiryWindow() {
        creditPutSpread(LocalDate.of(2026, 7, 10)); // Friday, 2 sessions out
        AlertCenterService.AlertSet set = alerts.compute("local");
        AlertCenterService.Alert expiry = ofKind(set, "EXPIRY").getFirst();
        assertThat(expiry.severity()).isEqualTo("ATTENTION");
        assertThat(expiry.headline()).contains("expires 2026-07-10").contains("2 sessions");
        // Inside the expiry window the 21-day roll reminder would say the same thing twice.
        assertThat(ofKind(set, "PROTOCOL_BREACH")).isEmpty();
    }

    @Test
    void pinRiskFiresOnlyWithinOnePercentOfAShortStrikeInsideThreeSessionsAndSaysHeuristic() {
        creditPutSpread(LocalDate.of(2026, 7, 10)); // short 100 put, 2 sessions out; spot 100.00
        AlertCenterService.AlertSet set = alerts.compute("local");
        List<AlertCenterService.Alert> pins = ofKind(set, "PIN_RISK");
        assertThat(pins).hasSize(1);
        AlertCenterService.Alert a = pins.getFirst();
        assertThat(a.severity()).isEqualTo("ATTENTION");
        assertThat(a.headline()).contains("sitting right on your $100.00 short strike");
        assertThat(a.detail()).contains("Heuristic").contains("1%").contains("3 or fewer trading sessions");
        assertThat(a.meta().get("heuristic")).isEqualTo(true);

        // Move spot 5% away: the pin alert disappears; nothing is fabricated.
        marks.underlying = new BigDecimal("105.00");
        trades.refresh(ofKind(set, "PIN_RISK").getFirst().tradeId());
        assertThat(ofKind(alerts.compute("local"), "PIN_RISK")).isEmpty();
    }

    @Test
    void earlyAssignmentWarnsOnShortItmOptionsWithExtrinsicBelowFeesAndSaysExDivUnavailable() {
        // A share-covered short 100 call expiring in 2 sessions; spot then moves deep ITM to
        // 130 while the call marks at exactly intrinsic: extrinsic $0.00 <= the $0.65 fee.
        positions.buy(account.id(), "AAPL", 100);
        trades.create(new TradeService.OpenRequest(account.id(), "AAPL", "COVERED_CALL", 1,
                List.of(call(LegAction.SELL, "100", "2.50", LocalDate.of(2026, 7, 10))),
                "neutral", "month", "balanced", "EXIT", true, null, null, null, "PROPOSED"));
        marks.underlying = new BigDecimal("130.00");
        marks.mids.put("CALL100", new BigDecimal("30.00"));
        AlertCenterService.AlertSet set = alerts.compute("local");
        List<AlertCenterService.Alert> assigns = ofKind(set, "ASSIGNMENT");
        assertThat(assigns).hasSize(1);
        AlertCenterService.Alert a = assigns.getFirst();
        assertThat(a.severity()).isEqualTo("ATTENTION");
        assertThat(a.headline()).contains("short $100.00 call").contains("assigned early")
                .contains("time value is below trading fees");
        assertThat(a.detail()).contains("Heuristic").contains("extrinsic")
                .contains("Ex-dividend dates: unavailable");
        assertThat(a.meta().get("heuristic")).isEqualTo(true);
    }

    @Test
    void earningsProximityIsInfoLabeledEstimatedAndNeverConfirmed() {
        creditPutSpread(EXP);
        earnings = Optional.of(new EventService.EarningsEstimate(
                TODAY.plusDays(6), 7, "SEC filing cadence (8 quarterly reports, ~91-day rhythm)", false));
        AlertCenterService.AlertSet set = alerts.compute("local");
        List<AlertCenterService.Alert> rows = ofKind(set, "EARNINGS");
        assertThat(rows).hasSize(1);
        AlertCenterService.Alert a = rows.getFirst();
        assertThat(a.severity()).isEqualTo("INFO");
        assertThat(a.severityLabel()).isEqualTo("Worth knowing");
        assertThat(a.headline()).contains("earnings estimated near 2026-07-14");
        assertThat(a.detail()).contains("Estimated window").contains("not a confirmed calendar date")
                .contains("SEC filing cadence");
        assertThat(a.meta().get("confirmed")).isEqualTo(false);
        assertThat(a.deepLink()).isEqualTo("#/research/AAPL?view=evidence");

        // A confirmed=true estimate would come from a licensed calendar; until the surface
        // supplies one, the estimate label is mandatory — and no estimate means no row.
        earnings = Optional.empty();
        assertThat(ofKind(alerts.compute("local"), "EARNINGS")).isEmpty();
    }

    @Test
    void unresolvedImportsSurfacePerSourceAccountWithACount() {
        var destination = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Import destination", "ROTH_IRA", null, "FIFO", null, null, null, null, null));
        db.exec("INSERT INTO portfolio_import_pending(id,user_id,source_system,source_account_fingerprint,"
                + "external_ref,occurred_at,package_net_cents,destination_portfolio_account_id,payload_fingerprint) "
                + "VALUES ('pend-a1','local','VANGUARD','fp-77','ref-1','2026-07-01T15:00:00Z',-5000,?,"
                + "'payload-pend-a1')", destination.id());
        db.exec("INSERT INTO portfolio_import_pending(id,user_id,source_system,source_account_fingerprint,"
                + "external_ref,occurred_at,package_net_cents,destination_portfolio_account_id,payload_fingerprint) "
                + "VALUES ('pend-a2','local','VANGUARD','fp-77','ref-2','2026-07-02T15:00:00Z',2000,?,"
                + "'payload-pend-a2')", destination.id());
        db.exec("INSERT INTO portfolio_import_pending(id,user_id,source_system,source_account_fingerprint,"
                + "external_ref,occurred_at,package_net_cents,destination_portfolio_account_id,payload_fingerprint,"
                + "status,resolved_at) VALUES ('pend-a3','local','VANGUARD','fp-77','ref-3',"
                + "'2026-07-03T15:00:00Z',0,?,'payload-pend-a3','REJECTED','2026-07-04T15:00:00Z')",
                destination.id());

        AlertCenterService.AlertSet set = alerts.compute("local");
        List<AlertCenterService.Alert> rows = ofKind(set, "PENDING_IMPORTS");
        assertThat(rows).hasSize(1);
        AlertCenterService.Alert a = rows.getFirst();
        assertThat(a.severity()).isEqualTo("ATTENTION");
        assertThat(a.headline()).isEqualTo("2 imported broker packages need fills or broker attestation.");
        assertThat(a.detail()).contains("VANGUARD …p-77").contains("User allocations remain quarantined")
                .contains("broker-attested fills create exact lots");
        assertThat(a.deepLink()).isEqualTo("#/portfolio/book/activity");
        assertThat(a.meta().get("pendingCount")).isEqualTo(2L);
    }

    @Test
    void trackedStructuresGetExpiryAndAssignmentAlertsFromTheirExactLots() {
        var tracked = books.createAccount("local", new PortfolioAccountingService.AccountInput(
                "Brokerage IRA", "ROTH_IRA", null, "FIFO", null, null, null, null, 2_000_000L));
        // A short 100 put expiring in 2 sessions, recorded as the broker reported it.
        books.record("local", tracked.id(), new PortfolioAccountingService.TransactionInput(
                "2026-07-01", "TRADE", null, 0L, null, "MANUAL", null, null,
                List.of(new PortfolioAccountingService.LegInput("OPTION", "SELL", "OPEN", "AAPL", "PUT",
                        new BigDecimal("100"), LocalDate.of(2026, 7, 10), 1L, 100, new BigDecimal("3.00"), null)),
                "EXECUTED"));
        String lotId = books.lots("local", tracked.id(), false).getFirst().id();
        db.exec("INSERT INTO portfolio_structure(id,user_id,portfolio_account_id,symbol,label,status) "
                + "VALUES ('st-alert-1','local',?,'AAPL','Cash-secured put','OPEN')", tracked.id());
        db.exec("INSERT INTO portfolio_structure_revision(id,structure_id,revision_no,position_state,action_role) "
                + "VALUES ('strev-alert-1','st-alert-1',1,'OPEN','ENTRY')");
        db.exec("UPDATE portfolio_structure SET current_revision_id='strev-alert-1' WHERE id='st-alert-1'");
        db.exec("INSERT INTO portfolio_structure_member(revision_id,leg_no,lot_id,allocated_quantity,leg_role) "
                + "VALUES ('strev-alert-1',0,?,1,'SHORT_PUT')", lotId);

        AlertCenterService.AlertSet set = alerts.compute("local");
        List<AlertCenterService.Alert> expiries = ofKind(set, "EXPIRY").stream()
                .filter(a -> "STRUCTURE".equals(a.entityType())).toList();
        assertThat(expiries).hasSize(1);
        AlertCenterService.Alert expiry = expiries.getFirst();
        assertThat(expiry.structureId()).isEqualTo("st-alert-1");
        assertThat(expiry.accountName()).isEqualTo("Brokerage IRA");
        assertThat(expiry.lane()).isEqualTo("TRACKED");
        assertThat(expiry.headline()).contains("Cash-secured put").contains("expires 2026-07-10");
        assertThat(expiry.deepLink()).isEqualTo("#/portfolio/book/overview");
        // Spot 100 on the 100 strike inside 3 sessions: the tracked lot pins too.
        assertThat(ofKind(set, "PIN_RISK")).anyMatch(a -> "STRUCTURE".equals(a.entityType()));
    }

    @Test
    void orderingIsSeverityFirstAndMaterialChangesPublishOneEventBusHint() {
        creditPutSpread(LocalDate.of(2026, 7, 10)); // expiry attention + pin attention
        AlertCenterService.AlertSet first = alerts.refreshAndPublish("local");
        assertThat(first.counts().total()).isGreaterThanOrEqualTo(2);
        long published = events.since(0).stream().filter(e -> "alerts.updated".equals(e.type())).count();
        assertThat(published).isEqualTo(1);

        // Unchanged state: no duplicate hint.
        alerts.refreshAndPublish("local");
        assertThat(events.since(0).stream().filter(e -> "alerts.updated".equals(e.type())).count())
                .isEqualTo(1);

        // A stop breach lands: severity re-orders urgent-first and one new hint is published.
        TradeRecord second = creditPutSpread(EXP);
        marks.mids.put("PUT100", new BigDecimal("6.80"));
        marks.mids.put("PUT95", new BigDecimal("1.00"));
        trades.refresh(second.id());
        AlertCenterService.AlertSet changed = alerts.refreshAndPublish("local");
        assertThat(changed.alerts().getFirst().kind()).isEqualTo("PROTOCOL_BREACH");
        assertThat(changed.alerts().getFirst().severity()).isEqualTo("URGENT");
        List<Integer> ranks = changed.alerts().stream()
                .map(a -> AlertCenterService.severityRank(a.severity())).toList();
        assertThat(ranks).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(events.since(0).stream().filter(e -> "alerts.updated".equals(e.type())).count())
                .isEqualTo(2);
        var hint = events.since(0).stream().filter(e -> "alerts.updated".equals(e.type()))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(hint.data()).containsEntry("user", "local").doesNotContainKey("owner");
        assertThat((int) hint.data().get("urgent")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void httpCurrentReadEstablishesBaselineWithoutPublishingACircularHint() {
        creditPutSpread(LocalDate.of(2026, 7, 10));

        AlertCenterService.AlertSet current = alerts.current("local");

        assertThat(current.counts().total()).isPositive();
        assertThat(events.since(0)).noneMatch(e -> "alerts.updated".equals(e.type()));
        alerts.refreshAndPublish("local");
        assertThat(events.since(0)).noneMatch(e -> "alerts.updated".equals(e.type()));
    }

    @Test
    void committedPracticeMutationInvalidatesAndPublishesWithoutAnAlertGet() {
        alerts.current("local"); // observe the owner and establish the quiet baseline
        audit.setAccountChangedHook(alerts::invalidateAccount);

        creditPutSpread(LocalDate.of(2026, 7, 10));

        await(() -> events.since(0).stream().anyMatch(e -> "alerts.updated".equals(e.type())));
        EventBus.Event hint = events.since(0).stream()
                .filter(e -> "alerts.updated".equals(e.type())).findFirst().orElseThrow();
        assertThat(hint.data()).containsEntry("user", "local");
        assertThat((int) hint.data().get("total")).isPositive();
    }

    @Test
    void protocolEvaluatorIsTheSingleSourceOfTriggerMath() {
        // Credit: stop at 2x credit, take-profit at 50% capture, roll window at 21 days.
        assertThat(ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(18000L, -36000L, 44)))
                .anyMatch(t -> t.rule().equals("STOP_LOSS") && t.severity().equals("URGENT"));
        assertThat(ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(18000L, 9000L, 44)))
                .anyMatch(t -> t.rule().equals("TAKE_PROFIT") && t.severity().equals("ATTENTION"));
        assertThat(ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(18000L, 0L, 20)))
                .anyMatch(t -> t.rule().equals("ROLL") && t.severity().equals("INFO"));
        // Debit: stop at 50% of the debit, take-profit at 50%, time exit at 21 days.
        assertThat(ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(-20000L, -10000L, 44)))
                .anyMatch(t -> t.rule().equals("STOP_LOSS"));
        assertThat(ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(-20000L, 10000L, 44)))
                .anyMatch(t -> t.rule().equals("TAKE_PROFIT"));
        assertThat(ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(-20000L, 0L, 10)))
                .anyMatch(t -> t.rule().equals("TIME_EXIT"));
        // Quiet inside the thresholds; no marks -> no profit/loss opinion.
        assertThat(ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(18000L, 1000L, 44))).isEmpty();
        assertThat(ProtocolEvaluator.evaluate(new ProtocolEvaluator.Inputs(18000L, null, 44))).isEmpty();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
