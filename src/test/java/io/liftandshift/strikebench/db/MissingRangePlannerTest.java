package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MissingRangePlannerTest {
    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    @Test
    void plansOnlyMissingTradingSessionsAndThenBecomesComplete() {
        db = TestDb.fresh();
        LocalDate from = LocalDate.parse("2026-06-01"), to = LocalDate.parse("2026-06-05");
        for (String d : new String[]{"2026-06-01", "2026-06-02", "2026-06-04", "2026-06-05"}) {
            db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed) VALUES ('AAPL',?,100,'test',1)",
                    LocalDate.parse(d));
        }
        MissingRangePlanner planner = new MissingRangePlanner(db);
        var plan = planner.plan("AAPL", from, to, "test");
        assertThat(plan.missingSessions()).isEqualTo(1);
        assertThat(plan.ranges()).hasSize(1);
        assertThat(plan.ranges().getFirst().to()).isEqualTo(LocalDate.parse("2026-06-03"));
        assertThat(plan.ranges().getFirst().from()).isEqualTo(from); // three-session revision overlap

        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed) VALUES ('AAPL',?,101,'test',1)",
                LocalDate.parse("2026-06-03"));
        assertThat(planner.plan("AAPL", from, to, "test").complete()).isTrue();
    }

    @Test
    void demoRowsNeverCountAsObservedCoverage() {
        db = TestDb.fresh();
        db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed,dataset_id) "
                + "VALUES ('AAPL','2026-06-01',100,'fixture',0,'demo-fixture')");
        var plan = new MissingRangePlanner(db).plan("AAPL", LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-01"), "fixture");
        assertThat(plan.missingSessions()).isEqualTo(1);
    }

    @Test
    void coverageFromAnotherSourceCannotCompleteThisSourcesPlan() {
        db = TestDb.fresh();
        LocalDate from = LocalDate.parse("2026-06-01"), to = LocalDate.parse("2026-06-05");
        for (String d : new String[]{"2026-06-01", "2026-06-02", "2026-06-03", "2026-06-04", "2026-06-05"}) {
            db.exec("INSERT INTO underlying_bar(symbol,d,close,source,observed) VALUES ('AAPL',?,100,'yahoo',1)",
                    LocalDate.parse(d));
        }
        var polygon = new MissingRangePlanner(db).plan("AAPL", from, to, "polygon");
        assertThat(polygon.existingSessions()).isZero();
        assertThat(polygon.missingSessions()).isEqualTo(5);
        assertThat(new MissingRangePlanner(db).plan("AAPL", from, to, "yahoo").complete()).isTrue();
    }
}
