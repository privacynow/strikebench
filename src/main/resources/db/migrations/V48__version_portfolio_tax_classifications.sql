-- Automated tax classifications are common-case models tied to a reviewed ruleset,
-- never timeless filing truth. Preserve that provenance on every wash allocation.
ALTER TABLE portfolio_wash_sale_allocation ADD COLUMN ruleset_id TEXT;
ALTER TABLE portfolio_wash_sale_allocation ADD COLUMN classification_status TEXT;

UPDATE portfolio_wash_sale_allocation
SET ruleset_id='LEGACY_UNVERSIONED', classification_status='UNREVIEWED'
WHERE ruleset_id IS NULL;

ALTER TABLE portfolio_wash_sale_allocation ALTER COLUMN ruleset_id SET NOT NULL;
ALTER TABLE portfolio_wash_sale_allocation ALTER COLUMN classification_status SET NOT NULL;
ALTER TABLE portfolio_wash_sale_allocation ADD CONSTRAINT portfolio_wash_classification_status_check
  CHECK (classification_status IN ('MODELED_COMMON_CASE','UNREVIEWED'));
