-- A Plan context can be examined against more than one analysis dataset without changing its
-- execution market. Keep each outcome and replay attached to the dataset that produced it.

ALTER TABLE plan_outcome_run
    ADD COLUMN dataset_id TEXT REFERENCES dataset(id) ON DELETE SET NULL;

UPDATE plan_outcome_run por
SET dataset_id = pe.dataset_id
FROM plan_ensemble pe
WHERE por.ensemble_id = pe.id;

ALTER TABLE plan_backtest
    ADD COLUMN dataset_id TEXT REFERENCES dataset(id) ON DELETE SET NULL;

CREATE INDEX idx_plan_outcome_dataset
    ON plan_outcome_run(plan_id, context_rev, dataset_id, basis, created_at DESC);

CREATE INDEX idx_plan_backtest_dataset
    ON plan_backtest(plan_id, context_rev, dataset_id, created_at DESC);
