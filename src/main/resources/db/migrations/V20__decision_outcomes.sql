-- Keep ledger/package P&L separate from the combined stock-plus-option outcome used to
-- calibrate a held-share recommendation. Existing trades remain unknown until they close again.
ALTER TABLE trades ADD COLUMN decision_pnl_cents BIGINT;
ALTER TABLE trade_marks ADD COLUMN decision_unrealized_cents BIGINT;
