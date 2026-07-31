-- OBSERVED_STALE joined EvidenceLevel: a real feed's book aged past its freshness gate is old
-- observed data, not no data. The position-receipt check must accept the new tier or every
-- transformation that persists a receipt graded on a closed-market book fails at the database.
ALTER TABLE position_receipt DROP CONSTRAINT position_receipt_evidence_level_check;
ALTER TABLE position_receipt ADD CONSTRAINT position_receipt_evidence_level_check
    CHECK (evidence_level = ANY (ARRAY['OBSERVED_LIVE'::text, 'OBSERVED_DELAYED'::text,
        'OBSERVED_EOD'::text, 'OBSERVED_STALE'::text, 'MODELED'::text, 'SIMULATED'::text,
        'DEMO_FIXTURE'::text, 'UNKNOWN'::text]));
