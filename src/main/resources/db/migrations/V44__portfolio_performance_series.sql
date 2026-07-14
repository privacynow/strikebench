ALTER TABLE portfolio_valuation ADD COLUMN complete INTEGER NOT NULL DEFAULT 1
  CHECK (complete IN (0,1));
ALTER TABLE portfolio_valuation ADD COLUMN missing_marks TEXT NOT NULL DEFAULT '[]';

CREATE INDEX idx_portfolio_valuation_complete_time
  ON portfolio_valuation(portfolio_account_id, complete, as_of);
