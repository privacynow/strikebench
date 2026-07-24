package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountRiskContextTest {
    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    @Test void malformedPersistedLimitsFailClosedInsteadOfRemovingTheCap() {
        db = TestDb.fresh();
        db.exec("INSERT INTO settings(k,v,updated_at) VALUES('risk_context:local','{not-json}',now())");

        assertThatThrownBy(() -> AccountRiskContext.load(db, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk limits are invalid");
    }

    @Test void emptyAndValidContextsRemainReadable() {
        db = TestDb.fresh();
        assertThat(AccountRiskContext.load(db, null).isEmpty()).isTrue();

        AccountRiskContext saved = new AccountRiskContext(10_000_000L, 5_000_000L,
                null, null, 100_000L);
        AccountRiskContext.save(db, null, saved);
        assertThat(AccountRiskContext.load(db, null)).isEqualTo(saved);
    }
}
