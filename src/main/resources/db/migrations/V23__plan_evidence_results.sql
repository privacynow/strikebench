-- Full-fidelity, normalized historical evidence owned by a Plan. The request and every
-- decision-bearing result are durable; no client cache or JSON payload is the source of truth.

CREATE TABLE plan_evidence_param (
    evidence_id          TEXT NOT NULL REFERENCES plan_evidence(id) ON DELETE CASCADE,
    param_key            TEXT NOT NULL,
    value_number         DOUBLE PRECISION,
    value_text           TEXT,
    value_boolean        INTEGER CHECK (value_boolean IS NULL OR value_boolean IN (0,1)),
    PRIMARY KEY (evidence_id, param_key),
    CHECK (((value_number IS NOT NULL)::int + (value_text IS NOT NULL)::int
          + (value_boolean IS NOT NULL)::int) = 1)
);

CREATE TABLE plan_evidence_stat (
    evidence_id          TEXT NOT NULL REFERENCES plan_evidence(id) ON DELETE CASCADE,
    sample_kind          TEXT NOT NULL CHECK (sample_kind IN ('BASELINE','CONDITIONED')),
    sample_size          INTEGER NOT NULL CHECK (sample_size >= 0),
    win_rate_pct         DOUBLE PRECISION NOT NULL CHECK (win_rate_pct BETWEEN 0 AND 100),
    mean_return_pct      DOUBLE PRECISION NOT NULL,
    median_return_pct    DOUBLE PRECISION NOT NULL,
    worst_pct            DOUBLE PRECISION NOT NULL,
    best_pct             DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (evidence_id, sample_kind)
);

CREATE TABLE plan_evidence_distribution (
    evidence_id          TEXT NOT NULL REFERENCES plan_evidence(id) ON DELETE CASCADE,
    bucket_index         INTEGER NOT NULL CHECK (bucket_index >= 0),
    from_pct             DOUBLE PRECISION NOT NULL,
    to_pct               DOUBLE PRECISION NOT NULL,
    sample_count         INTEGER NOT NULL CHECK (sample_count >= 0),
    PRIMARY KEY (evidence_id, bucket_index),
    CHECK (from_pct <= to_pct)
);

CREATE TABLE plan_evidence_example (
    evidence_id          TEXT NOT NULL REFERENCES plan_evidence(id) ON DELETE CASCADE,
    example_index        INTEGER NOT NULL CHECK (example_index >= 0),
    event_date           DATE NOT NULL,
    PRIMARY KEY (evidence_id, example_index)
);

CREATE TABLE plan_evidence_note (
    evidence_id          TEXT NOT NULL REFERENCES plan_evidence(id) ON DELETE CASCADE,
    note_index           INTEGER NOT NULL CHECK (note_index >= 0),
    note                 TEXT NOT NULL,
    PRIMARY KEY (evidence_id, note_index)
);

CREATE TABLE plan_evidence_analog (
    evidence_id          TEXT NOT NULL REFERENCES plan_evidence(id) ON DELETE CASCADE,
    path_index           INTEGER NOT NULL CHECK (path_index >= 0),
    step_index           INTEGER NOT NULL CHECK (step_index >= 0),
    relative_price       DOUBLE PRECISION NOT NULL CHECK (relative_price > 0),
    PRIMARY KEY (evidence_id, path_index, step_index)
);

CREATE TABLE plan_evidence_event (
    evidence_id          TEXT NOT NULL REFERENCES plan_evidence(id) ON DELETE CASCADE,
    event_index          INTEGER NOT NULL CHECK (event_index >= 0),
    event_date           DATE NOT NULL,
    PRIMARY KEY (evidence_id, event_index)
);

CREATE INDEX idx_plan_evidence_current
    ON plan_evidence(plan_id, basis, context_rev, created_at DESC) WHERE state='CURRENT';
