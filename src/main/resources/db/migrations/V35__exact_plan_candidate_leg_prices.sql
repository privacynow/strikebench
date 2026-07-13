-- Plan candidates preserve the exact package the user evaluated. Generated markets quote to
-- four decimal places, so whole-cent leg columns could not store a valid simulated package.
ALTER TABLE plan_candidate_leg RENAME COLUMN strike_cents TO strike_price;
ALTER TABLE plan_candidate_leg
    ALTER COLUMN strike_price TYPE NUMERIC(18,6)
    USING (strike_price::NUMERIC / 100);

ALTER TABLE plan_candidate_leg RENAME COLUMN entry_price_cents TO entry_price;
ALTER TABLE plan_candidate_leg
    ALTER COLUMN entry_price TYPE NUMERIC(18,6)
    USING (entry_price::NUMERIC / 100);

