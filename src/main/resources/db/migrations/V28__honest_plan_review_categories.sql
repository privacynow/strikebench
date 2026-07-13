-- Plan reviews describe decision quality in their owning market. They are not necessarily
-- observed-market trades and never imply eligibility for recommendation calibration.
ALTER TABLE plan_review DROP CONSTRAINT IF EXISTS plan_review_category_check;
UPDATE plan_review SET category='TRADE_DECISION' WHERE category='OBSERVED_TRADE';
ALTER TABLE plan_review ADD CONSTRAINT plan_review_category_check
    CHECK (category IN ('TRADE_DECISION','SIM_REHEARSAL','CASH_DECISION'));
