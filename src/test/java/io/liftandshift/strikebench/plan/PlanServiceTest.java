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
    void duplicateDescriptorsCoexistAndCreateIsIdempotent() {
        Plan.View first = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-1", "AAPL", "INCOME", 30));
        Plan.View replay = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-1", "AAPL", "INCOME", 30));
        Plan.View second = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-2", "AAPL", "INCOME", 60));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(plans.list(null, Plan.MarketKind.DEMO, null, true)).hasSize(2);
        assertThat(first.context().inputHash()).hasSize(64);
        assertThatThrownBy(() -> plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-1", "AAPL", "HEDGE", 30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different plan request");
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
                plan.version(), "bearish", 45, null, null, null, null, null, java.util.Set.of("targetCents")));

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
    void optimisticVersionAndImmutableIdentityFailClosed() {
        Plan.View plan = plans.create(null, Plan.MarketKind.DEMO, null, null,
                create("req-lock", "AAPL", null, 30));
        Plan.View claimed = plans.claimIntent(null, plan.id(), new Plan.IntentRequest(plan.version(), "INCOME"));
        assertThat(claimed.intent()).isEqualTo("INCOME");
        assertThat(claimed.context().rev()).isEqualTo(2);

        assertThatThrownBy(() -> plans.claimIntent(null, plan.id(),
                new Plan.IntentRequest(claimed.version(), "HEDGE")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fixed");
        assertThatThrownBy(() -> plans.updateContext(null, plan.id(), new Plan.ContextUpdateRequest(
                plan.version(), "neutral", null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("another tab");
        assertThatThrownBy(() -> plans.setStage(null, plan.id(),
                new Plan.StageRequest(claimed.version(), "MANAGE_REVIEW")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unlocks");
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

    private static Plan.CreateRequest create(String requestId, String symbol, String intent, int horizon) {
        return new Plan.CreateRequest(requestId, symbol, intent, null, null, "bullish", horizon,
                25000L, "conservative", 0L, null, null);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
