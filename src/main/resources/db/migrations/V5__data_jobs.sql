-- Data Center job system: cancellable, resumable, idempotent background jobs (warm/refresh/snapshot/
-- backfill/import) with per-item progress. The Data Center reads these for live job status; each
-- item's underlying work is idempotent (upserts), so retrying a failed job re-does only what's needed.
CREATE TABLE data_job (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL,                 -- warm_universe | refresh_now | snapshot_now | backfill_underlying | import_options_csv
  status       TEXT NOT NULL,                 -- QUEUED | RUNNING | DONE | FAILED | CANCELLED
  params       JSONB,
  total        INTEGER NOT NULL DEFAULT 0,
  done         INTEGER NOT NULL DEFAULT 0,
  rows_written BIGINT  NOT NULL DEFAULT 0,
  message      TEXT,
  error        TEXT,
  user_id      TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_data_job_created ON data_job (created_at DESC);

CREATE TABLE data_job_item (
  job_id       TEXT NOT NULL REFERENCES data_job(id) ON DELETE CASCADE,
  seq          INTEGER NOT NULL,
  label        TEXT NOT NULL,                 -- e.g. the symbol being backfilled
  status       TEXT NOT NULL,                 -- PENDING | DONE | FAILED | SKIPPED
  rows_written BIGINT DEFAULT 0,
  note         TEXT,
  PRIMARY KEY (job_id, seq)
);
