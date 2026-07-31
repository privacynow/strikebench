ALTER TABLE plan_candidate_leg
    ADD COLUMN quote_bid numeric(19,6),
    ADD COLUMN quote_ask numeric(19,6),
    ADD COLUMN quote_as_of_epoch_ms bigint,
    ADD COLUMN quote_source text,
    ADD COLUMN quote_freshness text;

ALTER TABLE plan_candidate_leg
    ADD CONSTRAINT plan_candidate_leg_quote_freshness_check
    CHECK (quote_freshness IS NULL OR quote_freshness IN
           ('REALTIME', 'DELAYED', 'EOD', 'MODELED', 'SIMULATED', 'FIXTURE',
            'STALE', 'MISSING'));
