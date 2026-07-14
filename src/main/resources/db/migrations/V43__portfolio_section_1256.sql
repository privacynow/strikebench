ALTER TABLE portfolio_transaction DROP CONSTRAINT portfolio_transaction_event_type_check;
ALTER TABLE portfolio_transaction ADD CONSTRAINT portfolio_transaction_event_type_check CHECK (event_type IN
  ('OPENING_BALANCE','DEPOSIT','WITHDRAWAL','TRANSFER_IN','TRANSFER_OUT','INTEREST','DIVIDEND','FEE',
   'TRADE','ROLL','EXPIRATION','ASSIGNMENT','EXERCISE','MARK_TO_MARKET','ADJUSTMENT'));

ALTER TABLE portfolio_transaction DROP CONSTRAINT portfolio_transaction_source_check;
ALTER TABLE portfolio_transaction ADD CONSTRAINT portfolio_transaction_source_check CHECK
  (source IN ('MANUAL','BROKER','IMPORT','CALCULATED'));

ALTER TABLE portfolio_transaction_leg ADD COLUMN section_1256 INTEGER NOT NULL DEFAULT 0
  CHECK (section_1256 IN (0,1));
ALTER TABLE portfolio_lot ADD COLUMN section_1256 INTEGER NOT NULL DEFAULT 0
  CHECK (section_1256 IN (0,1));
ALTER TABLE portfolio_lot_match ADD COLUMN section_1256 INTEGER NOT NULL DEFAULT 0
  CHECK (section_1256 IN (0,1));

ALTER TABLE portfolio_lot_match DROP CONSTRAINT portfolio_lot_match_holding_term_check;
ALTER TABLE portfolio_lot_match ADD CONSTRAINT portfolio_lot_match_holding_term_check CHECK
  (holding_term IN ('SHORT_TERM','LONG_TERM','SECTION_1256','ROLLED'));

CREATE INDEX idx_portfolio_lot_1256_open
  ON portfolio_lot(portfolio_account_id, section_1256, status, expiration);
