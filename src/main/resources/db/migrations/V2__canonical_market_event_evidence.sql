CREATE TABLE public.market_event_evidence (
    payload_fingerprint text PRIMARY KEY,
    symbol text NOT NULL,
    event_type text NOT NULL,
    evidence_status text NOT NULL,
    event_date date,
    event_session text NOT NULL,
    confidence_start date,
    confidence_end date,
    source_kind text NOT NULL,
    source_label text NOT NULL,
    source_url text,
    observed_at timestamp with time zone NOT NULL,
    basis text NOT NULL,
    note text NOT NULL,
    reviewed_by text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT market_event_evidence_event_type_check
        CHECK (event_type IN ('EARNINGS', 'EX_DIVIDEND')),
    CONSTRAINT market_event_evidence_status_check
        CHECK (evidence_status IN ('CONFIRMED', 'ESTIMATED', 'UNAVAILABLE')),
    CONSTRAINT market_event_evidence_session_check
        CHECK (event_session IN ('BEFORE_OPEN', 'AFTER_CLOSE', 'UNKNOWN')),
    CONSTRAINT market_event_evidence_source_kind_check
        CHECK (source_kind IN ('ISSUER_CONFIRMED', 'REVIEWED_IMPORT', 'SEC_CADENCE', 'UNAVAILABLE')),
    CONSTRAINT market_event_evidence_shape_check CHECK (
        (evidence_status = 'UNAVAILABLE'
            AND event_date IS NULL
            AND confidence_start IS NULL
            AND confidence_end IS NULL
            AND source_kind = 'UNAVAILABLE')
        OR
        (evidence_status <> 'UNAVAILABLE'
            AND event_date IS NOT NULL
            AND confidence_start IS NOT NULL
            AND confidence_end IS NOT NULL
            AND confidence_start <= event_date
            AND event_date <= confidence_end
            AND source_kind <> 'UNAVAILABLE')
    ),
    CONSTRAINT market_event_evidence_confirmed_session_check CHECK (
        evidence_status <> 'CONFIRMED' OR event_session IN ('BEFORE_OPEN', 'AFTER_CLOSE')
    ),
    CONSTRAINT market_event_evidence_issuer_check CHECK (
        source_kind <> 'ISSUER_CONFIRMED' OR evidence_status = 'CONFIRMED'
    ),
    CONSTRAINT market_event_evidence_fingerprint_check CHECK (
        payload_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX market_event_evidence_symbol_date_idx
    ON public.market_event_evidence (symbol, event_type, confidence_end, observed_at DESC);

CREATE INDEX market_event_evidence_source_priority_idx
    ON public.market_event_evidence (symbol, event_type, source_kind, observed_at DESC);
