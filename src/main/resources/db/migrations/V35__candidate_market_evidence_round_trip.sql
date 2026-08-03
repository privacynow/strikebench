-- Candidate market facts must survive a Plan save/reload exactly. Compact freshness remains the
-- existing DataEvidence.label() projection; provenance and age stay separate so an age such as
-- NOT_APPLICABLE never replaces the evidence origin.

ALTER TABLE plan_candidate
    ADD COLUMN price_freshness text;

UPDATE plan_candidate
SET price_freshness = freshness
WHERE valuation_basis <> 'UNAVAILABLE'
  AND price_source IS NOT NULL;

ALTER TABLE plan_candidate
    ADD CONSTRAINT plan_candidate_price_freshness_check
    CHECK (price_freshness IS NULL OR price_freshness IN
           ('REALTIME', 'DELAYED', 'EOD', 'MODELED', 'SIMULATED', 'FIXTURE',
            'STALE', 'MISSING'));

ALTER TABLE plan_candidate
    ADD CONSTRAINT plan_candidate_price_freshness_pairing_check
    CHECK ((valuation_basis = 'UNAVAILABLE' AND price_freshness IS NULL)
        OR (valuation_basis <> 'UNAVAILABLE' AND price_freshness IS NOT NULL));

ALTER TABLE plan_candidate_leg
    ADD COLUMN quote_mid numeric(19,6),
    ADD COLUMN fill_basis text,
    ADD COLUMN quote_provenance text,
    ADD COLUMN quote_data_age text,
    ADD COLUMN quote_gamma double precision,
    ADD COLUMN quote_theta double precision,
    ADD COLUMN quote_vega double precision;

-- A legacy row did not retain last trade, so only a genuine two-sided midpoint can be rebuilt.
UPDATE plan_candidate_leg
SET quote_mid = round((quote_bid + quote_ask) / 2, 4)
WHERE quote_bid IS NOT NULL AND quote_ask IS NOT NULL
  AND quote_bid >= 0 AND quote_ask > 0 AND quote_ask >= quote_bid;

UPDATE plan_candidate_leg
SET quote_provenance = CASE
        WHEN quote_freshness IN ('FIXTURE', 'DEMO') THEN 'DEMO'
        WHEN quote_freshness = 'SIMULATED' THEN 'SIMULATED'
        WHEN quote_freshness = 'MODELED' THEN 'MODELED'
        WHEN quote_freshness = 'MISSING' THEN 'MISSING'
        WHEN lower(coalesce(quote_source, '')) LIKE '%broker%'
          OR lower(coalesce(quote_source, '')) LIKE '%etrade%' THEN 'BROKER'
        WHEN quote_freshness IS NOT NULL THEN 'OBSERVED'
        ELSE NULL
    END,
    quote_data_age = CASE
        WHEN quote_freshness IN ('FIXTURE', 'DEMO', 'SIMULATED', 'MODELED')
            THEN 'NOT_APPLICABLE'
        WHEN quote_freshness IN ('REALTIME', 'DELAYED', 'EOD', 'STALE', 'MISSING')
            THEN quote_freshness
        ELSE NULL
    END
WHERE quote_freshness IS NOT NULL;

-- For legacy rows this is the only fill basis the retained fields can prove.
UPDATE plan_candidate_leg
SET fill_basis = 'EXECUTABLE_BOOK'
WHERE quote_mid IS NOT NULL
  AND quote_freshness IN ('REALTIME', 'DELAYED', 'FIXTURE', 'SIMULATED');

ALTER TABLE plan_candidate_leg
    ADD CONSTRAINT plan_candidate_leg_fill_basis_check
    CHECK (fill_basis IS NULL OR fill_basis IN
           ('EXECUTABLE_BOOK', 'LABELED_MODEL_OR_MID', 'MID_MARKET', 'MODELED',
            'USER_EXECUTED', 'USER_PROPOSED'));

ALTER TABLE plan_candidate_leg
    ADD CONSTRAINT plan_candidate_leg_quote_provenance_check
    CHECK (quote_provenance IS NULL OR quote_provenance IN
           ('OBSERVED', 'BROKER', 'DEMO', 'SIMULATED', 'MODELED', 'MIXED', 'MISSING'));

ALTER TABLE plan_candidate_leg
    ADD CONSTRAINT plan_candidate_leg_quote_data_age_check
    CHECK (quote_data_age IS NULL OR quote_data_age IN
           ('REALTIME', 'DELAYED', 'EOD', 'STALE', 'NOT_APPLICABLE', 'MISSING'));

ALTER TABLE plan_candidate_leg
    ADD CONSTRAINT plan_candidate_leg_quote_evidence_pairing_check
    CHECK ((quote_freshness IS NULL AND quote_provenance IS NULL AND quote_data_age IS NULL)
        OR (quote_freshness IS NOT NULL AND quote_provenance IS NOT NULL
            AND quote_data_age IS NOT NULL));

ALTER TABLE plan_candidate_leg
    ADD CONSTRAINT plan_candidate_leg_quote_evidence_label_check
    CHECK (quote_freshness IS NULL OR quote_provenance IS NULL OR quote_freshness = CASE
        WHEN quote_data_age IN ('REALTIME', 'DELAYED', 'EOD', 'STALE', 'MISSING')
            THEN quote_data_age
        WHEN quote_data_age = 'NOT_APPLICABLE' AND quote_provenance = 'DEMO'
            THEN 'FIXTURE'
        WHEN quote_data_age = 'NOT_APPLICABLE' AND quote_provenance = 'SIMULATED'
            THEN 'SIMULATED'
        WHEN quote_data_age = 'NOT_APPLICABLE' AND quote_provenance = 'MODELED'
            THEN 'MODELED'
        ELSE 'MISSING'
    END);
