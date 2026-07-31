ALTER TABLE plan_candidate
    ADD COLUMN holdings_evidence jsonb;

ALTER TABLE plan_candidate
    ADD CONSTRAINT plan_candidate_holdings_evidence_check
    CHECK (holdings_evidence IS NULL OR jsonb_typeof(holdings_evidence) = 'object');
