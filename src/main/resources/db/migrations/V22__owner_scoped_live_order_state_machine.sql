-- Live brokerage is optional, but when enabled its local ledger must be as owner-scoped and
-- crash-honest as the rest of the product. Existing pre-release rows belong to the local owner;
-- no historical broker command is reconstructed or silently upgraded.
ALTER TABLE live_orders ADD COLUMN owner_id text;
UPDATE live_orders SET owner_id='local' WHERE owner_id IS NULL;
ALTER TABLE live_orders ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE live_orders
    ADD CONSTRAINT live_orders_owner_fk FOREIGN KEY (owner_id) REFERENCES users(id);

ALTER TABLE live_orders ADD COLUMN practice_account_id text;
ALTER TABLE live_orders ADD COLUMN command_fingerprint text;
ALTER TABLE live_orders ADD COLUMN canonical_receipt_json jsonb;
ALTER TABLE live_orders ADD COLUMN provider_preview_json jsonb;
ALTER TABLE live_orders ADD COLUMN broker_result_json jsonb;
ALTER TABLE live_orders ADD COLUMN proceed_without_endorsement integer DEFAULT 0 NOT NULL;
ALTER TABLE live_orders ADD COLUMN last_error text;
ALTER TABLE live_orders ADD COLUMN consumed_at timestamp with time zone;
ALTER TABLE live_orders ADD COLUMN submitted_at timestamp with time zone;
ALTER TABLE live_orders ADD COLUMN reconciled_at timestamp with time zone;

ALTER TABLE live_orders DROP CONSTRAINT live_orders_client_order_id_key;
ALTER TABLE live_orders
    ADD CONSTRAINT live_orders_owner_client_order_id_key UNIQUE(owner_id,client_order_id);
CREATE UNIQUE INDEX live_orders_owner_preview_id_key
    ON live_orders(owner_id,preview_id) WHERE preview_id IS NOT NULL;

ALTER TABLE live_orders ADD CONSTRAINT live_orders_canonical_receipt_object_check
    CHECK (canonical_receipt_json IS NULL
        OR jsonb_typeof(canonical_receipt_json)='object') NOT VALID;
ALTER TABLE live_orders ADD CONSTRAINT live_orders_provider_preview_object_check
    CHECK (provider_preview_json IS NULL
        OR jsonb_typeof(provider_preview_json)='object') NOT VALID;
ALTER TABLE live_orders ADD CONSTRAINT live_orders_broker_result_object_check
    CHECK (broker_result_json IS NULL
        OR jsonb_typeof(broker_result_json)='object') NOT VALID;
ALTER TABLE live_orders ADD CONSTRAINT live_orders_unendorsed_override_check
    CHECK (proceed_without_endorsement IN (0,1)) NOT VALID;
