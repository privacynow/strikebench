-- Automated candle connectors own and disclose their price basis. The old schedule preference
-- was never consumed by planning, fetching, or persistence; remove the misleading dead control.
-- User-owned CSV import retains its separately enforced RAW / ADJUSTED / AUTO basis contract.
ALTER TABLE data_sync_schedule DROP COLUMN adjustment;
