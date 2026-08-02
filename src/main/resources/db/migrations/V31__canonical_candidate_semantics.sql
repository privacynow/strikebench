-- Candidate persistence uses the same names as the current recommendation and evaluation APIs.
-- Rename the historical storage labels once rather than translating them at every read and write.

ALTER TABLE plan_candidate
    RENAME COLUMN assignment_probability TO short_side_expiration_itm_probability;

ALTER TABLE plan_candidate
    RENAME COLUMN annualized_yield_pct TO annualized_opening_premium_rate_pct;

ALTER TABLE plan_candidate
    RENAME CONSTRAINT plan_candidate_assignment_probability_check
    TO plan_candidate_short_side_expiration_itm_probability_check;
