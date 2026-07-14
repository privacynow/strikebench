package io.liftandshift.strikebench.db;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactRetentionServiceTest {
    private static final String OLD = "2025-01-01T00:00:00Z";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-14T18:00:00Z"), ZoneOffset.UTC);

    private Db db;

    @BeforeEach void setUp() { db = TestDb.fresh(); }
    @AfterEach void tearDown() { db.close(); }

    @Test
    void prunesOnlySupersededMutableWorkAndPreservesDecisionRehearsalAndCalibrationEvidence() {
        plan("p_stale", "ACTIVE");
        context("p_stale");
        artifact("fp_stale", false);
        ensemble("pe_stale", "p_stale", "fp_stale", "STALE");
        strategyRun("run_stale", "p_stale", "STALE");
        candidate("candidate_stale", "p_stale", "run_stale", false);
        db.exec("INSERT INTO plan_evidence(id,plan_id,context_rev,basis,as_of,engine_version,input_hash,"
                        + "evidence_provenance,state,created_at) VALUES('evidence_stale','p_stale',1,'DEMO_HISTORY',"
                        + "?,'evidence-1','evidence-hash','DEMO_FIXTURE','STALE',?)", OLD, OLD);
        db.exec("INSERT INTO plan_outcome_run(id,plan_id,context_rev,candidate_id,ensemble_id,basis,interpretation,"
                        + "input_hash,engine_version,state,created_at) VALUES('outcome_stale','p_stale',1,"
                        + "'candidate_stale','pe_stale','PARAMETRIC','old run','outcome-hash','outcome-1','STALE',?)", OLD);
        db.exec("INSERT INTO plan_outcome_comparison(id,plan_id,context_rev,ensemble_id,ensemble_fingerprint,basis,"
                        + "interpretation,fairness,input_hash,engine_version,state,created_at) VALUES('comparison_stale',"
                        + "'p_stale',1,'pe_stale','fp_stale','PARAMETRIC','old comparison','same paths',"
                        + "'comparison-hash','comparison-1','STALE',?)", OLD);
        db.exec("INSERT INTO plan_outcome_comparison_item(comparison_id,item_key,candidate_id,rank_number,strategy,"
                        + "display_name,qty) VALUES('comparison_stale','candidate_stale','candidate_stale',1,"
                        + "'LONG_CALL','Long call',1)");
        db.exec("INSERT INTO plan_backtest(id,plan_id,context_rev,basis,as_of,input_hash,engine_version,state,created_at) "
                + "VALUES('plan_bt_stale','p_stale',1,'MODELED',?,'plan-bt-hash','bt-1','STALE',?)", OLD, OLD);

        // A current run may be old but remains the active Plan truth.
        plan("p_current", "ACTIVE");
        context("p_current");
        artifact("fp_current", false);
        ensemble("pe_current", "p_current", "fp_current", "CURRENT");
        strategyRun("run_current", "p_current", "CURRENT");
        db.exec("INSERT INTO plan_evidence(id,plan_id,context_rev,basis,as_of,engine_version,input_hash,"
                        + "evidence_provenance,state,created_at) VALUES('evidence_current','p_current',1,'DEMO_HISTORY',"
                        + "?,'evidence-1','current-hash','DEMO_FIXTURE','CURRENT',?)", OLD, OLD);

        // Frozen Plan evidence is permanent even when a row is labeled stale.
        plan("p_decided", "DECIDED_CASH");
        context("p_decided");
        artifact("fp_decided", true);
        ensemble("pe_decided", "p_decided", "fp_decided", "STALE");
        db.exec("INSERT INTO plan_decision(id,plan_id,context_rev,decision_seq,ensemble_id,action,quote_as_of,economic_verdict,"
                        + "evidence_provenance,model_version,review_horizon_days,created_at) VALUES('decision_frozen',"
                        + "'p_decided',1,1,'pe_decided','CASH',?,'MIXED','DEMO_FIXTURE','decision-1',30,?)", OLD, OLD);

        // A rehearsal source is a reproducibility receipt and protects both the Plan ensemble and blob.
        plan("p_rehearsal", "ACTIVE");
        context("p_rehearsal");
        artifact("fp_rehearsal", false);
        ensemble("pe_rehearsal", "p_rehearsal", "fp_rehearsal", "STALE");
        db.exec("INSERT INTO sim_session(id,name,user_id,config,status,created_at) "
                + "VALUES('sim_rehearsal','Rehearsal','local','{}'::jsonb,'FINISHED',?)", OLD);
        db.exec("INSERT INTO sim_replay_source(sim_session_id,plan_id,ensemble_id,fingerprint,path_index,"
                        + "selection_kind,symbol,model_version,n_steps,step_seconds,rate_annual,spot_path,iv_path,created_at) "
                        + "VALUES('sim_rehearsal','p_rehearsal','pe_rehearsal','fp_rehearsal',0,'TYPICAL','AAPL',"
                        + "'paths-1',2,60,0.04,?,?,?)", new byte[]{1}, new byte[]{2}, OLD);

        backtest("bt_orphan");
        backtest("bt_linked");
        db.exec("INSERT INTO plan_backtest(id,plan_id,context_rev,backtest_id,basis,as_of,input_hash,engine_version,"
                + "state,created_at) VALUES('plan_bt_current','p_current',1,'bt_linked','MODELED',?,"
                + "'linked-bt-hash','bt-1','CURRENT',?)", OLD, OLD);

        evaluation("eval_orphan");
        recommendation("reco_orphan", "eval_orphan", null);
        evaluation("eval_resolved");
        recommendation("reco_resolved", "eval_resolved", "WIN");

        var service = new ArtifactRetentionService(db, CLOCK,
                new ArtifactRetentionService.Policy(true, 30, 90, 0, 24));
        var result = service.runOnce();

        assertThat(result).isEqualTo(new ArtifactRetentionService.CleanupResult(
                1, 1, 1, 1, 1, 1, 1));
        assertThat(ids("plan_evidence")).containsExactly("evidence_current");
        assertThat(ids("plan_strategy_run")).containsExactly("run_current");
        assertThat(ids("plan_ensemble")).containsExactlyInAnyOrder("pe_current", "pe_decided", "pe_rehearsal");
        assertThat(ids("ensemble_artifact")).containsExactlyInAnyOrder("fp_current", "fp_decided", "fp_rehearsal");
        assertThat(ids("backtests")).containsExactlyInAnyOrder("bt_linked", "bt_orphan");
        assertThat(ids("recommendation")).containsExactlyInAnyOrder("reco_orphan", "reco_resolved");
        assertThat(ids("strategy_evaluation")).containsExactlyInAnyOrder("eval_orphan", "eval_resolved");
        assertThat(ids("plan_decision")).containsExactly("decision_frozen");
        assertThat(ids("sim_replay_source")).containsExactly("sim_rehearsal");

        assertThat(service.runOnce().total()).isZero();
    }

    @Test
    void policyHasConservativeFloorsAndKeepsOrphansLongerThanPlanIntermediates() {
        var cfg = new AppConfig(Map.of(
                "STALE_PLAN_ARTIFACT_RETENTION_DAYS", "45",
                "ORPHAN_ENSEMBLE_RETENTION_DAYS", "10",
                "ARTIFACT_RETENTION_INITIAL_DELAY_SECONDS", "-1",
                "ARTIFACT_RETENTION_INTERVAL_HOURS", "0"));
        var policy = ArtifactRetentionService.Policy.from(cfg);
        assertThat(policy.stalePlanDays()).isEqualTo(45);
        assertThat(policy.orphanEnsembleDays()).isEqualTo(45);
        assertThat(policy.initialDelaySeconds()).isZero();
        assertThat(policy.intervalHours()).isEqualTo(1);
        assertThat(policy.intervalSeconds()).isEqualTo(3600);
    }

    private void plan(String id, String status) {
        db.exec("INSERT INTO plans(id,user_id,symbol,market_kind,status,active_stage,created_at,updated_at) "
                + "VALUES(?,'local','AAPL','DEMO',?,'UNDERSTAND',?,?)", id, status, OLD, OLD);
    }

    private void context(String planId) {
        db.exec("INSERT INTO plan_context_revision(id,plan_id,rev,horizon_days,input_hash,engine_version,created_at) "
                + "VALUES(?, ?,1,30,?,'context-1',?)", "ctx_" + planId, planId, "hash_" + planId, OLD);
        db.exec("UPDATE plans SET active_context_rev=1 WHERE id=?", planId);
    }

    private void artifact(String fingerprint, boolean pinned) {
        db.exec("INSERT INTO ensemble_artifact(fingerprint,model_version,basis,n_paths,n_steps,codec,raw_bytes,"
                        + "spot_matrix,iv_path,rate_annual,step_seconds,source_content_hash,pinned,created_at) "
                        + "VALUES(?,'paths-1','PARAMETRIC',2,2,'raw',2,?,?,0.04,60,?, ?,?)",
                fingerprint, new byte[]{1}, new byte[]{2}, "source_" + fingerprint, pinned, OLD);
    }

    private void ensemble(String id, String planId, String fingerprint, String state) {
        db.exec("INSERT INTO plan_ensemble(id,plan_id,context_rev,fingerprint,model_version,anchor_spot_cents,"
                        + "anchor_source,anchor_freshness,as_of,input_hash,state,spec_model,spec_shape,spec_horizon_days,"
                        + "spec_steps_per_day,spec_drift_annual,spec_vol_annual,spec_jumps_per_year,spec_jump_mean,"
                        + "spec_jump_vol,spec_tail_nu,spec_seed,spec_paths,iv_start,iv_longrun,iv_shape,created_at) "
                        + "VALUES(?,?,1,?,'paths-1',10000,'fixture','FIXTURE',?,? ,?,'GBM','BASE',30,1,0,0.2,"
                        + "0,0,0,8,42,2,0.2,0.2,'FLAT',?)",
                id, planId, fingerprint, OLD, "input_" + id, state, OLD);
    }

    private void strategyRun(String id, String planId, String state) {
        db.exec("INSERT INTO plan_strategy_run(id,plan_id,context_rev,run_kind,scope_kind,horizon,risk_mode,intent,"
                        + "input_hash,engine_version,state,created_at) VALUES(?,?,1,'COMPETITION','PLAN','month',"
                        + "'balanced','DIRECTIONAL',?,'strategy-1',?,?)", id, planId, "input_" + id, state, OLD);
    }

    private void candidate(String id, String planId, String runId, boolean selected) {
        db.exec("INSERT INTO plan_candidate(id,plan_id,context_rev,family,input_hash,state,selected,run_id,created_at) "
                        + "VALUES(?,?,1,'LONG_CALL',?,'STALE',?,?,?)",
                id, planId, "input_" + id, selected, runId, OLD);
    }

    private void backtest(String id) {
        db.exec("INSERT INTO backtests(id,user_id,created_at,run_kind,symbol,strategy,from_date,to_date,pricing_mode,"
                        + "confidence,days_covered,sample_size,starting_cents,ending_cents,max_drawdown_pct,"
                        + "demo_underlying,disclaimer) VALUES(?,'local',?,'SINGLE','AAPL','LONG_CALL','2024-01-01',"
                        + "'2024-12-31','MODELED','low',250,1,100000,101000,0.01,1,'Educational')", id, OLD);
    }

    private void evaluation(String id) {
        db.exec("INSERT INTO strategy_evaluation(id,user_id,symbol,strategy,receipt,created_at) "
                + "VALUES(?,'local','AAPL','LONG_CALL','{}'::jsonb,?)", id, OLD);
    }

    private void recommendation(String id, String evaluationId, String outcome) {
        db.exec("INSERT INTO recommendation(id,user_id,evaluation_id,outcome_status,created_at) "
                + "VALUES(?,'local',?,?,?)", id, evaluationId, outcome, OLD);
    }

    private java.util.List<String> ids(String table) {
        String column = table.equals("ensemble_artifact") ? "fingerprint"
                : table.equals("sim_replay_source") ? "sim_session_id" : "id";
        return db.query("SELECT " + column + " value FROM " + table + " ORDER BY " + column,
                r -> r.str("value"));
    }
}
