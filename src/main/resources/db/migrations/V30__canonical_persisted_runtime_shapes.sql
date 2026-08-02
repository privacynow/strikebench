-- One-time data normalization. Runtime code reads only the current holdings and mark shapes.

-- A frozen decision previously split one order instruction across three generic metrics, then
-- rebuilt the object while reading it. Persist the same typed object used by preview, placement,
-- the held trade, and the broker command.
ALTER TABLE plan_decision ADD COLUMN order_instruction_json jsonb;

UPDATE plan_decision decision
SET order_instruction_json = jsonb_strip_nulls(jsonb_build_object(
        'type', type_metric.value_text,
        'timeInForce', COALESCE(time_metric.value_text, 'DAY'),
        'limitNetCents', limit_metric.value_cents
    ))
FROM plan_decision_metric type_metric
LEFT JOIN plan_decision_metric time_metric
       ON time_metric.decision_id = type_metric.decision_id
      AND time_metric.metric_key = 'orderTimeInForce'
LEFT JOIN plan_decision_metric limit_metric
       ON limit_metric.decision_id = type_metric.decision_id
      AND limit_metric.metric_key = 'orderLimitNetCents'
WHERE type_metric.decision_id = decision.id
  AND type_metric.metric_key = 'orderType';

DELETE FROM plan_decision_metric
WHERE metric_key IN ('orderType', 'orderTimeInForce', 'orderLimitNetCents');

ALTER TABLE plan_decision
    ADD CONSTRAINT plan_decision_order_instruction_current_check
    CHECK (
        order_instruction_json IS NULL
        OR (
            jsonb_typeof(order_instruction_json) = 'object'
            AND order_instruction_json ->> 'type' IN ('MARKET', 'LIMIT')
            AND order_instruction_json ->> 'timeInForce' = 'DAY'
            AND (
                (order_instruction_json ->> 'type' = 'MARKET'
                    AND NOT order_instruction_json ? 'limitNetCents')
                OR
                (order_instruction_json ->> 'type' = 'LIMIT'
                    AND NULLIF(order_instruction_json ->> 'limitNetCents', '') IS NOT NULL)
            )
        )
    );

-- A held trade previously repeated its opening instruction inside the entry snapshot and in a
-- LIMIT-only scalar column. Normalize both historical forms once, then keep one typed instruction.
ALTER TABLE trades ADD COLUMN order_instruction_json jsonb;

UPDATE trades
SET order_instruction_json = CASE
        WHEN jsonb_typeof(entry_snapshot_json -> 'orderInstruction') = 'object' THEN
            (entry_snapshot_json -> 'orderInstruction')
                || jsonb_build_object(
                    'timeInForce', COALESCE(
                        entry_snapshot_json #>> '{orderInstruction,timeInForce}', 'DAY'))
        WHEN order_limit_net_cents IS NOT NULL THEN
            jsonb_build_object(
                'type', 'LIMIT',
                'limitNetCents', order_limit_net_cents,
                'timeInForce', 'DAY')
        ELSE NULL
    END,
    entry_snapshot_json = entry_snapshot_json - 'orderInstruction';

ALTER TABLE trades
    ADD CONSTRAINT trades_order_instruction_current_check
    CHECK (
        order_instruction_json IS NULL
        OR (
            jsonb_typeof(order_instruction_json) = 'object'
            AND order_instruction_json ->> 'type' IN ('MARKET', 'LIMIT')
            AND order_instruction_json ->> 'timeInForce' = 'DAY'
            AND (
                (order_instruction_json ->> 'type' = 'MARKET'
                    AND NOT order_instruction_json ? 'limitNetCents')
                OR
                (order_instruction_json ->> 'type' = 'LIMIT'
                    AND NULLIF(order_instruction_json ->> 'limitNetCents', '') IS NOT NULL)
            )
        )
    );

ALTER TABLE trades DROP COLUMN order_limit_net_cents;

-- Historical replay rows used two incompatible trade vocabularies (single-run premiums versus
-- managed-run credits) and did not persist enough information to translate the managed rows
-- honestly. Replays are reproducible derived artifacts, so discard those old results instead of
-- inventing fee/cash-flow values, then make every new replay use one signed cash-flow shape.
DELETE FROM backtests;

ALTER TABLE backtest_trade
    DROP COLUMN label,
    DROP COLUMN entry_net_premium_cents,
    DROP COLUMN credit_cents,
    DROP COLUMN exit_value_cents,
    ADD COLUMN opening_cash_cents bigint NOT NULL,
    ADD COLUMN closing_cash_cents bigint NOT NULL;

ALTER TABLE backtest_trade
    ALTER COLUMN strategy SET NOT NULL,
    ALTER COLUMN fees_cents SET NOT NULL;

UPDATE plan_candidate candidate
SET screening_intent = run.intent
FROM plan_strategy_run run
WHERE candidate.run_id = run.id
  AND candidate.screening_intent IS NULL;

ALTER TABLE plan_candidate
    ALTER COLUMN screening_intent SET NOT NULL;

UPDATE plan_context_revision context
SET holdings_provenance = CASE
        WHEN plan.intent = 'ACQUIRE' THEN 'ACQUISITION_TARGET'
        ELSE 'HYPOTHETICAL_HOLDINGS'
    END
FROM plans plan
WHERE plan.id = context.plan_id
  AND context.holdings_provenance = 'LEGACY_UNVERIFIED';

ALTER TABLE plan_context_revision
    DROP CONSTRAINT plan_context_revision_holdings_provenance_check;

ALTER TABLE plan_context_revision
    ADD CONSTRAINT plan_context_revision_holdings_provenance_check
    CHECK (
        holdings_provenance IS NULL
        OR holdings_provenance IN (
            'ACCOUNT_BACKED',
            'HYPOTHETICAL_HOLDINGS',
            'ACQUISITION_TARGET'
        )
    );

UPDATE plan_candidate
SET holdings_evidence = jsonb_build_object(
        'provenance', 'HYPOTHETICAL_HOLDINGS',
        'shares', COALESCE(
            NULLIF(holdings_evidence ->> 'shares', '')::integer,
            shares_needed
        ),
        'costBasisCents', NULLIF(holdings_evidence ->> 'costBasisCents', '')::bigint,
        'basis', 'Shares and basis are a user-supplied hypothetical used only for analysis.',
        'destinationAccountId', NULL,
        'custodyType', NULL,
        'observedAtEpochMs', NULL
    )
WHERE uses_held_shares = 1
  AND (
      holdings_evidence IS NULL
      OR holdings_evidence ->> 'provenance' = 'LEGACY_UNVERIFIED'
  );

ALTER TABLE plan_candidate
    ADD CONSTRAINT plan_candidate_holdings_evidence_current_check
    CHECK (
        COALESCE(uses_held_shares, 0) <> 1
        OR (
            holdings_evidence IS NOT NULL
            AND holdings_evidence ->> 'provenance' IN (
                'ACCOUNT_BACKED',
                'HYPOTHETICAL_HOLDINGS',
                'ACQUISITION_TARGET'
            )
        )
    );

UPDATE strategy_evaluation
SET result_json = jsonb_set(
        result_json,
        '{candidate,holdingsEvidence}',
        jsonb_build_object(
            'provenance', 'HYPOTHETICAL_HOLDINGS',
            'shares', NULLIF(result_json #>> '{candidate,sharesNeeded}', '')::integer,
            'costBasisCents', NULL,
            'basis', 'Shares and basis are a user-supplied hypothetical used only for analysis.',
            'destinationAccountId', NULL,
            'custodyType', NULL,
            'observedAtEpochMs', NULL
        ),
        true
    )
WHERE result_json #>> '{candidate,usesHeldShares}' = 'true'
  AND (
      result_json #> '{candidate,holdingsEvidence}' IS NULL
      OR result_json #>> '{candidate,holdingsEvidence,provenance}' = 'LEGACY_UNVERIFIED'
  );

ALTER TABLE strategy_evaluation
    ADD CONSTRAINT strategy_evaluation_holdings_evidence_current_check
    CHECK (
        COALESCE(result_json #>> '{candidate,usesHeldShares}', 'false') <> 'true'
        OR result_json #>> '{candidate,holdingsEvidence,provenance}' IN (
            'ACCOUNT_BACKED',
            'HYPOTHETICAL_HOLDINGS',
            'ACQUISITION_TARGET'
        )
    );

-- Scalar trade-mark columns were a second historical response dialect. A mark history row now
-- exists only when it contains the exact current-position result written at observation time.
DELETE FROM trade_marks
WHERE current_mark_json IS NULL
   OR current_mark_json ->> 'schemaVersion' IS DISTINCT FROM 'current-position-mark-v1'
   OR NULLIF(current_mark_json ->> 'fingerprint', '') IS NULL
   OR current_mark_json ->> 'tradeId' IS NULL
   OR NULLIF(current_mark_json ->> 'ts', '') IS NULL
   OR jsonb_typeof(current_mark_json -> 'currentClosePrice') IS DISTINCT FROM 'object'
   OR jsonb_typeof(current_mark_json -> 'marketImpliedRisk') IS DISTINCT FROM 'object';

ALTER TABLE trade_marks
    ALTER COLUMN current_mark_json SET NOT NULL;

ALTER TABLE trade_marks
    ADD CONSTRAINT trade_marks_current_mark_schema_check
    CHECK (
        current_mark_json ->> 'schemaVersion' = 'current-position-mark-v1'
        AND NULLIF(current_mark_json ->> 'fingerprint', '') IS NOT NULL
        AND current_mark_json ->> 'tradeId' = trade_id
        AND NULLIF(current_mark_json ->> 'ts', '') IS NOT NULL
        AND jsonb_typeof(current_mark_json -> 'currentClosePrice') = 'object'
        AND jsonb_typeof(current_mark_json -> 'marketImpliedRisk') = 'object'
    );

ALTER TABLE trade_marks
    DROP COLUMN underlying_px_cents,
    DROP COLUMN close_cost_cents,
    DROP COLUMN unrealized_cents,
    DROP COLUMN decision_unrealized_cents,
    DROP COLUMN pop_now,
    DROP COLUMN freshness,
    DROP COLUMN detail_json;

UPDATE sim_session
SET anchors = (anchors - 'compatibilityStatus' - 'compatibilityReason')
        || jsonb_strip_nulls(jsonb_build_object(
            'configurationStatus', anchors -> 'compatibilityStatus',
            'configurationReason', anchors -> 'compatibilityReason'
        ))
WHERE anchors ? 'compatibilityStatus'
   OR anchors ? 'compatibilityReason';

-- Analyses written before Scenario Canvas persistence used a different valuation clock and cannot
-- be read through the current model. Preserve them as history, but remove them from every CURRENT
-- selector once, here, instead of maintaining a permanent runtime reconstruction path.
UPDATE plan_outcome_run outcome
SET state = 'BLOCKED'
WHERE outcome.state = 'CURRENT'
  AND outcome.ensemble_id IN (
      SELECT ensemble.id
      FROM plan_ensemble ensemble
      WHERE NOT EXISTS (
          SELECT 1 FROM plan_ensemble_canvas canvas
          WHERE canvas.ensemble_id = ensemble.id
      )
  );

UPDATE plan_outcome_comparison comparison
SET state = 'BLOCKED'
WHERE comparison.state = 'CURRENT'
  AND comparison.ensemble_id IN (
      SELECT ensemble.id
      FROM plan_ensemble ensemble
      WHERE NOT EXISTS (
          SELECT 1 FROM plan_ensemble_canvas canvas
          WHERE canvas.ensemble_id = ensemble.id
      )
  );

UPDATE plan_ensemble ensemble
SET state = 'BLOCKED'
WHERE ensemble.state = 'CURRENT'
  AND NOT EXISTS (
      SELECT 1 FROM plan_ensemble_canvas canvas
      WHERE canvas.ensemble_id = ensemble.id
  );
