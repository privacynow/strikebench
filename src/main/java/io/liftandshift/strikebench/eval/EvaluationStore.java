package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.OwnerScope;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists {@link StrategyEvaluation}s to the {@code strategy_evaluation} table: typed columns for
 * what we rank/query, plus one immutable JSONB producer receipt. This is what lets recommendations
 * be reviewed later and (Phase 4) calibrated against their outcomes.
 */
public final class EvaluationStore {

    private static final String INSERT_SQL = """
            INSERT INTO strategy_evaluation
              (id, user_id, symbol, strategy, objective, score, ev_cents, roc, ann_roc, pop,
               assignment_prob, capital_incremental_cents, capital_economic_cents, max_loss_cents,
               tail_loss_cents, evidence_level, receipt)
            VALUES (?,?,?,?,?, ?,?,?,?,?, ?,?,?,?, ?,?, ?::jsonb)
            """;

    private final Db db;

    public EvaluationStore(Db db) { this.db = db; }

    /** The bind values for one row, in INSERT_SQL column order. */
    private static Object[] params(StrategyEvaluation e, String userId) {
        return new Object[] {
                e.id(), OwnerScope.id(userId), e.symbol(), e.family(),
                e.spec() == null ? null : e.spec().objective(),
                e.decisionScore(), e.evCents(), e.roc(), e.annRoc(), e.pop(),
                e.assignmentProb(), e.capitalIncrementalCents(), e.capitalEconomicCents(), e.maxLossCents(),
                e.tailLossCents(), e.evidenceLevel().name(), Json.write(e) };
    }

    /** Saves one evaluation for a canonical user; null callers resolve to the explicit local owner. */
    public void save(StrategyEvaluation e, String userId) {
        db.tx(c -> {
            OwnerScope.ensure(c, userId);
            Db.execOn(c, INSERT_SQL, params(e, userId));
            return null;
        });
    }

    /**
     * Saves a whole ranked competition as one batched transaction — a single pooled connection,
     * one round-trip via {@code executeBatch}, one commit — instead of N separate connection
     * checkouts (the old per-row save() in a loop).
     */
    public void saveAll(List<StrategyEvaluation> evals, String userId) {
        if (evals == null || evals.isEmpty()) return;
        if (evals.size() == 1) { save(evals.getFirst(), userId); return; }
        db.tx(c -> {
            OwnerScope.ensure(c, userId);
            try (PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
                for (StrategyEvaluation e : evals) {
                    Object[] p = params(e, userId);
                    for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Recent evaluations for a user (summary rows for a history list). Newest first. */
    public List<Map<String, Object>> recent(String userId, int limit) {
        return db.query("""
                SELECT id, symbol, strategy, objective, score, evidence_level, max_loss_cents, asof
                FROM strategy_evaluation
                WHERE user_id=?::text
                ORDER BY asof DESC LIMIT ?
                """, EvaluationStore::summaryRow, OwnerScope.id(userId), limit);
    }

    private static Map<String, Object> summaryRow(Db.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.str("id"));
        m.put("symbol", r.str("symbol"));
        m.put("strategy", r.str("strategy"));
        m.put("objective", r.str("objective"));
        m.put("score", r.dblOrNull("score"));
        m.put("evidenceLevel", r.str("evidence_level"));
        m.put("maxLossCents", r.lngOrNull("max_loss_cents"));
        m.put("asof", r.str("asof"));
        return m;
    }
}
