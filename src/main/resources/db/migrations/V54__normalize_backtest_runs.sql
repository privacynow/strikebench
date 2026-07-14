-- Backtests are durable, queryable runs. Retire the V1 request/report blobs without losing history.

INSERT INTO users(id,name,created_at,updated_at)
SELECT DISTINCT CASE
  WHEN user_id IS NULL OR btrim(user_id)='' OR user_id='__local__' THEN 'local'
  WHEN user_id LIKE 'user:%' THEN substring(user_id FROM 6)
  ELSE user_id END,
  'Imported backtest owner',now(),now()
FROM backtests
ON CONFLICT(id) DO NOTHING;

UPDATE backtests SET user_id=CASE
  WHEN user_id IS NULL OR btrim(user_id)='' OR user_id='__local__' THEN 'local'
  WHEN user_id LIKE 'user:%' THEN substring(user_id FROM 6)
  ELSE user_id END;
ALTER TABLE backtests ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE backtests ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE backtests DROP CONSTRAINT IF EXISTS backtests_user_id_fkey;
ALTER TABLE backtests ADD CONSTRAINT backtests_user_id_fkey
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE backtests
  ADD COLUMN run_kind TEXT CHECK (run_kind IN ('SINGLE','PORTFOLIO')),
  ADD COLUMN symbol TEXT,
  ADD COLUMN strategy TEXT,
  ADD COLUMN from_date DATE,
  ADD COLUMN to_date DATE,
  ADD COLUMN target_dte INTEGER,
  ADD COLUMN entry_every_days INTEGER,
  ADD COLUMN qty INTEGER,
  ADD COLUMN slippage_pct DOUBLE PRECISION,
  ADD COLUMN starting_cash_cents BIGINT,
  ADD COLUMN max_concurrent INTEGER,
  ADD COLUMN short_delta DOUBLE PRECISION,
  ADD COLUMN width_pct DOUBLE PRECISION,
  ADD COLUMN profit_target_pct DOUBLE PRECISION,
  ADD COLUMN stop_fraction DOUBLE PRECISION,
  ADD COLUMN roll_dte INTEGER,
  ADD COLUMN pricing_mode TEXT,
  ADD COLUMN confidence TEXT,
  ADD COLUMN days_requested INTEGER,
  ADD COLUMN days_covered INTEGER,
  ADD COLUMN sample_size INTEGER,
  ADD COLUMN concurrent_peak INTEGER,
  ADD COLUMN win_rate DOUBLE PRECISION,
  ADD COLUMN avg_return_on_risk DOUBLE PRECISION,
  ADD COLUMN starting_cents BIGINT,
  ADD COLUMN ending_cents BIGINT,
  ADD COLUMN max_drawdown_pct DOUBLE PRECISION,
  ADD COLUMN assignments INTEGER,
  ADD COLUMN demo_underlying INTEGER CHECK (demo_underlying IN (0,1)),
  ADD COLUMN disclaimer TEXT;

UPDATE backtests SET
  run_kind=CASE WHEN request_json::jsonb ? 'maxConcurrent' THEN 'PORTFOLIO' ELSE 'SINGLE' END,
  symbol=report_json::jsonb->>'symbol',
  strategy=report_json::jsonb->>'strategy',
  from_date=(report_json::jsonb->>'from')::date,
  to_date=(report_json::jsonb->>'to')::date,
  target_dte=(request_json::jsonb->>'targetDte')::integer,
  entry_every_days=(request_json::jsonb->>'entryEveryDays')::integer,
  qty=(request_json::jsonb->>'qty')::integer,
  slippage_pct=(request_json::jsonb->>'slippagePct')::double precision,
  starting_cash_cents=(request_json::jsonb->>'startingCashCents')::bigint,
  max_concurrent=(request_json::jsonb->>'maxConcurrent')::integer,
  short_delta=(request_json::jsonb->>'shortDelta')::double precision,
  width_pct=(request_json::jsonb->>'widthPct')::double precision,
  profit_target_pct=(request_json::jsonb->>'profitTargetPct')::double precision,
  stop_fraction=(request_json::jsonb->>'stopFraction')::double precision,
  roll_dte=(request_json::jsonb->>'rollDte')::integer,
  pricing_mode=report_json::jsonb->>'pricingMode',
  confidence=report_json::jsonb->>'confidence',
  days_requested=(report_json::jsonb->>'daysRequested')::integer,
  days_covered=(report_json::jsonb->>'daysCovered')::integer,
  sample_size=(report_json::jsonb->>'sampleSize')::integer,
  concurrent_peak=(report_json::jsonb->>'concurrentPeak')::integer,
  win_rate=(report_json::jsonb->>'winRate')::double precision,
  avg_return_on_risk=(report_json::jsonb->>'avgReturnOnRisk')::double precision,
  starting_cents=(report_json::jsonb->>'startingCents')::bigint,
  ending_cents=(report_json::jsonb->>'endingCents')::bigint,
  max_drawdown_pct=(report_json::jsonb->>'maxDrawdownPct')::double precision,
  assignments=(report_json::jsonb->>'assignments')::integer,
  demo_underlying=CASE WHEN COALESCE((report_json::jsonb->>'demoUnderlying')::boolean,false) THEN 1 ELSE 0 END,
  disclaimer=report_json::jsonb->>'disclaimer';

CREATE TABLE backtest_trade (
  backtest_id               TEXT NOT NULL REFERENCES backtests(id) ON DELETE CASCADE,
  trade_index               INTEGER NOT NULL CHECK (trade_index>=0),
  entry_date                DATE NOT NULL,
  exit_date                 DATE NOT NULL,
  label                     TEXT,
  strategy                  TEXT,
  entry_net_premium_cents   BIGINT,
  credit_cents              BIGINT,
  exit_value_cents          BIGINT,
  fees_cents                BIGINT,
  pnl_cents                 BIGINT NOT NULL,
  max_loss_cents            BIGINT NOT NULL,
  return_on_risk            DOUBLE PRECISION,
  exit_reason               TEXT NOT NULL,
  assigned                  INTEGER CHECK (assigned IS NULL OR assigned IN (0,1)),
  entry_underlying_cents    BIGINT,
  is_worst                  INTEGER NOT NULL DEFAULT 0 CHECK (is_worst IN (0,1)),
  PRIMARY KEY(backtest_id,trade_index)
);

INSERT INTO backtest_trade(backtest_id,trade_index,entry_date,exit_date,label,strategy,
  entry_net_premium_cents,credit_cents,exit_value_cents,fees_cents,pnl_cents,max_loss_cents,
  return_on_risk,exit_reason,assigned,entry_underlying_cents,is_worst)
SELECT b.id,(item.ordinality-1)::integer,(item.value->>'entryDate')::date,(item.value->>'exitDate')::date,
  item.value->>'label',item.value->>'strategy',(item.value->>'entryNetPremiumCents')::bigint,
  (item.value->>'creditCents')::bigint,(item.value->>'exitValueCents')::bigint,
  (item.value->>'feesCents')::bigint,(item.value->>'pnlCents')::bigint,
  (item.value->>'maxLossCents')::bigint,(item.value->>'returnOnRisk')::double precision,
  item.value->>'exitReason',CASE WHEN item.value ? 'assigned' THEN CASE WHEN (item.value->>'assigned')::boolean THEN 1 ELSE 0 END END,
  (item.value->>'entryUnderlyingCents')::bigint,0
FROM backtests b
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(report_json::jsonb->'trades','[]'::jsonb))
  WITH ORDINALITY AS item(value,ordinality);

-- A report points to one worst trade. Equal-valued duplicate trades must not create two pointers.
UPDATE backtest_trade trade SET is_worst=1
FROM (
  SELECT DISTINCT ON (b.id) b.id AS backtest_id,(item.ordinality-1)::integer AS trade_index
  FROM backtests b
  CROSS JOIN LATERAL jsonb_array_elements(COALESCE(b.report_json::jsonb->'trades','[]'::jsonb))
    WITH ORDINALITY AS item(value,ordinality)
  WHERE b.report_json::jsonb ? 'worstTrade'
    AND item.value=b.report_json::jsonb->'worstTrade'
  ORDER BY b.id,item.ordinality
) worst
WHERE trade.backtest_id=worst.backtest_id AND trade.trade_index=worst.trade_index;
CREATE UNIQUE INDEX uq_backtest_one_worst_trade ON backtest_trade(backtest_id) WHERE is_worst=1;

CREATE TABLE backtest_skip (
  backtest_id TEXT NOT NULL REFERENCES backtests(id) ON DELETE CASCADE,
  skip_index INTEGER NOT NULL CHECK (skip_index>=0),
  skip_date DATE NOT NULL,
  reason TEXT NOT NULL,
  PRIMARY KEY(backtest_id,skip_index)
);
INSERT INTO backtest_skip(backtest_id,skip_index,skip_date,reason)
SELECT b.id,(item.ordinality-1)::integer,(item.value->>'date')::date,item.value->>'reason'
FROM backtests b
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(report_json::jsonb->'skipped','[]'::jsonb))
  WITH ORDINALITY AS item(value,ordinality);

CREATE TABLE backtest_equity_point (
  backtest_id TEXT NOT NULL REFERENCES backtests(id) ON DELETE CASCADE,
  point_index INTEGER NOT NULL CHECK (point_index>=0),
  point_date DATE NOT NULL,
  equity_cents BIGINT NOT NULL,
  PRIMARY KEY(backtest_id,point_index)
);
INSERT INTO backtest_equity_point(backtest_id,point_index,point_date,equity_cents)
SELECT b.id,(item.ordinality-1)::integer,(item.value->>'date')::date,(item.value->>'equityCents')::bigint
FROM backtests b
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(report_json::jsonb->'equityCurve','[]'::jsonb))
  WITH ORDINALITY AS item(value,ordinality);

CREATE TABLE backtest_note (
  backtest_id TEXT NOT NULL REFERENCES backtests(id) ON DELETE CASCADE,
  note_index INTEGER NOT NULL CHECK (note_index>=0),
  note TEXT NOT NULL,
  PRIMARY KEY(backtest_id,note_index)
);
INSERT INTO backtest_note(backtest_id,note_index,note)
SELECT b.id,(item.ordinality-1)::integer,item.value
FROM backtests b
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(report_json::jsonb->'notes','[]'::jsonb))
  WITH ORDINALITY AS item(value,ordinality);

CREATE TABLE backtest_assumption (
  backtest_id TEXT NOT NULL REFERENCES backtests(id) ON DELETE CASCADE,
  assumption_key TEXT NOT NULL,
  assumption_value JSONB NOT NULL,
  PRIMARY KEY(backtest_id,assumption_key)
);
INSERT INTO backtest_assumption(backtest_id,assumption_key,assumption_value)
SELECT b.id,item.key,item.value
FROM backtests b
CROSS JOIN LATERAL jsonb_each(COALESCE(report_json::jsonb->'assumptions','{}'::jsonb)) item;

ALTER TABLE backtests
  ALTER COLUMN run_kind SET NOT NULL,
  ALTER COLUMN symbol SET NOT NULL,
  ALTER COLUMN strategy SET NOT NULL,
  ALTER COLUMN from_date SET NOT NULL,
  ALTER COLUMN to_date SET NOT NULL,
  ALTER COLUMN pricing_mode SET NOT NULL,
  ALTER COLUMN confidence SET NOT NULL,
  ALTER COLUMN days_covered SET NOT NULL,
  ALTER COLUMN sample_size SET NOT NULL,
  ALTER COLUMN starting_cents SET NOT NULL,
  ALTER COLUMN ending_cents SET NOT NULL,
  ALTER COLUMN max_drawdown_pct SET NOT NULL,
  ALTER COLUMN demo_underlying SET NOT NULL,
  ALTER COLUMN disclaimer SET NOT NULL,
  DROP COLUMN request_json,
  DROP COLUMN report_json;

CREATE INDEX idx_backtests_owner_created ON backtests(user_id,created_at DESC,id DESC);
