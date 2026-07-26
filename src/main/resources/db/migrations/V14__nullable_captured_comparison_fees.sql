-- A proposal comparison is after-cost only when the captured package-price receipt states the
-- commission. The old NOT NULL DEFAULT 0 contract converted an absent historical commission into
-- a free round trip at rest, even when the controller correctly refused to simulate the proposal.
ALTER TABLE plan_outcome_comparison_item
    ALTER COLUMN round_trip_fees_cents DROP DEFAULT,
    ALTER COLUMN round_trip_fees_cents DROP NOT NULL;

-- A candidate's opening commission and its estimated round-trip commission are two captured
-- facts, not one amount reconstructed from the other by readers. Existing candidate rows were
-- produced under the historical symmetric schedule, so the only faithful backfill is the exact
-- rule in force when they were written. New producers persist both values explicitly.
ALTER TABLE plan_candidate ADD COLUMN estimated_round_trip_fees_cents bigint;
UPDATE plan_candidate
   SET estimated_round_trip_fees_cents = opening_fees_cents * 2
 WHERE opening_fees_cents IS NOT NULL;
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_round_trip_fees_nonnegative_check
    CHECK (estimated_round_trip_fees_cents IS NULL OR estimated_round_trip_fees_cents >= 0);
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_fee_pair_check
    CHECK ((opening_fees_cents IS NULL) = (estimated_round_trip_fees_cents IS NULL));
ALTER TABLE plan_candidate ADD CONSTRAINT plan_candidate_round_trip_not_less_than_open_check
    CHECK (estimated_round_trip_fees_cents IS NULL
        OR estimated_round_trip_fees_cents >= opening_fees_cents);

-- A frozen Plan decision owns the exact same package-price receipt that was reviewed. The legacy
-- proposed-net column and metric pair (`entryNetPremiumCents`, `feesOpenCents`) forced readers to
-- reconstruct a package price and silently diverged from candidates, previews, and held packages.
ALTER TABLE plan_decision ADD COLUMN price_receipt jsonb;

-- Historical decisions already froze enough evidence to build one honest receipt. Preserve a
-- stated option-only basis for campaign protocol reviews, the original package net and fee when
-- present, and explicitly mark decisions with no recorded price as unavailable. Historical
-- opening fees were written under the then-symmetric per-contract schedule, so only those rows
-- receive the corresponding 2x captured round-trip estimate.
WITH raw AS (
    SELECT d.id,
           COALESCE(d.qty,
                    (SELECT m.value_number::integer FROM plan_decision_metric m
                      WHERE m.decision_id=d.id AND m.metric_key='decisionQty'),
                    1) AS quantity,
           d.proposed_net_cents AS gross_net,
           (SELECT m.value_cents FROM plan_decision_metric m
             WHERE m.decision_id=d.id AND m.metric_key='feesOpenCents') AS opening_fee,
           (SELECT m.value_cents FROM plan_decision_metric m
             WHERE m.decision_id=d.id AND m.metric_key='orderLimitNetCents') AS resting_limit,
           (SELECT m.value_text FROM plan_decision_metric m
             WHERE m.decision_id=d.id AND m.metric_key='orderExecutability') AS executability,
           (SELECT m.value_text FROM plan_decision_metric m
             WHERE m.decision_id=d.id AND m.metric_key='orderValuationBasis') AS valuation_basis,
           EXISTS(SELECT 1 FROM plan_decision_leg l
                   WHERE l.decision_id=d.id AND l.instrument_type='STOCK') AS has_stock,
           (SELECT COUNT(*) FROM plan_decision_leg l
             WHERE l.decision_id=d.id) AS leg_count,
           (SELECT COUNT(*) FROM plan_decision_leg l
             WHERE l.decision_id=d.id
               AND COALESCE(l.fill_price,l.mid_price) IS NOT NULL) AS priced_leg_count,
           (SELECT COALESCE(SUM(
                       (CASE WHEN l.action='SELL' THEN 1 ELSE -1 END)
                       * ROUND(COALESCE(l.fill_price,l.mid_price)
                               * l.ratio * l.multiplier * 100)),0)::bigint
              FROM plan_decision_leg l
             WHERE l.decision_id=d.id AND l.instrument_type<>'STOCK') AS option_unit_net
           ,
           (SELECT COALESCE(SUM(
                       (CASE WHEN l.action='SELL' THEN 1 ELSE -1 END)
                       * ROUND(COALESCE(l.fill_price,l.mid_price)
                               * l.ratio * l.multiplier * 100)),0)::bigint
              FROM plan_decision_leg l
             WHERE l.decision_id=d.id AND l.instrument_type='STOCK') AS stock_unit_net,
           (SELECT COALESCE(SUM(
                       (CASE WHEN l.action='SELL' THEN 1 ELSE -1 END)
                       * ROUND(COALESCE(l.fill_price,l.mid_price)
                               * l.ratio * l.multiplier * 100)),0)::bigint
              FROM plan_decision_leg l
             WHERE l.decision_id=d.id) AS leg_unit_net
      FROM plan_decision d
),
frozen AS (
    SELECT r.*,
           r.gross_net IS NOT NULL
           AND COALESCE(r.valuation_basis, 'MODELED') <> 'UNAVAILABLE'
           AND (NOT r.has_stock OR (
                   r.leg_count = r.priced_leg_count
                   AND r.leg_unit_net * r.quantity = r.gross_net
               )) AS price_recoverable
      FROM raw r
)
UPDATE plan_decision d
   SET price_receipt = jsonb_build_object(
           'quantity', f.quantity,
           'optionNetPremiumCents',
               CASE WHEN NOT f.price_recoverable THEN NULL
                    WHEN f.has_stock THEN f.option_unit_net * f.quantity
                    ELSE f.gross_net END,
           'stockCashFlowCents',
               CASE WHEN NOT f.price_recoverable THEN NULL
                    WHEN f.has_stock THEN f.stock_unit_net * f.quantity
                    ELSE 0 END,
           'grossPackageNetCents',
               CASE WHEN f.price_recoverable THEN f.gross_net ELSE NULL END,
           'openingFeesCents',
               CASE WHEN f.price_recoverable THEN f.opening_fee ELSE NULL END,
           'estimatedRoundTripFeesCents',
               CASE WHEN NOT f.price_recoverable OR f.opening_fee IS NULL THEN NULL
                    ELSE f.opening_fee * 2 END,
           'afterFeeNetCents',
               CASE WHEN NOT f.price_recoverable OR f.opening_fee IS NULL THEN NULL
                    ELSE f.gross_net - f.opening_fee END,
           'executableNetCents', NULL,
           'restingLimitNetCents',
               CASE WHEN f.price_recoverable THEN f.resting_limit ELSE NULL END,
           'valuationBasis',
               CASE WHEN NOT f.price_recoverable THEN 'UNAVAILABLE'
                    WHEN f.valuation_basis IN ('EXECUTABLE_BOOK','RESTING_LIMIT',
                            'RECORDED_FILL','MID_MARKET','MODELED')
                        THEN f.valuation_basis
                    ELSE 'MODELED' END,
           'executability',
               CASE WHEN f.price_recoverable
                         AND f.executability IN ('IMMEDIATE','RESTING','UNAVAILABLE')
                    THEN f.executability
                    ELSE 'UNAVAILABLE' END,
           'source', 'legacy-plan-decision',
           'freshness', d.evidence_provenance,
           'observedAt', (EXTRACT(EPOCH FROM d.quote_as_of) * 1000)::bigint,
           'fingerprint', 'legacy-plan-decision:' || d.id,
           'feeSide', 'OPENING',
           'unavailableReason',
               CASE WHEN f.price_recoverable THEN NULL
                    WHEN f.gross_net IS NULL
                        THEN 'this decision predates the canonical package-price receipt and has no recorded package net'
                    WHEN COALESCE(f.valuation_basis, 'MODELED') = 'UNAVAILABLE'
                        THEN 'this historical decision explicitly recorded its package price as unavailable'
                    WHEN f.has_stock
                        THEN 'this historical stock-containing decision cannot prove both sides of its package price from the frozen legs'
                    ELSE NULL END)
  FROM frozen f
 WHERE f.id=d.id;

ALTER TABLE plan_decision ALTER COLUMN price_receipt SET NOT NULL;
ALTER TABLE plan_decision ADD CONSTRAINT plan_decision_price_receipt_object_check
    CHECK (jsonb_typeof(price_receipt) = 'object');
ALTER TABLE plan_decision ADD CONSTRAINT plan_decision_price_receipt_shape_check
    CHECK (
        price_receipt ?& ARRAY[
            'quantity','optionNetPremiumCents','stockCashFlowCents','grossPackageNetCents',
            'openingFeesCents','estimatedRoundTripFeesCents','afterFeeNetCents',
            'executableNetCents','restingLimitNetCents','valuationBasis','executability',
            'source','freshness','observedAt','fingerprint','feeSide','unavailableReason'
        ]
        AND jsonb_typeof(price_receipt->'quantity') = 'number'
        AND (price_receipt->>'quantity')::integer >= 1
        AND jsonb_typeof(price_receipt->'optionNetPremiumCents') IN ('number','null')
        AND jsonb_typeof(price_receipt->'stockCashFlowCents') IN ('number','null')
        AND jsonb_typeof(price_receipt->'grossPackageNetCents') IN ('number','null')
        AND jsonb_typeof(price_receipt->'openingFeesCents') IN ('number','null')
        AND jsonb_typeof(price_receipt->'estimatedRoundTripFeesCents') IN ('number','null')
        AND jsonb_typeof(price_receipt->'afterFeeNetCents') IN ('number','null')
        AND jsonb_typeof(price_receipt->'executableNetCents') IN ('number','null')
        AND jsonb_typeof(price_receipt->'restingLimitNetCents') IN ('number','null')
        AND price_receipt->>'valuationBasis' IN (
            'EXECUTABLE_BOOK','RESTING_LIMIT','RECORDED_FILL','MID_MARKET','MODELED','UNAVAILABLE')
        AND price_receipt->>'executability' IN ('IMMEDIATE','RESTING','UNAVAILABLE')
        AND price_receipt->>'feeSide' IN ('OPENING','CLOSING')
        AND jsonb_typeof(price_receipt->'source') IN ('string','null')
        AND jsonb_typeof(price_receipt->'freshness') IN ('string','null')
        AND jsonb_typeof(price_receipt->'observedAt') IN ('number','null')
        AND jsonb_typeof(price_receipt->'fingerprint') IN ('string','null')
        AND jsonb_typeof(price_receipt->'unavailableReason') IN ('string','null')
    );
ALTER TABLE plan_decision ADD CONSTRAINT plan_decision_price_receipt_amounts_check
    CHECK (
        CASE WHEN price_receipt->>'valuationBasis' = 'UNAVAILABLE' THEN
            jsonb_typeof(price_receipt->'optionNetPremiumCents') = 'null'
            AND jsonb_typeof(price_receipt->'stockCashFlowCents') = 'null'
            AND jsonb_typeof(price_receipt->'grossPackageNetCents') = 'null'
            AND jsonb_typeof(price_receipt->'openingFeesCents') = 'null'
            AND jsonb_typeof(price_receipt->'estimatedRoundTripFeesCents') = 'null'
            AND jsonb_typeof(price_receipt->'afterFeeNetCents') = 'null'
            AND jsonb_typeof(price_receipt->'executableNetCents') = 'null'
            AND jsonb_typeof(price_receipt->'restingLimitNetCents') = 'null'
            AND price_receipt->>'executability' = 'UNAVAILABLE'
            AND jsonb_typeof(price_receipt->'unavailableReason') = 'string'
            AND length(trim(price_receipt->>'unavailableReason')) > 0
        ELSE
            jsonb_typeof(price_receipt->'optionNetPremiumCents') = 'number'
            AND jsonb_typeof(price_receipt->'stockCashFlowCents') = 'number'
            AND jsonb_typeof(price_receipt->'grossPackageNetCents') = 'number'
            AND (price_receipt->>'grossPackageNetCents')::bigint =
                (price_receipt->>'optionNetPremiumCents')::bigint
                + (price_receipt->>'stockCashFlowCents')::bigint
            AND jsonb_typeof(price_receipt->'unavailableReason') = 'null'
            AND (
                (jsonb_typeof(price_receipt->'openingFeesCents') = 'null'
                    AND jsonb_typeof(price_receipt->'afterFeeNetCents') = 'null')
                OR
                (jsonb_typeof(price_receipt->'openingFeesCents') = 'number'
                    AND (price_receipt->>'openingFeesCents')::bigint >= 0
                    AND jsonb_typeof(price_receipt->'afterFeeNetCents') = 'number'
                    AND (price_receipt->>'afterFeeNetCents')::bigint =
                        (price_receipt->>'grossPackageNetCents')::bigint
                        - (price_receipt->>'openingFeesCents')::bigint)
            )
            AND (
                jsonb_typeof(price_receipt->'estimatedRoundTripFeesCents') = 'null'
                OR (price_receipt->>'estimatedRoundTripFeesCents')::bigint >= 0
            )
            AND (
                price_receipt->>'feeSide' <> 'OPENING'
                OR (
                    jsonb_typeof(price_receipt->'openingFeesCents')
                        = jsonb_typeof(price_receipt->'estimatedRoundTripFeesCents')
                    AND (
                        jsonb_typeof(price_receipt->'openingFeesCents') = 'null'
                        OR (price_receipt->>'estimatedRoundTripFeesCents')::bigint
                            >= (price_receipt->>'openingFeesCents')::bigint
                    )
                )
            )
        END
    );

-- The receipt is now the sole frozen price authority. Remove both the primitive column and its
-- string-keyed metric twins so no future reader can accidentally revive a second price path.
ALTER TABLE plan_decision DROP COLUMN proposed_net_cents;
DELETE FROM plan_decision_metric
 WHERE metric_key IN ('entryNetPremiumCents','feesOpenCents',
                      'orderExecutability','orderValuationBasis');
