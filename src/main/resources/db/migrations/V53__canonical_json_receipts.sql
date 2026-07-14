-- Structured snapshots are JSONB with shape checks. Queryable business facts remain typed columns.

ALTER TABLE trades
  ALTER COLUMN legs_json TYPE JSONB USING COALESCE(NULLIF(btrim(legs_json),'')::jsonb,'[]'::jsonb),
  ALTER COLUMN breakevens_json TYPE JSONB USING COALESCE(NULLIF(btrim(breakevens_json),'')::jsonb,'[]'::jsonb),
  ALTER COLUMN entry_snapshot_json TYPE JSONB USING COALESCE(NULLIF(btrim(entry_snapshot_json),'')::jsonb,'{}'::jsonb);
ALTER TABLE trades
  ADD CONSTRAINT trades_legs_json_array CHECK (jsonb_typeof(legs_json)='array'),
  ADD CONSTRAINT trades_breakevens_json_array CHECK (jsonb_typeof(breakevens_json)='array'),
  ADD CONSTRAINT trades_entry_snapshot_json_object CHECK (jsonb_typeof(entry_snapshot_json)='object');

ALTER TABLE trade_marks
  ALTER COLUMN detail_json TYPE JSONB
  USING CASE WHEN detail_json IS NULL OR btrim(detail_json)='' THEN NULL ELSE detail_json::jsonb END;
ALTER TABLE trade_marks
  ADD CONSTRAINT trade_marks_detail_json_object
  CHECK (detail_json IS NULL OR jsonb_typeof(detail_json)='object');

ALTER TABLE audit
  ALTER COLUMN detail_json TYPE JSONB
  USING CASE WHEN detail_json IS NULL OR btrim(detail_json)='' THEN NULL ELSE detail_json::jsonb END;
ALTER TABLE audit
  ADD CONSTRAINT audit_detail_json_object
  CHECK (detail_json IS NULL OR jsonb_typeof(detail_json)='object');

ALTER TABLE live_orders
  ALTER COLUMN payload_json TYPE JSONB
  USING COALESCE(NULLIF(btrim(payload_json),'')::jsonb,'{}'::jsonb);
ALTER TABLE live_orders
  ADD CONSTRAINT live_orders_payload_json_object CHECK (jsonb_typeof(payload_json)='object');

ALTER TABLE plan_candidate
  ALTER COLUMN evaluation_snapshot TYPE JSONB
  USING CASE WHEN evaluation_snapshot IS NULL OR btrim(evaluation_snapshot)='' THEN NULL ELSE evaluation_snapshot::jsonb END;
ALTER TABLE plan_candidate
  ADD CONSTRAINT plan_candidate_evaluation_snapshot_object
  CHECK (evaluation_snapshot IS NULL OR jsonb_typeof(evaluation_snapshot)='object');

-- A Strategy run's request is an immutable receipt. The old dotted-key EAV was write-only and
-- could not reconstruct arrays or nested objects without bespoke parsing.
ALTER TABLE plan_strategy_run ADD COLUMN request_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
UPDATE plan_strategy_run r
SET request_snapshot = p.snapshot
FROM (
  SELECT run_id,jsonb_object_agg(param_key,
    CASE
      WHEN value_boolean IS NOT NULL THEN to_jsonb(value_boolean=1)
      WHEN value_cents IS NOT NULL THEN to_jsonb(value_cents)
      WHEN value_number IS NOT NULL THEN to_jsonb(value_number)
      ELSE to_jsonb(value_text)
    END ORDER BY param_key) snapshot
  FROM plan_strategy_param
  GROUP BY run_id
) p
WHERE p.run_id=r.id;
ALTER TABLE plan_strategy_run
  ADD CONSTRAINT plan_strategy_run_request_snapshot_object CHECK (jsonb_typeof(request_snapshot)='object');
DROP TABLE plan_strategy_param;

-- Typed evaluation columns remain queryable. Rich producer detail is one immutable receipt rather
-- than ten parallel JSON columns that could drift independently.
ALTER TABLE strategy_evaluation ADD COLUMN receipt JSONB;
UPDATE strategy_evaluation SET receipt=jsonb_strip_nulls(jsonb_build_object(
  'spec',spec_json,
  'candidate',candidate_json,
  'capital',capital_json,
  'volatility',volatility_json,
  'risk',risk_json,
  'management',management_json,
  'score',score_json,
  'evidence',evidence_json,
  'economics',economics_json,
  'explanation',explanation_json
));
ALTER TABLE strategy_evaluation ALTER COLUMN receipt SET NOT NULL;
ALTER TABLE strategy_evaluation
  ADD CONSTRAINT strategy_evaluation_receipt_object CHECK (jsonb_typeof(receipt)='object'),
  DROP COLUMN spec_json,
  DROP COLUMN candidate_json,
  DROP COLUMN capital_json,
  DROP COLUMN volatility_json,
  DROP COLUMN risk_json,
  DROP COLUMN management_json,
  DROP COLUMN score_json,
  DROP COLUMN evidence_json,
  DROP COLUMN economics_json,
  DROP COLUMN explanation_json;
