-- Replace internal metaphors with the domain terms used by the Java model and public API.
-- This is a forward-only rename: no data is discarded and no compatibility schema is added.

ALTER TABLE public.research_ensemble_receipt RENAME TO stored_research_ensemble;
ALTER TABLE public.stored_research_ensemble RENAME COLUMN market_lane TO market_mode;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_pkey TO stored_research_ensemble_pkey;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_market_kind_check TO stored_research_ensemble_market_kind_check;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_market_lane_check TO stored_research_ensemble_market_mode_check;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_state_check TO stored_research_ensemble_state_check;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_spec_object TO stored_research_ensemble_spec_object;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_iv_object TO stored_research_ensemble_iv_object;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_canvas_object TO stored_research_ensemble_canvas_object;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_input_object TO stored_research_ensemble_input_object;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_preview_object TO stored_research_ensemble_preview_object;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_fingerprint_fkey TO stored_research_ensemble_fingerprint_fkey;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_user_id_fkey TO stored_research_ensemble_user_id_fkey;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_adopted_plan_id_fkey TO stored_research_ensemble_adopted_plan_id_fkey;
ALTER TABLE public.stored_research_ensemble
    RENAME CONSTRAINT research_ensemble_receipt_adopted_ensemble_id_fkey TO stored_research_ensemble_adopted_ensemble_id_fkey;
ALTER INDEX public.idx_research_ensemble_receipt_owner RENAME TO idx_stored_research_ensemble_owner;
ALTER INDEX public.idx_research_ensemble_receipt_expiry RENAME TO idx_stored_research_ensemble_expiry;
UPDATE public.stored_research_ensemble
SET preview = (preview - 'receipt') || jsonb_build_object('ensembleMetadata', preview->'receipt')
WHERE preview ? 'receipt' AND NOT preview ? 'ensembleMetadata';

ALTER TABLE public.position_receipt RENAME TO position_artifact;
ALTER TABLE public.position_artifact RENAME COLUMN kind TO artifact_type;
ALTER TABLE public.position_artifact RENAME COLUMN authority TO artifact_source;
ALTER TABLE public.position_artifact RENAME COLUMN execution_lane TO book_type;
ALTER TABLE public.position_artifact DROP CONSTRAINT position_receipt_execution_lane_check;
UPDATE public.position_artifact SET book_type='TRACKED' WHERE book_type='REAL';
ALTER TABLE public.position_artifact ADD CONSTRAINT position_artifact_book_type_check
    CHECK (book_type IN ('NONE','PRACTICE','TRACKED'));
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_pkey TO position_artifact_pkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_analysis_artifact_state_check TO position_artifact_state_check;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_authority_check TO position_artifact_source_check;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_check TO position_artifact_plan_context_check;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_evidence_level_check TO position_artifact_evidence_level_check;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_kind_check TO position_artifact_type_check;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_position_state_check TO position_artifact_position_state_check;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_transformation_action_check TO position_artifact_transformation_action_check;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_transformation_identity_check TO position_artifact_transformation_identity_check;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_account_objective_revision_id_fkey TO position_artifact_account_objective_revision_id_fkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_decision_id_fkey TO position_artifact_decision_id_fkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_plan_id_fkey TO position_artifact_plan_id_fkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_plan_id_plan_context_rev_fkey TO position_artifact_plan_context_fkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_portfolio_account_id_fkey TO position_artifact_portfolio_account_id_fkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_practice_trade_id_fkey TO position_artifact_practice_trade_id_fkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_structure_revision_id_fkey TO position_artifact_structure_revision_id_fkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_transaction_id_fkey TO position_artifact_transaction_id_fkey;
ALTER TABLE public.position_artifact
    RENAME CONSTRAINT position_receipt_user_id_fkey TO position_artifact_user_id_fkey;
ALTER INDEX public.idx_position_receipt_plan RENAME TO idx_position_artifact_plan;
ALTER INDEX public.idx_position_receipt_structure RENAME TO idx_position_artifact_structure;

ALTER TABLE public.position_receipt_leg RENAME TO position_artifact_leg;
ALTER TABLE public.position_artifact_leg RENAME COLUMN receipt_id TO artifact_id;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_pkey TO position_artifact_leg_pkey;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_receipt_id_fkey TO position_artifact_leg_artifact_id_fkey;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_action_check TO position_artifact_leg_action_check;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_check TO position_artifact_leg_shape_check;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_instrument_type_check TO position_artifact_leg_instrument_type_check;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_leg_no_check TO position_artifact_leg_leg_no_check;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_multiplier_check TO position_artifact_leg_multiplier_check;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_option_type_check TO position_artifact_leg_option_type_check;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_position_phase_check TO position_artifact_leg_position_phase_check;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_price_authority_check TO position_artifact_leg_price_authority_check;
ALTER TABLE public.position_artifact_leg
    RENAME CONSTRAINT position_receipt_leg_quantity_check TO position_artifact_leg_quantity_check;

ALTER TABLE public.position_receipt_metric RENAME TO position_artifact_metric;
ALTER TABLE public.position_artifact_metric RENAME COLUMN receipt_id TO artifact_id;
ALTER TABLE public.position_artifact_metric
    RENAME CONSTRAINT position_receipt_metric_pkey TO position_artifact_metric_pkey;
ALTER TABLE public.position_artifact_metric
    RENAME CONSTRAINT position_receipt_metric_receipt_id_fkey TO position_artifact_metric_artifact_id_fkey;
ALTER TABLE public.position_artifact_metric
    RENAME CONSTRAINT position_receipt_metric_check TO position_artifact_metric_value_check;

ALTER TABLE public.plan_management_action RENAME COLUMN receipt_id TO artifact_id;
ALTER TABLE public.plan_management_action
    RENAME CONSTRAINT plan_management_action_receipt_id_fkey TO plan_management_action_artifact_id_fkey;
ALTER TABLE public.plan_portfolio_action RENAME COLUMN receipt_id TO artifact_id;
ALTER TABLE public.plan_portfolio_action
    RENAME CONSTRAINT plan_portfolio_action_receipt_id_fkey TO plan_portfolio_action_artifact_id_fkey;
ALTER TABLE public.plan_portfolio_action
    RENAME CONSTRAINT plan_portfolio_action_plan_id_receipt_id_key TO plan_portfolio_action_plan_id_artifact_id_key;
ALTER TABLE public.plan_portfolio_action
    RENAME CONSTRAINT plan_portfolio_action_receipt_id_key TO plan_portfolio_action_artifact_id_key;
ALTER TABLE public.portfolio_import_resolution RENAME COLUMN receipt_id TO artifact_id;
ALTER TABLE public.portfolio_import_resolution
    RENAME CONSTRAINT portfolio_import_resolution_receipt_id_fkey TO portfolio_import_resolution_artifact_id_fkey;
ALTER TABLE public.portfolio_import_resolution
    RENAME CONSTRAINT portfolio_import_resolution_receipt_id_key TO portfolio_import_resolution_artifact_id_key;
ALTER TABLE public.portfolio_adoption_request RENAME COLUMN receipt_id TO artifact_id;
ALTER TABLE public.portfolio_adoption_request
    RENAME CONSTRAINT portfolio_adoption_request_receipt_id_fkey TO portfolio_adoption_request_artifact_id_fkey;

ALTER TABLE public.position_lifecycle_decision_receipt RENAME TO position_lifecycle_analysis;
ALTER TABLE public.position_lifecycle_analysis RENAME COLUMN receipt_fingerprint TO analysis_fingerprint;
ALTER TABLE public.position_lifecycle_analysis
    RENAME CONSTRAINT position_lifecycle_decision_receipt_pkey TO position_lifecycle_analysis_pkey;
ALTER TABLE public.position_lifecycle_analysis
    RENAME CONSTRAINT position_lifecycle_receipt_verdict_check TO position_lifecycle_analysis_verdict_check;
ALTER TABLE public.position_lifecycle_analysis
    RENAME CONSTRAINT position_lifecycle_receipt_fingerprints_check TO position_lifecycle_analysis_fingerprints_check;
ALTER INDEX public.idx_position_lifecycle_receipt_position RENAME TO idx_position_lifecycle_analysis_position;
ALTER TABLE public.position_lifecycle_user_decision RENAME COLUMN receipt_id TO analysis_id;
ALTER TABLE public.position_lifecycle_user_decision
    RENAME CONSTRAINT position_lifecycle_user_decision_receipt_id_fkey TO position_lifecycle_user_decision_analysis_id_fkey;
ALTER INDEX public.idx_position_lifecycle_user_decision_receipt
    RENAME TO idx_position_lifecycle_user_decision_analysis;

ALTER TABLE public.strategy_evaluation RENAME COLUMN receipt TO result_json;
ALTER TABLE public.strategy_evaluation
    RENAME CONSTRAINT strategy_evaluation_receipt_object TO strategy_evaluation_result_object;

-- Saved evaluations used the old book terminology inside their JSON. Rewrite it in place so
-- opening an existing Scout result reconstructs the same typed assessment after this rename.
UPDATE public.strategy_evaluation
SET result_json = jsonb_set(
        jsonb_set(
            result_json #- '{assessment,portfolioImpacts,real}',
            '{assessment,portfolioImpacts,tracked}',
            COALESCE(result_json #> '{assessment,portfolioImpacts,real}', 'null'::jsonb),
            true),
        '{assessment,portfolioImpacts,practice,bookType}',
        COALESCE(result_json #> '{assessment,portfolioImpacts,practice,lane}', '"PRACTICE"'::jsonb),
        true)
WHERE result_json #> '{assessment,portfolioImpacts}' IS NOT NULL;
UPDATE public.strategy_evaluation
SET result_json = jsonb_set(
        result_json #- '{assessment,portfolioImpacts,tracked,lane}',
        '{assessment,portfolioImpacts,tracked,bookType}',
        CASE result_json #>> '{assessment,portfolioImpacts,tracked,lane}'
            WHEN 'REAL' THEN '"TRACKED"'::jsonb
            WHEN 'TRACKED' THEN '"TRACKED"'::jsonb
            ELSE COALESCE(result_json #> '{assessment,portfolioImpacts,tracked,bookType}', '"TRACKED"'::jsonb)
        END,
        true)
WHERE result_json #> '{assessment,portfolioImpacts,tracked}' IS NOT NULL;
UPDATE public.strategy_evaluation
SET result_json = result_json #- '{assessment,portfolioImpacts,practice,lane}'
WHERE result_json #> '{assessment,portfolioImpacts,practice,lane}' IS NOT NULL;
UPDATE public.strategy_evaluation
SET result_json = regexp_replace(
        regexp_replace(
            replace(result_json::text, 'Practice and Real impacts', 'Practice and Tracked impacts'),
            '\mreceipts?\M', 'result', 'gi'),
        '\mlanes?\M', 'view', 'gi')::jsonb
WHERE result_json::text ~* '\m(receipts?|lanes?)\M'
   OR result_json::text LIKE '%Practice and Real impacts%';

-- Candidate snapshots are the same serialized evaluation shape and need the same transformation.
UPDATE public.plan_candidate
SET evaluation_snapshot = jsonb_set(
        jsonb_set(
            evaluation_snapshot #- '{assessment,portfolioImpacts,real}',
            '{assessment,portfolioImpacts,tracked}',
            COALESCE(evaluation_snapshot #> '{assessment,portfolioImpacts,real}', 'null'::jsonb),
            true),
        '{assessment,portfolioImpacts,practice,bookType}',
        COALESCE(evaluation_snapshot #> '{assessment,portfolioImpacts,practice,lane}', '"PRACTICE"'::jsonb),
        true)
WHERE evaluation_snapshot #> '{assessment,portfolioImpacts}' IS NOT NULL;
UPDATE public.plan_candidate
SET evaluation_snapshot = jsonb_set(
        evaluation_snapshot #- '{assessment,portfolioImpacts,tracked,lane}',
        '{assessment,portfolioImpacts,tracked,bookType}',
        CASE evaluation_snapshot #>> '{assessment,portfolioImpacts,tracked,lane}'
            WHEN 'REAL' THEN '"TRACKED"'::jsonb
            WHEN 'TRACKED' THEN '"TRACKED"'::jsonb
            ELSE COALESCE(evaluation_snapshot #> '{assessment,portfolioImpacts,tracked,bookType}', '"TRACKED"'::jsonb)
        END,
        true)
WHERE evaluation_snapshot #> '{assessment,portfolioImpacts,tracked}' IS NOT NULL;
UPDATE public.plan_candidate
SET evaluation_snapshot = evaluation_snapshot #- '{assessment,portfolioImpacts,practice,lane}'
WHERE evaluation_snapshot #> '{assessment,portfolioImpacts,practice,lane}' IS NOT NULL;
UPDATE public.plan_candidate
SET evaluation_snapshot = regexp_replace(
        regexp_replace(
            replace(evaluation_snapshot::text, 'Practice and Real impacts', 'Practice and Tracked impacts'),
            '\mreceipts?\M', 'result', 'gi'),
        '\mlanes?\M', 'view', 'gi')::jsonb
WHERE evaluation_snapshot::text ~* '\m(receipts?|lanes?)\M'
   OR evaluation_snapshot::text LIKE '%Practice and Real impacts%';
ALTER TABLE public.plan_decision RENAME COLUMN price_receipt TO package_price;
ALTER TABLE public.plan_decision
    RENAME CONSTRAINT plan_decision_price_receipt_object_check TO plan_decision_package_price_object_check;
ALTER TABLE public.plan_decision
    RENAME CONSTRAINT plan_decision_price_receipt_shape_check TO plan_decision_package_price_shape_check;
ALTER TABLE public.plan_decision
    RENAME CONSTRAINT plan_decision_price_receipt_amounts_check TO plan_decision_package_price_amounts_check;
ALTER TABLE public.trade_marks RENAME COLUMN current_receipt_json TO current_mark_json;
ALTER TABLE public.trade_marks
    RENAME CONSTRAINT trade_marks_current_receipt_json_object TO trade_marks_current_mark_json_object;
ALTER TABLE public.live_orders RENAME COLUMN canonical_receipt_json TO approved_preview_json;
ALTER TABLE public.live_orders
    RENAME CONSTRAINT live_orders_canonical_receipt_object_check TO live_orders_approved_preview_object_check;

-- Workspace state is a serialized WorkspaceContext. Rename the key in place so a saved browser
-- context remains readable after the Java/API component rename.
UPDATE public.workspace
SET state = (state - 'marketLane') || jsonb_build_object('marketMode', state->'marketLane')
WHERE state ? 'marketLane' AND NOT state ? 'marketMode';

-- PL/pgSQL stores these query bodies as source text. Table and column renames do not rewrite that
-- text, so replace the two validation functions against the renamed schema.
CREATE OR REPLACE FUNCTION public.enforce_import_resolution_agreement() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM portfolio_import_resolution x
    JOIN portfolio_import_pending p ON p.id=x.pending_id
    JOIN position_artifact a ON a.id=x.artifact_id
    LEFT JOIN portfolio_transaction t ON t.id=x.transaction_id
    WHERE x.package_total_cents IS DISTINCT FROM p.package_net_cents
       OR x.allocated_total_cents IS DISTINCT FROM p.package_net_cents
       OR x.portfolio_account_id IS DISTINCT FROM p.destination_portfolio_account_id
       OR a.artifact_type<>'RESOLUTION'
       OR a.artifact_source IS DISTINCT FROM x.authority
       OR a.book_type<>'TRACKED'
       OR a.portfolio_account_id IS DISTINCT FROM x.portfolio_account_id
       OR a.transaction_id IS DISTINCT FROM x.transaction_id
       OR (x.authority='USER_ALLOCATED' AND
           (x.tax_basis_status<>'PROVISIONAL' OR x.transaction_id IS NOT NULL
            OR a.position_state<>'PENDING' OR p.status NOT IN ('PROVISIONAL','RESOLVED','REJECTED')))
       OR (x.authority='BROKER_REPORTED' AND
           (x.tax_basis_status<>'AUTHORITATIVE' OR x.transaction_id IS NULL
            OR t.cash_effect_cents IS DISTINCT FROM x.allocated_total_cents
            OR t.portfolio_account_id IS DISTINCT FROM x.portfolio_account_id
            OR p.status<>'RESOLVED'))
  ) THEN
    RAISE EXCEPTION 'pending import resolution does not reconcile to its package, transaction, and analysis';
  END IF;
  IF EXISTS (
    SELECT 1 FROM portfolio_import_pending p
    WHERE (p.status='PROVISIONAL' AND NOT EXISTS (
             SELECT 1 FROM portfolio_import_resolution x
             WHERE x.pending_id=p.id AND x.authority='USER_ALLOCATED'))
       OR (p.status='RESOLVED' AND NOT EXISTS (
             SELECT 1 FROM portfolio_import_resolution x
             WHERE x.pending_id=p.id AND x.authority='BROKER_REPORTED'))
  ) THEN
    RAISE EXCEPTION 'pending import status lacks its matching immutable resolution event';
  END IF;
  RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.enforce_plan_action_artifact_agreement() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM plan_portfolio_action ppa
    JOIN position_artifact a ON a.id=ppa.artifact_id
    JOIN portfolio_structure_revision sr ON sr.id=ppa.structure_revision_id
    WHERE a.plan_id IS DISTINCT FROM ppa.plan_id
       OR a.structure_revision_id IS DISTINCT FROM ppa.structure_revision_id
       OR a.transaction_id IS DISTINCT FROM ppa.transaction_id
       OR a.book_type<>'TRACKED'
       OR sr.transaction_id IS DISTINCT FROM ppa.transaction_id
       OR sr.action_role IS DISTINCT FROM ppa.role
  ) THEN
    RAISE EXCEPTION 'Plan action data does not describe the same tracked action';
  END IF;
  RETURN NULL;
END;
$$;
