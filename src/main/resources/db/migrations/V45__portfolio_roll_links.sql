CREATE TABLE portfolio_roll (
  transaction_id                TEXT PRIMARY KEY REFERENCES portfolio_transaction(id) ON DELETE RESTRICT,
  portfolio_account_id          TEXT NOT NULL REFERENCES portfolio_account(id),
  closing_leg_no                INTEGER NOT NULL,
  opening_leg_no                INTEGER NOT NULL,
  replacement_lot_id            TEXT NOT NULL REFERENCES portfolio_lot(id) ON DELETE RESTRICT,
  quantity                      BIGINT NOT NULL CHECK (quantity > 0),
  closing_premium_cents         BIGINT NOT NULL,
  opening_premium_cents         BIGINT NOT NULL,
  premium_carryover_cents       BIGINT NOT NULL,
  created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (transaction_id, closing_leg_no)
    REFERENCES portfolio_transaction_leg(transaction_id, leg_no),
  FOREIGN KEY (transaction_id, opening_leg_no)
    REFERENCES portfolio_transaction_leg(transaction_id, leg_no),
  CHECK (premium_carryover_cents = closing_premium_cents + opening_premium_cents)
);

CREATE TABLE portfolio_roll_match (
  transaction_id                TEXT NOT NULL REFERENCES portfolio_roll(transaction_id) ON DELETE RESTRICT,
  lot_match_id                  BIGINT NOT NULL REFERENCES portfolio_lot_match(id) ON DELETE RESTRICT,
  PRIMARY KEY (transaction_id, lot_match_id)
);

CREATE INDEX idx_portfolio_roll_account
  ON portfolio_roll(portfolio_account_id, created_at DESC);
