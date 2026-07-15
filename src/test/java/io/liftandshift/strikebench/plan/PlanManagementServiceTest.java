package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
                        "neutral", 30, null, "conservative", 0L, null, null));
        db.exec("INSERT INTO plan_decision(id,plan_id,context_rev,action,quote_as_of,economic_verdict," +
                        "evidence_provenance,model_version,review_horizon_days,decision_seq) " +
                        "VALUES('pdec_archived',?,1,'CASH',now(),'MIXED','DEMO','test',30,1)", plan.id());
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
}
