package io.liftandshift.strikebench.plan;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanServiceTest {
    private Db db;
    private PlanService plans;

    @BeforeEach void setUp() {
        db = TestDb.fresh();
        plans = new PlanService(db, Clock.fixed(Instant.parse("2026-07-12T16:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void equivalentActiveContentIsIdempotentAcrossRequestIds() {
        Plan.View first = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-1", "AAPL", "INCOME", 30));
        Plan.View replay = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-1", "AAPL", "INCOME", 30));
        Plan.View equivalent = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-2", "AAPL", "INCOME", 30));
        Plan.View differentHorizon = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-3", "AAPL", "INCOME", 60));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(equivalent.id()).isEqualTo(first.id());
        assertThat(differentHorizon.id()).isNotEqualTo(first.id());
        assertThat(plans.list(null, Plan.MarketKind.DEMO, null, true)).hasSize(2);
        assertThat(db.query("SELECT client_request_id,plan_id FROM plan_create_request ORDER BY client_request_id",
                r -> r.str("client_request_id") + ":" + r.str("plan_id")))
                .containsExactly("req-1:" + first.id(), "req-2:" + first.id(), "req-3:" + differentHorizon.id());
        assertThat(first.context().inputHash()).hasSize(64);
        assertThatThrownBy(() -> plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-1", "AAPL", "HEDGE", 30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different plan request");

        Plan.View archived = plans.archive(null, first.id(), new Plan.ArchiveRequest(first.version()));
        Plan.View freshCycle = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-4", "AAPL", "INCOME", 30));
        assertThat(archived.status()).isEqualTo(Plan.Status.ARCHIVED);
        assertThat(freshCycle.id()).isNotEqualTo(first.id());
    }

    @Test
    void presentationAndRiskDefaultsDoNotDuplicateAnActivePlan() {
        Plan.CreateRequest initial = new Plan.CreateRequest("identity-1", "AAPL", "INCOME", null,
                "First title", "neutral", 30, 25000L, "conservative", 100L, 20000L, null, null);
        Plan.CreateRequest sameInquiry = new Plan.CreateRequest("identity-2", "AAPL", "INCOME", null,
                "Renamed in another entry point", "neutral", 30, 25000L, "aggressive", 100L, 20000L, null, null);
        Plan.View first = plans.create(null, Plan.MarketKind.DEMO, null, null, initial);
        Plan.View resumed = plans.create(null, Plan.MarketKind.DEMO, null, null, sameInquiry);

        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(resumed.title()).isEqualTo("First title");
        assertThat(resumed.context().riskMode()).isEqualTo("conservative");
        assertThat(plans.list(null, Plan.MarketKind.DEMO, null, false)).hasSize(1);

        Plan.View differentTarget = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("identity-3", "AAPL", "INCOME", null, "First title", "neutral",
                        30, 24000L, "conservative", 100L, 20000L, null, null));
        assertThat(differentTarget.id()).isNotEqualTo(first.id());

        Plan.View closed = plans.setOpen(null, first.id(), new Plan.OpenRequest(first.version(), false));
        Plan.View reopened = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("identity-reopen", "AAPL", "INCOME", null, "Resume", "neutral",
                        30, 25000L, "balanced", 100L, 20000L, null, null));
        assertThat(closed.open()).isFalse();
        assertThat(reopened.id()).isEqualTo(first.id());
        assertThat(reopened.open()).isTrue();
        assertThat(reopened.version()).isEqualTo(closed.version() + 1);

        db.exec("UPDATE plans SET status='DECIDED_CASH' WHERE id=?", first.id());
        Plan.View newCycle = plans.create(null, Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("identity-4", "AAPL", "INCOME", null, "Next cycle", "neutral",
                        30, 25000L, "balanced", 100L, 20000L, null, null));
        assertThat(newCycle.id()).isNotEqualTo(first.id());
    }

    @Test
    void concurrentEquivalentContentWithDifferentRequestIdsCreatesExactlyOnePlan() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> {
                ready.countDown(); go.await();
                return plans.create(null, Plan.MarketKind.DEMO, null, null,
                        create("req-content-a", "QQQ", "ACQUIRE", 30));
            });
            var b = pool.submit(() -> {
                ready.countDown(); go.await();
                return plans.create(null, Plan.MarketKind.DEMO, null, null,
                        create("req-content-b", "QQQ", "ACQUIRE", 30));
            });
            ready.await();
            go.countDown();
            assertThat(a.get().id()).isEqualTo(b.get().id());
        }
        assertThat(plans.list(null, Plan.MarketKind.DEMO, null, false)).hasSize(1);
        assertThat(db.query("SELECT COUNT(*) n FROM plan_create_request", r -> r.lng("n")))
                .containsExactly(2L);
    }

    @Test
    void concurrentPromotionWithOneRequestIdCreatesExactlyOnePlan() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> {
                ready.countDown(); go.await();
                return plans.create(null, Plan.MarketKind.DEMO, null, null,
                        create("req-race", "AAPL", "INCOME", 30));
            });
            var b = pool.submit(() -> {
                ready.countDown(); go.await();
                return plans.create(null, Plan.MarketKind.DEMO, null, null,
                        create("req-race", "AAPL", "INCOME", 30));
            });
            ready.await();
            go.countDown();
            assertThat(a.get().id()).isEqualTo(b.get().id());
        }
        assertThat(plans.list(null, Plan.MarketKind.DEMO, null, false)).hasSize(1);
        assertThat(db.query("SELECT COUNT(*) n FROM plan_context_revision", r -> r.lng("n")))
                .containsExactly(1L);
    }

    @Test
    void ownersAndMarketsAreIsolated() {
        db.exec("INSERT INTO users(id,email,created_at,updated_at) VALUES('u1','u1@example.test','now','now')");
        db.exec("INSERT INTO users(id,email,created_at,updated_at) VALUES('u2','u2@example.test','now','now')");
        db.exec("INSERT INTO sim_session(id,name,user_id,config,status) VALUES('simw_1','Test world','u1','{}','CREATED')");
        Plan.View observed = plans.create("u1", Plan.MarketKind.OBSERVED, null, null,
                create("shared-owner-request", "SPY", "DIRECTIONAL", 14));
        Plan.View simulated = plans.create("u1", Plan.MarketKind.SIMULATED, "simw_1", null,
                create("req-u1-sim", "SPY", "DIRECTIONAL", 14));
        plans.create("u2", Plan.MarketKind.OBSERVED, null, null,
                create("shared-owner-request", "QQQ", "HEDGE", 30));

        assertThat(plans.list("u1", Plan.MarketKind.OBSERVED, null, true))
                .extracting(Plan.View::id).containsExactly(observed.id());
        assertThat(plans.list("u1", Plan.MarketKind.SIMULATED, "simw_1", true))
                .extracting(Plan.View::id).containsExactly(simulated.id());
        assertThatThrownBy(() -> plans.get("u2", observed.id())).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void contextEditsAppendRevisionAndInvalidateOnlyDependentArtifacts() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-rev", "AAPL", "DIRECTIONAL", 30));
        db.exec("INSERT INTO plan_evidence(id,plan_id,context_rev,basis,as_of,engine_version,input_hash," +
                        "evidence_provenance,state) VALUES('pe_1',?,1,'DEMO_HISTORY',now(),'study-1','old','DEMO','CURRENT')",
                plan.id());
        String oldHash = plan.context().inputHash();

        Plan.View changed = plans.updateContext(null, plan.id(), new Plan.ContextUpdateRequest(
                plan.version(), "bearish", 45, null, null, null, null, null, null, java.util.Set.of("targetCents")));

        assertThat(changed.version()).isEqualTo(plan.version() + 1);
        assertThat(changed.context().rev()).isEqualTo(2);
        assertThat(changed.context().horizonDays()).isEqualTo(45);
        assertThat(changed.context().thesis()).isEqualTo("bearish");
        assertThat(changed.context().inputHash()).isNotEqualTo(oldHash);
        assertThat(db.query("SELECT input_hash FROM plan_context_revision WHERE plan_id=? ORDER BY rev",
                r -> r.str("input_hash"), plan.id())).containsExactly(oldHash, changed.context().inputHash());
        assertThat(db.query("SELECT state FROM plan_evidence WHERE id='pe_1'", r -> r.str("state")))
                .containsExactly("STALE");
    }

    @Test
    void intentIsEditableBeforeDecisionAndOptimisticVersionsFailClosed() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-lock", "AAPL", null, 30));
        Plan.View claimed = plans.claimIntent(null, plan.id(), new Plan.IntentRequest(plan.version(), "INCOME"));
        assertThat(claimed.intent()).isEqualTo("INCOME");
        assertThat(claimed.context().rev()).isEqualTo(2);

        Plan.View changedGoal = plans.claimIntent(null, plan.id(),
                new Plan.IntentRequest(claimed.version(), "HEDGE"));
        assertThat(changedGoal.intent()).isEqualTo("HEDGE");
        assertThat(changedGoal.context().rev()).isEqualTo(3);
        assertThat(changedGoal.furthestStage()).isEqualTo(Plan.Stage.STRATEGY);
        assertThat(changedGoal.assumptionsEditable()).isTrue();
        assertThatThrownBy(() -> plans.updateContext(null, plan.id(), new Plan.ContextUpdateRequest(
                plan.version(), "neutral", null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("another tab");
        assertThatThrownBy(() -> plans.advanceProgress(null, plan.id(),
                new Plan.ProgressRequest("MANAGE_REVIEW")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unlocks");
    }

    @Test
    void aFrozenPlanCannotRewriteItsActiveContext() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-frozen-context", "QQQ", "ACQUIRE", 30));
        db.exec("UPDATE plans SET status='DECIDED_CASH' WHERE id=?", plan.id());

        assertThatThrownBy(() -> plans.updateContext(null, plan.id(), new Plan.ContextUpdateRequest(
                plan.version(), "bearish", 45, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen decision")
                .hasMessageContaining("linked plan");
    }

    @Test
    void openAndArchiveAreVersionedAndAnnounced() {
        EventBus bus = new EventBus();
        plans.setEvents(bus);
        List<EventBus.Event> seen = new CopyOnWriteArrayList<>();
        bus.subscribe(seen::add);
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-open", "QQQ", "ACQUIRE", 30));
        Plan.View closedChip = plans.setOpen(null, plan.id(), new Plan.OpenRequest(plan.version(), false));
        Plan.View archived = plans.archive(null, plan.id(), new Plan.ArchiveRequest(closedChip.version()));

        assertThat(closedChip.open()).isFalse();
        assertThat(archived.status()).isEqualTo(Plan.Status.ARCHIVED);
        assertThat(plans.list(null, Plan.MarketKind.DEMO, null, true)).isEmpty();
        await(() -> seen.stream().filter(e -> e.type().equals("plan.updated")).count() >= 3);
    }

    @Test
    void archivedBlankGoalCannotBeClaimedOrMoveItsSavedStage() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-archived-blank-goal", "QQQ", null, 30));
        db.exec("INSERT INTO plan_evidence(id,plan_id,context_rev,basis,as_of,engine_version,input_hash," +
                        "evidence_provenance,state) VALUES('pe_archived',?,1,'DEMO_HISTORY',now()," +
                        "'study-1','frozen','DEMO','CURRENT')", plan.id());
        Plan.View archived = plans.archive(null, plan.id(), new Plan.ArchiveRequest(plan.version()));

        assertThatThrownBy(() -> plans.claimIntent(null, plan.id(),
                new Plan.IntentRequest(archived.version(), "ACQUIRE")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");
        assertThatThrownBy(() -> plans.advanceProgress(null, plan.id(),
                new Plan.ProgressRequest("EVIDENCE")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");

        Plan.View unchanged = plans.get(null, plan.id());
        assertThat(unchanged.intent()).isNull();
        assertThat(unchanged.context().rev()).isEqualTo(1);
        assertThat(unchanged.version()).isEqualTo(archived.version());
        assertThat(db.query("SELECT state FROM plan_evidence WHERE id='pe_archived'", r -> r.str("state")))
                .containsExactly("CURRENT");
    }

    @Test
    void archivedPlanCannotGainNewScoutRelationships() {
        Plan.View origin = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-archived-link-origin", "QQQ", "ACQUIRE", 30));
        Plan.View child = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-archived-link-child", "SPY", "DIRECTIONAL", 30));
        plans.archive(null, origin.id(), new Plan.ArchiveRequest(origin.version()));

        assertThatThrownBy(() -> plans.linkRelated(null, origin.id(), child.id(), "PEER"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");
        assertThat(db.query("SELECT COUNT(*) n FROM plan_link WHERE plan_id=?", r -> r.lng("n"), origin.id()))
                .containsExactly(0L);
    }

    @Test
    void undecidedDraftCanBeDeletedButDecisionHistoryCannot() {
        Plan.View disposable = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-delete", "AAPL", "DIRECTIONAL", 21));
        plans.deleteDraft(null, disposable.id(), disposable.version());
        assertThatThrownBy(() -> plans.get(null, disposable.id()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(db.query("SELECT COUNT(*) n FROM plan_create_request WHERE plan_id=?", r -> r.lng("n"),
                disposable.id())).containsExactly(0L);

        Plan.View decided = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-keep", "QQQ", "INCOME", 22));
        db.exec("UPDATE plans SET status='DECIDED_CASH' WHERE id=?", decided.id());
        assertThatThrownBy(() -> plans.deleteDraft(null, decided.id(), decided.version()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Archive");
        assertThat(plans.get(null, decided.id()).id()).isEqualTo(decided.id());
    }

    private static Plan.CreateRequest create(String requestId, String symbol, String intent, int horizon) {
        return new Plan.CreateRequest(requestId, symbol, intent, null, null, "bullish", horizon,
                25000L, "conservative", 0L, null, null, null);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
