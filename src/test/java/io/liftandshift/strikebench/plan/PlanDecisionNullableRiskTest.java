package io.liftandshift.strikebench.plan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Freshness;
import io.liftandshift.strikebench.paper.Account;
import io.liftandshift.strikebench.paper.AccountRiskContext;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.support.TestPrices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Null and zero are different frozen decision facts; every action preserves that distinction. */
class PlanDecisionNullableRiskTest {
    private static final Instant NOW = Instant.parse("2026-07-26T16:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Db db;
    private PlanDecisionService decisions;
    private Plan.View plan;
    private Account account;

    @BeforeEach
    void setUp() {
        db = TestDb.fresh();
        PlanService plans = new PlanService(db, CLOCK);
        plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("nullable-risk", "AAPL", "INCOME", null, null,
                        "neutral", 21, null, "balanced", 0L, null, null, null));
        db.exec("INSERT INTO plan_candidate(id,plan_id,context_rev,family,input_hash,state,selected,"
                        + "underlying_symbol,evaluation_snapshot,valuation_basis,price_executability,"
                        + "price_fee_side,price_unavailable_reason) "
                        + "VALUES('pcand-null',?,1,'CUSTOM','input-null','CURRENT',1,'AAPL','{}'::jsonb,"
                        + "'UNAVAILABLE','UNAVAILABLE','OPENING','risk unavailable')",
                plan.id());
        db.exec("INSERT INTO accounts(id,name,type,starting_cash_cents,cash_cents,reserved_cents,has_traded,"
                        + "created_at,updated_at) VALUES('acct-null','Nullable risk','DEMO',"
                        + "10000000,10000000,0,0,?,?)", NOW.toString(), NOW.toString());
        account = new Account("acct-null", "Nullable risk", "DEMO",
                10_000_000L, 10_000_000L, 0L, false,
                NOW.toString(), NOW.toString(), null);
        decisions = new PlanDecisionService(db, CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    @Test
    void cashDecisionRoundTripsUnavailableRiskAsExplicitNull() {
        ObjectNode decision = decisions.chooseCash(input(unavailablePreview()));

        assertThat(decision.has("maxLossCents")).isTrue();
        assertThat(decision.path("maxLossCents").isNull()).isTrue();
        assertThat(decision.at("/metrics/reserveCents").isNull()).isTrue();
        assertThat(decision.path("modelVersion").asText()).isEqualTo("plan-decision-3");
        assertThat(db.query("SELECT max_loss_cents FROM plan_decision WHERE plan_id=?",
                row -> row.lngOrNull("max_loss_cents"), plan.id())).containsExactly((Long) null);
        assertThat(db.query("SELECT value_cents FROM plan_decision_metric "
                        + "WHERE decision_id=? AND metric_key='reserveCents'",
                row -> row.lngOrNull("value_cents"), decision.path("id").asText())).isEmpty();
    }

    @Test
    void cashDecisionKeepsGenuineZeroRiskNumeric() {
        ObjectNode decision = decisions.chooseCash(input(zeroRiskPreview()));

        assertThat(decision.path("maxLossCents").isNumber()).isTrue();
        assertThat(decision.path("maxLossCents").asLong()).isZero();
        assertThat(decision.at("/metrics/reserveCents").isNumber()).isTrue();
        assertThat(decision.at("/metrics/reserveCents").asLong()).isZero();
    }

    @Test
    void practiceTradeIsRejectedBeforeOpeningWhenReviewedRiskIsUnavailable() {
        assertThatThrownBy(() -> decisions.prepareTrade(input(unavailablePreview())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires reviewed maximum-loss and reserve");
        assertThat(db.query("SELECT status FROM plans WHERE id=?",
                row -> row.str("status"), plan.id())).containsExactly("ACTIVE");
        assertThat(db.query("SELECT COUNT(*) n FROM plan_decision",
                row -> row.lng("n"))).containsExactly(0L);
    }

    @Test
    void brokerFactMayFreezeUnavailableRiskWithoutInventingIt() {
        PlanDecisionService.PreparedTradeDecision prepared =
                decisions.prepareBroker(input(unavailablePreview()));
        db.tx(connection -> {
            prepared.hook().afterTradeCreated(connection, null, null);
            return null;
        });

        ObjectNode decision = decisions.latest(null, plan.id());
        assertThat(decision.path("action").asText()).isEqualTo("BROKER");
        assertThat(decision.path("maxLossCents").isNull()).isTrue();
        assertThat(decision.at("/metrics/reserveCents").isNull()).isTrue();
        assertThat(db.query("SELECT status FROM plans WHERE id=?",
                row -> row.str("status"), plan.id())).containsExactly("POSITION_OPEN");
    }

    private PlanDecisionService.Input input(TradePreview preview) {
        return new PlanDecisionService.Input(null, plan, plan.version(), "pcand-null",
                account, preview, economics(), new AccountRiskContext(null, null, null, null, null),
                1, List.of(), "fixture", AnalysisContext.OBSERVED);
    }

    private static TradePreview unavailablePreview() {
        PackagePriceReceipt price = PackagePriceReceipt.unavailable(
                1, PackagePriceReceipt.FeeSide.OPENING, "risk unavailable");
        return new TradePreview(false, List.of("Maximum loss is unavailable."), List.of(),
                null, null, List.of(), null, null, null,
                10_000_000L, 10_000_000L, 0L, 0L,
                10_000_000L, 10_000_000L, "MISSING",
                DataEvidence.of("fixture", Freshness.FIXTURE), 10_000L, null,
                List.of(), List.of(), Map.of(), price);
    }

    private static TradePreview zeroRiskPreview() {
        return new TradePreview(true, List.of(), List.of(),
                0L, 0L, List.of(), .5, 0L, 0L,
                10_000_000L, 9_999_935L, 0L, 0L,
                10_000_000L, 9_999_935L, "FIXTURE",
                DataEvidence.of("fixture", Freshness.FIXTURE), 10_000L, null,
                List.of(), List.of(), Map.of(),
                TestPrices.withFees(1, 0L, 0L, 65L));
    }

    private static EconomicAssessment economics() {
        return new EconomicAssessment(EconomicAssessment.Verdict.UNAVAILABLE,
                "MECHANICS_ONLY", "Unavailable", "Risk is unavailable.",
                null, null, null, null, false, List.of("Risk is unavailable."));
    }
}
