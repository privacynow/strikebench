-- Options Lab initial schema. All money values are integer cents unless suffixed otherwise.

CREATE TABLE accounts (
  id               TEXT PRIMARY KEY,
  name             TEXT NOT NULL,
  type             TEXT NOT NULL CHECK (type IN ('PAPER','LIVE')),
  starting_cash_cents INTEGER NOT NULL,
  cash_cents       INTEGER NOT NULL,
  reserved_cents   INTEGER NOT NULL DEFAULT 0,
  has_traded       INTEGER NOT NULL DEFAULT 0,
  created_at       TEXT NOT NULL,
  updated_at       TEXT NOT NULL
);

CREATE TABLE trades (
  id                 TEXT PRIMARY KEY,
  account_id         TEXT NOT NULL REFERENCES accounts(id),
  symbol             TEXT NOT NULL,
  strategy           TEXT NOT NULL,
  status             TEXT NOT NULL CHECK (status IN ('ACTIVE','CLOSED','EXPIRED','DELETED')),
  qty                INTEGER NOT NULL,
  legs_json          TEXT NOT NULL,
  thesis             TEXT,
  horizon            TEXT,
  risk_mode          TEXT,
  entry_underlying_cents INTEGER NOT NULL,
  entry_net_premium_cents INTEGER NOT NULL, -- signed cash effect at open excl. fees: credit > 0, debit < 0
  max_loss_cents     INTEGER NOT NULL,      -- always finite for accepted trades, >= 0
  max_profit_cents   INTEGER,               -- null only for stock-backed strategies with unbounded upside
  breakevens_json    TEXT NOT NULL,
  pop_entry          REAL,
  fees_open_cents    INTEGER NOT NULL DEFAULT 0,
  fees_close_cents   INTEGER NOT NULL DEFAULT 0,
  realized_pnl_cents INTEGER,
  close_reason       TEXT,
  entry_snapshot_json TEXT NOT NULL,
  is_live            INTEGER NOT NULL DEFAULT 0,
  created_at         TEXT NOT NULL,
  closed_at          TEXT,
  updated_at         TEXT NOT NULL
);
CREATE INDEX idx_trades_account_status ON trades(account_id, status, created_at DESC);

CREATE TABLE ledger (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id     TEXT NOT NULL REFERENCES accounts(id),
  trade_id       TEXT,
  ts             TEXT NOT NULL,
  type           TEXT NOT NULL CHECK (type IN
    ('DEPOSIT','RESET','PREMIUM_OPEN','PREMIUM_CLOSE','SETTLEMENT','FEE','RESERVE_HOLD','RESERVE_RELEASE','ADJUSTMENT')),
  amount_cents   INTEGER NOT NULL,        -- signed; for RESERVE_* this moves reserve, not cash
  cash_after_cents     INTEGER NOT NULL,
  reserved_after_cents INTEGER NOT NULL,
  memo           TEXT
);
CREATE INDEX idx_ledger_account ON ledger(account_id, id);
CREATE INDEX idx_ledger_trade ON ledger(trade_id);

CREATE TABLE trade_marks (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  trade_id        TEXT NOT NULL REFERENCES trades(id),
  ts              TEXT NOT NULL,
  underlying_px_cents INTEGER,
  close_cost_cents INTEGER,   -- signed cash effect if position were closed now, excl. fees
  unrealized_cents INTEGER,
  pop_now         REAL,
  freshness       TEXT,
  detail_json     TEXT
);
CREATE INDEX idx_marks_trade ON trade_marks(trade_id, id DESC);

CREATE TABLE audit (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ts          TEXT NOT NULL,
  account_id  TEXT,
  trade_id    TEXT,
  action      TEXT NOT NULL,
  level       TEXT NOT NULL DEFAULT 'INFO' CHECK (level IN ('INFO','WARN','BLOCK','ERROR')),
  detail_json TEXT
);
CREATE INDEX idx_audit_ts ON audit(id DESC);

CREATE TABLE secrets (
  k          TEXT PRIMARY KEY,
  v          TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE live_orders (
  id               TEXT PRIMARY KEY,
  client_order_id  TEXT NOT NULL UNIQUE,
  broker_account_key TEXT NOT NULL,
  symbol           TEXT NOT NULL,
  preview_id       TEXT,
  broker_order_id  TEXT,
  status           TEXT NOT NULL,
  payload_json     TEXT NOT NULL,
  created_at       TEXT NOT NULL,
  updated_at       TEXT NOT NULL
);

CREATE TABLE backtests (
  id           TEXT PRIMARY KEY,
  created_at   TEXT NOT NULL,
  request_json TEXT NOT NULL,
  report_json  TEXT NOT NULL
);
