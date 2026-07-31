-- The frozen decision's review horizon is a count of TRADING SESSIONS, not calendar days: it is
-- written from the Plan context's horizon, which PlanStrategyService feeds to
-- Horizon.exactTradingSessions. The read side treated it as calendar days and added them to the
-- decision instant, so every cash-decision review gate fired early and benchmarked the wrong close
-- (a 21-session horizon reviewed on 2026-08-03 instead of 2026-08-11).
--
-- This rename is FORWARD-ONLY. V1 is already applied to every existing database and must never be
-- edited; changing it there would leave the column named review_horizon_days while the application
-- queries review_horizon_sessions, and no amount of `flyway repair` would reconcile that — repair
-- blesses a checksum, it does not touch the schema.
ALTER TABLE plan_decision
    RENAME COLUMN review_horizon_days TO review_horizon_sessions;

ALTER TABLE plan_decision
    RENAME CONSTRAINT plan_decision_review_horizon_days_check
    TO plan_decision_review_horizon_sessions_check;
