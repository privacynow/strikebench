-- R5/R6: package-price and execution provenance. proposed_net_cents records that a trade's
-- economics came from the USER'S price (limit or actual fill) rather than executable sides;
-- executed_at/broker/order_ref complete the external (real-fill) lane's identity.
ALTER TABLE trades ADD COLUMN proposed_net_cents BIGINT;
ALTER TABLE trades ADD COLUMN executed_at TIMESTAMPTZ;
ALTER TABLE trades ADD COLUMN broker TEXT;
ALTER TABLE trades ADD COLUMN order_ref TEXT;
