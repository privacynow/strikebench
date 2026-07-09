package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Ids;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closes the loop: records which evaluations were actually surfaced as recommendations, captures
 * their eventual outcomes, and reports CALIBRATION — how well the model's predicted probability of
 * profit matched the realized win rate. This is what makes "recommendations get more accurate over
 * time" measurable rather than aspirational; it reads the {@code recommendation} + evaluation tables.
 */
public final class CalibrationService {

    private final Db db;
    private final Clock clock;

    public CalibrationService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Records that an evaluation was surfaced to a user (the calibration sample). */
    public String record(String evaluationId, String userId, String context) {
        String id = Ids.newId("rec");
        db.exec("INSERT INTO recommendation(id, user_id, evaluation_id, context) VALUES (?,?,?,?)",
                id, userId, evaluationId, context);
        return id;
    }

    /** Resolves a recommendation's outcome (WIN/LOSS/EXPIRED/ASSIGNED/CLOSED + realized P/L). */
    public void resolveOutcome(String recommendationId, String status, Long pnlCents) {
        // outcome_asof is TIMESTAMPTZ (new-schema table) — bind a temporal type, not an ISO string.
        db.exec("UPDATE recommendation SET outcome_status=?, outcome_pnl_cents=?, outcome_asof=? WHERE id=?",
                status, pnlCents, java.time.OffsetDateTime.now(clock), recommendationId);
    }

    /**
     * Reliability report: for RESOLVED recommendations, bucket by the model's predicted probability
     * of profit and compare to the realized win rate. Well-calibrated == predicted ≈ realized.
     */
    public Map<String, Object> report(String userId) {
        var rows = db.query("""
                SELECT e.pop AS pop, e.score AS score, r.outcome_pnl_cents AS pnl
                FROM recommendation r JOIN strategy_evaluation e ON e.id = r.evaluation_id
                WHERE r.outcome_pnl_cents IS NOT NULL
                  AND (r.user_id = ?::text OR (?::text IS NULL AND r.user_id IS NULL))
                """, r -> new double[]{
                        r.dblOrNull("pop") == null ? -1 : r.dbl("pop"),
                        r.dblOrNull("score") == null ? -1 : r.dbl("score"),
                        r.lng("pnl")
                }, userId, userId);

        int n = rows.size();
        long wins = rows.stream().filter(x -> x[2] > 0).count();
        long totalPnl = rows.stream().mapToLong(x -> (long) x[2]).sum();

        // Predicted-POP reliability buckets.
        int[] bucketN = new int[5];
        double[] bucketPredSum = new double[5];
        int[] bucketWins = new int[5];
        for (double[] x : rows) {
            if (x[0] < 0) continue; // no predicted POP
            int b = Math.min(4, (int) (x[0] * 5)); // 0..1 -> 0..4
            bucketN[b]++;
            bucketPredSum[b] += x[0];
            if (x[2] > 0) bucketWins[b]++;
        }
        List<Map<String, Object>> reliability = new ArrayList<>();
        for (int b = 0; b < 5; b++) {
            if (bucketN[b] == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", (b * 20) + "–" + (b * 20 + 20) + "%");
            row.put("n", bucketN[b]);
            row.put("predictedWinRate", round(bucketPredSum[b] / bucketN[b]));
            row.put("realizedWinRate", round((double) bucketWins[b] / bucketN[b]));
            reliability.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resolved", n);
        out.put("overallWinRate", n == 0 ? null : round((double) wins / n));
        out.put("totalPnlCents", totalPnl);
        out.put("reliability", reliability);
        out.put("note", n == 0
                ? "No resolved recommendations yet — calibration appears once outcomes are recorded."
                : "Well-calibrated means predicted ≈ realized in each bucket. Model outputs, not a forecast.");
        return out;
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
