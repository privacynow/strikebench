-- Instants use one database type. Calendar-only market facts remain DATE.
-- Aggregate money remains BIGINT cents; per-unit prices use six decimal places.

ALTER TABLE users
  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING CASE WHEN created_at='now' THEN now() ELSE created_at::timestamptz END,
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CASE WHEN updated_at='now' THEN now() ELSE updated_at::timestamptz END;

ALTER TABLE accounts
  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING CASE WHEN created_at='now' THEN now() ELSE created_at::timestamptz END,
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CASE WHEN updated_at='now' THEN now() ELSE updated_at::timestamptz END;

ALTER TABLE trades
  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING CASE WHEN created_at='now' THEN now() ELSE created_at::timestamptz END,
  ALTER COLUMN closed_at TYPE TIMESTAMPTZ USING CASE WHEN closed_at='now' THEN now() ELSE closed_at::timestamptz END,
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CASE WHEN updated_at='now' THEN now() ELSE updated_at::timestamptz END;

ALTER TABLE ledger
  ALTER COLUMN ts TYPE TIMESTAMPTZ USING CASE WHEN ts='now' THEN now() ELSE ts::timestamptz END;

ALTER TABLE trade_marks
  ALTER COLUMN ts TYPE TIMESTAMPTZ USING CASE WHEN ts='now' THEN now() ELSE ts::timestamptz END;

ALTER TABLE positions
  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING CASE WHEN created_at='now' THEN now() ELSE created_at::timestamptz END,
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CASE WHEN updated_at='now' THEN now() ELSE updated_at::timestamptz END;

ALTER TABLE audit
  ALTER COLUMN ts TYPE TIMESTAMPTZ USING CASE WHEN ts='now' THEN now() ELSE ts::timestamptz END;

ALTER TABLE secrets
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CASE WHEN updated_at='now' THEN now() ELSE updated_at::timestamptz END;

ALTER TABLE settings
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CASE WHEN updated_at='now' THEN now() ELSE updated_at::timestamptz END;

ALTER TABLE live_orders
  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING CASE WHEN created_at='now' THEN now() ELSE created_at::timestamptz END,
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CASE WHEN updated_at='now' THEN now() ELSE updated_at::timestamptz END;

ALTER TABLE backtests
  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING CASE WHEN created_at='now' THEN now() ELSE created_at::timestamptz END;

ALTER TABLE research_note
  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING CASE WHEN created_at='now' THEN now() ELSE created_at::timestamptz END,
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CASE WHEN updated_at='now' THEN now() ELSE updated_at::timestamptz END;

ALTER TABLE market_snapshot
  ALTER COLUMN last TYPE NUMERIC(19,6),
  ALTER COLUMN bid TYPE NUMERIC(19,6),
  ALTER COLUMN ask TYPE NUMERIC(19,6),
  ALTER COLUMN prev_close TYPE NUMERIC(19,6),
  ALTER COLUMN as_of TYPE TIMESTAMPTZ USING
    CASE WHEN as_of IS NULL THEN NULL ELSE to_timestamp(as_of / 1000.0) END;

ALTER TABLE underlying_bar
  ALTER COLUMN open TYPE NUMERIC(19,6),
  ALTER COLUMN high TYPE NUMERIC(19,6),
  ALTER COLUMN low TYPE NUMERIC(19,6),
  ALTER COLUMN close TYPE NUMERIC(19,6);

ALTER TABLE option_bar
  ALTER COLUMN strike TYPE NUMERIC(19,6),
  ALTER COLUMN bid TYPE NUMERIC(19,6),
  ALTER COLUMN ask TYPE NUMERIC(19,6),
  ALTER COLUMN last TYPE NUMERIC(19,6),
  ALTER COLUMN mark TYPE NUMERIC(19,6),
  ALTER COLUMN underlying TYPE NUMERIC(19,6);

ALTER TABLE portfolio_transaction_leg
  ALTER COLUMN strike TYPE NUMERIC(19,6),
  ALTER COLUMN price TYPE NUMERIC(19,6);

ALTER TABLE portfolio_lot
  ALTER COLUMN strike TYPE NUMERIC(19,6);

ALTER TABLE plan_candidate_leg
  ALTER COLUMN strike_price TYPE NUMERIC(19,6),
  ALTER COLUMN entry_price TYPE NUMERIC(19,6);

ALTER TABLE plan_candidate_breakeven
  ALTER COLUMN price TYPE NUMERIC(19,6);

ALTER TABLE plan_decision_leg
  ALTER COLUMN strike_price TYPE NUMERIC(19,6),
  ALTER COLUMN bid_price TYPE NUMERIC(19,6),
  ALTER COLUMN ask_price TYPE NUMERIC(19,6),
  ALTER COLUMN mid_price TYPE NUMERIC(19,6),
  ALTER COLUMN fill_price TYPE NUMERIC(19,6);
