package io.liftandshift.strikebench.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestDbLifecycleTest {
    @Test
    void closingAFreshHandleDropsItsPhysicalDatabaseImmediatelyAndOnlyOnce() {
        int before = TestDb.retainedCount();
        var db = TestDb.fresh();
        assertThat(TestDb.retainedCount()).isEqualTo(before + 1);
        String synchronousCommit = db.with(c -> {
            try (var statement = c.createStatement();
                 var result = statement.executeQuery("SHOW synchronous_commit")) {
                result.next();
                return result.getString(1);
            }
        });
        assertThat(synchronousCommit).isEqualTo("off");

        db.close();
        db.close();

        assertThat(TestDb.retainedCount()).isEqualTo(before);
    }
}
