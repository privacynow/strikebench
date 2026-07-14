package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaReconciliationTest {

    @Test
    void currentSchemaKeepsCanonicalOwnershipJsonTimePriceAndRetentionContracts() {
        try (Db db = TestDb.fresh()) {
            assertThat(db.query("SELECT table_name || '.' || column_name value "
                            + "FROM information_schema.columns WHERE table_schema='public' "
                            + "AND column_name IN ('owner_id','owner_key') ORDER BY 1",
                    r -> r.str("value"))).as("legacy owner aliases").isEmpty();

            assertThat(db.query("SELECT c.table_name value FROM information_schema.columns c "
                            + "WHERE c.table_schema='public' AND c.column_name='user_id' AND (c.is_nullable<>'NO' "
                            + "OR NOT EXISTS (SELECT 1 FROM pg_constraint fk "
                            + "JOIN pg_class child ON child.oid=fk.conrelid "
                            + "JOIN pg_class parent ON parent.oid=fk.confrelid "
                            + "JOIN pg_attribute a ON a.attrelid=child.oid AND a.attnum=ANY(fk.conkey) "
                            + "WHERE fk.contype='f' AND child.relname=c.table_name "
                            + "AND parent.relname='users' AND a.attname='user_id')) ORDER BY 1",
                    r -> r.str("value"))).as("nullable or unconstrained user ownership").isEmpty();

            assertThat(db.query("SELECT table_name || '.' || column_name value "
                            + "FROM information_schema.columns WHERE table_schema='public' "
                            + "AND column_name LIKE '%\\_json' ESCAPE '\\' AND data_type<>'jsonb' ORDER BY 1",
                    r -> r.str("value"))).as("JSON receipts stored as text").isEmpty();

            List<String> jsonbReceipts = db.query("SELECT table_name || '.' || column_name value "
                            + "FROM information_schema.columns WHERE table_schema='public' AND data_type='jsonb' "
                            + "AND (column_name IN ('state','config','receipt','request_snapshot','evaluation_snapshot') "
                            + "OR column_name LIKE '%\\_json' ESCAPE '\\') ORDER BY 1",
                    r -> r.str("value"));
            assertThat(jsonbReceipts).contains(
                    "workspace.state", "sim_session.config", "strategy_evaluation.receipt",
                    "plan_strategy_run.request_snapshot", "plan_candidate.evaluation_snapshot",
                    "trades.legs_json", "trades.entry_snapshot_json");

            assertThat(db.query("SELECT table_name || '.' || column_name value "
                            + "FROM information_schema.columns WHERE table_schema='public' "
                            + "AND data_type<>'timestamp with time zone' "
                            + "AND (column_name ~ '_at$' OR column_name IN ('as_of','ts')) ORDER BY 1",
                    r -> r.str("value"))).as("non-canonical instants").isEmpty();
            assertThat(db.query("SELECT table_name || '.' || column_name value "
                            + "FROM information_schema.columns WHERE table_schema='public' AND data_type='numeric' "
                            + "AND numeric_scale<>6 ORDER BY 1", r -> r.str("value")))
                    .as("non-canonical decimal prices").isEmpty();

            assertThat(db.query("SELECT table_name value FROM information_schema.tables "
                            + "WHERE table_schema='public' AND table_name IN ('plan_strategy_param')",
                    r -> r.str("value"))).isEmpty();
            assertThat(db.query("SELECT table_name || '.' || column_name value "
                            + "FROM information_schema.columns WHERE table_schema='public' AND "
                            + "((table_name='backtests' AND column_name IN ('request_json','report_json')) OR "
                            + "(table_name='strategy_evaluation' AND column_name LIKE '%\\_json' ESCAPE '\\') OR "
                            + "(table_name='portfolio_valuation' AND column_name='missing_marks') OR "
                            + "(table_name='sim_session' AND column_name='events')) ORDER BY 1",
                    r -> r.str("value"))).as("superseded blob columns").isEmpty();

            assertThat(db.query("SELECT indexname value FROM pg_indexes WHERE schemaname='public' "
                            + "AND indexname IN ('idx_plan_evidence_retention','idx_plan_strategy_run_retention',"
                            + "'idx_plan_ensemble_retention','idx_plan_outcome_run_retention',"
                            + "'idx_plan_outcome_comparison_retention','idx_plan_backtest_retention',"
                            + "'idx_ensemble_artifact_retention') ORDER BY 1", r -> r.str("value")))
                    .containsExactlyInAnyOrder(
                            "idx_plan_evidence_retention", "idx_plan_strategy_run_retention",
                            "idx_plan_ensemble_retention", "idx_plan_outcome_run_retention",
                            "idx_plan_outcome_comparison_retention", "idx_plan_backtest_retention",
                            "idx_ensemble_artifact_retention");
        }
    }
}
