-- Exact-position Outcome results owned by a Plan. The dense path/IV payload remains in the
-- content-addressed ensemble_artifact table created in V22; every rendered summary stays relational.

ALTER TABLE plan_ensemble ADD COLUMN heston_kappa DOUBLE PRECISION;
ALTER TABLE plan_ensemble ADD COLUMN heston_theta DOUBLE PRECISION;
ALTER TABLE plan_ensemble ADD COLUMN heston_xi DOUBLE PRECISION;
ALTER TABLE plan_ensemble ADD COLUMN heston_rho DOUBLE PRECISION;
ALTER TABLE plan_ensemble ADD COLUMN heston_v0 DOUBLE PRECISION;
ALTER TABLE plan_ensemble ADD COLUMN iv_drift_per_year DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE plan_ensemble ADD COLUMN iv_mean_revert_speed DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE plan_ensemble ADD COLUMN iv_event_day INTEGER NOT NULL DEFAULT -1;
ALTER TABLE plan_ensemble ADD COLUMN iv_event_shock_pct DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE plan_ensemble ADD COLUMN iv_min DOUBLE PRECISION NOT NULL DEFAULT 0.03;
ALTER TABLE plan_ensemble ADD COLUMN iv_max DOUBLE PRECISION NOT NULL DEFAULT 4.0;

CREATE TABLE plan_outcome_run (
    id                       TEXT PRIMARY KEY,
    plan_id                  TEXT NOT NULL,
    context_rev              INTEGER NOT NULL,
    candidate_id             TEXT NOT NULL REFERENCES plan_candidate(id) ON DELETE RESTRICT,
    ensemble_id              TEXT REFERENCES plan_ensemble(id) ON DELETE RESTRICT,
    basis                    TEXT NOT NULL CHECK (basis IN
                               ('RISK_NEUTRAL','PARAMETRIC','HISTORICAL_ANALOGS','CONDITIONAL_BOOTSTRAP')),
    interpretation           TEXT NOT NULL,
    entry_cost_cents         BIGINT,
    paths                    INTEGER,
    horizon_days             INTEGER,
    p5_cents                 BIGINT,
    p25_cents                BIGINT,
    p50_cents                BIGINT,
    p75_cents                BIGINT,
    p95_cents                BIGINT,
    expected_pnl_cents       BIGINT,
    win_rate_pct             DOUBLE PRECISION,
    best_cents               BIGINT,
    worst_cents              BIGINT,
    breach_probability_pct   DOUBLE PRECISION,
    input_hash               TEXT NOT NULL,
    engine_version           TEXT NOT NULL,
    state                    TEXT NOT NULL CHECK (state IN ('CURRENT','STALE','BLOCKED')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, context_rev) REFERENCES plan_context_revision(plan_id, rev) ON DELETE CASCADE
);
CREATE INDEX idx_plan_outcome_current
    ON plan_outcome_run(plan_id, context_rev, basis, created_at DESC) WHERE state='CURRENT';

CREATE TABLE plan_outcome_band (
    outcome_id           TEXT NOT NULL REFERENCES plan_outcome_run(id) ON DELETE CASCADE,
    day_index            INTEGER NOT NULL CHECK (day_index >= 0),
    p10_cents            BIGINT NOT NULL,
    p50_cents            BIGINT NOT NULL,
    p90_cents            BIGINT NOT NULL,
    PRIMARY KEY (outcome_id, day_index)
);

CREATE TABLE plan_outcome_bucket (
    outcome_id           TEXT NOT NULL REFERENCES plan_outcome_run(id) ON DELETE CASCADE,
    bucket_index         INTEGER NOT NULL CHECK (bucket_index >= 0),
    from_cents           BIGINT NOT NULL,
    to_cents             BIGINT NOT NULL,
    sample_count         INTEGER NOT NULL CHECK (sample_count >= 0),
    PRIMARY KEY (outcome_id, bucket_index),
    CHECK (from_cents <= to_cents)
);

CREATE TABLE plan_outcome_note (
    outcome_id           TEXT NOT NULL REFERENCES plan_outcome_run(id) ON DELETE CASCADE,
    note_index           INTEGER NOT NULL CHECK (note_index >= 0),
    note                 TEXT NOT NULL,
    PRIMARY KEY (outcome_id, note_index)
);

CREATE TABLE plan_outcome_market_metric (
    outcome_id           TEXT NOT NULL REFERENCES plan_outcome_run(id) ON DELETE CASCADE,
    metric_key           TEXT NOT NULL,
    value_number         DOUBLE PRECISION,
    value_cents          BIGINT,
    value_text           TEXT,
    PRIMARY KEY (outcome_id, metric_key),
    CHECK (((value_number IS NOT NULL)::int + (value_cents IS NOT NULL)::int
          + (value_text IS NOT NULL)::int) = 1)
);

ALTER TABLE plan_backtest ADD COLUMN engine_kind TEXT;
ALTER TABLE plan_backtest ADD COLUMN pricing_mode TEXT;
ALTER TABLE plan_backtest ADD COLUMN confidence TEXT;
ALTER TABLE plan_backtest ADD COLUMN starting_cents BIGINT;
ALTER TABLE plan_backtest ADD COLUMN ending_cents BIGINT;
ALTER TABLE plan_backtest ADD COLUMN max_drawdown_pct DOUBLE PRECISION;
ALTER TABLE plan_backtest ADD COLUMN demo_underlying INTEGER CHECK (demo_underlying IS NULL OR demo_underlying IN (0,1));
