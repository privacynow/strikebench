-- A custom Builder package is a first-class Strategy-stage result. It shares the
-- normalized candidate/leg tables with ranked and scouted structures; only its run
-- kind differs so a custom choice never masquerades as a fresh competition.
ALTER TABLE plan_strategy_run DROP CONSTRAINT plan_strategy_run_run_kind_check;
ALTER TABLE plan_strategy_run ADD CONSTRAINT plan_strategy_run_run_kind_check
    CHECK (run_kind IN ('COMPETITION','SCOUT','CUSTOM'));

