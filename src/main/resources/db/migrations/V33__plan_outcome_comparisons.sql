-- Compare every current Plan proposal on one exact, persisted path ensemble. The comparison
-- receipt and normalized item rows survive reloads without regenerating paths or repricing entry.

CREATE TABLE plan_outcome_comparison (
    id                  TEXT PRIMARY KEY,
    plan_id             TEXT NOT NULL,
    context_rev         INTEGER NOT NULL,
    ensemble_id         TEXT NOT NULL REFERENCES plan_ensemble(id) ON DELETE RESTRICT,
    ensemble_fingerprint TEXT NOT NULL,
    dataset_id          TEXT REFERENCES dataset(id) ON DELETE SET NULL,
    basis               TEXT NOT NULL CHECK (basis IN
                          ('PARAMETRIC','HISTORICAL_ANALOGS','CONDITIONAL_BOOTSTRAP')),
    interpretation      TEXT NOT NULL,
    fairness            TEXT NOT NULL,
    input_hash          TEXT NOT NULL,
    engine_version      TEXT NOT NULL,
    state               TEXT NOT NULL CHECK (state IN ('CURRENT','STALE','BLOCKED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, context_rev)
        REFERENCES plan_context_revision(plan_id, rev) ON DELETE CASCADE
);

CREATE INDEX idx_plan_outcome_comparison_current
    ON plan_outcome_comparison(plan_id, context_rev, dataset_id, basis, created_at DESC)
    WHERE state='CURRENT';

CREATE TABLE plan_outcome_comparison_item (
    comparison_id       TEXT NOT NULL REFERENCES plan_outcome_comparison(id) ON DELETE CASCADE,
    item_key            TEXT NOT NULL,
    candidate_id        TEXT REFERENCES plan_candidate(id) ON DELETE RESTRICT,
    rank_number         INTEGER NOT NULL CHECK (rank_number >= 0),
    strategy            TEXT NOT NULL,
    display_name        TEXT NOT NULL,
    qty                 INTEGER NOT NULL CHECK (qty >= 0),
    entry_cost_cents    BIGINT,
    max_loss_cents      BIGINT,
    win_rate_pct        DOUBLE PRECISION,
    expected_pnl_cents  BIGINT,
    p5_cents            BIGINT,
    p50_cents           BIGINT,
    p95_cents           BIGINT,
    tail_return_score   DOUBLE PRECISION,
    round_trip_fees_cents BIGINT NOT NULL DEFAULT 0 CHECK (round_trip_fees_cents >= 0),
    economic_verdict    TEXT,
    economic_placement  TEXT,
    mechanically_eligible INTEGER CHECK (mechanically_eligible IS NULL OR mechanically_eligible IN (0,1)),
    decision_score      DOUBLE PRECISION,
    selected            INTEGER NOT NULL DEFAULT 0 CHECK (selected IN (0,1)),
    refusal_reason      TEXT,
    PRIMARY KEY (comparison_id, item_key)
);

CREATE INDEX idx_plan_outcome_comparison_item_candidate
    ON plan_outcome_comparison_item(candidate_id, comparison_id);
