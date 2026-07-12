-- Last-known quote per symbol, persisted so the in-memory engine boots stale-first: the tape and
-- quote tiles show immediately from local data (labeled stale), then refresh slowly and politely,
-- instead of the app being blank while a heavy/keyless provider (Cboe) warms or is throttled.
CREATE TABLE market_snapshot (
  symbol      TEXT PRIMARY KEY,
  description TEXT,
  last        NUMERIC(19,4),
  bid         NUMERIC(19,4),
  ask         NUMERIC(19,4),
  prev_close  NUMERIC(19,4),
  optionable  INTEGER NOT NULL DEFAULT 0,
  source      TEXT,
  freshness   TEXT,
  as_of       BIGINT,
  captured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
