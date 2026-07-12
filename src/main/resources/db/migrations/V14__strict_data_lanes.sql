-- Strict lane integrity: fabricated rows may exist only in the explicit Demo dataset.
INSERT INTO dataset (id, name, kind, evidence)
VALUES ('demo-fixture', 'Built-in demo data', 'FIXTURE_DEMO',
        '{"provenance":"DEMO","observed":false}'::jsonb)
ON CONFLICT (id) DO NOTHING;

INSERT INTO underlying_bar
  (symbol,d,open,high,low,close,volume,source,observed,created_at,dataset_id)
SELECT symbol,d,open,high,low,close,volume,source,0,created_at,'demo-fixture'
FROM underlying_bar
WHERE dataset_id='observed'
  AND (observed=0 OR lower(source) LIKE '%fixture%' OR lower(source) LIKE '%demo%')
ON CONFLICT (symbol,d,source,dataset_id) DO NOTHING;

DELETE FROM underlying_bar
WHERE dataset_id='observed'
  AND (observed=0 OR lower(source) LIKE '%fixture%' OR lower(source) LIKE '%demo%');

INSERT INTO option_bar
  (symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,theta,vega,
   open_interest,volume,underlying,source,bid_ask_observed,iv_source,greeks_source,created_at,dataset_id)
SELECT symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,theta,vega,
       open_interest,volume,underlying,source,0,iv_source,greeks_source,created_at,'demo-fixture'
FROM option_bar
WHERE dataset_id='observed'
  AND (lower(source) LIKE '%fixture%' OR lower(source) LIKE '%demo%'
       OR (lower(source)='strikebench-snapshot' AND bid_ask_observed=0
           AND coalesce(iv_source,'')='model' AND coalesce(greeks_source,'')='model'))
ON CONFLICT (symbol,asof,expiration,strike,opt_type,source,dataset_id) DO NOTHING;

DELETE FROM option_bar
WHERE dataset_id='observed'
  AND (lower(source) LIKE '%fixture%' OR lower(source) LIKE '%demo%'
       OR (lower(source)='strikebench-snapshot' AND bid_ask_observed=0
           AND coalesce(iv_source,'')='model' AND coalesce(greeks_source,'')='model'));

DELETE FROM market_snapshot
WHERE coalesce(lower(source),'') LIKE '%fixture%'
   OR coalesce(lower(source),'') LIKE '%demo%'
   OR coalesce(freshness,'') IN ('FIXTURE','SIMULATED','MODELED');

ALTER TABLE trades ADD COLUMN data_provenance TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE trades ADD COLUMN data_age TEXT;
ALTER TABLE trades ADD COLUMN data_source TEXT;

ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_type_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_type_check
  CHECK (type IN ('PAPER','LIVE','SIMULATION','DEMO'));

UPDATE trades
SET data_provenance='DEMO', data_age='NOT_APPLICABLE', data_source='fixture'
WHERE entry_snapshot_json LIKE '%"freshness":"FIXTURE"%'
   OR entry_snapshot_json LIKE '%"provenance":"DEMO"%';

UPDATE trades
SET data_provenance='SIMULATED', data_age='NOT_APPLICABLE', data_source='simulated'
WHERE data_provenance='UNKNOWN'
  AND (entry_snapshot_json LIKE '%"freshness":"SIMULATED"%'
       OR entry_snapshot_json LIKE '%"provenance":"SIMULATED"%');

UPDATE trades
SET data_provenance='OBSERVED'
WHERE data_provenance='UNKNOWN';
