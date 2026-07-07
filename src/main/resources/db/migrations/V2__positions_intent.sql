-- V2: equity positions, trade intent + held-share locks, STOCK_BUY/STOCK_SELL ledger types.
-- SQLite cannot ALTER a CHECK constraint, so the ledger table is recreated with the two new
-- types and its rows copied verbatim (ids preserved; nothing referencing ledger exists).

CREATE TABLE positions (
  id             TEXT PRIMARY KEY,
  account_id     TEXT NOT NULL REFERENCES accounts(id),
  symbol         TEXT NOT NULL,
  shares         INTEGER NOT NULL,
  avg_cost_cents INTEGER NOT NULL,
  created_at     TEXT NOT NULL,
  updated_at     TEXT NOT NULL,
  UNIQUE(account_id, symbol)
);

ALTER TABLE trades ADD COLUMN intent TEXT;
ALTER TABLE trades ADD COLUMN shares_locked INTEGER NOT NULL DEFAULT 0;

CREATE TABLE ledger_new (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id     TEXT NOT NULL REFERENCES accounts(id),
  trade_id       TEXT,
  ts             TEXT NOT NULL,
  type           TEXT NOT NULL CHECK (type IN
    ('DEPOSIT','RESET','PREMIUM_OPEN','PREMIUM_CLOSE','SETTLEMENT','FEE','RESERVE_HOLD','RESERVE_RELEASE','ADJUSTMENT','STOCK_BUY','STOCK_SELL')),
  amount_cents   INTEGER NOT NULL,
  cash_after_cents     INTEGER NOT NULL,
  reserved_after_cents INTEGER NOT NULL,
  memo           TEXT
);
INSERT INTO ledger_new (id,account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo)
  SELECT id,account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_after_cents,memo FROM ledger;
DROP TABLE ledger;
ALTER TABLE ledger_new RENAME TO ledger;
CREATE INDEX idx_ledger_account ON ledger(account_id, id);
CREATE INDEX idx_ledger_trade ON ledger(trade_id);
