package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.market.MarketHours;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountRiskContext;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import io.liftandshift.strikebench.support.TestPrices;

/**
 * The frozen decision receipt counts TRADING SESSIONS, so the cash-decision review lands on the
 * session calendar. Reading the same number as calendar days scheduled a 21-session review on
 * 2026-08-03 instead of 2026-08-11 and benchmarked the decision against the wrong close.
 */
class PlanDecisionReviewHorizonTest {

    /** Monday 2026-07-13, 10:30 ET — a regular session, so the decision date is unambiguous. */
    private static final Instant DECIDED_AT = Instant.parse("2026-07-13T14:30:00Z");
    private static final int DECLARED_SESSIONS = 21;

    private Db db;

    @BeforeEach void setUp() { db = TestDb.fresh(); }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void frozenReviewHorizonRoundTripsAsSessionsAndDatesTheReviewOnTheSessionCalendar() {
        Clock clock = Clock.fixed(DECIDED_AT, ZoneOffset.UTC);
        PlanService plans = new PlanService(db, clock);
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("review-horizon", "AAPL", "INCOME", null, null,
                        "neutral", DECLARED_SESSIONS, null, "conservative", 0L, null, null, null));
        // This fixture is about the review clock, not the price, so the row carries an explicit
        // UNAVAILABLE §7.2 receipt: every candidate states a basis, and "no receipt" is not a state.
        db.exec("INSERT INTO plan_candidate(id,plan_id,context_rev,family,input_hash,state,selected," +
                        "underlying_symbol,evaluation_snapshot,valuation_basis,price_executability," +
                        "price_fee_side,price_unavailable_reason) " +
                        "VALUES('pcand-review',?,1,'LONG_CALL','input-review','CURRENT',1,'AAPL','{}'::jsonb," +
                        "'UNAVAILABLE','UNAVAILABLE','OPENING','this review-clock fixture never priced the package')",
                plan.id());
        db.exec("INSERT INTO accounts(id,name,type,starting_cash_cents,cash_cents,reserved_cents,has_traded," +
                        "created_at,updated_at) VALUES('acct-review','Review account','DEMO'," +
                        "10000000,10000000,0,0,?,?)", DECIDED_AT.toString(), DECIDED_AT.toString());

        ObjectNode decision = new PlanDecisionService(db, clock).chooseCash(
                new PlanDecisionService.Input(null, plan, plan.version(), "pcand-review", account(),
                        preview(), economics(), new AccountRiskContext(null, null, null, null, null),
                        1, List.of(), "Kept cash", AnalysisContext.OBSERVED));

        assertThat(decision.path("reviewHorizonSessions").asInt())
                .as("the receipt freezes the Plan's declared trading sessions")
                .isEqualTo(DECLARED_SESSIONS);
        assertThat(decision.has("reviewHorizonDays"))
                .as("no hop may name a session count 'days'")
                .isFalse();
        assertThat(db.query("SELECT review_horizon_sessions s FROM plan_decision WHERE plan_id=?",
                r -> r.intv("s"), plan.id())).containsExactly(DECLARED_SESSIONS);

        Instant decidedAt = OffsetDateTime.parse(decision.path("createdAt").asText()).toInstant();
        LocalDate decidedOn = LocalDate.ofInstant(decidedAt, MarketHours.EASTERN);
        LocalDate due = PlanDecisionService.reviewDueDate(decidedAt, DECLARED_SESSIONS);

        assertThat(decidedOn).isEqualTo(LocalDate.parse("2026-07-13"));
        assertThat(MarketHours.tradingDaysBetween(decidedOn, due))
                .as("the review date is exactly the declared number of sessions away")
                .isEqualTo(DECLARED_SESSIONS);
        assertThat(due).isEqualTo(LocalDate.parse("2026-08-11"));
        assertThat(due).as("21 sessions is not 21 calendar days")
                .isNotEqualTo(decidedOn.plusDays(DECLARED_SESSIONS));
    }

    private static Account account() {
        return new Account("acct-review", "Review account", "DEMO", 10_000_000L, 10_000_000L, 0L,
                false, DECIDED_AT.toString(), DECIDED_AT.toString(), null);
    }

    private static TradePreview preview() {
        return new TradePreview(true, List.of(), List.of(), -30_000L, 65L,
                30_000L, 70_000L, List.of("253"), 0.45, -900L, 0L,
                10_000_000L, 9_969_935L, 0L, 0L, 10_000_000L, 9_969_935L,
                "FIXTURE", DataEvidence.of("fixture", Freshness.FIXTURE), 25_000L, null,
                List.of(Map.ofEntries(Map.entry("action", "BUY"), Map.entry("type", "CALL"),
                        Map.entry("strike", "250"), Map.entry("expiration", "2026-08-21"),
                        Map.entry("ratio", 1), Map.entry("multiplier", 100), Map.entry("bid", "6.9123"),
                        Map.entry("ask", "7.0456"), Map.entry("mid", "6.97895"),
                        Map.entry("fill", "7.0456"), Map.entry("iv", 0.3))), List.of(),
                Map.of("probabilityMap", Map.of("pMaxProfit", 0.2, "pMaxLoss", 0.3,
                        "cvar95Cents", -28_000L)),
                TestPrices.withFees(1, -30_000L, -30_000L, 65L));
    }

    private static EconomicAssessment economics() {
        return new EconomicAssessment(EconomicAssessment.Verdict.MIXED, "LEARN_FROM", "Mixed",
                "Costs matter", -1_420L, 480L, 520L, -4.7, false, List.of("Generated evidence"));
    }
}
