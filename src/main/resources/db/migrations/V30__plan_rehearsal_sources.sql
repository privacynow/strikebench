-- One selected ensemble path can drive the existing simulated exchange. Dense path vectors are
-- computed numeric payloads; identity, ownership and interpretation remain normalized columns.
CREATE TABLE sim_replay_source (
    sim_session_id   TEXT PRIMARY KEY REFERENCES sim_session(id) ON DELETE CASCADE,
    plan_id          TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    ensemble_id      TEXT NOT NULL REFERENCES plan_ensemble(id) ON DELETE RESTRICT,
    fingerprint      TEXT NOT NULL REFERENCES ensemble_artifact(fingerprint) ON DELETE RESTRICT,
    path_index       INTEGER NOT NULL CHECK (path_index >= 0),
    selection_kind   TEXT NOT NULL CHECK (selection_kind IN ('RANDOM','TYPICAL','FAVORABLE','ADVERSE','STRESS','SAMPLE')),
    symbol           TEXT NOT NULL,
    model_version    TEXT NOT NULL,
    n_steps          INTEGER NOT NULL CHECK (n_steps > 0),
    step_seconds     DOUBLE PRECISION NOT NULL CHECK (step_seconds > 0),
    rate_annual      DOUBLE PRECISION NOT NULL,
    spot_path        BYTEA NOT NULL,
    iv_path          BYTEA NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sim_replay_plan ON sim_replay_source(plan_id,created_at DESC);
