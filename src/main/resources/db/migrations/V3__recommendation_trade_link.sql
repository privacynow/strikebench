-- Phase 4/5: link a placed paper trade back to the recommendation it came from, so closing the
-- trade auto-resolves the recommendation's outcome and feeds the calibration report.
ALTER TABLE recommendation ADD COLUMN trade_id TEXT;
CREATE INDEX idx_reco_trade ON recommendation(trade_id);
