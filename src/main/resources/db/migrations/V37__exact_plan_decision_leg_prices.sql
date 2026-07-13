-- A frozen decision must retain the exact generated-market package that the user judged.
-- Whole-cent columns rounded four-decimal simulated books and later cash reviews repriced
-- a subtly different structure. Store dollar prices directly, matching plan_candidate_leg.
ALTER TABLE plan_decision_leg RENAME COLUMN strike_cents TO strike_price;
ALTER TABLE plan_decision_leg
    ALTER COLUMN strike_price TYPE NUMERIC(18,6)
    USING (strike_price::NUMERIC / 100);

ALTER TABLE plan_decision_leg RENAME COLUMN bid_cents TO bid_price;
ALTER TABLE plan_decision_leg
    ALTER COLUMN bid_price TYPE NUMERIC(18,6)
    USING (bid_price::NUMERIC / 100);

ALTER TABLE plan_decision_leg RENAME COLUMN ask_cents TO ask_price;
ALTER TABLE plan_decision_leg
    ALTER COLUMN ask_price TYPE NUMERIC(18,6)
    USING (ask_price::NUMERIC / 100);

ALTER TABLE plan_decision_leg RENAME COLUMN mid_cents TO mid_price;
ALTER TABLE plan_decision_leg
    ALTER COLUMN mid_price TYPE NUMERIC(18,6)
    USING (mid_price::NUMERIC / 100);

ALTER TABLE plan_decision_leg RENAME COLUMN fill_cents TO fill_price;
ALTER TABLE plan_decision_leg
    ALTER COLUMN fill_price TYPE NUMERIC(18,6)
    USING (fill_price::NUMERIC / 100);
