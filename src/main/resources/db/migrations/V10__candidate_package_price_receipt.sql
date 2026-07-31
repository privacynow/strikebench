-- §7.2: the persisted candidate carries the WHOLE package-price receipt, not just two amounts.
-- Before this, a plan rail restored from the database knew the package net and the option net but
-- not the basis they were struck on, not the fee, not whether the book was two-sided, and — the
-- load-bearing omission — not WHEN they were observed. A restored rail therefore could not be
-- reconciled against a live order dock at all: §3.3 stayed open no matter how good the object was.
-- entry_net_cents and option_net_cents are retained as the receipt's gross and option-only columns.
ALTER TABLE plan_candidate ADD COLUMN stock_cash_flow_cents bigint;
ALTER TABLE plan_candidate ADD COLUMN opening_fees_cents bigint;
ALTER TABLE plan_candidate ADD COLUMN after_fee_net_cents bigint;
ALTER TABLE plan_candidate ADD COLUMN executable_net_cents bigint;
ALTER TABLE plan_candidate ADD COLUMN resting_limit_net_cents bigint;
ALTER TABLE plan_candidate ADD COLUMN valuation_basis text;
ALTER TABLE plan_candidate ADD COLUMN price_executability text;
ALTER TABLE plan_candidate ADD COLUMN price_fee_side text;
ALTER TABLE plan_candidate ADD COLUMN price_source text;
ALTER TABLE plan_candidate ADD COLUMN price_observed_at_epoch_ms bigint;
ALTER TABLE plan_candidate ADD COLUMN price_fingerprint text;
ALTER TABLE plan_candidate ADD COLUMN price_unavailable_reason text;

-- THE data decision for rows that predate the receipt (pre-release, so this is a clean break, not
-- a shim). Such a row carries a package net with NO basis, NO fee, NO observation stamp and NO
-- fingerprint. That amount cannot be reconciled against a live order dock — which is the entire
-- purpose of the receipt — and publishing it beside a defaulted "UNAVAILABLE" basis is exactly the
-- self-contradiction §3.3 forbids. The candidate itself (structure, legs, evaluation, identity)
-- stays; only its unattributable amounts are dropped, and the row states plainly that it is
-- unpriced so a re-scan is what prices it (§3.2: no fabricated value, no silent promotion).
UPDATE plan_candidate SET
        entry_net_cents = NULL,
        option_net_cents = NULL,
        stock_cash_flow_cents = NULL,
        opening_fees_cents = NULL,
        after_fee_net_cents = NULL,
        executable_net_cents = NULL,
        resting_limit_net_cents = NULL,
        valuation_basis = 'UNAVAILABLE',
        price_executability = 'UNAVAILABLE',
        price_fee_side = COALESCE(price_fee_side, 'OPENING'),
        price_unavailable_reason =
            'this candidate was stored before its price receipt existed — re-scan to price it'
    WHERE valuation_basis IS NULL;

ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_valuation_basis_check
    CHECK (valuation_basis = ANY (ARRAY[
        'EXECUTABLE_BOOK'::text, 'RESTING_LIMIT'::text, 'RECORDED_FILL'::text,
        'MID_MARKET'::text, 'MODELED'::text, 'UNAVAILABLE'::text]));
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_price_executability_check
    CHECK (price_executability = ANY (ARRAY[
        'IMMEDIATE'::text, 'RESTING'::text, 'UNAVAILABLE'::text]));
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_price_fee_side_check
    CHECK (price_fee_side = ANY (ARRAY['OPENING'::text, 'CLOSING'::text]));

-- Every candidate now states a basis, an executability and a fee side. "No receipt written" is no
-- longer a third state the reader has to interpret; the honest answer is the UNAVAILABLE basis.
ALTER TABLE plan_candidate ALTER COLUMN valuation_basis SET NOT NULL;
ALTER TABLE plan_candidate ALTER COLUMN price_executability SET NOT NULL;
ALTER TABLE plan_candidate ALTER COLUMN price_fee_side SET NOT NULL;

-- The receipt's own identities, enforced at rest as well as in the record, so no writer — now or
-- later — can persist a rail that the record would refuse to construct.
--   1. a stated price and a stated basis stand or fall together;
--   2. a priced package names BOTH sides and they add up to it;
--   3. an after-fee net exists exactly when a gross and a fee do, and equals their difference;
--   4. an unpriced package says why.
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_price_basis_pairing_check
    CHECK ((entry_net_cents IS NOT NULL) = (valuation_basis <> 'UNAVAILABLE'));
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_price_additive_check
    CHECK (entry_net_cents IS NULL
        OR (option_net_cents IS NOT NULL AND stock_cash_flow_cents IS NOT NULL
            AND entry_net_cents = option_net_cents + stock_cash_flow_cents));
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_price_after_fee_check
    CHECK (CASE WHEN entry_net_cents IS NOT NULL AND opening_fees_cents IS NOT NULL
                THEN after_fee_net_cents = entry_net_cents - opening_fees_cents
                ELSE after_fee_net_cents IS NULL END);
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_price_unavailable_reason_check
    CHECK (valuation_basis <> 'UNAVAILABLE'
        OR (price_unavailable_reason IS NOT NULL AND btrim(price_unavailable_reason) <> ''));
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_opening_fees_check
    CHECK (opening_fees_cents IS NULL OR opening_fees_cents >= 0);
