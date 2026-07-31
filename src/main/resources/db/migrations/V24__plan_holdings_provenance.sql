ALTER TABLE plan_context_revision
    ADD COLUMN holdings_provenance text;

-- Historical revisions did not capture where their share count came from. Intent is not proof of
-- custody, so keep the durable context readable without fabricating account or hypothetical
-- provenance. A subsequent explicit edit creates a new revision with current provenance.
UPDATE plan_context_revision
SET holdings_provenance = 'LEGACY_UNVERIFIED'
WHERE holdings_shares IS NOT NULL;

ALTER TABLE plan_context_revision
    ADD CONSTRAINT plan_context_revision_holdings_provenance_check
    CHECK (
        holdings_provenance IS NULL
        OR holdings_provenance IN (
            'ACCOUNT_BACKED',
            'HYPOTHETICAL_HOLDINGS',
            'ACQUISITION_TARGET',
            'LEGACY_UNVERIFIED'
        )
    );

ALTER TABLE plan_context_revision
    ADD CONSTRAINT plan_context_revision_holdings_evidence_check
    CHECK (
        (holdings_shares IS NULL AND holdings_provenance IS NULL)
        OR (holdings_shares IS NOT NULL AND holdings_provenance IS NOT NULL)
    );
