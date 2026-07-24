CREATE TABLE public.position_lifecycle_decision_receipt (
    id text PRIMARY KEY,
    user_id text NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    portfolio_account_id text NOT NULL REFERENCES public.portfolio_account(id) ON DELETE CASCADE,
    position_fingerprint text NOT NULL,
    receipt_fingerprint text NOT NULL,
    policy_id text NOT NULL,
    policy_fingerprint text NOT NULL,
    market_snapshot_fingerprint text NOT NULL,
    model_fingerprint text NOT NULL,
    account_objective_revision_id text REFERENCES public.account_objective_revision(id) ON DELETE SET NULL,
    declaration_fingerprint text,
    verdict text NOT NULL,
    lifecycle_json jsonb NOT NULL,
    book_actions_json jsonb NOT NULL,
    capacity_json jsonb NOT NULL,
    decision_json jsonb NOT NULL,
    surfaced_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT position_lifecycle_receipt_verdict_check
        CHECK (verdict IN ('KEEP','HARVEST','REDUCE','DEFEND','ACCEPT_ASSIGNMENT')),
    CONSTRAINT position_lifecycle_receipt_fingerprints_check
        CHECK (length(position_fingerprint)=64 AND length(receipt_fingerprint)=64
            AND length(policy_fingerprint)=64 AND length(market_snapshot_fingerprint)=64)
);

CREATE INDEX idx_position_lifecycle_receipt_position
    ON public.position_lifecycle_decision_receipt
    (user_id, portfolio_account_id, position_fingerprint, surfaced_at DESC);

CREATE TABLE public.position_lifecycle_user_decision (
    id text PRIMARY KEY,
    receipt_id text NOT NULL REFERENCES public.position_lifecycle_decision_receipt(id) ON DELETE RESTRICT,
    user_id text NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    decision text NOT NULL,
    selected_action text,
    quantity integer,
    note text,
    decided_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT position_lifecycle_user_decision_check
        CHECK (decision IN ('KEEP','HARVEST','REDUCE','DEFEND','ACCEPT_ASSIGNMENT')),
    CONSTRAINT position_lifecycle_user_action_check
        CHECK (selected_action IS NULL OR selected_action IN
            ('HOLD','CLOSE_ONE','CLOSE_K','CLOSE_ALL','ASSIGNMENT','CALL_AWAY','ROLL','NO_ACTION')),
    CONSTRAINT position_lifecycle_user_quantity_check
        CHECK (quantity IS NULL OR quantity > 0)
);

CREATE INDEX idx_position_lifecycle_user_decision_receipt
    ON public.position_lifecycle_user_decision (receipt_id, decided_at DESC);
