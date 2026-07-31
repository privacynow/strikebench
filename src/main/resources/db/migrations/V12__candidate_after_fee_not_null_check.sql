-- V10's after-fee check had a hole Postgres does not warn about: a CHECK whose expression
-- evaluates to NULL PASSES. So when a candidate had a gross net and an opening fee but a NULL
-- after_fee_net_cents, `after_fee_net_cents = entry_net_cents - opening_fees_cents` evaluated to
-- NULL and the row was admitted — exactly the absence the receipt exists to make impossible.
--
-- Forward-only: V10 is already applied (strikebench_dev, checksum -977288737) and must not be
-- edited. Dropping and recreating the constraint here is the only way to tighten it.
ALTER TABLE plan_candidate DROP CONSTRAINT plan_candidate_price_after_fee_check;

ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_price_after_fee_check
    CHECK (CASE WHEN entry_net_cents IS NOT NULL AND opening_fees_cents IS NOT NULL
                THEN after_fee_net_cents IS NOT NULL
                     AND after_fee_net_cents = entry_net_cents - opening_fees_cents
                ELSE after_fee_net_cents IS NULL END);
