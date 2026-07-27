package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.paper.PackagePriceReceipt;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanManagementServiceTest {
    private Db db;
    private PlanService plans;
    private PlanManagementService management;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC);
        plans = new PlanService(db, clock);
        management = new PlanManagementService(db, clock);
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void archivedCashDecisionCannotRecordAnotherReview() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("cash-review-archive", "QQQ", "INCOME", null, null,
                        "neutral", 30, null, "conservative", 0L, null, null, null));
        db.exec("INSERT INTO plan_decision(id,plan_id,context_rev,action,price_receipt,quote_as_of,economic_verdict," +
                        "evidence_provenance,model_version,review_horizon_sessions,decision_seq) " +
                        "VALUES('pdec_archived',?,1,'CASH',?::jsonb,now(),'MIXED','DEMO','test',30,1)",
                plan.id(), Json.write(PackagePriceReceipt.unavailable(1,
                        PackagePriceReceipt.FeeSide.OPENING, "cash decision")));
        db.exec("UPDATE plans SET status='DECIDED_CASH' WHERE id=?", plan.id());
        Plan.View decided = plans.get(null, plan.id());
        Plan.View archived = plans.archive(null, plan.id(), new Plan.ArchiveRequest(decided.version()));

        var review = new PlanManagementService.CashReview(50_000, 51_000, 2_000,
                0, 0, 0, 30, 0.55, "test review");
        assertThatThrownBy(() -> management.recordCashReview(null, plan.id(), archived.version(), review))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("active frozen cash decision");
        assertThat(db.query("SELECT COUNT(*) n FROM plan_review WHERE plan_id=?", r -> r.lng("n"), plan.id()))
                .containsExactly(0L);
        assertThat(plans.get(null, plan.id()).version()).isEqualTo(archived.version());
    }

    @Test void malformedMarketMarkTimeIsNeverRewrittenAsNow() {
        assertThatThrownBy(() -> PlanManagementService.requireMarkTime("not-a-time"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timestamp is invalid");
        assertThatThrownBy(() -> PlanManagementService.requireMarkTime(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no timestamp");
        assertThat(PlanManagementService.requireMarkTime("2026-07-12T16:00:00Z"))
                .isEqualTo(java.time.OffsetDateTime.parse("2026-07-12T16:00:00Z"));
    }

    @Test void markHistoryDoesNotSubstituteLegacyPackagePnlForMissingDecisionPnl() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("mark-no-fallback", "XYZ", "INCOME", null, null,
                        "neutral", 30, null, "conservative", 0L, null, null, null));
        OffsetDateTime now = OffsetDateTime.parse("2026-07-12T16:00:00Z");
        db.exec("INSERT INTO accounts(id,user_id,name,type,starting_cash_cents,cash_cents,"
                        + "reserved_cents,has_traded,created_at,updated_at) "
                        + "VALUES('acct-mark','local','Mark fixture','PAPER',1000000,1000000,0,0,?,?)",
                now, now);
        db.exec("INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,"
                        + "entry_underlying_cents,entry_net_premium_cents,max_loss_cents,"
                        + "breakevens_json,entry_snapshot_json,created_at,updated_at) "
                        + "VALUES('trade-mark','acct-mark','XYZ','CASH_SECURED_PUT','ACTIVE',1,"
                        + "'[]'::jsonb,10000,20000,980000,'[]'::jsonb,'{}'::jsonb,?,?)",
                now, now);
        db.exec("INSERT INTO plan_link(id,plan_id,role,trade_id,created_at) "
                        + "VALUES('link-mark',?,'ENTRY','trade-mark',?)",
                plan.id(), now);
        TradeService.MarkView legacyOnly = new TradeService.MarkView(
                "trade-mark", "2026-07-12T16:00:00Z", 10_000L, 1_500L,
                12_345L, null, 0.7, "DELAYED", null, List.of());

        management.recordMark(null, plan.id(), plan.version(), "trade-mark", legacyOnly);

        List<Long> recorded = db.query(
                "SELECT unrealized_cents FROM plan_management_action "
                        + "WHERE plan_id=? AND kind='MARK'",
                r -> r.lngOrNull("unrealized_cents"), plan.id());
        assertThat(recorded).hasSize(1);
        assertThat(recorded.getFirst()).isNull();
    }
}
