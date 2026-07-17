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

/** Ensures lifecycle progress describes declared facts, never the mere presence of a goal. */
final class PlanDeclarationLifecycleTest {
    private Db db;
    private PlanService plans;

    @BeforeEach
    void setUp() {
        db = TestDb.fresh();
        plans = new PlanService(db, Clock.fixed(Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void close() {
        if (db != null) db.close();
    }

    @Test
    void goalOnlyPlanStartsAtUnderstandWithNullAssumptions() {
        Plan.View plan = plans.create("local", Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("no-defaults", "AAPL", "DIRECTIONAL", null, null,
                        null, null, null, null, null, null, null, null));

        assertThat(plan.furthestStage()).isEqualTo(Plan.Stage.UNDERSTAND);
        assertThat(plan.context().thesis()).isNull();
        assertThat(plan.context().horizonDays()).isNull();
        assertThat(plan.context().riskMode()).isNull();
    }

    @Test
    void exactStagedDeclarationStartsReadyForStrategy() {
        Plan.View plan = plans.create("local", Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("exact-stage", "TSLA", "DIRECTIONAL", null, null,
                        "bearish", 23, 25000L, "balanced", null, null, null, null));

        assertThat(plan.furthestStage()).isEqualTo(Plan.Stage.STRATEGY);
        assertThat(plan.context().thesis()).isEqualTo("bearish");
        assertThat(plan.context().horizonDays()).isEqualTo(23);
        assertThat(plan.context().targetCents()).isEqualTo(25000L);
        assertThat(plan.context().riskMode()).isEqualTo("balanced");
    }

    @Test
    void partialContextEditsRemainAtUnderstandUntilDeclarationIsComplete() {
        Plan.View plan = plans.create("local", Plan.MarketKind.DEMO, null, null,
                new Plan.CreateRequest("partial-stage", "QQQ", "DIRECTIONAL", null, null,
                        null, null, null, null, null, null, null, null));
        Plan.View partial = plans.updateContext("local", plan.id(), new Plan.ContextUpdateRequest(
                plan.version(), "bullish", 10, null, null, null, null, null, null, null));
        Plan.View complete = plans.updateContext("local", plan.id(), new Plan.ContextUpdateRequest(
                partial.version(), null, null, null, "conservative", null, null, null, null, null));

        assertThat(partial.furthestStage()).isEqualTo(Plan.Stage.UNDERSTAND);
        assertThat(complete.furthestStage()).isEqualTo(Plan.Stage.STRATEGY);
    }
}
