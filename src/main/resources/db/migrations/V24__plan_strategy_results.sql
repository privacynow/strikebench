-- Durable Strategy-stage runs. The server-owned recommendation/evaluation engines remain the
-- producer; these rows preserve their exact decision-bearing output for one Plan context.

CREATE TABLE plan_strategy_run (
    id                       TEXT PRIMARY KEY,
    plan_id                  TEXT NOT NULL,
    context_rev              INTEGER NOT NULL,
    run_kind                 TEXT NOT NULL CHECK (run_kind IN ('COMPETITION','SCOUT')),
    scope_kind               TEXT NOT NULL CHECK (scope_kind IN ('PLAN','UNIVERSE','PEERS','ALTERNATIVES','HEDGES')),
    thesis                   TEXT,
    horizon                  TEXT NOT NULL,
    risk_mode                TEXT NOT NULL,
    intent                   TEXT NOT NULL,
    risk_budget_cents        BIGINT,
    ranking_policy           TEXT,
    economic_message         TEXT,
    favorable_count          INTEGER NOT NULL DEFAULT 0,
    mixed_count              INTEGER NOT NULL DEFAULT 0,
    unfavorable_count        INTEGER NOT NULL DEFAULT 0,
    unavailable_count        INTEGER NOT NULL DEFAULT 0,
    disclaimer               TEXT,
    input_hash               TEXT NOT NULL,
    engine_version           TEXT NOT NULL,
    state                    TEXT NOT NULL CHECK (state IN ('CURRENT','STALE','BLOCKED')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, context_rev) REFERENCES plan_context_revision(plan_id, rev) ON DELETE CASCADE
);
CREATE INDEX idx_plan_strategy_run_current
    ON plan_strategy_run(plan_id, context_rev, run_kind, created_at DESC) WHERE state='CURRENT';

CREATE TABLE plan_strategy_param (
    run_id                   TEXT NOT NULL REFERENCES plan_strategy_run(id) ON DELETE CASCADE,
    param_key                TEXT NOT NULL,
    value_number             DOUBLE PRECISION,
    value_cents              BIGINT,
    value_text               TEXT,
    value_boolean            INTEGER CHECK (value_boolean IS NULL OR value_boolean IN (0,1)),
    PRIMARY KEY (run_id, param_key),
    CHECK (((value_number IS NOT NULL)::int + (value_cents IS NOT NULL)::int
          + (value_text IS NOT NULL)::int + (value_boolean IS NOT NULL)::int) = 1)
);

ALTER TABLE plan_candidate ADD COLUMN run_id TEXT REFERENCES plan_strategy_run(id) ON DELETE CASCADE;
ALTER TABLE plan_candidate_leg ADD COLUMN entry_price_cents BIGINT;
ALTER TABLE plan_candidate ADD COLUMN source_kind TEXT CHECK (source_kind IN ('RANKED','LADDER','SCOUT','CUSTOM'));
ALTER TABLE plan_candidate ADD COLUMN display_name TEXT;
ALTER TABLE plan_candidate ADD COLUMN position_label TEXT;
ALTER TABLE plan_candidate ADD COLUMN qty INTEGER CHECK (qty IS NULL OR qty > 0);
ALTER TABLE plan_candidate ADD COLUMN expected_value_cents BIGINT;
ALTER TABLE plan_candidate ADD COLUMN liquidity_score DOUBLE PRECISION;
ALTER TABLE plan_candidate ADD COLUMN freshness TEXT;
ALTER TABLE plan_candidate ADD COLUMN screen_score DOUBLE PRECISION;
ALTER TABLE plan_candidate ADD COLUMN confidence DOUBLE PRECISION;
ALTER TABLE plan_candidate ADD COLUMN why_considered TEXT;
ALTER TABLE plan_candidate ADD COLUMN best_upside TEXT;
ALTER TABLE plan_candidate ADD COLUMN biggest_risk TEXT;
ALTER TABLE plan_candidate ADD COLUMN would_invalidate TEXT;
ALTER TABLE plan_candidate ADD COLUMN beginner_explanation TEXT;
ALTER TABLE plan_candidate ADD COLUMN annualized_yield_pct DOUBLE PRECISION;
ALTER TABLE plan_candidate ADD COLUMN effective_price TEXT;
ALTER TABLE plan_candidate ADD COLUMN intent_note TEXT;
ALTER TABLE plan_candidate ADD COLUMN uses_held_shares INTEGER CHECK (uses_held_shares IS NULL OR uses_held_shares IN (0,1));
ALTER TABLE plan_candidate ADD COLUMN shares_needed INTEGER;
ALTER TABLE plan_candidate ADD COLUMN combined_max_loss_cents BIGINT;
ALTER TABLE plan_candidate ADD COLUMN decision_viable INTEGER CHECK (decision_viable IS NULL OR decision_viable IN (0,1));
ALTER TABLE plan_candidate ADD COLUMN structurally_eligible INTEGER CHECK (structurally_eligible IS NULL OR structurally_eligible IN (0,1));
ALTER TABLE plan_candidate ADD COLUMN economic_placement TEXT;
ALTER TABLE plan_candidate ADD COLUMN economic_label TEXT;
ALTER TABLE plan_candidate ADD COLUMN economic_summary TEXT;
ALTER TABLE plan_candidate ADD COLUMN estimated_roundtrip_fees_cents BIGINT;
ALTER TABLE plan_candidate ADD COLUMN market_ev_pct_of_risk DOUBLE PRECISION;
ALTER TABLE plan_candidate ADD COLUMN observed_evidence INTEGER CHECK (observed_evidence IS NULL OR observed_evidence IN (0,1));
CREATE INDEX idx_plan_candidate_run ON plan_candidate(run_id, source_kind, rank_number);

CREATE TABLE plan_candidate_intent (
    candidate_id             TEXT NOT NULL REFERENCES plan_candidate(id) ON DELETE CASCADE,
    intent_index             INTEGER NOT NULL CHECK (intent_index >= 0),
    intent                   TEXT NOT NULL,
    PRIMARY KEY (candidate_id, intent_index)
);

CREATE TABLE plan_candidate_breakeven (
    candidate_id             TEXT NOT NULL REFERENCES plan_candidate(id) ON DELETE CASCADE,
    breakeven_index          INTEGER NOT NULL CHECK (breakeven_index >= 0),
    price                    NUMERIC(18,6) NOT NULL,
    PRIMARY KEY (candidate_id, breakeven_index)
);

CREATE TABLE plan_candidate_message (
    candidate_id             TEXT NOT NULL REFERENCES plan_candidate(id) ON DELETE CASCADE,
    message_kind             TEXT NOT NULL CHECK (message_kind IN ('WARNING','ECONOMIC_REASON')),
    message_index            INTEGER NOT NULL CHECK (message_index >= 0),
    message                  TEXT NOT NULL,
    PRIMARY KEY (candidate_id, message_kind, message_index)
);

CREATE TABLE plan_strategy_note (
    run_id                   TEXT NOT NULL REFERENCES plan_strategy_run(id) ON DELETE CASCADE,
    note_index               INTEGER NOT NULL CHECK (note_index >= 0),
    note                     TEXT NOT NULL,
    PRIMARY KEY (run_id, note_index)
);

CREATE TABLE plan_strategy_rejection (
    run_id                   TEXT NOT NULL REFERENCES plan_strategy_run(id) ON DELETE CASCADE,
    rejection_index          INTEGER NOT NULL CHECK (rejection_index >= 0),
    family                   TEXT NOT NULL,
    display_name             TEXT,
    reason_index             INTEGER NOT NULL CHECK (reason_index >= 0),
    reason                   TEXT NOT NULL,
    PRIMARY KEY (run_id, rejection_index, reason_index)
);
