-- Retention is evidence-aware and runs with indexed age/state predicates. These indexes do not
-- define policy; ArtifactRetentionService owns the preservation rules and performs one transaction.

CREATE INDEX idx_plan_evidence_retention
  ON plan_evidence(created_at) WHERE state IN ('STALE','BLOCKED');
CREATE INDEX idx_plan_strategy_run_retention
  ON plan_strategy_run(created_at) WHERE state IN ('STALE','BLOCKED');
CREATE INDEX idx_plan_ensemble_retention
  ON plan_ensemble(created_at) WHERE state IN ('STALE','BLOCKED');
CREATE INDEX idx_plan_outcome_run_retention
  ON plan_outcome_run(created_at) WHERE state IN ('STALE','BLOCKED');
CREATE INDEX idx_plan_outcome_comparison_retention
  ON plan_outcome_comparison(created_at) WHERE state IN ('STALE','BLOCKED');
CREATE INDEX idx_plan_backtest_retention
  ON plan_backtest(created_at) WHERE state IN ('STALE','BLOCKED');
CREATE INDEX idx_ensemble_artifact_retention
  ON ensemble_artifact(created_at) WHERE pinned=0;
