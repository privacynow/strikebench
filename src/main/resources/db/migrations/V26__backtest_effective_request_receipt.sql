-- Preserve the exact default-resolved replay inputs, not the caller's nullable DTO.
-- Existing rows remain readable through the scalar columns; every new run writes both fields.
ALTER TABLE backtests
    ADD COLUMN effective_request jsonb,
    ADD COLUMN input_hash text;

ALTER TABLE backtests
    ADD CONSTRAINT backtests_effective_request_receipt_check CHECK (
        (effective_request IS NULL AND input_hash IS NULL)
        OR (effective_request IS NOT NULL AND input_hash IS NOT NULL
            AND jsonb_typeof(effective_request) = 'object' AND length(input_hash) = 64)
    );
