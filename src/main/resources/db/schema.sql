--
-- PostgreSQL database dump
--


-- Dumped from database version 16.13 (Debian 16.13-1.pgdg13+1)
-- Dumped by pg_dump version 16.13 (Debian 16.13-1.pgdg13+1)


--
-- Name: enforce_current_structure_lot_allocations(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_current_structure_lot_allocations() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM portfolio_structure_member m
    JOIN portfolio_structure_revision r ON r.id=m.revision_id
    JOIN portfolio_structure s ON s.id=r.structure_id AND s.current_revision_id=r.id
    JOIN portfolio_lot l ON l.id=m.lot_id
    GROUP BY m.lot_id,l.remaining_quantity
    HAVING SUM(m.allocated_quantity)>l.remaining_quantity
  ) THEN
    RAISE EXCEPTION 'current structure allocations exceed the remaining lot quantity';
  END IF;
  RETURN NULL;
END;
$$;


--
-- Name: enforce_import_resolution_agreement(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_import_resolution_agreement() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM portfolio_import_resolution x
    JOIN portfolio_import_pending p ON p.id=x.pending_id
    JOIN portfolio_transaction t ON t.id=x.transaction_id
    JOIN position_receipt r ON r.id=x.receipt_id
    WHERE x.package_total_cents IS DISTINCT FROM p.package_net_cents
       OR x.allocated_total_cents IS DISTINCT FROM t.cash_effect_cents
       OR x.portfolio_account_id IS DISTINCT FROM t.portfolio_account_id
       OR p.status<>'RESOLVED' OR p.resolved_at IS NULL
       OR r.kind<>'RESOLUTION'
       OR r.authority IS DISTINCT FROM x.authority
       OR r.execution_lane<>'REAL'
       OR r.portfolio_account_id IS DISTINCT FROM x.portfolio_account_id
       OR r.transaction_id IS DISTINCT FROM x.transaction_id
  ) THEN
    RAISE EXCEPTION 'pending import resolution does not reconcile to its package, transaction, and receipt';
  END IF;
  RETURN NULL;
END;
$$;


--
-- Name: enforce_plan_action_artifact_agreement(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_plan_action_artifact_agreement() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM plan_portfolio_action a
    JOIN position_receipt r ON r.id=a.receipt_id
    JOIN portfolio_structure_revision sr ON sr.id=a.structure_revision_id
    WHERE r.plan_id IS DISTINCT FROM a.plan_id
       OR r.structure_revision_id IS DISTINCT FROM a.structure_revision_id
       OR r.transaction_id IS DISTINCT FROM a.transaction_id
       OR r.execution_lane<>'REAL'
       OR sr.transaction_id IS DISTINCT FROM a.transaction_id
       OR sr.action_role IS DISTINCT FROM a.role
  ) THEN
    RAISE EXCEPTION 'Plan action artifacts do not describe the same tracked action';
  END IF;
  RETURN NULL;
END;
$$;




--
-- Name: account_objective_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_objective_revision (
    id text NOT NULL,
    portfolio_account_id text NOT NULL,
    revision_no integer NOT NULL,
    objective text NOT NULL,
    direction text,
    target_exposure_cents bigint,
    assignment_preference text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT account_objective_revision_assignment_preference_check CHECK ((assignment_preference = ANY (ARRAY['AVOID'::text, 'ACCEPT'::text, 'PREFER_BELOW_BASIS'::text, 'SEEK'::text]))),
    CONSTRAINT account_objective_revision_direction_check CHECK (((direction IS NULL) OR (direction = ANY (ARRAY['BULLISH'::text, 'BEARISH'::text, 'NEUTRAL'::text, 'NON_DIRECTIONAL'::text])))),
    CONSTRAINT account_objective_revision_objective_check CHECK ((objective = ANY (ARRAY['INCOME'::text, 'ACCUMULATE'::text, 'HEDGE'::text, 'DIRECTIONAL'::text, 'CAPITAL_PRESERVATION'::text]))),
    CONSTRAINT account_objective_revision_revision_no_check CHECK ((revision_no > 0))
);


--
-- Name: accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.accounts (
    id text NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL,
    name text NOT NULL,
    type text NOT NULL,
    starting_cash_cents bigint NOT NULL,
    cash_cents bigint NOT NULL,
    reserved_cents bigint DEFAULT 0 NOT NULL,
    has_traded integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    world_id text,
    CONSTRAINT accounts_type_check CHECK ((type = ANY (ARRAY['PAPER'::text, 'LIVE'::text, 'SIMULATION'::text, 'DEMO'::text])))
);


--
-- Name: audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit (
    id bigint NOT NULL,
    ts timestamp with time zone NOT NULL,
    account_id text,
    trade_id text,
    action text NOT NULL,
    level text DEFAULT 'INFO'::text NOT NULL,
    detail_json jsonb,
    CONSTRAINT audit_detail_json_object CHECK (((detail_json IS NULL) OR (jsonb_typeof(detail_json) = 'object'::text))),
    CONSTRAINT audit_level_check CHECK ((level = ANY (ARRAY['INFO'::text, 'WARN'::text, 'BLOCK'::text, 'ERROR'::text])))
);


--
-- Name: audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: backtest_assumption; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backtest_assumption (
    backtest_id text NOT NULL,
    assumption_key text NOT NULL,
    assumption_value jsonb NOT NULL
);


--
-- Name: backtest_equity_point; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backtest_equity_point (
    backtest_id text NOT NULL,
    point_index integer NOT NULL,
    point_date date NOT NULL,
    equity_cents bigint NOT NULL,
    CONSTRAINT backtest_equity_point_point_index_check CHECK ((point_index >= 0))
);


--
-- Name: backtest_note; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backtest_note (
    backtest_id text NOT NULL,
    note_index integer NOT NULL,
    note text NOT NULL,
    CONSTRAINT backtest_note_note_index_check CHECK ((note_index >= 0))
);


--
-- Name: backtest_skip; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backtest_skip (
    backtest_id text NOT NULL,
    skip_index integer NOT NULL,
    skip_date date NOT NULL,
    reason text NOT NULL,
    CONSTRAINT backtest_skip_skip_index_check CHECK ((skip_index >= 0))
);


--
-- Name: backtest_trade; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backtest_trade (
    backtest_id text NOT NULL,
    trade_index integer NOT NULL,
    entry_date date NOT NULL,
    exit_date date NOT NULL,
    label text,
    strategy text,
    entry_net_premium_cents bigint,
    credit_cents bigint,
    exit_value_cents bigint,
    fees_cents bigint,
    pnl_cents bigint NOT NULL,
    max_loss_cents bigint NOT NULL,
    return_on_risk double precision,
    exit_reason text NOT NULL,
    assigned integer,
    entry_underlying_cents bigint,
    is_worst integer DEFAULT 0 NOT NULL,
    CONSTRAINT backtest_trade_assigned_check CHECK (((assigned IS NULL) OR (assigned = ANY (ARRAY[0, 1])))),
    CONSTRAINT backtest_trade_is_worst_check CHECK ((is_worst = ANY (ARRAY[0, 1]))),
    CONSTRAINT backtest_trade_trade_index_check CHECK ((trade_index >= 0))
);


--
-- Name: backtests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backtests (
    id text NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    run_kind text NOT NULL,
    symbol text NOT NULL,
    strategy text NOT NULL,
    from_date date NOT NULL,
    to_date date NOT NULL,
    target_dte integer,
    entry_every_days integer,
    qty integer,
    slippage_pct double precision,
    starting_cash_cents bigint,
    max_concurrent integer,
    short_delta double precision,
    width_pct double precision,
    profit_target_pct double precision,
    stop_fraction double precision,
    roll_dte integer,
    pricing_mode text NOT NULL,
    confidence text NOT NULL,
    days_requested integer,
    days_covered integer NOT NULL,
    sample_size integer NOT NULL,
    concurrent_peak integer,
    win_rate double precision,
    avg_return_on_risk double precision,
    starting_cents bigint NOT NULL,
    ending_cents bigint NOT NULL,
    max_drawdown_pct double precision NOT NULL,
    assignments integer,
    demo_underlying integer NOT NULL,
    disclaimer text NOT NULL,
    CONSTRAINT backtests_demo_underlying_check CHECK ((demo_underlying = ANY (ARRAY[0, 1]))),
    CONSTRAINT backtests_run_kind_check CHECK ((run_kind = ANY (ARRAY['SINGLE'::text, 'PORTFOLIO'::text])))
);


--
-- Name: campaign; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaign (
    id text NOT NULL,
    user_id text NOT NULL,
    symbol text,
    title text NOT NULL,
    status text NOT NULL,
    account_objective_revision_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT campaign_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'CLOSED'::text, 'ARCHIVED'::text]))),
    CONSTRAINT campaign_title_check CHECK ((btrim(title) <> ''::text))
);


--
-- Name: campaign_pending_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaign_pending_member (
    campaign_id text NOT NULL,
    pending_id text NOT NULL,
    attached_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: campaign_plan_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaign_plan_member (
    campaign_id text NOT NULL,
    plan_id text NOT NULL,
    attached_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: campaign_practice_trade_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaign_practice_trade_member (
    campaign_id text NOT NULL,
    trade_id text NOT NULL,
    attached_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: campaign_structure_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaign_structure_member (
    campaign_id text NOT NULL,
    structure_id text NOT NULL,
    attached_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: campaign_transaction_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.campaign_transaction_member (
    campaign_id text NOT NULL,
    transaction_id text NOT NULL,
    explicit_interest integer DEFAULT 0 NOT NULL,
    attached_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT campaign_transaction_member_explicit_interest_check CHECK ((explicit_interest = ANY (ARRAY[0, 1])))
);


--
-- Name: data_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_job (
    id text NOT NULL,
    kind text NOT NULL,
    status text NOT NULL,
    params jsonb,
    total integer DEFAULT 0 NOT NULL,
    done integer DEFAULT 0 NOT NULL,
    rows_written bigint DEFAULT 0 NOT NULL,
    message text,
    error text,
    user_id text DEFAULT 'local'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: data_job_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_job_item (
    job_id text NOT NULL,
    seq integer NOT NULL,
    label text NOT NULL,
    status text NOT NULL,
    rows_written bigint DEFAULT 0,
    note text
);


--
-- Name: data_quarantine; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_quarantine (
    id bigint NOT NULL,
    job_id text,
    source_key text NOT NULL,
    symbol text,
    row_ref text,
    reason text NOT NULL,
    payload_excerpt text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL
);


--
-- Name: data_quarantine_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.data_quarantine ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.data_quarantine_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: data_sync_cursor; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_sync_cursor (
    user_id text DEFAULT 'local'::text NOT NULL,
    source_key text NOT NULL,
    symbol text NOT NULL,
    domain text DEFAULT 'CANDLES'::text NOT NULL,
    interval_key text DEFAULT '1d'::text NOT NULL,
    status text DEFAULT 'NEVER'::text NOT NULL,
    requested_from date,
    requested_to date,
    last_success_date date,
    last_attempt_at timestamp with time zone,
    next_allowed_at timestamp with time zone,
    failure_count integer DEFAULT 0 NOT NULL,
    rows_written bigint DEFAULT 0 NOT NULL,
    note text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: data_sync_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_sync_schedule (
    user_id text DEFAULT 'local'::text NOT NULL,
    enabled integer DEFAULT 0 NOT NULL,
    source_key text DEFAULT 'auto'::text NOT NULL,
    symbols text DEFAULT ''::text NOT NULL,
    years integer DEFAULT 5 NOT NULL,
    last_run_date date,
    last_status text,
    last_job_id text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: dataset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dataset (
    id text NOT NULL,
    name text NOT NULL,
    kind text NOT NULL,
    symbol text,
    seed bigint,
    spec jsonb,
    evidence jsonb,
    user_id text DEFAULT 'local'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: ensemble_artifact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ensemble_artifact (
    fingerprint text NOT NULL,
    model_version text NOT NULL,
    basis text NOT NULL,
    n_paths integer NOT NULL,
    n_steps integer NOT NULL,
    codec text NOT NULL,
    raw_bytes bigint NOT NULL,
    spot_matrix bytea NOT NULL,
    iv_path bytea NOT NULL,
    rate_annual double precision NOT NULL,
    step_seconds double precision NOT NULL,
    source_content_hash text NOT NULL,
    pinned integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ensemble_artifact_basis_check CHECK ((basis = ANY (ARRAY['PARAMETRIC'::text, 'HISTORICAL_ANALOGS'::text, 'CONDITIONAL_BOOTSTRAP'::text]))),
    CONSTRAINT ensemble_artifact_n_paths_check CHECK ((n_paths > 0)),
    CONSTRAINT ensemble_artifact_n_steps_check CHECK ((n_steps > 0)),
    CONSTRAINT ensemble_artifact_pinned_check CHECK ((pinned = ANY (ARRAY[0, 1]))),
    CONSTRAINT ensemble_artifact_raw_bytes_check CHECK ((raw_bytes > 0)),
    CONSTRAINT ensemble_artifact_step_seconds_check CHECK ((step_seconds > (0)::double precision))
);


--
-- Name: ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ledger (
    id bigint NOT NULL,
    account_id text NOT NULL,
    trade_id text,
    ts timestamp with time zone NOT NULL,
    type text NOT NULL,
    amount_cents bigint NOT NULL,
    cash_after_cents bigint NOT NULL,
    reserved_after_cents bigint NOT NULL,
    memo text,
    CONSTRAINT ledger_type_check CHECK ((type = ANY (ARRAY['DEPOSIT'::text, 'RESET'::text, 'PREMIUM_OPEN'::text, 'PREMIUM_CLOSE'::text, 'SETTLEMENT'::text, 'FEE'::text, 'RESERVE_HOLD'::text, 'RESERVE_RELEASE'::text, 'ADJUSTMENT'::text, 'STOCK_BUY'::text, 'STOCK_SELL'::text])))
);


--
-- Name: ledger_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.ledger ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.ledger_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: live_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.live_orders (
    id text NOT NULL,
    client_order_id text NOT NULL,
    broker_account_key text NOT NULL,
    symbol text NOT NULL,
    preview_id text,
    broker_order_id text,
    status text NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT live_orders_payload_json_object CHECK ((jsonb_typeof(payload_json) = 'object'::text))
);


--
-- Name: market_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.market_snapshot (
    symbol text NOT NULL,
    description text,
    last numeric(19,6),
    bid numeric(19,6),
    ask numeric(19,6),
    prev_close numeric(19,6),
    optionable integer DEFAULT 0 NOT NULL,
    source text,
    freshness text,
    as_of timestamp with time zone,
    captured_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: option_bar; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.option_bar (
    id bigint NOT NULL,
    symbol text NOT NULL,
    asof date NOT NULL,
    expiration date NOT NULL,
    strike numeric(19,6) NOT NULL,
    opt_type text NOT NULL,
    bid numeric(19,6),
    ask numeric(19,6),
    last numeric(19,6),
    mark numeric(19,6),
    iv double precision,
    delta double precision,
    gamma double precision,
    theta double precision,
    vega double precision,
    open_interest bigint,
    volume bigint,
    underlying numeric(19,6),
    source text NOT NULL,
    bid_ask_observed integer DEFAULT 0 NOT NULL,
    iv_source text,
    greeks_source text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    dataset_id text DEFAULT 'observed'::text NOT NULL,
    CONSTRAINT option_bar_opt_type_check CHECK ((opt_type = ANY (ARRAY['CALL'::text, 'PUT'::text])))
);


--
-- Name: option_bar_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.option_bar ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.option_bar_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: plan_backtest; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_backtest (
    id text NOT NULL,
    plan_id text NOT NULL,
    context_rev integer NOT NULL,
    backtest_id text,
    candidate_id text,
    basis text NOT NULL,
    as_of timestamp with time zone NOT NULL,
    sample_size integer,
    win_rate double precision,
    total_pnl_cents bigint,
    max_drawdown_cents bigint,
    avg_return_on_risk double precision,
    evidence_provenance text,
    input_hash text NOT NULL,
    engine_version text NOT NULL,
    state text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    engine_kind text,
    pricing_mode text,
    confidence text,
    starting_cents bigint,
    ending_cents bigint,
    max_drawdown_pct double precision,
    demo_underlying integer,
    dataset_id text,
    CONSTRAINT plan_backtest_demo_underlying_check CHECK (((demo_underlying IS NULL) OR (demo_underlying = ANY (ARRAY[0, 1])))),
    CONSTRAINT plan_backtest_state_check CHECK ((state = ANY (ARRAY['CURRENT'::text, 'STALE'::text, 'BLOCKED'::text])))
);


--
-- Name: plan_candidate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_candidate (
    id text NOT NULL,
    plan_id text NOT NULL,
    context_rev integer NOT NULL,
    recommendation_id text,
    family text NOT NULL,
    structure_group text,
    rank_number integer,
    assignment_probability double precision,
    entry_net_cents bigint,
    max_loss_cents bigint,
    max_profit_cents bigint,
    input_hash text NOT NULL,
    state text NOT NULL,
    selected integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    run_id text,
    source_kind text,
    display_name text,
    position_label text,
    qty integer,
    liquidity_score double precision,
    freshness text,
    confidence double precision,
    why_considered text,
    best_upside text,
    biggest_risk text,
    would_invalidate text,
    beginner_explanation text,
    annualized_yield_pct double precision,
    effective_price text,
    intent_note text,
    uses_held_shares integer,
    shares_needed integer,
    combined_max_loss_cents bigint,
    underlying_symbol text,
    scout_thesis text,
    evaluation_snapshot jsonb NOT NULL,
    CONSTRAINT plan_candidate_assignment_probability_check CHECK (((assignment_probability IS NULL) OR ((assignment_probability >= (0)::double precision) AND (assignment_probability <= (1)::double precision)))),
    CONSTRAINT plan_candidate_evaluation_snapshot_object CHECK (((evaluation_snapshot IS NULL) OR (jsonb_typeof(evaluation_snapshot) = 'object'::text))),
    CONSTRAINT plan_candidate_qty_check CHECK (((qty IS NULL) OR (qty > 0))),
    CONSTRAINT plan_candidate_selected_check CHECK ((selected = ANY (ARRAY[0, 1]))),
    CONSTRAINT plan_candidate_source_kind_check CHECK ((source_kind = ANY (ARRAY['RANKED'::text, 'LADDER'::text, 'SCOUT'::text, 'CUSTOM'::text]))),
    CONSTRAINT plan_candidate_state_check CHECK ((state = ANY (ARRAY['CURRENT'::text, 'STALE'::text, 'BLOCKED'::text]))),
    CONSTRAINT plan_candidate_uses_held_shares_check CHECK (((uses_held_shares IS NULL) OR (uses_held_shares = ANY (ARRAY[0, 1]))))
);


--
-- Name: plan_candidate_breakeven; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_candidate_breakeven (
    candidate_id text NOT NULL,
    breakeven_index integer NOT NULL,
    price numeric(19,6) NOT NULL,
    CONSTRAINT plan_candidate_breakeven_breakeven_index_check CHECK ((breakeven_index >= 0))
);


--
-- Name: plan_candidate_intent; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_candidate_intent (
    candidate_id text NOT NULL,
    intent_index integer NOT NULL,
    intent text NOT NULL,
    CONSTRAINT plan_candidate_intent_intent_index_check CHECK ((intent_index >= 0))
);


--
-- Name: plan_candidate_leg; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_candidate_leg (
    candidate_id text NOT NULL,
    leg_index integer NOT NULL,
    action text NOT NULL,
    instrument_type text NOT NULL,
    strike_price numeric(19,6),
    expiration date,
    ratio integer NOT NULL,
    entry_price numeric(19,6),
    multiplier integer NOT NULL,
    CONSTRAINT plan_candidate_leg_action_check CHECK ((action = ANY (ARRAY['BUY'::text, 'SELL'::text]))),
    CONSTRAINT plan_candidate_leg_instrument_type_check CHECK ((instrument_type = ANY (ARRAY['CALL'::text, 'PUT'::text, 'STOCK'::text]))),
    CONSTRAINT plan_candidate_leg_multiplier_check CHECK (((multiplier >= 1) AND (multiplier <= 10000))),
    CONSTRAINT plan_candidate_leg_ratio_check CHECK ((ratio > 0))
);


--
-- Name: plan_candidate_warning; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_candidate_warning (
    candidate_id text NOT NULL,
    warning_index integer NOT NULL,
    message text NOT NULL,
    CONSTRAINT plan_candidate_warning_warning_index_check CHECK ((warning_index >= 0))
);


--
-- Name: plan_context_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_context_revision (
    id text NOT NULL,
    plan_id text NOT NULL,
    rev integer NOT NULL,
    thesis text,
    horizon_days integer,
    target_cents bigint,
    risk_mode text,
    holdings_shares bigint,
    cost_basis_cents bigint,
    price_assumption_cents bigint,
    input_hash text NOT NULL,
    engine_version text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plan_context_revision_cost_basis_cents_check CHECK (((cost_basis_cents IS NULL) OR (cost_basis_cents >= 0))),
    CONSTRAINT plan_context_revision_holdings_shares_check CHECK (((holdings_shares IS NULL) OR (holdings_shares >= 0))),
    CONSTRAINT plan_context_revision_horizon_days_check CHECK (((horizon_days IS NULL) OR (horizon_days > 0))),
    CONSTRAINT plan_context_revision_price_assumption_cents_check CHECK (((price_assumption_cents IS NULL) OR (price_assumption_cents > 0))),
    CONSTRAINT plan_context_revision_risk_mode_check CHECK (((risk_mode IS NULL) OR (risk_mode = ANY (ARRAY['conservative'::text, 'balanced'::text, 'aggressive'::text])))),
    CONSTRAINT plan_context_revision_target_cents_check CHECK (((target_cents IS NULL) OR (target_cents > 0)))
);


--
-- Name: plan_create_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_create_request (
    user_id text DEFAULT 'local'::text NOT NULL,
    client_request_id text NOT NULL,
    input_hash text NOT NULL,
    plan_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: plan_decision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_decision (
    id text NOT NULL,
    plan_id text NOT NULL,
    context_rev integer NOT NULL,
    candidate_id text,
    recommendation_id text,
    ensemble_id text,
    account_id text,
    action text NOT NULL,
    qty integer,
    proposed_net_cents bigint,
    quote_as_of timestamp with time zone NOT NULL,
    account_nlv_cents bigint,
    buying_power_cents bigint,
    risk_capital_cents bigint,
    max_loss_cents bigint,
    max_profit_cents bigint,
    pop double precision,
    p_max_profit double precision,
    p_max_loss double precision,
    ev_market_cents bigint,
    ev_histvol_cents bigint,
    cvar_cents bigint,
    economic_verdict text NOT NULL,
    evidence_provenance text NOT NULL,
    model_version text NOT NULL,
    study_key text,
    review_horizon_days integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    decision_seq bigint NOT NULL,
    CONSTRAINT plan_decision_action_check CHECK ((action = ANY (ARRAY['TRADE'::text, 'CASH'::text, 'BROKER'::text]))),
    CONSTRAINT plan_decision_check CHECK ((((action = ANY (ARRAY['TRADE'::text, 'BROKER'::text])) AND (qty IS NOT NULL) AND (qty > 0)) OR ((action = 'CASH'::text) AND (qty IS NULL)))),
    CONSTRAINT plan_decision_review_horizon_days_check CHECK ((review_horizon_days > 0))
);


--
-- Name: plan_decision_ack; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_decision_ack (
    decision_id text NOT NULL,
    ack_key text NOT NULL,
    ack_at timestamp with time zone NOT NULL
);


--
-- Name: plan_decision_leg; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_decision_leg (
    decision_id text NOT NULL,
    leg_index integer NOT NULL,
    action text NOT NULL,
    instrument_type text NOT NULL,
    strike_price numeric(19,6),
    expiration date,
    ratio integer NOT NULL,
    bid_price numeric(19,6),
    ask_price numeric(19,6),
    mid_price numeric(19,6),
    fill_price numeric(19,6),
    iv double precision,
    multiplier integer NOT NULL,
    CONSTRAINT plan_decision_leg_action_check CHECK ((action = ANY (ARRAY['BUY'::text, 'SELL'::text]))),
    CONSTRAINT plan_decision_leg_instrument_type_check CHECK ((instrument_type = ANY (ARRAY['CALL'::text, 'PUT'::text, 'STOCK'::text]))),
    CONSTRAINT plan_decision_leg_multiplier_check CHECK (((multiplier >= 1) AND (multiplier <= 10000))),
    CONSTRAINT plan_decision_leg_ratio_check CHECK ((ratio > 0))
);


--
-- Name: plan_decision_metric; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_decision_metric (
    decision_id text NOT NULL,
    metric_key text NOT NULL,
    value_number double precision,
    value_cents bigint,
    value_text text,
    CONSTRAINT plan_decision_metric_check CHECK ((((((value_number IS NOT NULL))::integer + ((value_cents IS NOT NULL))::integer) + ((value_text IS NOT NULL))::integer) = 1))
);


--
-- Name: plan_ensemble; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_ensemble (
    id text NOT NULL,
    plan_id text NOT NULL,
    context_rev integer NOT NULL,
    fingerprint text NOT NULL,
    model_version text NOT NULL,
    anchor_spot_cents bigint NOT NULL,
    anchor_source text NOT NULL,
    anchor_freshness text NOT NULL,
    dataset_id text,
    as_of timestamp with time zone NOT NULL,
    input_hash text NOT NULL,
    state text NOT NULL,
    spec_model text NOT NULL,
    spec_shape text NOT NULL,
    spec_horizon_days integer NOT NULL,
    spec_steps_per_day integer NOT NULL,
    spec_drift_annual double precision NOT NULL,
    spec_vol_annual double precision NOT NULL,
    spec_jumps_per_year double precision NOT NULL,
    spec_jump_mean double precision NOT NULL,
    spec_jump_vol double precision NOT NULL,
    spec_tail_nu double precision NOT NULL,
    spec_seed bigint NOT NULL,
    spec_paths integer NOT NULL,
    iv_start double precision NOT NULL,
    iv_longrun double precision NOT NULL,
    iv_shape text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    heston_kappa double precision,
    heston_theta double precision,
    heston_xi double precision,
    heston_rho double precision,
    heston_v0 double precision,
    iv_drift_per_year double precision DEFAULT 0 NOT NULL,
    iv_mean_revert_speed double precision DEFAULT 0 NOT NULL,
    iv_event_day integer DEFAULT '-1'::integer NOT NULL,
    iv_event_shock_pct double precision DEFAULT 0 NOT NULL,
    iv_min double precision DEFAULT 0.03 NOT NULL,
    iv_max double precision DEFAULT 4.0 NOT NULL,
    CONSTRAINT plan_ensemble_spec_horizon_days_check CHECK ((spec_horizon_days > 0)),
    CONSTRAINT plan_ensemble_spec_paths_check CHECK ((spec_paths > 0)),
    CONSTRAINT plan_ensemble_spec_steps_per_day_check CHECK ((spec_steps_per_day > 0)),
    CONSTRAINT plan_ensemble_spec_vol_annual_check CHECK ((spec_vol_annual > (0)::double precision)),
    CONSTRAINT plan_ensemble_state_check CHECK ((state = ANY (ARRAY['CURRENT'::text, 'STALE'::text, 'BLOCKED'::text])))
);


--
-- Name: plan_ensemble_quantile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_ensemble_quantile (
    ensemble_id text NOT NULL,
    probability double precision NOT NULL,
    value_cents bigint NOT NULL,
    CONSTRAINT plan_ensemble_quantile_probability_check CHECK (((probability >= (0)::double precision) AND (probability <= (1)::double precision)))
);


--
-- Name: plan_evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence (
    id text NOT NULL,
    plan_id text NOT NULL,
    context_rev integer NOT NULL,
    basis text NOT NULL,
    dataset_id text,
    question_key text,
    study_key text,
    from_date date,
    to_date date,
    as_of timestamp with time zone NOT NULL,
    engine_version text NOT NULL,
    input_hash text NOT NULL,
    evidence_provenance text NOT NULL,
    sample_size integer,
    state text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plan_evidence_basis_check CHECK ((basis = ANY (ARRAY['OBSERVED_HISTORY'::text, 'DEMO_HISTORY'::text, 'SIMULATED_HISTORY'::text, 'SCENARIO_DATASET'::text]))),
    CONSTRAINT plan_evidence_state_check CHECK ((state = ANY (ARRAY['CURRENT'::text, 'STALE'::text, 'BLOCKED'::text])))
);


--
-- Name: plan_evidence_analog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence_analog (
    evidence_id text NOT NULL,
    path_index integer NOT NULL,
    step_index integer NOT NULL,
    relative_price double precision NOT NULL,
    CONSTRAINT plan_evidence_analog_path_index_check CHECK ((path_index >= 0)),
    CONSTRAINT plan_evidence_analog_relative_price_check CHECK ((relative_price > (0)::double precision)),
    CONSTRAINT plan_evidence_analog_step_index_check CHECK ((step_index >= 0))
);


--
-- Name: plan_evidence_distribution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence_distribution (
    evidence_id text NOT NULL,
    bucket_index integer NOT NULL,
    from_pct double precision NOT NULL,
    to_pct double precision NOT NULL,
    sample_count integer NOT NULL,
    CONSTRAINT plan_evidence_distribution_bucket_index_check CHECK ((bucket_index >= 0)),
    CONSTRAINT plan_evidence_distribution_check CHECK ((from_pct <= to_pct)),
    CONSTRAINT plan_evidence_distribution_sample_count_check CHECK ((sample_count >= 0))
);


--
-- Name: plan_evidence_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence_event (
    evidence_id text NOT NULL,
    event_index integer NOT NULL,
    event_date date NOT NULL,
    CONSTRAINT plan_evidence_event_event_index_check CHECK ((event_index >= 0))
);


--
-- Name: plan_evidence_example; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence_example (
    evidence_id text NOT NULL,
    example_index integer NOT NULL,
    event_date date NOT NULL,
    CONSTRAINT plan_evidence_example_example_index_check CHECK ((example_index >= 0))
);


--
-- Name: plan_evidence_metric; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence_metric (
    evidence_id text NOT NULL,
    metric_key text NOT NULL,
    value_number double precision,
    value_cents bigint,
    value_text text,
    CONSTRAINT plan_evidence_metric_check CHECK ((((((value_number IS NOT NULL))::integer + ((value_cents IS NOT NULL))::integer) + ((value_text IS NOT NULL))::integer) = 1))
);


--
-- Name: plan_evidence_note; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence_note (
    evidence_id text NOT NULL,
    note_index integer NOT NULL,
    note text NOT NULL,
    CONSTRAINT plan_evidence_note_note_index_check CHECK ((note_index >= 0))
);


--
-- Name: plan_evidence_param; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence_param (
    evidence_id text NOT NULL,
    param_key text NOT NULL,
    value_number double precision,
    value_text text,
    value_boolean integer,
    CONSTRAINT plan_evidence_param_check CHECK ((((((value_number IS NOT NULL))::integer + ((value_text IS NOT NULL))::integer) + ((value_boolean IS NOT NULL))::integer) = 1)),
    CONSTRAINT plan_evidence_param_value_boolean_check CHECK (((value_boolean IS NULL) OR (value_boolean = ANY (ARRAY[0, 1]))))
);


--
-- Name: plan_evidence_stat; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_evidence_stat (
    evidence_id text NOT NULL,
    sample_kind text NOT NULL,
    sample_size integer NOT NULL,
    win_rate_pct double precision NOT NULL,
    mean_return_pct double precision NOT NULL,
    median_return_pct double precision NOT NULL,
    worst_pct double precision NOT NULL,
    best_pct double precision NOT NULL,
    CONSTRAINT plan_evidence_stat_sample_kind_check CHECK ((sample_kind = ANY (ARRAY['BASELINE'::text, 'CONDITIONED'::text]))),
    CONSTRAINT plan_evidence_stat_sample_size_check CHECK ((sample_size >= 0)),
    CONSTRAINT plan_evidence_stat_win_rate_pct_check CHECK (((win_rate_pct >= (0)::double precision) AND (win_rate_pct <= (100)::double precision)))
);


--
-- Name: plan_level_odds; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_level_odds (
    ensemble_id text NOT NULL,
    level_key text NOT NULL,
    price_cents bigint NOT NULL,
    direction text NOT NULL,
    end_above_probability double precision NOT NULL,
    end_below_probability double precision NOT NULL,
    end_beyond_probability double precision NOT NULL,
    touch_probability double precision NOT NULL,
    touch_ci_low double precision NOT NULL,
    touch_ci_high double precision NOT NULL,
    median_first_touch_day double precision,
    CONSTRAINT plan_level_odds_check CHECK ((touch_ci_low <= touch_ci_high)),
    CONSTRAINT plan_level_odds_check1 CHECK ((touch_probability >= end_beyond_probability)),
    CONSTRAINT plan_level_odds_direction_check CHECK ((direction = ANY (ARRAY['ABOVE'::text, 'BELOW'::text, 'EITHER'::text]))),
    CONSTRAINT plan_level_odds_end_above_probability_check CHECK (((end_above_probability >= (0)::double precision) AND (end_above_probability <= (1)::double precision))),
    CONSTRAINT plan_level_odds_end_below_probability_check CHECK (((end_below_probability >= (0)::double precision) AND (end_below_probability <= (1)::double precision))),
    CONSTRAINT plan_level_odds_end_beyond_probability_check CHECK (((end_beyond_probability >= (0)::double precision) AND (end_beyond_probability <= (1)::double precision))),
    CONSTRAINT plan_level_odds_touch_ci_high_check CHECK (((touch_ci_high >= (0)::double precision) AND (touch_ci_high <= (1)::double precision))),
    CONSTRAINT plan_level_odds_touch_ci_low_check CHECK (((touch_ci_low >= (0)::double precision) AND (touch_ci_low <= (1)::double precision))),
    CONSTRAINT plan_level_odds_touch_probability_check CHECK (((touch_probability >= (0)::double precision) AND (touch_probability <= (1)::double precision)))
);


--
-- Name: plan_link; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_link (
    id text NOT NULL,
    plan_id text NOT NULL,
    role text NOT NULL,
    trade_id text,
    recommendation_id text,
    sim_session_id text,
    related_plan_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    decision_id text,
    CONSTRAINT plan_link_check CHECK (((((((trade_id IS NOT NULL))::integer + ((recommendation_id IS NOT NULL))::integer) + ((sim_session_id IS NOT NULL))::integer) + ((related_plan_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT plan_link_check1 CHECK ((((role = ANY (ARRAY['ENTRY'::text, 'ADJUST'::text, 'ROLL'::text, 'PARTIAL_CLOSE'::text, 'CLOSE'::text, 'ASSIGNMENT'::text, 'EXERCISE'::text, 'EXPIRATION'::text, 'EXTERNAL'::text])) AND (trade_id IS NOT NULL)) OR ((role = 'RECOMMENDATION'::text) AND (recommendation_id IS NOT NULL)) OR ((role = 'REHEARSAL'::text) AND (sim_session_id IS NOT NULL)) OR ((role = ANY (ARRAY['PEER'::text, 'ALTERNATIVE'::text, 'HEDGE'::text, 'COMPARISON'::text])) AND (related_plan_id IS NOT NULL)))),
    CONSTRAINT plan_link_role_check CHECK ((role = ANY (ARRAY['ENTRY'::text, 'ADJUST'::text, 'ROLL'::text, 'PARTIAL_CLOSE'::text, 'CLOSE'::text, 'ASSIGNMENT'::text, 'EXERCISE'::text, 'EXPIRATION'::text, 'REHEARSAL'::text, 'EXTERNAL'::text, 'RECOMMENDATION'::text, 'PEER'::text, 'ALTERNATIVE'::text, 'HEDGE'::text, 'COMPARISON'::text])))
);


--
-- Name: plan_management_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_management_action (
    id text NOT NULL,
    plan_id text NOT NULL,
    decision_id text,
    trade_id text,
    receipt_id text,
    sim_session_id text,
    kind text NOT NULL,
    action_at timestamp with time zone NOT NULL,
    underlying_cents bigint,
    position_value_cents bigint,
    realized_cents bigint,
    unrealized_cents bigint,
    pop double precision,
    mae_cents bigint,
    mfe_cents bigint,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plan_management_action_kind_check CHECK ((kind = ANY (ARRAY['MARK'::text, 'ROLL'::text, 'ADJUST'::text, 'PARTIAL_CLOSE'::text, 'CLOSE'::text, 'ASSIGNMENT'::text, 'EXERCISE'::text, 'EXPIRATION'::text, 'SETTLE'::text, 'VOID'::text, 'REHEARSAL_RESULT'::text])))
);


--
-- Name: plan_outcome_band; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_outcome_band (
    outcome_id text NOT NULL,
    day_index integer NOT NULL,
    p10_cents bigint NOT NULL,
    p50_cents bigint NOT NULL,
    p90_cents bigint NOT NULL,
    CONSTRAINT plan_outcome_band_day_index_check CHECK ((day_index >= 0))
);


--
-- Name: plan_outcome_bucket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_outcome_bucket (
    outcome_id text NOT NULL,
    bucket_index integer NOT NULL,
    from_cents bigint NOT NULL,
    to_cents bigint NOT NULL,
    sample_count integer NOT NULL,
    CONSTRAINT plan_outcome_bucket_bucket_index_check CHECK ((bucket_index >= 0)),
    CONSTRAINT plan_outcome_bucket_check CHECK ((from_cents <= to_cents)),
    CONSTRAINT plan_outcome_bucket_sample_count_check CHECK ((sample_count >= 0))
);


--
-- Name: plan_outcome_comparison; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_outcome_comparison (
    id text NOT NULL,
    plan_id text NOT NULL,
    context_rev integer NOT NULL,
    ensemble_id text NOT NULL,
    ensemble_fingerprint text NOT NULL,
    dataset_id text,
    basis text NOT NULL,
    interpretation text NOT NULL,
    fairness text NOT NULL,
    input_hash text NOT NULL,
    engine_version text NOT NULL,
    state text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plan_outcome_comparison_basis_check CHECK ((basis = ANY (ARRAY['PARAMETRIC'::text, 'HISTORICAL_ANALOGS'::text, 'CONDITIONAL_BOOTSTRAP'::text]))),
    CONSTRAINT plan_outcome_comparison_state_check CHECK ((state = ANY (ARRAY['CURRENT'::text, 'STALE'::text, 'BLOCKED'::text])))
);


--
-- Name: plan_outcome_comparison_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_outcome_comparison_item (
    comparison_id text NOT NULL,
    item_key text NOT NULL,
    candidate_id text,
    rank_number integer NOT NULL,
    strategy text NOT NULL,
    display_name text NOT NULL,
    qty integer NOT NULL,
    entry_cost_cents bigint,
    max_loss_cents bigint,
    win_rate_pct double precision,
    expected_pnl_cents bigint,
    p5_cents bigint,
    p50_cents bigint,
    p95_cents bigint,
    tail_return_score double precision,
    round_trip_fees_cents bigint DEFAULT 0 NOT NULL,
    economic_verdict text,
    economic_placement text,
    mechanically_eligible integer,
    decision_score double precision,
    selected integer DEFAULT 0 NOT NULL,
    refusal_reason text,
    CONSTRAINT plan_outcome_comparison_item_mechanically_eligible_check CHECK (((mechanically_eligible IS NULL) OR (mechanically_eligible = ANY (ARRAY[0, 1])))),
    CONSTRAINT plan_outcome_comparison_item_qty_check CHECK ((qty >= 0)),
    CONSTRAINT plan_outcome_comparison_item_rank_number_check CHECK ((rank_number >= 0)),
    CONSTRAINT plan_outcome_comparison_item_round_trip_fees_cents_check CHECK ((round_trip_fees_cents >= 0)),
    CONSTRAINT plan_outcome_comparison_item_selected_check CHECK ((selected = ANY (ARRAY[0, 1])))
);


--
-- Name: plan_outcome_market_metric; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_outcome_market_metric (
    outcome_id text NOT NULL,
    metric_key text NOT NULL,
    value_number double precision,
    value_cents bigint,
    value_text text,
    CONSTRAINT plan_outcome_market_metric_check CHECK ((((((value_number IS NOT NULL))::integer + ((value_cents IS NOT NULL))::integer) + ((value_text IS NOT NULL))::integer) = 1))
);


--
-- Name: plan_outcome_note; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_outcome_note (
    outcome_id text NOT NULL,
    note_index integer NOT NULL,
    note text NOT NULL,
    CONSTRAINT plan_outcome_note_note_index_check CHECK ((note_index >= 0))
);


--
-- Name: plan_outcome_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_outcome_run (
    id text NOT NULL,
    plan_id text NOT NULL,
    context_rev integer NOT NULL,
    candidate_id text NOT NULL,
    ensemble_id text,
    basis text NOT NULL,
    interpretation text NOT NULL,
    entry_cost_cents bigint,
    paths integer,
    horizon_days integer,
    p5_cents bigint,
    p25_cents bigint,
    p50_cents bigint,
    p75_cents bigint,
    p95_cents bigint,
    expected_pnl_cents bigint,
    win_rate_pct double precision,
    best_cents bigint,
    worst_cents bigint,
    breach_probability_pct double precision,
    input_hash text NOT NULL,
    engine_version text NOT NULL,
    state text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    dataset_id text,
    CONSTRAINT plan_outcome_run_basis_check CHECK ((basis = ANY (ARRAY['RISK_NEUTRAL'::text, 'PARAMETRIC'::text, 'HISTORICAL_ANALOGS'::text, 'CONDITIONAL_BOOTSTRAP'::text]))),
    CONSTRAINT plan_outcome_run_state_check CHECK ((state = ANY (ARRAY['CURRENT'::text, 'STALE'::text, 'BLOCKED'::text])))
);


--
-- Name: plan_portfolio_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_portfolio_action (
    id text NOT NULL,
    plan_id text NOT NULL,
    structure_revision_id text NOT NULL,
    transaction_id text NOT NULL,
    receipt_id text NOT NULL,
    role text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plan_portfolio_action_role_check CHECK ((role = ANY (ARRAY['ENTRY'::text, 'ADJUST'::text, 'ROLL'::text, 'PARTIAL_CLOSE'::text, 'CLOSE'::text, 'ASSIGNMENT'::text, 'EXERCISE'::text, 'EXPIRATION'::text])))
);


--
-- Name: plan_review; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_review (
    id text NOT NULL,
    plan_id text NOT NULL,
    decision_id text,
    category text NOT NULL,
    horizon_days integer NOT NULL,
    benchmark_kind text NOT NULL,
    benchmark_start_cents bigint,
    benchmark_end_cents bigint,
    realized_cents bigint,
    predicted_pop double precision,
    won integer,
    reviewed_at timestamp with time zone NOT NULL,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plan_review_benchmark_kind_check CHECK ((benchmark_kind = ANY (ARRAY['CASH'::text, 'STOCK'::text, 'REJECTED_STRATEGY'::text, 'PLAN_POSITION'::text]))),
    CONSTRAINT plan_review_category_check CHECK ((category = ANY (ARRAY['TRADE_DECISION'::text, 'SIM_REHEARSAL'::text, 'CASH_DECISION'::text]))),
    CONSTRAINT plan_review_horizon_days_check CHECK ((horizon_days > 0)),
    CONSTRAINT plan_review_predicted_pop_check CHECK (((predicted_pop IS NULL) OR ((predicted_pop >= (0)::double precision) AND (predicted_pop <= (1)::double precision)))),
    CONSTRAINT plan_review_won_check CHECK (((won IS NULL) OR (won = ANY (ARRAY[0, 1]))))
);


--
-- Name: plan_strategy_note; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_strategy_note (
    run_id text NOT NULL,
    note_index integer NOT NULL,
    note text NOT NULL,
    CONSTRAINT plan_strategy_note_note_index_check CHECK ((note_index >= 0))
);


--
-- Name: plan_strategy_rejection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_strategy_rejection (
    run_id text NOT NULL,
    rejection_index integer NOT NULL,
    family text NOT NULL,
    display_name text,
    reason_index integer NOT NULL,
    reason text NOT NULL,
    CONSTRAINT plan_strategy_rejection_reason_index_check CHECK ((reason_index >= 0)),
    CONSTRAINT plan_strategy_rejection_rejection_index_check CHECK ((rejection_index >= 0))
);


--
-- Name: plan_strategy_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_strategy_run (
    id text NOT NULL,
    plan_id text NOT NULL,
    context_rev integer NOT NULL,
    run_kind text NOT NULL,
    scope_kind text NOT NULL,
    thesis text,
    horizon text NOT NULL,
    risk_mode text NOT NULL,
    intent text NOT NULL,
    risk_budget_cents bigint,
    spot_cents bigint,
    ranking_policy text,
    economic_message text,
    favorable_count integer DEFAULT 0 NOT NULL,
    mixed_count integer DEFAULT 0 NOT NULL,
    unfavorable_count integer DEFAULT 0 NOT NULL,
    unavailable_count integer DEFAULT 0 NOT NULL,
    disclaimer text,
    input_hash text NOT NULL,
    engine_version text NOT NULL,
    state text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    request_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT plan_strategy_run_request_snapshot_object CHECK ((jsonb_typeof(request_snapshot) = 'object'::text)),
    CONSTRAINT plan_strategy_run_run_kind_check CHECK ((run_kind = ANY (ARRAY['COMPETITION'::text, 'SCOUT'::text, 'CUSTOM'::text]))),
    CONSTRAINT plan_strategy_run_scope_kind_check CHECK ((scope_kind = ANY (ARRAY['PLAN'::text, 'UNIVERSE'::text, 'PEERS'::text, 'ALTERNATIVES'::text, 'HEDGES'::text]))),
    CONSTRAINT plan_strategy_run_state_check CHECK ((state = ANY (ARRAY['CURRENT'::text, 'STALE'::text, 'BLOCKED'::text])))
);


--
-- Name: plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plans (
    id text NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL,
    origin_plan_id text,
    symbol text NOT NULL,
    intent text,
    market_kind text NOT NULL,
    world_id text,
    account_id text,
    custom_title text,
    status text NOT NULL,
    furthest_stage text DEFAULT 'UNDERSTAND'::text NOT NULL,
    active_context_rev integer,
    version bigint DEFAULT 1 NOT NULL,
    is_open integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plans_active_stage_check CHECK ((furthest_stage = ANY (ARRAY['UNDERSTAND'::text, 'EVIDENCE'::text, 'STRATEGY'::text, 'OUTCOMES'::text, 'DECIDE'::text, 'MANAGE_REVIEW'::text]))),
    CONSTRAINT plans_check CHECK ((((market_kind = 'SIMULATED'::text) AND (world_id IS NOT NULL)) OR ((market_kind = ANY (ARRAY['OBSERVED'::text, 'DEMO'::text])) AND (world_id IS NULL)))),
    CONSTRAINT plans_intent_check CHECK (((intent IS NULL) OR (intent = ANY (ARRAY['DIRECTIONAL'::text, 'INCOME'::text, 'HEDGE'::text, 'ACQUIRE'::text, 'EXIT'::text])))),
    CONSTRAINT plans_is_open_check CHECK ((is_open = ANY (ARRAY[0, 1]))),
    CONSTRAINT plans_market_kind_check CHECK ((market_kind = ANY (ARRAY['OBSERVED'::text, 'DEMO'::text, 'SIMULATED'::text]))),
    CONSTRAINT plans_status_check CHECK ((status = ANY (ARRAY['DRAFT'::text, 'ACTIVE'::text, 'DECIDED_CASH'::text, 'POSITION_OPEN'::text, 'CLOSED'::text, 'ABANDONED'::text, 'ARCHIVED'::text])))
);


--
-- Name: portfolio_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_account (
    id text NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL,
    name text NOT NULL,
    account_type text NOT NULL,
    broker text,
    lot_method text DEFAULT 'FIFO'::text NOT NULL,
    short_term_tax_rate_bps integer,
    long_term_tax_rate_bps integer,
    ordinary_tax_rate_bps integer,
    state_tax_rate_bps integer,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT portfolio_account_account_type_check CHECK ((account_type = ANY (ARRAY['TAXABLE'::text, 'TRADITIONAL_IRA'::text, 'ROTH_IRA'::text, 'TRADITIONAL_401K'::text, 'ROTH_401K'::text]))),
    CONSTRAINT portfolio_account_lot_method_check CHECK ((lot_method = ANY (ARRAY['FIFO'::text, 'LIFO'::text, 'HIFO'::text]))),
    CONSTRAINT portfolio_account_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'ARCHIVED'::text])))
);


--
-- Name: portfolio_import_pending; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_import_pending (
    id text NOT NULL,
    user_id text NOT NULL,
    source_system text NOT NULL,
    source_account_fingerprint text NOT NULL,
    external_ref text NOT NULL,
    broker text,
    occurred_at timestamp with time zone NOT NULL,
    package_net_cents bigint NOT NULL,
    fees_cents bigint DEFAULT 0 NOT NULL,
    plan_id text,
    status text DEFAULT 'PENDING'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    CONSTRAINT portfolio_import_pending_check CHECK ((((status = 'PENDING'::text) AND (resolved_at IS NULL)) OR ((status = ANY (ARRAY['RESOLVED'::text, 'REJECTED'::text])) AND (resolved_at IS NOT NULL)))),
    CONSTRAINT portfolio_import_pending_external_ref_check CHECK ((btrim(external_ref) <> ''::text)),
    CONSTRAINT portfolio_import_pending_fees_cents_check CHECK ((fees_cents >= 0)),
    CONSTRAINT portfolio_import_pending_source_account_fingerprint_check CHECK ((btrim(source_account_fingerprint) <> ''::text)),
    CONSTRAINT portfolio_import_pending_source_system_check CHECK ((btrim(source_system) <> ''::text)),
    CONSTRAINT portfolio_import_pending_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'RESOLVED'::text, 'REJECTED'::text])))
);


--
-- Name: portfolio_import_pending_leg; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_import_pending_leg (
    pending_id text NOT NULL,
    leg_no integer NOT NULL,
    instrument_type text NOT NULL,
    action text NOT NULL,
    position_effect text NOT NULL,
    symbol text NOT NULL,
    option_type text,
    strike numeric(19,6),
    expiration date,
    quantity bigint NOT NULL,
    multiplier integer NOT NULL,
    reported_price numeric(19,6),
    reported_price_authority text DEFAULT 'MISSING'::text NOT NULL,
    CONSTRAINT portfolio_import_pending_leg_action_check CHECK ((action = ANY (ARRAY['BUY'::text, 'SELL'::text]))),
    CONSTRAINT portfolio_import_pending_leg_check CHECK ((((instrument_type = 'STOCK'::text) AND (option_type IS NULL) AND (strike IS NULL) AND (expiration IS NULL) AND (multiplier = 1)) OR ((instrument_type = 'OPTION'::text) AND (option_type IS NOT NULL) AND (strike IS NOT NULL) AND (expiration IS NOT NULL)))),
    CONSTRAINT portfolio_import_pending_leg_check1 CHECK ((((reported_price_authority = 'MISSING'::text) AND (reported_price IS NULL)) OR ((reported_price_authority <> 'MISSING'::text) AND (reported_price IS NOT NULL) AND (reported_price >= (0)::numeric)))),
    CONSTRAINT portfolio_import_pending_leg_instrument_type_check CHECK ((instrument_type = ANY (ARRAY['STOCK'::text, 'OPTION'::text]))),
    CONSTRAINT portfolio_import_pending_leg_leg_no_check CHECK ((leg_no >= 0)),
    CONSTRAINT portfolio_import_pending_leg_multiplier_check CHECK ((multiplier > 0)),
    CONSTRAINT portfolio_import_pending_leg_option_type_check CHECK (((option_type IS NULL) OR (option_type = ANY (ARRAY['CALL'::text, 'PUT'::text])))),
    CONSTRAINT portfolio_import_pending_leg_position_effect_check CHECK ((position_effect = ANY (ARRAY['OPEN'::text, 'CLOSE'::text]))),
    CONSTRAINT portfolio_import_pending_leg_quantity_check CHECK ((quantity > 0)),
    CONSTRAINT portfolio_import_pending_leg_reported_price_authority_check CHECK ((reported_price_authority = ANY (ARRAY['MISSING'::text, 'BROKER_REPORTED'::text, 'USER_ALLOCATED'::text]))),
    CONSTRAINT portfolio_import_pending_leg_symbol_check CHECK ((btrim(symbol) <> ''::text))
);


--
-- Name: portfolio_import_resolution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_import_resolution (
    pending_id text NOT NULL,
    portfolio_account_id text NOT NULL,
    transaction_id text NOT NULL,
    receipt_id text NOT NULL,
    authority text NOT NULL,
    tax_basis_status text NOT NULL,
    package_total_cents bigint NOT NULL,
    allocated_total_cents bigint NOT NULL,
    resolver_user_id text NOT NULL,
    resolved_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT portfolio_import_resolution_authority_check CHECK ((authority = ANY (ARRAY['BROKER_REPORTED'::text, 'USER_ALLOCATED'::text]))),
    CONSTRAINT portfolio_import_resolution_check CHECK ((package_total_cents = allocated_total_cents)),
    CONSTRAINT portfolio_import_resolution_check1 CHECK ((((authority = 'BROKER_REPORTED'::text) AND (tax_basis_status = 'AUTHORITATIVE'::text)) OR ((authority = 'USER_ALLOCATED'::text) AND (tax_basis_status = 'PROVISIONAL'::text)))),
    CONSTRAINT portfolio_import_resolution_tax_basis_status_check CHECK ((tax_basis_status = ANY (ARRAY['AUTHORITATIVE'::text, 'PROVISIONAL'::text])))
);


--
-- Name: portfolio_lot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_lot (
    id text NOT NULL,
    portfolio_account_id text NOT NULL,
    opening_transaction_id text NOT NULL,
    opening_leg_no integer NOT NULL,
    instrument_type text NOT NULL,
    side text NOT NULL,
    symbol text NOT NULL,
    option_type text,
    strike numeric(19,6),
    expiration date,
    opened_at timestamp with time zone NOT NULL,
    original_quantity bigint NOT NULL,
    remaining_quantity bigint NOT NULL,
    original_open_amount_cents bigint NOT NULL,
    remaining_open_amount_cents bigint NOT NULL,
    status text DEFAULT 'OPEN'::text NOT NULL,
    multiplier integer DEFAULT 1 NOT NULL,
    acquired_at timestamp with time zone NOT NULL,
    section_1256 integer DEFAULT 0 NOT NULL,
    economic_original_open_amount_cents bigint NOT NULL,
    economic_remaining_open_amount_cents bigint NOT NULL,
    CONSTRAINT portfolio_lot_economic_amounts_nonnegative CHECK (((economic_original_open_amount_cents >= 0) AND (economic_remaining_open_amount_cents >= 0))),
    CONSTRAINT portfolio_lot_instrument_type_check CHECK ((instrument_type = ANY (ARRAY['STOCK'::text, 'OPTION'::text]))),
    CONSTRAINT portfolio_lot_multiplier_check CHECK ((multiplier > 0)),
    CONSTRAINT portfolio_lot_original_open_amount_cents_check CHECK ((original_open_amount_cents >= 0)),
    CONSTRAINT portfolio_lot_original_quantity_check CHECK ((original_quantity > 0)),
    CONSTRAINT portfolio_lot_remaining_open_amount_cents_check CHECK ((remaining_open_amount_cents >= 0)),
    CONSTRAINT portfolio_lot_remaining_quantity_check CHECK ((remaining_quantity >= 0)),
    CONSTRAINT portfolio_lot_section_1256_check CHECK ((section_1256 = ANY (ARRAY[0, 1]))),
    CONSTRAINT portfolio_lot_side_check CHECK ((side = ANY (ARRAY['LONG'::text, 'SHORT'::text]))),
    CONSTRAINT portfolio_lot_status_check CHECK ((status = ANY (ARRAY['OPEN'::text, 'CLOSED'::text, 'ROLLED'::text])))
);


--
-- Name: portfolio_lot_match; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_lot_match (
    id bigint NOT NULL,
    portfolio_account_id text NOT NULL,
    lot_id text NOT NULL,
    closing_transaction_id text NOT NULL,
    closing_leg_no integer NOT NULL,
    quantity bigint NOT NULL,
    opened_at timestamp with time zone NOT NULL,
    closed_at timestamp with time zone NOT NULL,
    open_amount_cents bigint NOT NULL,
    close_amount_cents bigint NOT NULL,
    realized_gain_cents bigint NOT NULL,
    holding_term text NOT NULL,
    wash_sale_adjustment_cents bigint DEFAULT 0 NOT NULL,
    section_1256 integer DEFAULT 0 NOT NULL,
    economic_open_amount_cents bigint NOT NULL,
    economic_close_amount_cents bigint NOT NULL,
    economic_realized_gain_cents bigint NOT NULL,
    CONSTRAINT portfolio_lot_match_holding_term_check CHECK ((holding_term = ANY (ARRAY['SHORT_TERM'::text, 'LONG_TERM'::text, 'SECTION_1256'::text, 'ROLLED'::text]))),
    CONSTRAINT portfolio_lot_match_quantity_check CHECK ((quantity > 0)),
    CONSTRAINT portfolio_lot_match_section_1256_check CHECK ((section_1256 = ANY (ARRAY[0, 1])))
);


--
-- Name: portfolio_lot_match_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.portfolio_lot_match ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.portfolio_lot_match_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: portfolio_roll; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_roll (
    transaction_id text NOT NULL,
    portfolio_account_id text NOT NULL,
    closing_leg_no integer NOT NULL,
    opening_leg_no integer NOT NULL,
    replacement_lot_id text NOT NULL,
    quantity bigint NOT NULL,
    closing_premium_cents bigint NOT NULL,
    opening_premium_cents bigint NOT NULL,
    premium_carryover_cents bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT portfolio_roll_check CHECK ((premium_carryover_cents = (closing_premium_cents + opening_premium_cents))),
    CONSTRAINT portfolio_roll_quantity_check CHECK ((quantity > 0))
);


--
-- Name: portfolio_roll_match; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_roll_match (
    transaction_id text NOT NULL,
    lot_match_id bigint NOT NULL
);


--
-- Name: portfolio_structure; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_structure (
    id text NOT NULL,
    user_id text NOT NULL,
    portfolio_account_id text NOT NULL,
    symbol text NOT NULL,
    label text,
    status text NOT NULL,
    current_revision_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT portfolio_structure_status_check CHECK ((status = ANY (ARRAY['OPEN'::text, 'CLOSED'::text, 'RETIRED'::text]))),
    CONSTRAINT portfolio_structure_symbol_check CHECK ((btrim(symbol) <> ''::text))
);


--
-- Name: portfolio_structure_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_structure_member (
    revision_id text NOT NULL,
    leg_no integer NOT NULL,
    lot_id text NOT NULL,
    allocated_quantity bigint NOT NULL,
    leg_role text NOT NULL,
    CONSTRAINT portfolio_structure_member_allocated_quantity_check CHECK ((allocated_quantity > 0)),
    CONSTRAINT portfolio_structure_member_leg_no_check CHECK ((leg_no >= 0)),
    CONSTRAINT portfolio_structure_member_leg_role_check CHECK ((leg_role = ANY (ARRAY['UNDERLYING'::text, 'LONG_CALL'::text, 'SHORT_CALL'::text, 'LONG_PUT'::text, 'SHORT_PUT'::text, 'CUSTOM'::text])))
);


--
-- Name: portfolio_structure_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_structure_revision (
    id text NOT NULL,
    structure_id text NOT NULL,
    revision_no integer NOT NULL,
    prior_revision_id text,
    position_state text NOT NULL,
    action_role text NOT NULL,
    transaction_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT portfolio_structure_revision_action_role_check CHECK ((action_role = ANY (ARRAY['ENTRY'::text, 'ADJUST'::text, 'ROLL'::text, 'PARTIAL_CLOSE'::text, 'CLOSE'::text, 'ASSIGNMENT'::text, 'EXERCISE'::text, 'EXPIRATION'::text]))),
    CONSTRAINT portfolio_structure_revision_position_state_check CHECK ((position_state = ANY (ARRAY['PENDING'::text, 'OPEN'::text, 'PARTIALLY_CLOSED'::text, 'ASSIGNED'::text, 'EXERCISED'::text, 'EXPIRED'::text, 'CLOSED'::text]))),
    CONSTRAINT portfolio_structure_revision_revision_no_check CHECK ((revision_no > 0))
);


--
-- Name: portfolio_tax_reconciliation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_tax_reconciliation (
    portfolio_account_id text NOT NULL,
    tax_year integer NOT NULL,
    status text NOT NULL,
    form_reference text,
    broker_short_term_gain_cents bigint,
    broker_long_term_gain_cents bigint,
    broker_wash_adjustment_cents bigint,
    broker_section_1256_gain_cents bigint,
    broker_interest_cents bigint,
    broker_ordinary_dividend_cents bigint,
    broker_qualified_dividend_cents bigint,
    broker_capital_gain_distribution_cents bigint,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT portfolio_tax_reconciliation_broker_wash_adjustment_cents_check CHECK (((broker_wash_adjustment_cents IS NULL) OR (broker_wash_adjustment_cents >= 0))),
    CONSTRAINT portfolio_tax_reconciliation_status_check CHECK ((status = ANY (ARRAY['DRAFT'::text, 'RECONCILED'::text]))),
    CONSTRAINT portfolio_tax_reconciliation_tax_year_check CHECK (((tax_year >= 1970) AND (tax_year <= 9999)))
);


--
-- Name: portfolio_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_transaction (
    id text NOT NULL,
    portfolio_account_id text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    event_type text NOT NULL,
    cash_effect_cents bigint NOT NULL,
    fees_cents bigint DEFAULT 0 NOT NULL,
    tax_category text,
    source text DEFAULT 'MANUAL'::text NOT NULL,
    external_ref text,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    record_seq bigint NOT NULL,
    CONSTRAINT portfolio_transaction_event_type_check CHECK ((event_type = ANY (ARRAY['OPENING_BALANCE'::text, 'DEPOSIT'::text, 'WITHDRAWAL'::text, 'TRANSFER_IN'::text, 'TRANSFER_OUT'::text, 'INTEREST'::text, 'DIVIDEND'::text, 'FEE'::text, 'TRADE'::text, 'ROLL'::text, 'EXPIRATION'::text, 'ASSIGNMENT'::text, 'EXERCISE'::text, 'MARK_TO_MARKET'::text, 'ADJUSTMENT'::text]))),
    CONSTRAINT portfolio_transaction_source_check CHECK ((source = ANY (ARRAY['MANUAL'::text, 'BROKER'::text, 'IMPORT'::text, 'CALCULATED'::text]))),
    CONSTRAINT portfolio_transaction_tax_category_check CHECK (((tax_category IS NULL) OR (tax_category = ANY (ARRAY['ORDINARY_INTEREST'::text, 'ORDINARY_DIVIDEND'::text, 'QUALIFIED_DIVIDEND'::text, 'RETURN_OF_CAPITAL'::text, 'CAPITAL_GAIN_DISTRIBUTION'::text]))))
);


--
-- Name: portfolio_transaction_leg; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_transaction_leg (
    transaction_id text NOT NULL,
    leg_no integer NOT NULL,
    instrument_type text NOT NULL,
    action text NOT NULL,
    position_effect text NOT NULL,
    symbol text NOT NULL,
    option_type text,
    strike numeric(19,6),
    expiration date,
    quantity bigint NOT NULL,
    multiplier integer NOT NULL,
    price numeric(19,6) NOT NULL,
    gross_amount_cents bigint NOT NULL,
    allocated_fee_cents bigint DEFAULT 0 NOT NULL,
    section_1256 integer DEFAULT 0 NOT NULL,
    CONSTRAINT portfolio_transaction_leg_action_check CHECK ((action = ANY (ARRAY['BUY'::text, 'SELL'::text]))),
    CONSTRAINT portfolio_transaction_leg_check CHECK ((((instrument_type = 'STOCK'::text) AND (option_type IS NULL) AND (strike IS NULL) AND (expiration IS NULL) AND (multiplier = 1)) OR ((instrument_type = 'OPTION'::text) AND (option_type IS NOT NULL) AND (strike IS NOT NULL) AND (expiration IS NOT NULL)))),
    CONSTRAINT portfolio_transaction_leg_instrument_type_check CHECK ((instrument_type = ANY (ARRAY['STOCK'::text, 'OPTION'::text]))),
    CONSTRAINT portfolio_transaction_leg_multiplier_check CHECK ((multiplier > 0)),
    CONSTRAINT portfolio_transaction_leg_option_type_check CHECK (((option_type IS NULL) OR (option_type = ANY (ARRAY['CALL'::text, 'PUT'::text])))),
    CONSTRAINT portfolio_transaction_leg_position_effect_check CHECK ((position_effect = ANY (ARRAY['OPEN'::text, 'CLOSE'::text]))),
    CONSTRAINT portfolio_transaction_leg_price_check CHECK ((price >= (0)::numeric)),
    CONSTRAINT portfolio_transaction_leg_quantity_check CHECK ((quantity > 0)),
    CONSTRAINT portfolio_transaction_leg_section_1256_check CHECK ((section_1256 = ANY (ARRAY[0, 1])))
);


--
-- Name: portfolio_transaction_record_seq_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.portfolio_transaction_record_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: portfolio_transaction_record_seq_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.portfolio_transaction_record_seq_seq OWNED BY public.portfolio_transaction.record_seq;


--
-- Name: portfolio_valuation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_valuation (
    id text NOT NULL,
    portfolio_account_id text NOT NULL,
    as_of timestamp with time zone NOT NULL,
    cash_cents bigint,
    securities_value_cents bigint,
    total_value_cents bigint NOT NULL,
    source text DEFAULT 'MANUAL'::text NOT NULL,
    external_ref text,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    complete integer DEFAULT 1 NOT NULL,
    CONSTRAINT portfolio_valuation_complete_check CHECK ((complete = ANY (ARRAY[0, 1]))),
    CONSTRAINT portfolio_valuation_source_check CHECK ((source = ANY (ARRAY['MANUAL'::text, 'BROKER'::text, 'CALCULATED'::text, 'IMPORT'::text])))
);


--
-- Name: portfolio_valuation_missing_mark; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_valuation_missing_mark (
    valuation_id text NOT NULL,
    ordinal integer NOT NULL,
    mark_label text NOT NULL,
    CONSTRAINT portfolio_valuation_missing_mark_mark_label_check CHECK ((btrim(mark_label) <> ''::text)),
    CONSTRAINT portfolio_valuation_missing_mark_ordinal_check CHECK ((ordinal >= 0))
);


--
-- Name: portfolio_wash_sale_allocation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_wash_sale_allocation (
    id bigint NOT NULL,
    portfolio_account_id text NOT NULL,
    loss_match_id bigint NOT NULL,
    replacement_lot_id text NOT NULL,
    quantity bigint NOT NULL,
    adjustment_cents bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    ruleset_id text NOT NULL,
    classification_status text NOT NULL,
    CONSTRAINT portfolio_wash_classification_status_check CHECK ((classification_status = ANY (ARRAY['MODELED_COMMON_CASE'::text, 'UNREVIEWED'::text]))),
    CONSTRAINT portfolio_wash_sale_allocation_adjustment_cents_check CHECK ((adjustment_cents > 0)),
    CONSTRAINT portfolio_wash_sale_allocation_quantity_check CHECK ((quantity > 0))
);


--
-- Name: portfolio_wash_sale_allocation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.portfolio_wash_sale_allocation ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.portfolio_wash_sale_allocation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: position_receipt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.position_receipt (
    id text NOT NULL,
    user_id text NOT NULL,
    kind text NOT NULL,
    authority text NOT NULL,
    analysis_artifact_state text DEFAULT 'FROZEN'::text NOT NULL,
    execution_lane text NOT NULL,
    position_state text NOT NULL,
    plan_id text,
    plan_context_rev integer,
    account_objective_revision_id text,
    portfolio_account_id text,
    structure_revision_id text,
    practice_trade_id text,
    decision_id text,
    transaction_id text,
    transformation_action text,
    preview_fingerprint text,
    marks_as_of timestamp with time zone NOT NULL,
    evidence_level text NOT NULL,
    model_version text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT position_receipt_analysis_artifact_state_check CHECK ((analysis_artifact_state = 'FROZEN'::text)),
    CONSTRAINT position_receipt_authority_check CHECK ((authority = ANY (ARRAY['SYSTEM_ANALYSIS'::text, 'BROKER_REPORTED'::text, 'USER_ALLOCATED'::text]))),
    CONSTRAINT position_receipt_check CHECK ((((plan_id IS NULL) AND (plan_context_rev IS NULL)) OR ((plan_id IS NOT NULL) AND (plan_context_rev IS NOT NULL)))),
    CONSTRAINT position_receipt_evidence_level_check CHECK ((evidence_level = ANY (ARRAY['OBSERVED_LIVE'::text, 'OBSERVED_DELAYED'::text, 'OBSERVED_EOD'::text, 'MODELED'::text, 'SIMULATED'::text, 'DEMO_FIXTURE'::text, 'UNKNOWN'::text]))),
    CONSTRAINT position_receipt_execution_lane_check CHECK ((execution_lane = ANY (ARRAY['NONE'::text, 'PRACTICE'::text, 'REAL'::text]))),
    CONSTRAINT position_receipt_kind_check CHECK ((kind = ANY (ARRAY['DECISION'::text, 'ADOPTION'::text, 'TRANSFORMATION'::text, 'RESOLUTION'::text]))),
    CONSTRAINT position_receipt_position_state_check CHECK ((position_state = ANY (ARRAY['PENDING'::text, 'OPEN'::text, 'PARTIALLY_CLOSED'::text, 'ASSIGNED'::text, 'EXERCISED'::text, 'EXPIRED'::text, 'CLOSED'::text]))),
    CONSTRAINT position_receipt_transformation_action_check CHECK (((transformation_action IS NULL) OR (transformation_action = ANY (ARRAY['CLOSE'::text, 'VOID'::text, 'PARTIAL_CLOSE'::text, 'LEG_CLOSE'::text, 'ROLL'::text, 'ADD_LEG'::text, 'REMOVE_LEG'::text, 'ADD_STOCK'::text, 'REMOVE_STOCK'::text, 'ASSIGNMENT'::text, 'EXERCISE'::text, 'EXPIRATION'::text])))),
    CONSTRAINT position_receipt_transformation_identity_check CHECK ((((kind = 'TRANSFORMATION'::text) AND (transformation_action IS NOT NULL) AND (preview_fingerprint IS NOT NULL)) OR ((kind <> 'TRANSFORMATION'::text) AND (transformation_action IS NULL) AND (preview_fingerprint IS NULL))))
);


--
-- Name: position_receipt_leg; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.position_receipt_leg (
    receipt_id text NOT NULL,
    position_phase text NOT NULL,
    leg_no integer NOT NULL,
    instrument_type text NOT NULL,
    action text NOT NULL,
    symbol text NOT NULL,
    option_type text,
    strike numeric(19,6),
    expiration date,
    quantity bigint NOT NULL,
    multiplier integer NOT NULL,
    bid numeric(19,6),
    ask numeric(19,6),
    mid numeric(19,6),
    fill_price numeric(19,6),
    price_authority text NOT NULL,
    CONSTRAINT position_receipt_leg_action_check CHECK ((action = ANY (ARRAY['BUY'::text, 'SELL'::text]))),
    CONSTRAINT position_receipt_leg_check CHECK ((((instrument_type = 'STOCK'::text) AND (option_type IS NULL) AND (strike IS NULL) AND (expiration IS NULL) AND (multiplier = 1)) OR ((instrument_type = 'OPTION'::text) AND (option_type IS NOT NULL) AND (strike IS NOT NULL) AND (expiration IS NOT NULL)))),
    CONSTRAINT position_receipt_leg_instrument_type_check CHECK ((instrument_type = ANY (ARRAY['STOCK'::text, 'OPTION'::text]))),
    CONSTRAINT position_receipt_leg_leg_no_check CHECK ((leg_no >= 0)),
    CONSTRAINT position_receipt_leg_multiplier_check CHECK ((multiplier > 0)),
    CONSTRAINT position_receipt_leg_option_type_check CHECK (((option_type IS NULL) OR (option_type = ANY (ARRAY['CALL'::text, 'PUT'::text])))),
    CONSTRAINT position_receipt_leg_position_phase_check CHECK ((position_phase = ANY (ARRAY['BEFORE'::text, 'AFTER'::text]))),
    CONSTRAINT position_receipt_leg_price_authority_check CHECK ((price_authority = ANY (ARRAY['OBSERVED'::text, 'BROKER_REPORTED'::text, 'USER_REPORTED'::text, 'MODELED'::text]))),
    CONSTRAINT position_receipt_leg_quantity_check CHECK ((quantity > 0))
);


--
-- Name: position_receipt_metric; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.position_receipt_metric (
    receipt_id text NOT NULL,
    metric_key text NOT NULL,
    value_cents bigint,
    value_number double precision,
    value_text text,
    CONSTRAINT position_receipt_metric_check CHECK ((((((value_cents IS NOT NULL))::integer + ((value_number IS NOT NULL))::integer) + ((value_text IS NOT NULL))::integer) = 1))
);


--
-- Name: positions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.positions (
    id text NOT NULL,
    account_id text NOT NULL,
    symbol text NOT NULL,
    shares integer NOT NULL,
    avg_cost_cents bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: provider_request_budget; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_request_budget (
    source_key text NOT NULL,
    period_key date NOT NULL,
    used_count integer DEFAULT 0 NOT NULL,
    limit_count integer NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: recommendation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recommendation (
    id text NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL,
    evaluation_id text NOT NULL,
    context text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    outcome_status text,
    outcome_pnl_cents bigint,
    outcome_asof timestamp with time zone,
    outcome_json jsonb,
    trade_id text
);


--
-- Name: research_note; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.research_note (
    id text NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    tags text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: secrets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.secrets (
    k text NOT NULL,
    v text NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.settings (
    k text NOT NULL,
    v text NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: sim_replay_source; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sim_replay_source (
    sim_session_id text NOT NULL,
    plan_id text NOT NULL,
    ensemble_id text NOT NULL,
    fingerprint text NOT NULL,
    path_index integer NOT NULL,
    selection_kind text NOT NULL,
    symbol text NOT NULL,
    model_version text NOT NULL,
    n_steps integer NOT NULL,
    step_seconds double precision NOT NULL,
    rate_annual double precision NOT NULL,
    spot_path bytea NOT NULL,
    iv_path bytea NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT sim_replay_source_n_steps_check CHECK ((n_steps > 0)),
    CONSTRAINT sim_replay_source_path_index_check CHECK ((path_index >= 0)),
    CONSTRAINT sim_replay_source_selection_kind_check CHECK ((selection_kind = ANY (ARRAY['RANDOM'::text, 'TYPICAL'::text, 'FAVORABLE'::text, 'ADVERSE'::text, 'STRESS'::text, 'SAMPLE'::text]))),
    CONSTRAINT sim_replay_source_step_seconds_check CHECK ((step_seconds > (0)::double precision))
);


--
-- Name: sim_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sim_session (
    id text NOT NULL,
    name text NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL,
    config jsonb NOT NULL,
    status text DEFAULT 'CREATED'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    state jsonb,
    model_version text DEFAULT 'sim-1'::text NOT NULL,
    anchors jsonb
);


--
-- Name: sim_session_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sim_session_event (
    sim_session_id text NOT NULL,
    event_index integer NOT NULL,
    quantum bigint NOT NULL,
    kind text NOT NULL,
    symbol text,
    value double precision NOT NULL,
    CONSTRAINT sim_session_event_check CHECK ((((kind = 'MOVE'::text) AND (symbol IS NOT NULL)) OR ((kind = ANY (ARRAY['VOL'::text, 'SPEED'::text])) AND (symbol IS NULL)))),
    CONSTRAINT sim_session_event_event_index_check CHECK ((event_index >= 0)),
    CONSTRAINT sim_session_event_kind_check CHECK ((kind = ANY (ARRAY['MOVE'::text, 'VOL'::text, 'SPEED'::text]))),
    CONSTRAINT sim_session_event_quantum_check CHECK ((quantum >= 0))
);


--
-- Name: strategy_evaluation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.strategy_evaluation (
    id text NOT NULL,
    user_id text DEFAULT 'local'::text NOT NULL,
    symbol text NOT NULL,
    strategy text NOT NULL,
    objective text,
    asof timestamp with time zone DEFAULT now() NOT NULL,
    score double precision,
    ev_cents bigint,
    roc double precision,
    ann_roc double precision,
    pop double precision,
    assignment_prob double precision,
    capital_incremental_cents bigint,
    capital_economic_cents bigint,
    max_loss_cents bigint,
    tail_loss_cents bigint,
    evidence_level text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    receipt jsonb NOT NULL,
    CONSTRAINT strategy_evaluation_receipt_object CHECK ((jsonb_typeof(receipt) = 'object'::text))
);


--
-- Name: trade_marks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trade_marks (
    id bigint NOT NULL,
    trade_id text NOT NULL,
    ts timestamp with time zone NOT NULL,
    underlying_px_cents bigint,
    close_cost_cents bigint,
    unrealized_cents bigint,
    pop_now double precision,
    freshness text,
    detail_json jsonb,
    decision_unrealized_cents bigint,
    CONSTRAINT trade_marks_detail_json_object CHECK (((detail_json IS NULL) OR (jsonb_typeof(detail_json) = 'object'::text)))
);


--
-- Name: trade_marks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.trade_marks ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.trade_marks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: trades; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trades (
    id text NOT NULL,
    account_id text NOT NULL,
    symbol text NOT NULL,
    strategy text NOT NULL,
    status text NOT NULL,
    qty integer NOT NULL,
    legs_json jsonb NOT NULL,
    thesis text,
    horizon text,
    risk_mode text,
    intent text,
    shares_locked integer DEFAULT 0 NOT NULL,
    entry_underlying_cents bigint NOT NULL,
    entry_net_premium_cents bigint NOT NULL,
    max_loss_cents bigint NOT NULL,
    max_profit_cents bigint,
    breakevens_json jsonb NOT NULL,
    pop_entry double precision,
    fees_open_cents bigint DEFAULT 0 NOT NULL,
    fees_close_cents bigint DEFAULT 0 NOT NULL,
    realized_pnl_cents bigint,
    close_reason text,
    entry_snapshot_json jsonb NOT NULL,
    is_live integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    closed_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    origin text DEFAULT 'PAPER'::text NOT NULL,
    proposed_net_cents bigint,
    executed_at timestamp with time zone,
    broker text,
    order_ref text,
    data_provenance text DEFAULT 'UNKNOWN'::text NOT NULL,
    data_age text,
    data_source text,
    decision_pnl_cents bigint,
    CONSTRAINT trades_breakevens_json_array CHECK ((jsonb_typeof(breakevens_json) = 'array'::text)),
    CONSTRAINT trades_entry_snapshot_json_object CHECK ((jsonb_typeof(entry_snapshot_json) = 'object'::text)),
    CONSTRAINT trades_legs_json_array CHECK ((jsonb_typeof(legs_json) = 'array'::text)),
    CONSTRAINT trades_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'CLOSED'::text, 'EXPIRED'::text, 'DELETED'::text])))
);


--
-- Name: underlying_bar; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.underlying_bar (
    symbol text NOT NULL,
    d date NOT NULL,
    open numeric(19,6),
    high numeric(19,6),
    low numeric(19,6),
    close numeric(19,6) NOT NULL,
    volume bigint,
    source text NOT NULL,
    observed integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    dataset_id text DEFAULT 'observed'::text NOT NULL,
    adjusted integer DEFAULT 0 NOT NULL,
    quality_rank integer DEFAULT 0 NOT NULL,
    bar_kind text DEFAULT 'OHLCV'::text NOT NULL,
    CONSTRAINT underlying_bar_bar_kind_check CHECK ((bar_kind = ANY (ARRAY['OHLCV'::text, 'OHLC'::text, 'CLOSE_ONLY'::text])))
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id text NOT NULL,
    email text,
    provider text,
    subject text,
    name text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: workspace; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workspace (
    user_id text DEFAULT 'local'::text NOT NULL,
    state jsonb NOT NULL,
    rev bigint DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: portfolio_transaction record_seq; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_transaction ALTER COLUMN record_seq SET DEFAULT nextval('public.portfolio_transaction_record_seq_seq'::regclass);


--
-- Name: account_objective_revision account_objective_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_objective_revision
    ADD CONSTRAINT account_objective_revision_pkey PRIMARY KEY (id);


--
-- Name: account_objective_revision account_objective_revision_portfolio_account_id_revision_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_objective_revision
    ADD CONSTRAINT account_objective_revision_portfolio_account_id_revision_no_key UNIQUE (portfolio_account_id, revision_no);


--
-- Name: accounts accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT accounts_pkey PRIMARY KEY (id);


--
-- Name: audit audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit
    ADD CONSTRAINT audit_pkey PRIMARY KEY (id);


--
-- Name: backtest_assumption backtest_assumption_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_assumption
    ADD CONSTRAINT backtest_assumption_pkey PRIMARY KEY (backtest_id, assumption_key);


--
-- Name: backtest_equity_point backtest_equity_point_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_equity_point
    ADD CONSTRAINT backtest_equity_point_pkey PRIMARY KEY (backtest_id, point_index);


--
-- Name: backtest_note backtest_note_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_note
    ADD CONSTRAINT backtest_note_pkey PRIMARY KEY (backtest_id, note_index);


--
-- Name: backtest_skip backtest_skip_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_skip
    ADD CONSTRAINT backtest_skip_pkey PRIMARY KEY (backtest_id, skip_index);


--
-- Name: backtest_trade backtest_trade_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_trade
    ADD CONSTRAINT backtest_trade_pkey PRIMARY KEY (backtest_id, trade_index);


--
-- Name: backtests backtests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtests
    ADD CONSTRAINT backtests_pkey PRIMARY KEY (id);


--
-- Name: campaign_pending_member campaign_pending_member_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_pending_member
    ADD CONSTRAINT campaign_pending_member_pkey PRIMARY KEY (campaign_id, pending_id);


--
-- Name: campaign campaign_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign
    ADD CONSTRAINT campaign_pkey PRIMARY KEY (id);


--
-- Name: campaign_plan_member campaign_plan_member_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_plan_member
    ADD CONSTRAINT campaign_plan_member_pkey PRIMARY KEY (campaign_id, plan_id);


--
-- Name: campaign_practice_trade_member campaign_practice_trade_member_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_practice_trade_member
    ADD CONSTRAINT campaign_practice_trade_member_pkey PRIMARY KEY (campaign_id, trade_id);


--
-- Name: campaign_structure_member campaign_structure_member_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_structure_member
    ADD CONSTRAINT campaign_structure_member_pkey PRIMARY KEY (campaign_id, structure_id);


--
-- Name: campaign_transaction_member campaign_transaction_member_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_transaction_member
    ADD CONSTRAINT campaign_transaction_member_pkey PRIMARY KEY (campaign_id, transaction_id);


--
-- Name: data_job_item data_job_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_job_item
    ADD CONSTRAINT data_job_item_pkey PRIMARY KEY (job_id, seq);


--
-- Name: data_job data_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_job
    ADD CONSTRAINT data_job_pkey PRIMARY KEY (id);


--
-- Name: data_quarantine data_quarantine_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_quarantine
    ADD CONSTRAINT data_quarantine_pkey PRIMARY KEY (id);


--
-- Name: data_sync_cursor data_sync_cursor_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_sync_cursor
    ADD CONSTRAINT data_sync_cursor_pkey PRIMARY KEY (user_id, source_key, symbol, domain, interval_key);


--
-- Name: data_sync_schedule data_sync_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_sync_schedule
    ADD CONSTRAINT data_sync_schedule_pkey PRIMARY KEY (user_id);


--
-- Name: dataset dataset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dataset
    ADD CONSTRAINT dataset_pkey PRIMARY KEY (id);


--
-- Name: ensemble_artifact ensemble_artifact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ensemble_artifact
    ADD CONSTRAINT ensemble_artifact_pkey PRIMARY KEY (fingerprint);


--
-- Name: ledger ledger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger
    ADD CONSTRAINT ledger_pkey PRIMARY KEY (id);


--
-- Name: live_orders live_orders_client_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_orders
    ADD CONSTRAINT live_orders_client_order_id_key UNIQUE (client_order_id);


--
-- Name: live_orders live_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_orders
    ADD CONSTRAINT live_orders_pkey PRIMARY KEY (id);


--
-- Name: market_snapshot market_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_snapshot
    ADD CONSTRAINT market_snapshot_pkey PRIMARY KEY (symbol);


--
-- Name: option_bar option_bar_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.option_bar
    ADD CONSTRAINT option_bar_pkey PRIMARY KEY (id);


--
-- Name: option_bar option_bar_uniq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.option_bar
    ADD CONSTRAINT option_bar_uniq UNIQUE (symbol, asof, expiration, strike, opt_type, source, dataset_id);


--
-- Name: plan_backtest plan_backtest_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_backtest
    ADD CONSTRAINT plan_backtest_pkey PRIMARY KEY (id);


--
-- Name: plan_candidate_breakeven plan_candidate_breakeven_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate_breakeven
    ADD CONSTRAINT plan_candidate_breakeven_pkey PRIMARY KEY (candidate_id, breakeven_index);


--
-- Name: plan_candidate_intent plan_candidate_intent_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate_intent
    ADD CONSTRAINT plan_candidate_intent_pkey PRIMARY KEY (candidate_id, intent_index);


--
-- Name: plan_candidate_leg plan_candidate_leg_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate_leg
    ADD CONSTRAINT plan_candidate_leg_pkey PRIMARY KEY (candidate_id, leg_index);


--
-- Name: plan_candidate plan_candidate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate
    ADD CONSTRAINT plan_candidate_pkey PRIMARY KEY (id);


--
-- Name: plan_candidate_warning plan_candidate_warning_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate_warning
    ADD CONSTRAINT plan_candidate_warning_pkey PRIMARY KEY (candidate_id, warning_index);


--
-- Name: plan_context_revision plan_context_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_context_revision
    ADD CONSTRAINT plan_context_revision_pkey PRIMARY KEY (id);


--
-- Name: plan_context_revision plan_context_revision_plan_id_rev_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_context_revision
    ADD CONSTRAINT plan_context_revision_plan_id_rev_key UNIQUE (plan_id, rev);


--
-- Name: plan_create_request plan_create_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_create_request
    ADD CONSTRAINT plan_create_request_pkey PRIMARY KEY (user_id, client_request_id);


--
-- Name: plan_decision_ack plan_decision_ack_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision_ack
    ADD CONSTRAINT plan_decision_ack_pkey PRIMARY KEY (decision_id, ack_key);


--
-- Name: plan_decision_leg plan_decision_leg_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision_leg
    ADD CONSTRAINT plan_decision_leg_pkey PRIMARY KEY (decision_id, leg_index);


--
-- Name: plan_decision_metric plan_decision_metric_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision_metric
    ADD CONSTRAINT plan_decision_metric_pkey PRIMARY KEY (decision_id, metric_key);


--
-- Name: plan_decision plan_decision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision
    ADD CONSTRAINT plan_decision_pkey PRIMARY KEY (id);


--
-- Name: plan_ensemble plan_ensemble_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_ensemble
    ADD CONSTRAINT plan_ensemble_pkey PRIMARY KEY (id);


--
-- Name: plan_ensemble_quantile plan_ensemble_quantile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_ensemble_quantile
    ADD CONSTRAINT plan_ensemble_quantile_pkey PRIMARY KEY (ensemble_id, probability);


--
-- Name: plan_evidence_analog plan_evidence_analog_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_analog
    ADD CONSTRAINT plan_evidence_analog_pkey PRIMARY KEY (evidence_id, path_index, step_index);


--
-- Name: plan_evidence_distribution plan_evidence_distribution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_distribution
    ADD CONSTRAINT plan_evidence_distribution_pkey PRIMARY KEY (evidence_id, bucket_index);


--
-- Name: plan_evidence_event plan_evidence_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_event
    ADD CONSTRAINT plan_evidence_event_pkey PRIMARY KEY (evidence_id, event_index);


--
-- Name: plan_evidence_example plan_evidence_example_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_example
    ADD CONSTRAINT plan_evidence_example_pkey PRIMARY KEY (evidence_id, example_index);


--
-- Name: plan_evidence_metric plan_evidence_metric_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_metric
    ADD CONSTRAINT plan_evidence_metric_pkey PRIMARY KEY (evidence_id, metric_key);


--
-- Name: plan_evidence_note plan_evidence_note_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_note
    ADD CONSTRAINT plan_evidence_note_pkey PRIMARY KEY (evidence_id, note_index);


--
-- Name: plan_evidence_param plan_evidence_param_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_param
    ADD CONSTRAINT plan_evidence_param_pkey PRIMARY KEY (evidence_id, param_key);


--
-- Name: plan_evidence plan_evidence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence
    ADD CONSTRAINT plan_evidence_pkey PRIMARY KEY (id);


--
-- Name: plan_evidence_stat plan_evidence_stat_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_stat
    ADD CONSTRAINT plan_evidence_stat_pkey PRIMARY KEY (evidence_id, sample_kind);


--
-- Name: plan_level_odds plan_level_odds_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_level_odds
    ADD CONSTRAINT plan_level_odds_pkey PRIMARY KEY (ensemble_id, level_key);


--
-- Name: plan_link plan_link_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_link
    ADD CONSTRAINT plan_link_pkey PRIMARY KEY (id);


--
-- Name: plan_management_action plan_management_action_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_management_action
    ADD CONSTRAINT plan_management_action_pkey PRIMARY KEY (id);


--
-- Name: plan_outcome_band plan_outcome_band_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_band
    ADD CONSTRAINT plan_outcome_band_pkey PRIMARY KEY (outcome_id, day_index);


--
-- Name: plan_outcome_bucket plan_outcome_bucket_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_bucket
    ADD CONSTRAINT plan_outcome_bucket_pkey PRIMARY KEY (outcome_id, bucket_index);


--
-- Name: plan_outcome_comparison_item plan_outcome_comparison_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_comparison_item
    ADD CONSTRAINT plan_outcome_comparison_item_pkey PRIMARY KEY (comparison_id, item_key);


--
-- Name: plan_outcome_comparison plan_outcome_comparison_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_comparison
    ADD CONSTRAINT plan_outcome_comparison_pkey PRIMARY KEY (id);


--
-- Name: plan_outcome_market_metric plan_outcome_market_metric_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_market_metric
    ADD CONSTRAINT plan_outcome_market_metric_pkey PRIMARY KEY (outcome_id, metric_key);


--
-- Name: plan_outcome_note plan_outcome_note_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_note
    ADD CONSTRAINT plan_outcome_note_pkey PRIMARY KEY (outcome_id, note_index);


--
-- Name: plan_outcome_run plan_outcome_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_run
    ADD CONSTRAINT plan_outcome_run_pkey PRIMARY KEY (id);


--
-- Name: plan_portfolio_action plan_portfolio_action_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_portfolio_action
    ADD CONSTRAINT plan_portfolio_action_pkey PRIMARY KEY (id);


--
-- Name: plan_portfolio_action plan_portfolio_action_plan_id_receipt_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_portfolio_action
    ADD CONSTRAINT plan_portfolio_action_plan_id_receipt_id_key UNIQUE (plan_id, receipt_id);


--
-- Name: plan_portfolio_action plan_portfolio_action_receipt_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_portfolio_action
    ADD CONSTRAINT plan_portfolio_action_receipt_id_key UNIQUE (receipt_id);


--
-- Name: plan_review plan_review_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_review
    ADD CONSTRAINT plan_review_pkey PRIMARY KEY (id);


--
-- Name: plan_strategy_note plan_strategy_note_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_strategy_note
    ADD CONSTRAINT plan_strategy_note_pkey PRIMARY KEY (run_id, note_index);


--
-- Name: plan_strategy_rejection plan_strategy_rejection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_strategy_rejection
    ADD CONSTRAINT plan_strategy_rejection_pkey PRIMARY KEY (run_id, rejection_index, reason_index);


--
-- Name: plan_strategy_run plan_strategy_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_strategy_run
    ADD CONSTRAINT plan_strategy_run_pkey PRIMARY KEY (id);


--
-- Name: plans plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plans
    ADD CONSTRAINT plans_pkey PRIMARY KEY (id);


--
-- Name: portfolio_account portfolio_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_account
    ADD CONSTRAINT portfolio_account_pkey PRIMARY KEY (id);


--
-- Name: portfolio_import_pending_leg portfolio_import_pending_leg_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_pending_leg
    ADD CONSTRAINT portfolio_import_pending_leg_pkey PRIMARY KEY (pending_id, leg_no);


--
-- Name: portfolio_import_pending portfolio_import_pending_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_pending
    ADD CONSTRAINT portfolio_import_pending_pkey PRIMARY KEY (id);


--
-- Name: portfolio_import_pending portfolio_import_pending_user_id_source_system_source_accou_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_pending
    ADD CONSTRAINT portfolio_import_pending_user_id_source_system_source_accou_key UNIQUE (user_id, source_system, source_account_fingerprint, external_ref);


--
-- Name: portfolio_import_resolution portfolio_import_resolution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_resolution
    ADD CONSTRAINT portfolio_import_resolution_pkey PRIMARY KEY (pending_id);


--
-- Name: portfolio_import_resolution portfolio_import_resolution_receipt_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_resolution
    ADD CONSTRAINT portfolio_import_resolution_receipt_id_key UNIQUE (receipt_id);


--
-- Name: portfolio_import_resolution portfolio_import_resolution_transaction_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_resolution
    ADD CONSTRAINT portfolio_import_resolution_transaction_id_key UNIQUE (transaction_id);


--
-- Name: portfolio_lot_match portfolio_lot_match_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_lot_match
    ADD CONSTRAINT portfolio_lot_match_pkey PRIMARY KEY (id);


--
-- Name: portfolio_lot portfolio_lot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_lot
    ADD CONSTRAINT portfolio_lot_pkey PRIMARY KEY (id);


--
-- Name: portfolio_roll_match portfolio_roll_match_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll_match
    ADD CONSTRAINT portfolio_roll_match_pkey PRIMARY KEY (transaction_id, lot_match_id);


--
-- Name: portfolio_roll portfolio_roll_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll
    ADD CONSTRAINT portfolio_roll_pkey PRIMARY KEY (transaction_id);


--
-- Name: portfolio_structure_member portfolio_structure_member_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_member
    ADD CONSTRAINT portfolio_structure_member_pkey PRIMARY KEY (revision_id, leg_no);


--
-- Name: portfolio_structure_member portfolio_structure_member_revision_id_lot_id_leg_role_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_member
    ADD CONSTRAINT portfolio_structure_member_revision_id_lot_id_leg_role_key UNIQUE (revision_id, lot_id, leg_role);


--
-- Name: portfolio_structure portfolio_structure_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure
    ADD CONSTRAINT portfolio_structure_pkey PRIMARY KEY (id);


--
-- Name: portfolio_structure_revision portfolio_structure_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_revision
    ADD CONSTRAINT portfolio_structure_revision_pkey PRIMARY KEY (id);


--
-- Name: portfolio_structure_revision portfolio_structure_revision_structure_id_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_revision
    ADD CONSTRAINT portfolio_structure_revision_structure_id_id_key UNIQUE (structure_id, id);


--
-- Name: portfolio_structure_revision portfolio_structure_revision_structure_id_revision_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_revision
    ADD CONSTRAINT portfolio_structure_revision_structure_id_revision_no_key UNIQUE (structure_id, revision_no);


--
-- Name: portfolio_tax_reconciliation portfolio_tax_reconciliation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_tax_reconciliation
    ADD CONSTRAINT portfolio_tax_reconciliation_pkey PRIMARY KEY (portfolio_account_id, tax_year);


--
-- Name: portfolio_transaction_leg portfolio_transaction_leg_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_transaction_leg
    ADD CONSTRAINT portfolio_transaction_leg_pkey PRIMARY KEY (transaction_id, leg_no);


--
-- Name: portfolio_transaction portfolio_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_transaction
    ADD CONSTRAINT portfolio_transaction_pkey PRIMARY KEY (id);


--
-- Name: portfolio_transaction portfolio_transaction_portfolio_account_id_source_external__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_transaction
    ADD CONSTRAINT portfolio_transaction_portfolio_account_id_source_external__key UNIQUE (portfolio_account_id, source, external_ref);


--
-- Name: portfolio_valuation_missing_mark portfolio_valuation_missing_mark_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_valuation_missing_mark
    ADD CONSTRAINT portfolio_valuation_missing_mark_pkey PRIMARY KEY (valuation_id, ordinal);


--
-- Name: portfolio_valuation portfolio_valuation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_valuation
    ADD CONSTRAINT portfolio_valuation_pkey PRIMARY KEY (id);


--
-- Name: portfolio_valuation portfolio_valuation_portfolio_account_id_source_as_of_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_valuation
    ADD CONSTRAINT portfolio_valuation_portfolio_account_id_source_as_of_key UNIQUE (portfolio_account_id, source, as_of);


--
-- Name: portfolio_wash_sale_allocation portfolio_wash_sale_allocatio_loss_match_id_replacement_lot_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_wash_sale_allocation
    ADD CONSTRAINT portfolio_wash_sale_allocatio_loss_match_id_replacement_lot_key UNIQUE (loss_match_id, replacement_lot_id);


--
-- Name: portfolio_wash_sale_allocation portfolio_wash_sale_allocation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_wash_sale_allocation
    ADD CONSTRAINT portfolio_wash_sale_allocation_pkey PRIMARY KEY (id);


--
-- Name: position_receipt_leg position_receipt_leg_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt_leg
    ADD CONSTRAINT position_receipt_leg_pkey PRIMARY KEY (receipt_id, position_phase, leg_no);


--
-- Name: position_receipt_metric position_receipt_metric_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt_metric
    ADD CONSTRAINT position_receipt_metric_pkey PRIMARY KEY (receipt_id, metric_key);


--
-- Name: position_receipt position_receipt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_pkey PRIMARY KEY (id);


--
-- Name: positions positions_account_id_symbol_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_account_id_symbol_key UNIQUE (account_id, symbol);


--
-- Name: positions positions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_pkey PRIMARY KEY (id);


--
-- Name: provider_request_budget provider_request_budget_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_request_budget
    ADD CONSTRAINT provider_request_budget_pkey PRIMARY KEY (source_key, period_key);


--
-- Name: recommendation recommendation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recommendation
    ADD CONSTRAINT recommendation_pkey PRIMARY KEY (id);


--
-- Name: research_note research_note_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.research_note
    ADD CONSTRAINT research_note_pkey PRIMARY KEY (id);


--
-- Name: secrets secrets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.secrets
    ADD CONSTRAINT secrets_pkey PRIMARY KEY (k);


--
-- Name: settings settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.settings
    ADD CONSTRAINT settings_pkey PRIMARY KEY (k);


--
-- Name: sim_replay_source sim_replay_source_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_replay_source
    ADD CONSTRAINT sim_replay_source_pkey PRIMARY KEY (sim_session_id);


--
-- Name: sim_session_event sim_session_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_session_event
    ADD CONSTRAINT sim_session_event_pkey PRIMARY KEY (sim_session_id, event_index);


--
-- Name: sim_session sim_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_session
    ADD CONSTRAINT sim_session_pkey PRIMARY KEY (id);


--
-- Name: strategy_evaluation strategy_evaluation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.strategy_evaluation
    ADD CONSTRAINT strategy_evaluation_pkey PRIMARY KEY (id);


--
-- Name: trade_marks trade_marks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trade_marks
    ADD CONSTRAINT trade_marks_pkey PRIMARY KEY (id);


--
-- Name: trades trades_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trades
    ADD CONSTRAINT trades_pkey PRIMARY KEY (id);


--
-- Name: underlying_bar underlying_bar_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.underlying_bar
    ADD CONSTRAINT underlying_bar_pkey PRIMARY KEY (symbol, d, source, dataset_id);


--
-- Name: portfolio_account uq_portfolio_account_user_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_account
    ADD CONSTRAINT uq_portfolio_account_user_id UNIQUE (user_id, id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: workspace workspace_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT workspace_pkey PRIMARY KEY (user_id);


--
-- Name: idx_accounts_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_accounts_user ON public.accounts USING btree (user_id);


--
-- Name: idx_accounts_world; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_accounts_world ON public.accounts USING btree (world_id) WHERE (world_id IS NOT NULL);


--
-- Name: idx_audit_ts; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_ts ON public.audit USING btree (id DESC);


--
-- Name: idx_backtests_owner_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_backtests_owner_created ON public.backtests USING btree (user_id, created_at DESC, id DESC);


--
-- Name: idx_campaign_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_campaign_user_status ON public.campaign USING btree (user_id, status, updated_at DESC);


--
-- Name: idx_data_job_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_job_created ON public.data_job USING btree (created_at DESC);


--
-- Name: idx_data_quarantine_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_quarantine_created ON public.data_quarantine USING btree (created_at DESC);


--
-- Name: idx_data_quarantine_owner_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_quarantine_owner_created ON public.data_quarantine USING btree (user_id, created_at DESC);


--
-- Name: idx_data_sync_cursor_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_sync_cursor_updated ON public.data_sync_cursor USING btree (updated_at DESC);


--
-- Name: idx_dataset_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dataset_created ON public.dataset USING btree (created_at DESC);


--
-- Name: idx_ensemble_artifact_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ensemble_artifact_retention ON public.ensemble_artifact USING btree (created_at) WHERE (pinned = 0);


--
-- Name: idx_eval_user_symbol; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_user_symbol ON public.strategy_evaluation USING btree (user_id, symbol, asof DESC);


--
-- Name: idx_ledger_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ledger_account ON public.ledger USING btree (account_id, id);


--
-- Name: idx_ledger_trade; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ledger_trade ON public.ledger USING btree (trade_id);


--
-- Name: idx_marks_trade; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_marks_trade ON public.trade_marks USING btree (trade_id, id DESC);


--
-- Name: idx_option_bar_dataset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_option_bar_dataset ON public.option_bar USING btree (dataset_id, symbol, asof);


--
-- Name: idx_option_bar_iv_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_option_bar_iv_history ON public.option_bar USING btree (symbol, asof) WHERE ((iv IS NOT NULL) AND (iv_source = 'vendor'::text) AND (underlying IS NOT NULL));


--
-- Name: idx_option_bar_symbol_asof; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_option_bar_symbol_asof ON public.option_bar USING btree (symbol, asof);


--
-- Name: idx_option_bar_symbol_exp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_option_bar_symbol_exp ON public.option_bar USING btree (symbol, expiration);


--
-- Name: idx_plan_backtest_dataset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_backtest_dataset ON public.plan_backtest USING btree (plan_id, context_rev, dataset_id, created_at DESC);


--
-- Name: idx_plan_backtest_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_backtest_retention ON public.plan_backtest USING btree (created_at) WHERE (state = ANY (ARRAY['STALE'::text, 'BLOCKED'::text]));


--
-- Name: idx_plan_candidate_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_candidate_plan ON public.plan_candidate USING btree (plan_id, context_rev, rank_number, created_at DESC);


--
-- Name: idx_plan_candidate_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_candidate_run ON public.plan_candidate USING btree (run_id, source_kind, rank_number);


--
-- Name: idx_plan_candidate_symbol; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_candidate_symbol ON public.plan_candidate USING btree (plan_id, underlying_symbol, source_kind);


--
-- Name: idx_plan_context_plan_rev; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_context_plan_rev ON public.plan_context_revision USING btree (plan_id, rev DESC);


--
-- Name: idx_plan_create_request_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_create_request_plan ON public.plan_create_request USING btree (plan_id);


--
-- Name: idx_plan_decision_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_decision_plan ON public.plan_decision USING btree (plan_id, created_at DESC);


--
-- Name: idx_plan_ensemble_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_ensemble_plan ON public.plan_ensemble USING btree (plan_id, context_rev, created_at DESC);


--
-- Name: idx_plan_ensemble_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_ensemble_retention ON public.plan_ensemble USING btree (created_at) WHERE (state = ANY (ARRAY['STALE'::text, 'BLOCKED'::text]));


--
-- Name: idx_plan_evidence_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_evidence_current ON public.plan_evidence USING btree (plan_id, basis, context_rev, created_at DESC) WHERE (state = 'CURRENT'::text);


--
-- Name: idx_plan_evidence_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_evidence_plan ON public.plan_evidence USING btree (plan_id, context_rev, created_at DESC);


--
-- Name: idx_plan_evidence_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_evidence_retention ON public.plan_evidence USING btree (created_at) WHERE (state = ANY (ARRAY['STALE'::text, 'BLOCKED'::text]));


--
-- Name: idx_plan_link_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_link_decision ON public.plan_link USING btree (decision_id);


--
-- Name: idx_plan_link_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_link_plan ON public.plan_link USING btree (plan_id, created_at DESC);


--
-- Name: idx_plan_link_related_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_plan_link_related_unique ON public.plan_link USING btree (plan_id, role, related_plan_id) WHERE (related_plan_id IS NOT NULL);


--
-- Name: idx_plan_link_sim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_link_sim ON public.plan_link USING btree (sim_session_id);


--
-- Name: idx_plan_link_trade; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_link_trade ON public.plan_link USING btree (trade_id);


--
-- Name: idx_plan_management_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_management_plan ON public.plan_management_action USING btree (plan_id, action_at DESC);


--
-- Name: idx_plan_outcome_comparison_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_outcome_comparison_current ON public.plan_outcome_comparison USING btree (plan_id, context_rev, dataset_id, basis, created_at DESC) WHERE (state = 'CURRENT'::text);


--
-- Name: idx_plan_outcome_comparison_item_candidate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_outcome_comparison_item_candidate ON public.plan_outcome_comparison_item USING btree (candidate_id, comparison_id);


--
-- Name: idx_plan_outcome_comparison_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_outcome_comparison_retention ON public.plan_outcome_comparison USING btree (created_at) WHERE (state = ANY (ARRAY['STALE'::text, 'BLOCKED'::text]));


--
-- Name: idx_plan_outcome_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_outcome_current ON public.plan_outcome_run USING btree (plan_id, context_rev, basis, created_at DESC) WHERE (state = 'CURRENT'::text);


--
-- Name: idx_plan_outcome_dataset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_outcome_dataset ON public.plan_outcome_run USING btree (plan_id, context_rev, dataset_id, basis, created_at DESC);


--
-- Name: idx_plan_outcome_run_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_outcome_run_retention ON public.plan_outcome_run USING btree (created_at) WHERE (state = ANY (ARRAY['STALE'::text, 'BLOCKED'::text]));


--
-- Name: idx_plan_portfolio_action_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_portfolio_action_plan ON public.plan_portfolio_action USING btree (plan_id, created_at DESC);


--
-- Name: idx_plan_review_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_review_plan ON public.plan_review USING btree (plan_id, reviewed_at DESC);


--
-- Name: idx_plan_strategy_run_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_strategy_run_current ON public.plan_strategy_run USING btree (plan_id, context_rev, run_kind, created_at DESC) WHERE (state = 'CURRENT'::text);


--
-- Name: idx_plan_strategy_run_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_strategy_run_retention ON public.plan_strategy_run USING btree (created_at) WHERE (state = ANY (ARRAY['STALE'::text, 'BLOCKED'::text]));


--
-- Name: idx_plans_origin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plans_origin ON public.plans USING btree (origin_plan_id);


--
-- Name: idx_plans_user_market_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plans_user_market_open ON public.plans USING btree (user_id, market_kind, world_id, is_open, updated_at DESC);


--
-- Name: idx_plans_user_symbol_intent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plans_user_symbol_intent ON public.plans USING btree (user_id, symbol, intent, updated_at DESC);


--
-- Name: idx_portfolio_account_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_account_owner ON public.portfolio_account USING btree (user_id, status, created_at);


--
-- Name: idx_portfolio_import_pending_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_import_pending_user ON public.portfolio_import_pending USING btree (user_id, status, occurred_at DESC);


--
-- Name: idx_portfolio_lot_1256_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_lot_1256_open ON public.portfolio_lot USING btree (portfolio_account_id, section_1256, status, expiration);


--
-- Name: idx_portfolio_lot_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_lot_open ON public.portfolio_lot USING btree (portfolio_account_id, status, symbol, instrument_type);


--
-- Name: idx_portfolio_match_account_close; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_match_account_close ON public.portfolio_lot_match USING btree (portfolio_account_id, closed_at DESC);


--
-- Name: idx_portfolio_roll_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_roll_account ON public.portfolio_roll USING btree (portfolio_account_id, created_at DESC);


--
-- Name: idx_portfolio_structure_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_structure_account ON public.portfolio_structure USING btree (portfolio_account_id, status, symbol);


--
-- Name: idx_portfolio_structure_member_lot; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_structure_member_lot ON public.portfolio_structure_member USING btree (lot_id);


--
-- Name: idx_portfolio_tx_account_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_tx_account_time ON public.portfolio_transaction USING btree (portfolio_account_id, occurred_at DESC, id);


--
-- Name: idx_portfolio_tx_record_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_portfolio_tx_record_seq ON public.portfolio_transaction USING btree (record_seq);


--
-- Name: idx_portfolio_valuation_account_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_valuation_account_time ON public.portfolio_valuation USING btree (portfolio_account_id, as_of);


--
-- Name: idx_portfolio_valuation_complete_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_valuation_complete_time ON public.portfolio_valuation USING btree (portfolio_account_id, complete, as_of);


--
-- Name: idx_portfolio_wash_loss; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_wash_loss ON public.portfolio_wash_sale_allocation USING btree (loss_match_id);


--
-- Name: idx_portfolio_wash_replacement; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_wash_replacement ON public.portfolio_wash_sale_allocation USING btree (replacement_lot_id);


--
-- Name: idx_position_receipt_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_position_receipt_plan ON public.position_receipt USING btree (plan_id, created_at DESC);


--
-- Name: idx_position_receipt_structure; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_position_receipt_structure ON public.position_receipt USING btree (structure_revision_id, created_at DESC);


--
-- Name: idx_reco_trade; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reco_trade ON public.recommendation USING btree (trade_id);


--
-- Name: idx_reco_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reco_user ON public.recommendation USING btree (user_id, created_at DESC);


--
-- Name: idx_research_note_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_research_note_user ON public.research_note USING btree (user_id, updated_at DESC);


--
-- Name: idx_sim_replay_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sim_replay_plan ON public.sim_replay_source USING btree (plan_id, created_at DESC);


--
-- Name: idx_trades_account_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trades_account_status ON public.trades USING btree (account_id, status, created_at DESC);


--
-- Name: idx_underlying_bar_dataset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_underlying_bar_dataset ON public.underlying_bar USING btree (dataset_id, symbol, d);


--
-- Name: uq_backtest_one_worst_trade; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_backtest_one_worst_trade ON public.backtest_trade USING btree (backtest_id) WHERE (is_worst = 1);


--
-- Name: uq_plan_candidate_selected; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_plan_candidate_selected ON public.plan_candidate USING btree (plan_id, context_rev) WHERE (selected = 1);


--
-- Name: uq_plan_decision_sequence; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_plan_decision_sequence ON public.plan_decision USING btree (plan_id, decision_seq);


--
-- Name: portfolio_import_pending trg_import_pending_resolution_agreement; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_import_pending_resolution_agreement AFTER UPDATE ON public.portfolio_import_pending DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_import_resolution_agreement();


--
-- Name: portfolio_import_resolution trg_import_resolution_agreement; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_import_resolution_agreement AFTER INSERT OR UPDATE ON public.portfolio_import_resolution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_import_resolution_agreement();


--
-- Name: plan_portfolio_action trg_plan_action_artifact_agreement; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_plan_action_artifact_agreement AFTER INSERT OR UPDATE ON public.plan_portfolio_action DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_plan_action_artifact_agreement();


--
-- Name: portfolio_structure trg_structure_current_revision_allocation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_structure_current_revision_allocation AFTER INSERT OR DELETE OR UPDATE ON public.portfolio_structure DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_current_structure_lot_allocations();


--
-- Name: portfolio_lot trg_structure_lot_remaining_allocation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_structure_lot_remaining_allocation AFTER DELETE OR UPDATE ON public.portfolio_lot DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_current_structure_lot_allocations();


--
-- Name: portfolio_structure_member trg_structure_member_allocation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER trg_structure_member_allocation AFTER INSERT OR DELETE OR UPDATE ON public.portfolio_structure_member DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_current_structure_lot_allocations();


--
-- Name: account_objective_revision account_objective_revision_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_objective_revision
    ADD CONSTRAINT account_objective_revision_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id) ON DELETE CASCADE;


--
-- Name: accounts accounts_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: backtest_assumption backtest_assumption_backtest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_assumption
    ADD CONSTRAINT backtest_assumption_backtest_id_fkey FOREIGN KEY (backtest_id) REFERENCES public.backtests(id) ON DELETE CASCADE;


--
-- Name: backtest_equity_point backtest_equity_point_backtest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_equity_point
    ADD CONSTRAINT backtest_equity_point_backtest_id_fkey FOREIGN KEY (backtest_id) REFERENCES public.backtests(id) ON DELETE CASCADE;


--
-- Name: backtest_note backtest_note_backtest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_note
    ADD CONSTRAINT backtest_note_backtest_id_fkey FOREIGN KEY (backtest_id) REFERENCES public.backtests(id) ON DELETE CASCADE;


--
-- Name: backtest_skip backtest_skip_backtest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_skip
    ADD CONSTRAINT backtest_skip_backtest_id_fkey FOREIGN KEY (backtest_id) REFERENCES public.backtests(id) ON DELETE CASCADE;


--
-- Name: backtest_trade backtest_trade_backtest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtest_trade
    ADD CONSTRAINT backtest_trade_backtest_id_fkey FOREIGN KEY (backtest_id) REFERENCES public.backtests(id) ON DELETE CASCADE;


--
-- Name: backtests backtests_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backtests
    ADD CONSTRAINT backtests_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: campaign campaign_account_objective_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign
    ADD CONSTRAINT campaign_account_objective_revision_id_fkey FOREIGN KEY (account_objective_revision_id) REFERENCES public.account_objective_revision(id) ON DELETE SET NULL;


--
-- Name: campaign_pending_member campaign_pending_member_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_pending_member
    ADD CONSTRAINT campaign_pending_member_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.campaign(id) ON DELETE CASCADE;


--
-- Name: campaign_pending_member campaign_pending_member_pending_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_pending_member
    ADD CONSTRAINT campaign_pending_member_pending_id_fkey FOREIGN KEY (pending_id) REFERENCES public.portfolio_import_pending(id) ON DELETE RESTRICT;


--
-- Name: campaign_plan_member campaign_plan_member_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_plan_member
    ADD CONSTRAINT campaign_plan_member_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.campaign(id) ON DELETE CASCADE;


--
-- Name: campaign_plan_member campaign_plan_member_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_plan_member
    ADD CONSTRAINT campaign_plan_member_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE RESTRICT;


--
-- Name: campaign_practice_trade_member campaign_practice_trade_member_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_practice_trade_member
    ADD CONSTRAINT campaign_practice_trade_member_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.campaign(id) ON DELETE CASCADE;


--
-- Name: campaign_practice_trade_member campaign_practice_trade_member_trade_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_practice_trade_member
    ADD CONSTRAINT campaign_practice_trade_member_trade_id_fkey FOREIGN KEY (trade_id) REFERENCES public.trades(id) ON DELETE RESTRICT;


--
-- Name: campaign_structure_member campaign_structure_member_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_structure_member
    ADD CONSTRAINT campaign_structure_member_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.campaign(id) ON DELETE CASCADE;


--
-- Name: campaign_structure_member campaign_structure_member_structure_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_structure_member
    ADD CONSTRAINT campaign_structure_member_structure_id_fkey FOREIGN KEY (structure_id) REFERENCES public.portfolio_structure(id) ON DELETE RESTRICT;


--
-- Name: campaign_transaction_member campaign_transaction_member_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_transaction_member
    ADD CONSTRAINT campaign_transaction_member_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.campaign(id) ON DELETE CASCADE;


--
-- Name: campaign_transaction_member campaign_transaction_member_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign_transaction_member
    ADD CONSTRAINT campaign_transaction_member_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.portfolio_transaction(id) ON DELETE RESTRICT;


--
-- Name: campaign campaign_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.campaign
    ADD CONSTRAINT campaign_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: data_job_item data_job_item_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_job_item
    ADD CONSTRAINT data_job_item_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.data_job(id) ON DELETE CASCADE;


--
-- Name: accounts fk_accounts_world; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT fk_accounts_world FOREIGN KEY (world_id) REFERENCES public.sim_session(id);


--
-- Name: data_job fk_data_job_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_job
    ADD CONSTRAINT fk_data_job_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: data_quarantine fk_data_quarantine_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_quarantine
    ADD CONSTRAINT fk_data_quarantine_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: data_sync_cursor fk_data_sync_cursor_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_sync_cursor
    ADD CONSTRAINT fk_data_sync_cursor_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: data_sync_schedule fk_data_sync_schedule_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_sync_schedule
    ADD CONSTRAINT fk_data_sync_schedule_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: dataset fk_dataset_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dataset
    ADD CONSTRAINT fk_dataset_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: plan_create_request fk_plan_create_request_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_create_request
    ADD CONSTRAINT fk_plan_create_request_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: plans fk_plans_active_context; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plans
    ADD CONSTRAINT fk_plans_active_context FOREIGN KEY (id, active_context_rev) REFERENCES public.plan_context_revision(plan_id, rev);


--
-- Name: plans fk_plans_world; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plans
    ADD CONSTRAINT fk_plans_world FOREIGN KEY (world_id) REFERENCES public.sim_session(id);


--
-- Name: portfolio_account fk_portfolio_account_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_account
    ADD CONSTRAINT fk_portfolio_account_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: portfolio_structure fk_portfolio_structure_current_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure
    ADD CONSTRAINT fk_portfolio_structure_current_revision FOREIGN KEY (id, current_revision_id) REFERENCES public.portfolio_structure_revision(structure_id, id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: portfolio_structure fk_portfolio_structure_owned_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure
    ADD CONSTRAINT fk_portfolio_structure_owned_account FOREIGN KEY (user_id, portfolio_account_id) REFERENCES public.portfolio_account(user_id, id) ON DELETE CASCADE;


--
-- Name: sim_session fk_sim_session_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_session
    ADD CONSTRAINT fk_sim_session_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: workspace fk_workspace_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT fk_workspace_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: ledger ledger_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger
    ADD CONSTRAINT ledger_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: option_bar option_bar_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.option_bar
    ADD CONSTRAINT option_bar_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.dataset(id) ON DELETE CASCADE;


--
-- Name: plan_backtest plan_backtest_backtest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_backtest
    ADD CONSTRAINT plan_backtest_backtest_id_fkey FOREIGN KEY (backtest_id) REFERENCES public.backtests(id) ON DELETE SET NULL;


--
-- Name: plan_backtest plan_backtest_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_backtest
    ADD CONSTRAINT plan_backtest_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.plan_candidate(id) ON DELETE SET NULL;


--
-- Name: plan_backtest plan_backtest_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_backtest
    ADD CONSTRAINT plan_backtest_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.dataset(id) ON DELETE SET NULL;


--
-- Name: plan_backtest plan_backtest_plan_id_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_backtest
    ADD CONSTRAINT plan_backtest_plan_id_context_rev_fkey FOREIGN KEY (plan_id, context_rev) REFERENCES public.plan_context_revision(plan_id, rev) ON DELETE CASCADE;


--
-- Name: plan_candidate_breakeven plan_candidate_breakeven_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate_breakeven
    ADD CONSTRAINT plan_candidate_breakeven_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.plan_candidate(id) ON DELETE CASCADE;


--
-- Name: plan_candidate_intent plan_candidate_intent_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate_intent
    ADD CONSTRAINT plan_candidate_intent_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.plan_candidate(id) ON DELETE CASCADE;


--
-- Name: plan_candidate_leg plan_candidate_leg_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate_leg
    ADD CONSTRAINT plan_candidate_leg_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.plan_candidate(id) ON DELETE CASCADE;


--
-- Name: plan_candidate plan_candidate_plan_id_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate
    ADD CONSTRAINT plan_candidate_plan_id_context_rev_fkey FOREIGN KEY (plan_id, context_rev) REFERENCES public.plan_context_revision(plan_id, rev) ON DELETE CASCADE;


--
-- Name: plan_candidate plan_candidate_recommendation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate
    ADD CONSTRAINT plan_candidate_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES public.recommendation(id) ON DELETE SET NULL;


--
-- Name: plan_candidate plan_candidate_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate
    ADD CONSTRAINT plan_candidate_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.plan_strategy_run(id) ON DELETE CASCADE;


--
-- Name: plan_candidate_warning plan_candidate_warning_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_candidate_warning
    ADD CONSTRAINT plan_candidate_warning_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.plan_candidate(id) ON DELETE CASCADE;


--
-- Name: plan_context_revision plan_context_revision_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_context_revision
    ADD CONSTRAINT plan_context_revision_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: plan_create_request plan_create_request_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_create_request
    ADD CONSTRAINT plan_create_request_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: plan_decision plan_decision_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision
    ADD CONSTRAINT plan_decision_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.accounts(id) ON DELETE SET NULL;


--
-- Name: plan_decision_ack plan_decision_ack_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision_ack
    ADD CONSTRAINT plan_decision_ack_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES public.plan_decision(id) ON DELETE CASCADE;


--
-- Name: plan_decision plan_decision_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision
    ADD CONSTRAINT plan_decision_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.plan_candidate(id) ON DELETE SET NULL;


--
-- Name: plan_decision plan_decision_ensemble_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision
    ADD CONSTRAINT plan_decision_ensemble_id_fkey FOREIGN KEY (ensemble_id) REFERENCES public.plan_ensemble(id) ON DELETE SET NULL;


--
-- Name: plan_decision_leg plan_decision_leg_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision_leg
    ADD CONSTRAINT plan_decision_leg_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES public.plan_decision(id) ON DELETE CASCADE;


--
-- Name: plan_decision_metric plan_decision_metric_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision_metric
    ADD CONSTRAINT plan_decision_metric_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES public.plan_decision(id) ON DELETE CASCADE;


--
-- Name: plan_decision plan_decision_plan_id_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision
    ADD CONSTRAINT plan_decision_plan_id_context_rev_fkey FOREIGN KEY (plan_id, context_rev) REFERENCES public.plan_context_revision(plan_id, rev);


--
-- Name: plan_decision plan_decision_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision
    ADD CONSTRAINT plan_decision_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: plan_decision plan_decision_recommendation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_decision
    ADD CONSTRAINT plan_decision_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES public.recommendation(id) ON DELETE SET NULL;


--
-- Name: plan_ensemble plan_ensemble_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_ensemble
    ADD CONSTRAINT plan_ensemble_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.dataset(id) ON DELETE SET NULL;


--
-- Name: plan_ensemble plan_ensemble_fingerprint_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_ensemble
    ADD CONSTRAINT plan_ensemble_fingerprint_fkey FOREIGN KEY (fingerprint) REFERENCES public.ensemble_artifact(fingerprint) ON DELETE RESTRICT;


--
-- Name: plan_ensemble plan_ensemble_plan_id_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_ensemble
    ADD CONSTRAINT plan_ensemble_plan_id_context_rev_fkey FOREIGN KEY (plan_id, context_rev) REFERENCES public.plan_context_revision(plan_id, rev) ON DELETE CASCADE;


--
-- Name: plan_ensemble_quantile plan_ensemble_quantile_ensemble_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_ensemble_quantile
    ADD CONSTRAINT plan_ensemble_quantile_ensemble_id_fkey FOREIGN KEY (ensemble_id) REFERENCES public.plan_ensemble(id) ON DELETE CASCADE;


--
-- Name: plan_evidence_analog plan_evidence_analog_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_analog
    ADD CONSTRAINT plan_evidence_analog_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.plan_evidence(id) ON DELETE CASCADE;


--
-- Name: plan_evidence plan_evidence_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence
    ADD CONSTRAINT plan_evidence_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.dataset(id) ON DELETE SET NULL;


--
-- Name: plan_evidence_distribution plan_evidence_distribution_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_distribution
    ADD CONSTRAINT plan_evidence_distribution_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.plan_evidence(id) ON DELETE CASCADE;


--
-- Name: plan_evidence_event plan_evidence_event_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_event
    ADD CONSTRAINT plan_evidence_event_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.plan_evidence(id) ON DELETE CASCADE;


--
-- Name: plan_evidence_example plan_evidence_example_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_example
    ADD CONSTRAINT plan_evidence_example_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.plan_evidence(id) ON DELETE CASCADE;


--
-- Name: plan_evidence_metric plan_evidence_metric_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_metric
    ADD CONSTRAINT plan_evidence_metric_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.plan_evidence(id) ON DELETE CASCADE;


--
-- Name: plan_evidence_note plan_evidence_note_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_note
    ADD CONSTRAINT plan_evidence_note_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.plan_evidence(id) ON DELETE CASCADE;


--
-- Name: plan_evidence_param plan_evidence_param_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_param
    ADD CONSTRAINT plan_evidence_param_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.plan_evidence(id) ON DELETE CASCADE;


--
-- Name: plan_evidence plan_evidence_plan_id_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence
    ADD CONSTRAINT plan_evidence_plan_id_context_rev_fkey FOREIGN KEY (plan_id, context_rev) REFERENCES public.plan_context_revision(plan_id, rev) ON DELETE CASCADE;


--
-- Name: plan_evidence_stat plan_evidence_stat_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_evidence_stat
    ADD CONSTRAINT plan_evidence_stat_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.plan_evidence(id) ON DELETE CASCADE;


--
-- Name: plan_level_odds plan_level_odds_ensemble_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_level_odds
    ADD CONSTRAINT plan_level_odds_ensemble_id_fkey FOREIGN KEY (ensemble_id) REFERENCES public.plan_ensemble(id) ON DELETE CASCADE;


--
-- Name: plan_link plan_link_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_link
    ADD CONSTRAINT plan_link_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES public.plan_decision(id) ON DELETE SET NULL;


--
-- Name: plan_link plan_link_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_link
    ADD CONSTRAINT plan_link_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: plan_link plan_link_recommendation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_link
    ADD CONSTRAINT plan_link_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES public.recommendation(id) ON DELETE CASCADE;


--
-- Name: plan_link plan_link_related_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_link
    ADD CONSTRAINT plan_link_related_plan_id_fkey FOREIGN KEY (related_plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: plan_link plan_link_sim_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_link
    ADD CONSTRAINT plan_link_sim_session_id_fkey FOREIGN KEY (sim_session_id) REFERENCES public.sim_session(id) ON DELETE CASCADE;


--
-- Name: plan_link plan_link_trade_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_link
    ADD CONSTRAINT plan_link_trade_id_fkey FOREIGN KEY (trade_id) REFERENCES public.trades(id) ON DELETE CASCADE;


--
-- Name: plan_management_action plan_management_action_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_management_action
    ADD CONSTRAINT plan_management_action_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES public.plan_decision(id) ON DELETE SET NULL;


--
-- Name: plan_management_action plan_management_action_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_management_action
    ADD CONSTRAINT plan_management_action_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: plan_management_action plan_management_action_receipt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_management_action
    ADD CONSTRAINT plan_management_action_receipt_id_fkey FOREIGN KEY (receipt_id) REFERENCES public.position_receipt(id) ON DELETE RESTRICT;


--
-- Name: plan_management_action plan_management_action_sim_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_management_action
    ADD CONSTRAINT plan_management_action_sim_session_id_fkey FOREIGN KEY (sim_session_id) REFERENCES public.sim_session(id) ON DELETE SET NULL;


--
-- Name: plan_management_action plan_management_action_trade_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_management_action
    ADD CONSTRAINT plan_management_action_trade_id_fkey FOREIGN KEY (trade_id) REFERENCES public.trades(id) ON DELETE SET NULL;


--
-- Name: plan_outcome_band plan_outcome_band_outcome_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_band
    ADD CONSTRAINT plan_outcome_band_outcome_id_fkey FOREIGN KEY (outcome_id) REFERENCES public.plan_outcome_run(id) ON DELETE CASCADE;


--
-- Name: plan_outcome_bucket plan_outcome_bucket_outcome_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_bucket
    ADD CONSTRAINT plan_outcome_bucket_outcome_id_fkey FOREIGN KEY (outcome_id) REFERENCES public.plan_outcome_run(id) ON DELETE CASCADE;


--
-- Name: plan_outcome_comparison plan_outcome_comparison_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_comparison
    ADD CONSTRAINT plan_outcome_comparison_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.dataset(id) ON DELETE SET NULL;


--
-- Name: plan_outcome_comparison plan_outcome_comparison_ensemble_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_comparison
    ADD CONSTRAINT plan_outcome_comparison_ensemble_id_fkey FOREIGN KEY (ensemble_id) REFERENCES public.plan_ensemble(id) ON DELETE RESTRICT;


--
-- Name: plan_outcome_comparison_item plan_outcome_comparison_item_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_comparison_item
    ADD CONSTRAINT plan_outcome_comparison_item_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.plan_candidate(id) ON DELETE RESTRICT;


--
-- Name: plan_outcome_comparison_item plan_outcome_comparison_item_comparison_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_comparison_item
    ADD CONSTRAINT plan_outcome_comparison_item_comparison_id_fkey FOREIGN KEY (comparison_id) REFERENCES public.plan_outcome_comparison(id) ON DELETE CASCADE;


--
-- Name: plan_outcome_comparison plan_outcome_comparison_plan_id_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_comparison
    ADD CONSTRAINT plan_outcome_comparison_plan_id_context_rev_fkey FOREIGN KEY (plan_id, context_rev) REFERENCES public.plan_context_revision(plan_id, rev) ON DELETE CASCADE;


--
-- Name: plan_outcome_market_metric plan_outcome_market_metric_outcome_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_market_metric
    ADD CONSTRAINT plan_outcome_market_metric_outcome_id_fkey FOREIGN KEY (outcome_id) REFERENCES public.plan_outcome_run(id) ON DELETE CASCADE;


--
-- Name: plan_outcome_note plan_outcome_note_outcome_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_note
    ADD CONSTRAINT plan_outcome_note_outcome_id_fkey FOREIGN KEY (outcome_id) REFERENCES public.plan_outcome_run(id) ON DELETE CASCADE;


--
-- Name: plan_outcome_run plan_outcome_run_candidate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_run
    ADD CONSTRAINT plan_outcome_run_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES public.plan_candidate(id) ON DELETE RESTRICT;


--
-- Name: plan_outcome_run plan_outcome_run_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_run
    ADD CONSTRAINT plan_outcome_run_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.dataset(id) ON DELETE SET NULL;


--
-- Name: plan_outcome_run plan_outcome_run_ensemble_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_run
    ADD CONSTRAINT plan_outcome_run_ensemble_id_fkey FOREIGN KEY (ensemble_id) REFERENCES public.plan_ensemble(id) ON DELETE RESTRICT;


--
-- Name: plan_outcome_run plan_outcome_run_plan_id_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_outcome_run
    ADD CONSTRAINT plan_outcome_run_plan_id_context_rev_fkey FOREIGN KEY (plan_id, context_rev) REFERENCES public.plan_context_revision(plan_id, rev) ON DELETE CASCADE;


--
-- Name: plan_portfolio_action plan_portfolio_action_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_portfolio_action
    ADD CONSTRAINT plan_portfolio_action_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: plan_portfolio_action plan_portfolio_action_receipt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_portfolio_action
    ADD CONSTRAINT plan_portfolio_action_receipt_id_fkey FOREIGN KEY (receipt_id) REFERENCES public.position_receipt(id) ON DELETE RESTRICT;


--
-- Name: plan_portfolio_action plan_portfolio_action_structure_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_portfolio_action
    ADD CONSTRAINT plan_portfolio_action_structure_revision_id_fkey FOREIGN KEY (structure_revision_id) REFERENCES public.portfolio_structure_revision(id) ON DELETE RESTRICT;


--
-- Name: plan_portfolio_action plan_portfolio_action_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_portfolio_action
    ADD CONSTRAINT plan_portfolio_action_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.portfolio_transaction(id) ON DELETE RESTRICT;


--
-- Name: plan_review plan_review_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_review
    ADD CONSTRAINT plan_review_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES public.plan_decision(id) ON DELETE SET NULL;


--
-- Name: plan_review plan_review_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_review
    ADD CONSTRAINT plan_review_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: plan_strategy_note plan_strategy_note_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_strategy_note
    ADD CONSTRAINT plan_strategy_note_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.plan_strategy_run(id) ON DELETE CASCADE;


--
-- Name: plan_strategy_rejection plan_strategy_rejection_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_strategy_rejection
    ADD CONSTRAINT plan_strategy_rejection_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.plan_strategy_run(id) ON DELETE CASCADE;


--
-- Name: plan_strategy_run plan_strategy_run_plan_id_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_strategy_run
    ADD CONSTRAINT plan_strategy_run_plan_id_context_rev_fkey FOREIGN KEY (plan_id, context_rev) REFERENCES public.plan_context_revision(plan_id, rev) ON DELETE CASCADE;


--
-- Name: plans plans_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plans
    ADD CONSTRAINT plans_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.accounts(id) ON DELETE SET NULL;


--
-- Name: plans plans_origin_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plans
    ADD CONSTRAINT plans_origin_plan_id_fkey FOREIGN KEY (origin_plan_id) REFERENCES public.plans(id) ON DELETE SET NULL;


--
-- Name: plans plans_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plans
    ADD CONSTRAINT plans_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: portfolio_import_pending_leg portfolio_import_pending_leg_pending_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_pending_leg
    ADD CONSTRAINT portfolio_import_pending_leg_pending_id_fkey FOREIGN KEY (pending_id) REFERENCES public.portfolio_import_pending(id) ON DELETE CASCADE;


--
-- Name: portfolio_import_pending portfolio_import_pending_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_pending
    ADD CONSTRAINT portfolio_import_pending_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE SET NULL;


--
-- Name: portfolio_import_pending portfolio_import_pending_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_pending
    ADD CONSTRAINT portfolio_import_pending_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: portfolio_import_resolution portfolio_import_resolution_pending_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_resolution
    ADD CONSTRAINT portfolio_import_resolution_pending_id_fkey FOREIGN KEY (pending_id) REFERENCES public.portfolio_import_pending(id) ON DELETE RESTRICT;


--
-- Name: portfolio_import_resolution portfolio_import_resolution_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_resolution
    ADD CONSTRAINT portfolio_import_resolution_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id) ON DELETE RESTRICT;


--
-- Name: portfolio_import_resolution portfolio_import_resolution_receipt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_resolution
    ADD CONSTRAINT portfolio_import_resolution_receipt_id_fkey FOREIGN KEY (receipt_id) REFERENCES public.position_receipt(id) ON DELETE RESTRICT;


--
-- Name: portfolio_import_resolution portfolio_import_resolution_resolver_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_resolution
    ADD CONSTRAINT portfolio_import_resolution_resolver_user_id_fkey FOREIGN KEY (resolver_user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: portfolio_import_resolution portfolio_import_resolution_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_import_resolution
    ADD CONSTRAINT portfolio_import_resolution_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.portfolio_transaction(id) ON DELETE RESTRICT;


--
-- Name: portfolio_lot_match portfolio_lot_match_closing_transaction_id_closing_leg_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_lot_match
    ADD CONSTRAINT portfolio_lot_match_closing_transaction_id_closing_leg_no_fkey FOREIGN KEY (closing_transaction_id, closing_leg_no) REFERENCES public.portfolio_transaction_leg(transaction_id, leg_no);


--
-- Name: portfolio_lot_match portfolio_lot_match_lot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_lot_match
    ADD CONSTRAINT portfolio_lot_match_lot_id_fkey FOREIGN KEY (lot_id) REFERENCES public.portfolio_lot(id);


--
-- Name: portfolio_lot_match portfolio_lot_match_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_lot_match
    ADD CONSTRAINT portfolio_lot_match_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id);


--
-- Name: portfolio_lot portfolio_lot_opening_transaction_id_opening_leg_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_lot
    ADD CONSTRAINT portfolio_lot_opening_transaction_id_opening_leg_no_fkey FOREIGN KEY (opening_transaction_id, opening_leg_no) REFERENCES public.portfolio_transaction_leg(transaction_id, leg_no);


--
-- Name: portfolio_lot portfolio_lot_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_lot
    ADD CONSTRAINT portfolio_lot_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id);


--
-- Name: portfolio_roll_match portfolio_roll_match_lot_match_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll_match
    ADD CONSTRAINT portfolio_roll_match_lot_match_id_fkey FOREIGN KEY (lot_match_id) REFERENCES public.portfolio_lot_match(id) ON DELETE RESTRICT;


--
-- Name: portfolio_roll_match portfolio_roll_match_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll_match
    ADD CONSTRAINT portfolio_roll_match_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.portfolio_roll(transaction_id) ON DELETE RESTRICT;


--
-- Name: portfolio_roll portfolio_roll_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll
    ADD CONSTRAINT portfolio_roll_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id);


--
-- Name: portfolio_roll portfolio_roll_replacement_lot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll
    ADD CONSTRAINT portfolio_roll_replacement_lot_id_fkey FOREIGN KEY (replacement_lot_id) REFERENCES public.portfolio_lot(id) ON DELETE RESTRICT;


--
-- Name: portfolio_roll portfolio_roll_transaction_id_closing_leg_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll
    ADD CONSTRAINT portfolio_roll_transaction_id_closing_leg_no_fkey FOREIGN KEY (transaction_id, closing_leg_no) REFERENCES public.portfolio_transaction_leg(transaction_id, leg_no);


--
-- Name: portfolio_roll portfolio_roll_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll
    ADD CONSTRAINT portfolio_roll_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.portfolio_transaction(id) ON DELETE RESTRICT;


--
-- Name: portfolio_roll portfolio_roll_transaction_id_opening_leg_no_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_roll
    ADD CONSTRAINT portfolio_roll_transaction_id_opening_leg_no_fkey FOREIGN KEY (transaction_id, opening_leg_no) REFERENCES public.portfolio_transaction_leg(transaction_id, leg_no);


--
-- Name: portfolio_structure_member portfolio_structure_member_lot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_member
    ADD CONSTRAINT portfolio_structure_member_lot_id_fkey FOREIGN KEY (lot_id) REFERENCES public.portfolio_lot(id) ON DELETE RESTRICT;


--
-- Name: portfolio_structure_member portfolio_structure_member_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_member
    ADD CONSTRAINT portfolio_structure_member_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.portfolio_structure_revision(id) ON DELETE CASCADE;


--
-- Name: portfolio_structure portfolio_structure_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure
    ADD CONSTRAINT portfolio_structure_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id) ON DELETE CASCADE;


--
-- Name: portfolio_structure_revision portfolio_structure_revision_prior_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_revision
    ADD CONSTRAINT portfolio_structure_revision_prior_revision_id_fkey FOREIGN KEY (prior_revision_id) REFERENCES public.portfolio_structure_revision(id) ON DELETE RESTRICT;


--
-- Name: portfolio_structure_revision portfolio_structure_revision_structure_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_revision
    ADD CONSTRAINT portfolio_structure_revision_structure_id_fkey FOREIGN KEY (structure_id) REFERENCES public.portfolio_structure(id) ON DELETE CASCADE;


--
-- Name: portfolio_structure_revision portfolio_structure_revision_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure_revision
    ADD CONSTRAINT portfolio_structure_revision_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.portfolio_transaction(id) ON DELETE RESTRICT;


--
-- Name: portfolio_structure portfolio_structure_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_structure
    ADD CONSTRAINT portfolio_structure_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: portfolio_tax_reconciliation portfolio_tax_reconciliation_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_tax_reconciliation
    ADD CONSTRAINT portfolio_tax_reconciliation_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id);


--
-- Name: portfolio_transaction_leg portfolio_transaction_leg_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_transaction_leg
    ADD CONSTRAINT portfolio_transaction_leg_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.portfolio_transaction(id) ON DELETE RESTRICT;


--
-- Name: portfolio_transaction portfolio_transaction_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_transaction
    ADD CONSTRAINT portfolio_transaction_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id);


--
-- Name: portfolio_valuation_missing_mark portfolio_valuation_missing_mark_valuation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_valuation_missing_mark
    ADD CONSTRAINT portfolio_valuation_missing_mark_valuation_id_fkey FOREIGN KEY (valuation_id) REFERENCES public.portfolio_valuation(id) ON DELETE CASCADE;


--
-- Name: portfolio_valuation portfolio_valuation_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_valuation
    ADD CONSTRAINT portfolio_valuation_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id);


--
-- Name: portfolio_wash_sale_allocation portfolio_wash_sale_allocation_loss_match_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_wash_sale_allocation
    ADD CONSTRAINT portfolio_wash_sale_allocation_loss_match_id_fkey FOREIGN KEY (loss_match_id) REFERENCES public.portfolio_lot_match(id);


--
-- Name: portfolio_wash_sale_allocation portfolio_wash_sale_allocation_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_wash_sale_allocation
    ADD CONSTRAINT portfolio_wash_sale_allocation_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id);


--
-- Name: portfolio_wash_sale_allocation portfolio_wash_sale_allocation_replacement_lot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_wash_sale_allocation
    ADD CONSTRAINT portfolio_wash_sale_allocation_replacement_lot_id_fkey FOREIGN KEY (replacement_lot_id) REFERENCES public.portfolio_lot(id);


--
-- Name: position_receipt position_receipt_account_objective_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_account_objective_revision_id_fkey FOREIGN KEY (account_objective_revision_id) REFERENCES public.account_objective_revision(id) ON DELETE SET NULL;


--
-- Name: position_receipt position_receipt_decision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_decision_id_fkey FOREIGN KEY (decision_id) REFERENCES public.plan_decision(id) ON DELETE SET NULL;


--
-- Name: position_receipt_leg position_receipt_leg_receipt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt_leg
    ADD CONSTRAINT position_receipt_leg_receipt_id_fkey FOREIGN KEY (receipt_id) REFERENCES public.position_receipt(id) ON DELETE CASCADE;


--
-- Name: position_receipt_metric position_receipt_metric_receipt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt_metric
    ADD CONSTRAINT position_receipt_metric_receipt_id_fkey FOREIGN KEY (receipt_id) REFERENCES public.position_receipt(id) ON DELETE CASCADE;


--
-- Name: position_receipt position_receipt_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE SET NULL;


--
-- Name: position_receipt position_receipt_plan_id_plan_context_rev_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_plan_id_plan_context_rev_fkey FOREIGN KEY (plan_id, plan_context_rev) REFERENCES public.plan_context_revision(plan_id, rev) ON DELETE SET NULL;


--
-- Name: position_receipt position_receipt_portfolio_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_portfolio_account_id_fkey FOREIGN KEY (portfolio_account_id) REFERENCES public.portfolio_account(id) ON DELETE SET NULL;


--
-- Name: position_receipt position_receipt_practice_trade_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_practice_trade_id_fkey FOREIGN KEY (practice_trade_id) REFERENCES public.trades(id) ON DELETE SET NULL;


--
-- Name: position_receipt position_receipt_structure_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_structure_revision_id_fkey FOREIGN KEY (structure_revision_id) REFERENCES public.portfolio_structure_revision(id) ON DELETE SET NULL;


--
-- Name: position_receipt position_receipt_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.portfolio_transaction(id) ON DELETE SET NULL;


--
-- Name: position_receipt position_receipt_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.position_receipt
    ADD CONSTRAINT position_receipt_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: positions positions_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: recommendation recommendation_evaluation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recommendation
    ADD CONSTRAINT recommendation_evaluation_id_fkey FOREIGN KEY (evaluation_id) REFERENCES public.strategy_evaluation(id);


--
-- Name: recommendation recommendation_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recommendation
    ADD CONSTRAINT recommendation_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: research_note research_note_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.research_note
    ADD CONSTRAINT research_note_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: sim_replay_source sim_replay_source_ensemble_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_replay_source
    ADD CONSTRAINT sim_replay_source_ensemble_id_fkey FOREIGN KEY (ensemble_id) REFERENCES public.plan_ensemble(id) ON DELETE RESTRICT;


--
-- Name: sim_replay_source sim_replay_source_fingerprint_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_replay_source
    ADD CONSTRAINT sim_replay_source_fingerprint_fkey FOREIGN KEY (fingerprint) REFERENCES public.ensemble_artifact(fingerprint) ON DELETE RESTRICT;


--
-- Name: sim_replay_source sim_replay_source_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_replay_source
    ADD CONSTRAINT sim_replay_source_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE;


--
-- Name: sim_replay_source sim_replay_source_sim_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_replay_source
    ADD CONSTRAINT sim_replay_source_sim_session_id_fkey FOREIGN KEY (sim_session_id) REFERENCES public.sim_session(id) ON DELETE CASCADE;


--
-- Name: sim_session_event sim_session_event_sim_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sim_session_event
    ADD CONSTRAINT sim_session_event_sim_session_id_fkey FOREIGN KEY (sim_session_id) REFERENCES public.sim_session(id) ON DELETE CASCADE;


--
-- Name: strategy_evaluation strategy_evaluation_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.strategy_evaluation
    ADD CONSTRAINT strategy_evaluation_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: trade_marks trade_marks_trade_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trade_marks
    ADD CONSTRAINT trade_marks_trade_id_fkey FOREIGN KEY (trade_id) REFERENCES public.trades(id);


--
-- Name: trades trades_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trades
    ADD CONSTRAINT trades_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: underlying_bar underlying_bar_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.underlying_bar
    ADD CONSTRAINT underlying_bar_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.dataset(id) ON DELETE CASCADE;


-- Current baseline identities and the one non-generated market dataset.
INSERT INTO public.users(id,name,created_at,updated_at) VALUES
    ('local','Local user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
    ('system','System data',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO public.dataset(id,name,kind,evidence,user_id,created_at) VALUES
    ('demo-fixture','Built-in demo data','FIXTURE_DEMO',
        '{"observed":false,"provenance":"DEMO"}'::jsonb,'local',CURRENT_TIMESTAMP),
    ('observed','Observed market data','OBSERVED_VENDOR',NULL,'system',CURRENT_TIMESTAMP);


--
-- PostgreSQL database dump complete
--
