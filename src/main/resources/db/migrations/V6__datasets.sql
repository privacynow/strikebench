-- Dataset registry: observed history and each synthetic simulation run coexist as first-class
-- datasets, so synthetic data NEVER overwrites real rows and every read can carry its provenance.
CREATE TABLE dataset (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  kind        TEXT NOT NULL,   -- OBSERVED_VENDOR | OBSERVED_SNAPSHOT | OBSERVED_PERSONAL | MODELED
                               -- | SYNTHETIC_OVERLAY | SYNTHETIC_PURE | FIXTURE_DEMO
  symbol      TEXT,            -- null = universe / multi-symbol
  seed        BIGINT,
  spec        JSONB,           -- the ScenarioSpec / DatasetSpec that produced it
  evidence    JSONB,           -- per-dimension EvidenceProfile (worst-of rollup)
  user_id     TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dataset_created ON dataset (created_at DESC);

-- The canonical OBSERVED dataset. Existing bars belong to it.
INSERT INTO dataset (id, name, kind) VALUES ('observed', 'Observed market data', 'OBSERVED_VENDOR');

-- Bars carry a dataset_id (existing rows default to 'observed'); uniqueness now includes it, so a
-- synthetic run for the same symbol/date coexists instead of colliding with real history.
ALTER TABLE underlying_bar ADD COLUMN dataset_id TEXT NOT NULL DEFAULT 'observed'
  REFERENCES dataset(id) ON DELETE CASCADE;
ALTER TABLE underlying_bar DROP CONSTRAINT underlying_bar_pkey;
ALTER TABLE underlying_bar ADD PRIMARY KEY (symbol, d, source, dataset_id);
CREATE INDEX idx_underlying_bar_dataset ON underlying_bar (dataset_id, symbol, d);

ALTER TABLE option_bar ADD COLUMN dataset_id TEXT NOT NULL DEFAULT 'observed'
  REFERENCES dataset(id) ON DELETE CASCADE;
ALTER TABLE option_bar DROP CONSTRAINT option_bar_symbol_asof_expiration_strike_opt_type_source_key;
ALTER TABLE option_bar ADD CONSTRAINT option_bar_uniq
  UNIQUE (symbol, asof, expiration, strike, opt_type, source, dataset_id);
CREATE INDEX idx_option_bar_dataset ON option_bar (dataset_id, symbol, asof);
