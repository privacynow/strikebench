-- A proposed package limit is an order instruction, never an alternate package-price field.
-- Recorded executions carry exact leg fills and a RECORDED_FILL PackagePriceReceipt instead.
ALTER TABLE trades ADD COLUMN order_limit_net_cents bigint;

-- The legacy column mixed two meanings: an actual customer LIMIT and an aggregate
-- recorded-fill override. Only a typed LIMIT instruction is order provenance.
UPDATE trades
SET order_limit_net_cents = COALESCE(
        NULLIF(entry_snapshot_json::jsonb #>> '{orderInstruction,limitNetCents}', '')::bigint,
        proposed_net_cents)
WHERE entry_snapshot_json::jsonb #>> '{orderInstruction,type}' = 'LIMIT';

ALTER TABLE trades DROP COLUMN proposed_net_cents;
