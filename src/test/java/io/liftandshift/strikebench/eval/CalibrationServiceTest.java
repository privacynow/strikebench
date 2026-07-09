package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The calibration loop: predicted probability of profit vs realized win rate. */
class CalibrationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("UTC"));

    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    private void insertEval(String id, double pop) {
        db.exec("INSERT INTO strategy_evaluation(id, symbol, strategy, pop, score) VALUES (?,?,?,?,?)",
                id, "AAPL", "DEBIT_CALL_SPREAD", pop, 50.0);
    }

    private void resolve(CalibrationService cal, String evalId, long pnlCents) {
        String recId = cal.record(evalId, null, "test");
        cal.resolveOutcome(recId, pnlCents > 0 ? "WIN" : "LOSS", pnlCents);
    }

    @SuppressWarnings("unchecked")
    @Test void reportBucketsPredictedAgainstRealized() {
        db = TestDb.fresh();
        CalibrationService cal = new CalibrationService(db, CLOCK);

        // 80–100% predicted bucket: 3 recs, 2 wins -> realized 0.667.
        insertEval("e1", 0.80); insertEval("e2", 0.82); insertEval("e3", 0.85);
        resolve(cal, "e1", 500); resolve(cal, "e2", 300); resolve(cal, "e3", -200);
        // 20–40% predicted bucket: 2 recs, 0 wins -> realized 0.
        insertEval("e4", 0.30); insertEval("e5", 0.32);
        resolve(cal, "e4", -100); resolve(cal, "e5", -150);

        Map<String, Object> rep = cal.report(null);
        assertThat(rep.get("resolved")).isEqualTo(5);
        assertThat((double) rep.get("overallWinRate")).isEqualTo(0.4);  // 2 of 5
        assertThat((long) rep.get("totalPnlCents")).isEqualTo(500 + 300 - 200 - 100 - 150);

        List<Map<String, Object>> reliability = (List<Map<String, Object>>) rep.get("reliability");
        Map<String, Object> hi = reliability.stream()
                .filter(x -> ((String) x.get("bucket")).startsWith("80")).findFirst().orElseThrow();
        assertThat(hi.get("n")).isEqualTo(3);
        assertThat((double) hi.get("realizedWinRate")).isEqualTo(0.667);
        assertThat((double) hi.get("predictedWinRate")).isCloseTo(0.823, org.assertj.core.data.Offset.offset(0.01));

        Map<String, Object> lo = reliability.stream()
                .filter(x -> ((String) x.get("bucket")).startsWith("20")).findFirst().orElseThrow();
        assertThat(lo.get("n")).isEqualTo(2);
        assertThat((double) lo.get("realizedWinRate")).isEqualTo(0.0);
    }

    @Test void emptyWhenNothingResolved() {
        db = TestDb.fresh();
        Map<String, Object> rep = new CalibrationService(db, CLOCK).report(null);
        assertThat(rep.get("resolved")).isEqualTo(0);
        assertThat(rep.get("overallWinRate")).isNull();
        assertThat((String) rep.get("note")).contains("No resolved recommendations");
    }
}
