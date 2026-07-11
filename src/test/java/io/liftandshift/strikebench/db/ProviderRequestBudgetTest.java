package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRequestBudgetTest {
    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void allowanceIsDurableAcrossServiceInstances() {
        db = TestDb.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC);
        var first = new ProviderRequestBudget(db, clock);
        assertThat(first.acquire("alphaVantage", 2).remaining()).isEqualTo(1);

        var afterRestart = new ProviderRequestBudget(db, clock);
        assertThat(afterRestart.acquire("alphavantage", 2).remaining()).isZero();
        assertThatThrownBy(() -> afterRestart.acquire("alphavantage", 2))
                .isInstanceOf(ProviderRequestBudget.Exhausted.class)
                .hasMessageContaining("exhausted for today");
        assertThat(afterRestart.usage("alphavantage", 2).used()).isEqualTo(2);
    }

    @Test
    void unknownPlanLimitDoesNotInventAnAllowance() {
        db = TestDb.fresh();
        var budget = new ProviderRequestBudget(db, Clock.systemUTC());
        assertThat(budget.acquire("polygon", 0).remaining()).isEqualTo(Integer.MAX_VALUE);
        assertThat(db.query("SELECT count(*) c FROM provider_request_budget", r -> r.lng("c")).getFirst()).isZero();
    }
}
