-- Preserve when the source observed a market fact independently from when StrikeBench stored it.
-- Legacy rows remain nullable rather than receiving an invented timestamp.
ALTER TABLE option_bar
    ADD COLUMN source_observed_at timestamp with time zone;

ALTER TABLE underlying_bar
    ADD COLUMN source_observed_at timestamp with time zone;

-- Canonical integrity gates apply to every new or changed row without pretending legacy vendor
-- imports have already been audited. PostgreSQL enforces NOT VALID checks for future writes.
ALTER TABLE option_bar ADD CONSTRAINT option_bar_canonical_values_check CHECK (
    expiration >= asof
    AND strike > 0
    AND (bid IS NULL OR bid >= 0)
    AND (ask IS NULL OR ask >= 0)
    AND (last IS NULL OR last >= 0)
    AND (mark IS NULL OR mark >= 0)
    AND (bid IS NULL OR ask IS NULL OR bid <= ask)
    AND (iv IS NULL OR iv >= 0)
    AND (open_interest IS NULL OR open_interest >= 0)
    AND (volume IS NULL OR volume >= 0)
) NOT VALID;

ALTER TABLE underlying_bar ADD CONSTRAINT underlying_bar_canonical_values_check CHECK (
    close > 0
    AND (open IS NULL OR open > 0)
    AND (high IS NULL OR high > 0)
    AND (low IS NULL OR low > 0)
    AND (volume IS NULL OR volume >= 0)
    AND (high IS NULL OR low IS NULL OR high >= low)
) NOT VALID;

CREATE INDEX option_bar_coherent_source_lookup
    ON option_bar (dataset_id, symbol, asof, expiration, source, bid_ask_observed);
