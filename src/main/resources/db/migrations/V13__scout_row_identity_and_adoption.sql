-- Audit §8.2: a Scout row must open the EXACT package it showed.
--
-- Two forward-only additions make that possible without a second analysis engine:
--
-- 1. strategy_evaluation.world_id — the market lane an evaluation was priced in. Until now the
--    scan path simply refused to persist anything produced inside a generated market, because the
--    table could not say which market a row belonged to and an unmarked simulated evaluation would
--    have read as observed evidence. With the lane recorded, the opportunity scan can persist every
--    row it surfaced in EITHER lane, and adoption can require the lane to match: an observed Plan
--    can never adopt a package priced in a generated world, and vice versa. NULL means observed,
--    which is exactly what every already-stored row is.
--
-- 2. plan_candidate.source_evaluation_id — the immutable evaluation a Plan structure was adopted
--    from. It is the receipt that lets a restored Plan still say which scanned row it came from
--    rather than looking like a package that appeared from nowhere.
--
-- Forward-only: V1-V12 are applied to real databases and are never edited.
ALTER TABLE strategy_evaluation ADD COLUMN world_id text;

COMMENT ON COLUMN strategy_evaluation.world_id IS
    'Market lane this evaluation was priced in; NULL = observed. Adoption requires an exact match.';

CREATE INDEX idx_eval_user_world ON strategy_evaluation (user_id, world_id, asof DESC);

ALTER TABLE plan_candidate ADD COLUMN source_evaluation_id text;

COMMENT ON COLUMN plan_candidate.source_evaluation_id IS
    'The immutable strategy_evaluation this exact package was adopted from, when it came from a scan.';
