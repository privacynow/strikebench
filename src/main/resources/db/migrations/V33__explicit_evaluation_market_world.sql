-- Evaluation identity uses the same explicit market token at rest and in requests.
-- Existing NULL rows are observed-market evaluations from the former storage convention.
UPDATE strategy_evaluation SET world_id = 'observed' WHERE world_id IS NULL;

ALTER TABLE strategy_evaluation ALTER COLUMN world_id SET NOT NULL;

COMMENT ON COLUMN strategy_evaluation.world_id IS
    'Explicit market world in which this evaluation was priced; observed uses the literal observed token.';
