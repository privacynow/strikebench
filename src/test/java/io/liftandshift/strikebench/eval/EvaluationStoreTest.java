package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.recommend.Candidate;
import io.liftandshift.strikebench.recommend.LegView;
import io.liftandshift.strikebench.support.TestDb;
import io.liftandshift.strikebench.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Persistence of evaluations: typed rank columns + JSONB sub-profiles round-trip. */
class EvaluationStoreTest {

    private Db db;

    @AfterEach void close() { if (db != null) db.close(); }

    private StrategyEvaluation anEvaluation() {
        List<LegView> legs = List.of(
                new LegView("BUY", "CALL", "250", "2026-08-21", 1, "4.00"),
                new LegView("SELL", "CALL", "255", "2026-08-21", 1, "2.00"));
        Candidate c = new Candidate("DEBIT_CALL_SPREAD", "Bull call spread", "debit_vertical", "BUY 250C / SELL 255C",
                legs, 1, -20_000L, 30_000L, 20_000L, List.of("252.00"),
                0.45, 2_000L, 0.70, "DELAYED", List.of(), 55.0, 0.6,
                "why", "up", "down", "inval", "plain", "DIRECTIONAL", List.of("DIRECTIONAL"),
                0.30, null, null, null, false, null, null);
        EvalContext ctx = new EvalContext("AAPL", 25_200L, 30, 0.30, 0.25,
                List.of(0.2, 0.25, 0.3, 0.35, 0.4, 0.28, 0.31, 0.27, 0.33, 0.29, 0.26, 0.32),
                10_000_000L, true, 65, 0.04,
                io.liftandshift.strikebench.model.DataEvidence.of("treasury", io.liftandshift.strikebench.model.Freshness.EOD));
        return new StrategyEvaluator().evaluate(c,
                new StrategySpec("AAPL", "DEBIT_CALL_SPREAD", "DIRECTIONAL", "month", "bullish", "balanced", "decision"),
                ctx);
    }

    @Test void savesTypedColumnsAndJsonbSubProfiles() {
        db = TestDb.fresh();
        EvaluationStore store = new EvaluationStore(db);
        StrategyEvaluation e = anEvaluation();

        store.save(e, null); // local/anonymous (user_id nullable)

        var row = db.query("""
                SELECT symbol, strategy, score, ev_cents, max_loss_cents, capital_economic_cents,
                       evidence_level, risk_json, economics_json, explanation_json
                FROM strategy_evaluation WHERE id=?""",
                r -> Map.of(
                        "symbol", r.str("symbol"),
                        "strategy", r.str("strategy"),
                        "score", r.dbl("score"),
                        "ev", r.lng("ev_cents"),
                        "maxLoss", r.lng("max_loss_cents"),
                        "economic", r.lng("capital_economic_cents"),
                        "evidence", r.str("evidence_level"),
                        "riskJson", r.str("risk_json"),
                        "economicsJson", r.str("economics_json"),
                        "explJson", r.str("explanation_json")),
                e.id()).getFirst();

        assertThat(row.get("symbol")).isEqualTo("AAPL");
        assertThat(row.get("strategy")).isEqualTo("DEBIT_CALL_SPREAD");
        assertThat((double) row.get("score")).isEqualTo(e.decisionScore());
        assertThat((long) row.get("maxLoss")).isEqualTo(20_000L);
        assertThat((long) row.get("economic")).isEqualTo(20_000L);
        assertThat(row.get("evidence")).isEqualTo(e.evidenceLevel().name());

        // JSONB round-trips as real JSON with the producer detail inside.
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = Json.read((String) row.get("riskJson"), Map.class);
        assertThat(risk).containsKey("scenarios");
        assertThat((List<?>) risk.get("scenarios")).hasSize(7);
        @SuppressWarnings("unchecked")
        Map<String, Object> economics = Json.read((String) row.get("economicsJson"), Map.class);
        assertThat(economics).containsKeys("verdict", "marketEvAfterCostsCents", "realizedVolEvAfterCostsCents");
        @SuppressWarnings("unchecked")
        Map<String, Object> expl = Json.read((String) row.get("explJson"), Map.class);
        assertThat(expl).containsKeys("assumptions", "failureModes");
    }

    @Test void listsRecentForTheUser() {
        db = TestDb.fresh();
        EvaluationStore store = new EvaluationStore(db);
        store.saveAll(List.of(anEvaluation(), anEvaluation()), null);

        var recent = store.recent(null, 10);
        assertThat(recent).hasSize(2);
        assertThat(recent.getFirst()).containsEntry("symbol", "AAPL").containsKey("evidenceLevel");
    }
}
