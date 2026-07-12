-- A Plan is the durable unit of work. Product data is normalized here; the existing workspace
-- JSON remains presentation-only (active plan, expanded panels, range and scroll position).

CREATE TABLE plans (
    id                   TEXT PRIMARY KEY,
    user_id              TEXT REFERENCES users(id) ON DELETE CASCADE,
    client_request_id    TEXT NOT NULL,
    create_input_hash    TEXT NOT NULL,
    origin_plan_id       TEXT REFERENCES plans(id) ON DELETE SET NULL,
    symbol               TEXT NOT NULL,
    intent               TEXT CHECK (intent IS NULL OR intent IN ('DIRECTIONAL','INCOME','HEDGE','ACQUIRE','EXIT')),
    market_kind          TEXT NOT NULL CHECK (market_kind IN ('OBSERVED','DEMO','SIMULATED')),
    world_id             TEXT,
    account_id           TEXT REFERENCES accounts(id) ON DELETE SET NULL,
    custom_title         TEXT,
    status               TEXT NOT NULL CHECK (status IN
                           ('DRAFT','ACTIVE','DECIDED_CASH','POSITION_OPEN','CLOSED','ABANDONED','ARCHIVED')),
    active_stage         TEXT NOT NULL DEFAULT 'UNDERSTAND' CHECK (active_stage IN
                           ('UNDERSTAND','EVIDENCE','STRATEGY','OUTCOMES','DECIDE','MANAGE_REVIEW')),
    active_context_rev   INTEGER,
    version              BIGINT NOT NULL DEFAULT 1,
    is_open              INTEGER NOT NULL DEFAULT 1 CHECK (is_open IN (0,1)),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((market_kind = 'SIMULATED' AND world_id IS NOT NULL)
        OR (market_kind IN ('OBSERVED','DEMO') AND world_id IS NULL))
);
CREATE UNIQUE INDEX uq_plans_owner_request
    ON plans(COALESCE(user_id, 'local'), client_request_id);
CREATE INDEX idx_plans_user_market_open ON plans(user_id, market_kind, world_id, is_open, updated_at DESC);
CREATE INDEX idx_plans_user_symbol_intent ON plans(user_id, symbol, intent, updated_at DESC);
CREATE INDEX idx_plans_origin ON plans(origin_plan_id);

CREATE TABLE plan_context_revision (
    id                       TEXT PRIMARY KEY,
    plan_id                  TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    rev                      INTEGER NOT NULL,
    thesis                   TEXT,
    horizon_days             INTEGER CHECK (horizon_days IS NULL OR horizon_days > 0),
    target_cents             BIGINT CHECK (target_cents IS NULL OR target_cents > 0),
    risk_mode                TEXT CHECK (risk_mode IS NULL OR risk_mode IN ('conservative','balanced','aggressive')),
    holdings_shares          BIGINT CHECK (holdings_shares IS NULL OR holdings_shares >= 0),
    cost_basis_cents         BIGINT CHECK (cost_basis_cents IS NULL OR cost_basis_cents >= 0),
    price_assumption_cents   BIGINT CHECK (price_assumption_cents IS NULL OR price_assumption_cents > 0),
    input_hash               TEXT NOT NULL,
    engine_version           TEXT NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(plan_id, rev)
);
CREATE INDEX idx_plan_context_plan_rev ON plan_context_revision(plan_id, rev DESC);
ALTER TABLE plans ADD CONSTRAINT fk_plans_active_context
    FOREIGN KEY (id, active_context_rev) REFERENCES plan_context_revision(plan_id, rev);

CREATE TABLE plan_evidence (
    id                   TEXT PRIMARY KEY,
    plan_id              TEXT NOT NULL,
    context_rev          INTEGER NOT NULL,
    basis                TEXT NOT NULL CHECK (basis IN
                           ('OBSERVED_HISTORY','DEMO_HISTORY','SIMULATED_HISTORY','SCENARIO_DATASET')),
    dataset_id           TEXT REFERENCES dataset(id) ON DELETE SET NULL,
    question_key         TEXT,
    study_key            TEXT,
    from_date            DATE,
    to_date              DATE,
    as_of                TIMESTAMPTZ NOT NULL,
    engine_version       TEXT NOT NULL,
    input_hash           TEXT NOT NULL,
    evidence_provenance  TEXT NOT NULL,
    sample_size          INTEGER,
    state                TEXT NOT NULL CHECK (state IN ('CURRENT','STALE','BLOCKED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, context_rev) REFERENCES plan_context_revision(plan_id, rev) ON DELETE CASCADE
);
CREATE INDEX idx_plan_evidence_plan ON plan_evidence(plan_id, context_rev, created_at DESC);

CREATE TABLE plan_evidence_metric (
    evidence_id          TEXT NOT NULL REFERENCES plan_evidence(id) ON DELETE CASCADE,
    metric_key           TEXT NOT NULL,
    value_number         DOUBLE PRECISION,
    value_cents          BIGINT,
    value_text           TEXT,
    PRIMARY KEY (evidence_id, metric_key),
    CHECK (((value_number IS NOT NULL)::int + (value_cents IS NOT NULL)::int
          + (value_text IS NOT NULL)::int) = 1)
);

-- Dense computed numeric material is intentionally stored as a compressed binary artifact. It is
-- read and verified as a whole, not queried as business fields; all decision-bearing summaries are
-- normalized in the plan tables below.
CREATE TABLE ensemble_artifact (
    fingerprint          TEXT PRIMARY KEY,
    model_version        TEXT NOT NULL,
    basis                TEXT NOT NULL CHECK (basis IN ('PARAMETRIC','HISTORICAL_ANALOGS','CONDITIONAL_BOOTSTRAP')),
    n_paths              INTEGER NOT NULL CHECK (n_paths > 0),
    n_steps              INTEGER NOT NULL CHECK (n_steps > 0),
    codec                TEXT NOT NULL,
    raw_bytes            BIGINT NOT NULL CHECK (raw_bytes > 0),
    spot_matrix          BYTEA NOT NULL,
    iv_path              BYTEA NOT NULL,
    rate_annual          DOUBLE PRECISION NOT NULL,
    step_seconds         DOUBLE PRECISION NOT NULL CHECK (step_seconds > 0),
    source_content_hash  TEXT NOT NULL,
    pinned               INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0,1)),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE plan_ensemble (
    id                       TEXT PRIMARY KEY,
    plan_id                  TEXT NOT NULL,
    context_rev              INTEGER NOT NULL,
    fingerprint              TEXT NOT NULL REFERENCES ensemble_artifact(fingerprint) ON DELETE RESTRICT,
    model_version            TEXT NOT NULL,
    anchor_spot_cents        BIGINT NOT NULL,
    anchor_source            TEXT NOT NULL,
    anchor_freshness         TEXT NOT NULL,
    dataset_id               TEXT REFERENCES dataset(id) ON DELETE SET NULL,
    as_of                    TIMESTAMPTZ NOT NULL,
    input_hash               TEXT NOT NULL,
    state                    TEXT NOT NULL CHECK (state IN ('CURRENT','STALE','BLOCKED')),
    spec_model               TEXT NOT NULL,
    spec_shape               TEXT NOT NULL,
    spec_horizon_days        INTEGER NOT NULL CHECK (spec_horizon_days > 0),
    spec_steps_per_day       INTEGER NOT NULL CHECK (spec_steps_per_day > 0),
    spec_drift_annual        DOUBLE PRECISION NOT NULL,
    spec_vol_annual          DOUBLE PRECISION NOT NULL CHECK (spec_vol_annual > 0),
    spec_jumps_per_year      DOUBLE PRECISION NOT NULL,
    spec_jump_mean           DOUBLE PRECISION NOT NULL,
    spec_jump_vol            DOUBLE PRECISION NOT NULL,
    spec_tail_nu             DOUBLE PRECISION NOT NULL,
    spec_seed                BIGINT NOT NULL,
    spec_paths               INTEGER NOT NULL CHECK (spec_paths > 0),
    iv_start                 DOUBLE PRECISION NOT NULL,
    iv_longrun               DOUBLE PRECISION NOT NULL,
    iv_shape                 TEXT NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, context_rev) REFERENCES plan_context_revision(plan_id, rev) ON DELETE CASCADE
);
CREATE INDEX idx_plan_ensemble_plan ON plan_ensemble(plan_id, context_rev, created_at DESC);

CREATE TABLE plan_ensemble_quantile (
    ensemble_id          TEXT NOT NULL REFERENCES plan_ensemble(id) ON DELETE CASCADE,
    probability          DOUBLE PRECISION NOT NULL CHECK (probability >= 0 AND probability <= 1),
    value_cents          BIGINT NOT NULL,
    PRIMARY KEY (ensemble_id, probability)
);

CREATE TABLE plan_level_odds (
    ensemble_id              TEXT NOT NULL REFERENCES plan_ensemble(id) ON DELETE CASCADE,
    level_key                TEXT NOT NULL,
    price_cents              BIGINT NOT NULL,
    direction                TEXT NOT NULL CHECK (direction IN ('ABOVE','BELOW','EITHER')),
    end_above_probability    DOUBLE PRECISION NOT NULL CHECK (end_above_probability BETWEEN 0 AND 1),
    end_below_probability    DOUBLE PRECISION NOT NULL CHECK (end_below_probability BETWEEN 0 AND 1),
    end_beyond_probability   DOUBLE PRECISION NOT NULL CHECK (end_beyond_probability BETWEEN 0 AND 1),
    touch_probability        DOUBLE PRECISION NOT NULL CHECK (touch_probability BETWEEN 0 AND 1),
    touch_ci_low             DOUBLE PRECISION NOT NULL CHECK (touch_ci_low BETWEEN 0 AND 1),
    touch_ci_high            DOUBLE PRECISION NOT NULL CHECK (touch_ci_high BETWEEN 0 AND 1),
    median_first_touch_day   DOUBLE PRECISION,
    PRIMARY KEY (ensemble_id, level_key),
    CHECK (touch_ci_low <= touch_ci_high),
    CHECK (touch_probability >= end_beyond_probability)
);

CREATE TABLE plan_candidate (
    id                       TEXT PRIMARY KEY,
    plan_id                  TEXT NOT NULL,
    context_rev              INTEGER NOT NULL,
    recommendation_id        TEXT REFERENCES recommendation(id) ON DELETE SET NULL,
    evaluation_id            TEXT REFERENCES strategy_evaluation(id) ON DELETE SET NULL,
    family                   TEXT NOT NULL,
    structure_group          TEXT,
    rank_number              INTEGER,
    decision_score           DOUBLE PRECISION,
    ev_market_cents          BIGINT,
    ev_histvol_cents         BIGINT,
    pop                      DOUBLE PRECISION CHECK (pop IS NULL OR pop BETWEEN 0 AND 1),
    assignment_probability   DOUBLE PRECISION CHECK (assignment_probability IS NULL OR assignment_probability BETWEEN 0 AND 1),
    entry_net_cents          BIGINT,
    max_loss_cents           BIGINT,
    max_profit_cents         BIGINT,
    cvar_cents               BIGINT,
    economic_verdict         TEXT,
    evidence_provenance      TEXT,
    input_hash               TEXT NOT NULL,
    state                    TEXT NOT NULL CHECK (state IN ('CURRENT','STALE','BLOCKED')),
    selected                 INTEGER NOT NULL DEFAULT 0 CHECK (selected IN (0,1)),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, context_rev) REFERENCES plan_context_revision(plan_id, rev) ON DELETE CASCADE
);
CREATE INDEX idx_plan_candidate_plan ON plan_candidate(plan_id, context_rev, rank_number, created_at DESC);
CREATE UNIQUE INDEX uq_plan_candidate_selected
    ON plan_candidate(plan_id, context_rev) WHERE selected=1;

CREATE TABLE plan_candidate_leg (
    candidate_id         TEXT NOT NULL REFERENCES plan_candidate(id) ON DELETE CASCADE,
    leg_index            INTEGER NOT NULL,
    action               TEXT NOT NULL CHECK (action IN ('BUY','SELL')),
    instrument_type      TEXT NOT NULL CHECK (instrument_type IN ('CALL','PUT','STOCK')),
    strike_cents         BIGINT,
    expiration           DATE,
    ratio                INTEGER NOT NULL CHECK (ratio > 0),
    PRIMARY KEY (candidate_id, leg_index)
);

CREATE TABLE plan_backtest (
    id                   TEXT PRIMARY KEY,
    plan_id              TEXT NOT NULL,
    context_rev          INTEGER NOT NULL,
    backtest_id          TEXT REFERENCES backtests(id) ON DELETE SET NULL,
    candidate_id         TEXT REFERENCES plan_candidate(id) ON DELETE SET NULL,
    basis                TEXT NOT NULL,
    as_of                TIMESTAMPTZ NOT NULL,
    sample_size          INTEGER,
    win_rate             DOUBLE PRECISION,
    total_pnl_cents      BIGINT,
    max_drawdown_cents   BIGINT,
    avg_return_on_risk   DOUBLE PRECISION,
    evidence_provenance  TEXT,
    input_hash           TEXT NOT NULL,
    engine_version       TEXT NOT NULL,
    state                TEXT NOT NULL CHECK (state IN ('CURRENT','STALE','BLOCKED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, context_rev) REFERENCES plan_context_revision(plan_id, rev) ON DELETE CASCADE
);

CREATE TABLE plan_decision (
    id                       TEXT PRIMARY KEY,
    plan_id                  TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    context_rev              INTEGER NOT NULL,
    candidate_id             TEXT REFERENCES plan_candidate(id) ON DELETE SET NULL,
    recommendation_id        TEXT REFERENCES recommendation(id) ON DELETE SET NULL,
    ensemble_id              TEXT REFERENCES plan_ensemble(id) ON DELETE SET NULL,
    account_id               TEXT REFERENCES accounts(id) ON DELETE SET NULL,
    action                   TEXT NOT NULL CHECK (action IN ('TRADE','CASH')),
    qty                      INTEGER,
    proposed_net_cents       BIGINT,
    quote_as_of              TIMESTAMPTZ NOT NULL,
    account_nlv_cents        BIGINT,
    buying_power_cents       BIGINT,
    risk_capital_cents       BIGINT,
    max_loss_cents           BIGINT,
    max_profit_cents         BIGINT,
    pop                      DOUBLE PRECISION,
    p_max_profit             DOUBLE PRECISION,
    p_max_loss               DOUBLE PRECISION,
    ev_market_cents          BIGINT,
    ev_histvol_cents         BIGINT,
    cvar_cents               BIGINT,
    economic_verdict         TEXT NOT NULL,
    evidence_provenance      TEXT NOT NULL,
    model_version            TEXT NOT NULL,
    study_key                TEXT,
    review_horizon_days      INTEGER NOT NULL CHECK (review_horizon_days > 0),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, context_rev) REFERENCES plan_context_revision(plan_id, rev),
    CHECK ((action='TRADE' AND qty IS NOT NULL AND qty > 0)
        OR (action='CASH' AND qty IS NULL))
);
CREATE INDEX idx_plan_decision_plan ON plan_decision(plan_id, created_at DESC);

CREATE TABLE plan_decision_leg (
    decision_id          TEXT NOT NULL REFERENCES plan_decision(id) ON DELETE CASCADE,
    leg_index            INTEGER NOT NULL,
    action               TEXT NOT NULL CHECK (action IN ('BUY','SELL')),
    instrument_type      TEXT NOT NULL CHECK (instrument_type IN ('CALL','PUT','STOCK')),
    strike_cents         BIGINT,
    expiration           DATE,
    ratio                INTEGER NOT NULL CHECK (ratio > 0),
    bid_cents            BIGINT,
    ask_cents            BIGINT,
    mid_cents            BIGINT,
    fill_cents           BIGINT,
    iv                   DOUBLE PRECISION,
    PRIMARY KEY (decision_id, leg_index)
);

CREATE TABLE plan_decision_ack (
    decision_id          TEXT NOT NULL REFERENCES plan_decision(id) ON DELETE CASCADE,
    ack_key              TEXT NOT NULL,
    ack_at               TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (decision_id, ack_key)
);

CREATE TABLE plan_decision_metric (
    decision_id          TEXT NOT NULL REFERENCES plan_decision(id) ON DELETE CASCADE,
    metric_key           TEXT NOT NULL,
    value_number         DOUBLE PRECISION,
    value_cents          BIGINT,
    value_text           TEXT,
    PRIMARY KEY (decision_id, metric_key),
    CHECK (((value_number IS NOT NULL)::int + (value_cents IS NOT NULL)::int
          + (value_text IS NOT NULL)::int) = 1)
);

CREATE TABLE plan_link (
    id                   TEXT PRIMARY KEY,
    plan_id              TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    role                 TEXT NOT NULL CHECK (role IN
                           ('ENTRY','ADJUST','ROLL','PARTIAL_CLOSE','CLOSE','REHEARSAL','EXTERNAL',
                            'RECOMMENDATION','PEER','ALTERNATIVE','HEDGE','COMPARISON')),
    trade_id             TEXT REFERENCES trades(id) ON DELETE CASCADE,
    recommendation_id    TEXT REFERENCES recommendation(id) ON DELETE CASCADE,
    sim_session_id       TEXT REFERENCES sim_session(id) ON DELETE CASCADE,
    related_plan_id      TEXT REFERENCES plans(id) ON DELETE CASCADE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (((trade_id IS NOT NULL)::int + (recommendation_id IS NOT NULL)::int
          + (sim_session_id IS NOT NULL)::int + (related_plan_id IS NOT NULL)::int) = 1),
    CHECK ((role IN ('ENTRY','ADJUST','ROLL','PARTIAL_CLOSE','CLOSE','EXTERNAL') AND trade_id IS NOT NULL)
        OR (role='RECOMMENDATION' AND recommendation_id IS NOT NULL)
        OR (role='REHEARSAL' AND sim_session_id IS NOT NULL)
        OR (role IN ('PEER','ALTERNATIVE','HEDGE','COMPARISON') AND related_plan_id IS NOT NULL))
);
CREATE INDEX idx_plan_link_plan ON plan_link(plan_id, created_at DESC);
CREATE INDEX idx_plan_link_trade ON plan_link(trade_id);
CREATE INDEX idx_plan_link_sim ON plan_link(sim_session_id);

CREATE TABLE plan_management_action (
    id                       TEXT PRIMARY KEY,
    plan_id                  TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    decision_id              TEXT REFERENCES plan_decision(id) ON DELETE SET NULL,
    trade_id                 TEXT REFERENCES trades(id) ON DELETE SET NULL,
    sim_session_id           TEXT REFERENCES sim_session(id) ON DELETE SET NULL,
    kind                     TEXT NOT NULL CHECK (kind IN
                               ('MARK','ROLL','ADJUST','PARTIAL_CLOSE','CLOSE','SETTLE','REHEARSAL_RESULT')),
    action_at                TIMESTAMPTZ NOT NULL,
    underlying_cents         BIGINT,
    position_value_cents     BIGINT,
    realized_cents           BIGINT,
    unrealized_cents         BIGINT,
    pop                      DOUBLE PRECISION,
    mae_cents                BIGINT,
    mfe_cents                BIGINT,
    note                     TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_plan_management_plan ON plan_management_action(plan_id, action_at DESC);

CREATE TABLE plan_review (
    id                       TEXT PRIMARY KEY,
    plan_id                  TEXT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    decision_id              TEXT REFERENCES plan_decision(id) ON DELETE SET NULL,
    category                 TEXT NOT NULL CHECK (category IN
                               ('OBSERVED_TRADE','SIM_REHEARSAL','CASH_DECISION')),
    horizon_days             INTEGER NOT NULL CHECK (horizon_days > 0),
    benchmark_kind           TEXT NOT NULL CHECK (benchmark_kind IN ('CASH','STOCK','REJECTED_STRATEGY','PLAN_POSITION')),
    benchmark_start_cents    BIGINT,
    benchmark_end_cents      BIGINT,
    realized_cents           BIGINT,
    predicted_pop            DOUBLE PRECISION CHECK (predicted_pop IS NULL OR predicted_pop BETWEEN 0 AND 1),
    won                      INTEGER CHECK (won IS NULL OR won IN (0,1)),
    reviewed_at              TIMESTAMPTZ NOT NULL,
    note                     TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_plan_review_plan ON plan_review(plan_id, reviewed_at DESC);
