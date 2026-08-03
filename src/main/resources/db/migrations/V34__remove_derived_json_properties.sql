-- Strict persisted JSON must contain only constructor-owned fields. Earlier writes included
-- Java convenience-method projections that the same current records do not accept on read.

UPDATE settings
SET v = (v::jsonb - 'empty')::text
WHERE k LIKE 'risk_context:%'
  AND jsonb_typeof(v::jsonb) = 'object'
  AND v::jsonb ? 'empty';

UPDATE plan_candidate
SET holdings_evidence = holdings_evidence - 'accountBacked'
WHERE holdings_evidence ? 'accountBacked';

UPDATE strategy_evaluation
SET result_json = result_json #- '{candidate,holdingsEvidence,accountBacked}'
WHERE (result_json #> '{candidate,holdingsEvidence}') ? 'accountBacked';

UPDATE trade_marks
SET current_mark_json = current_mark_json
        #- '{underlyingQuote,rawEvidence,observedLive}'
        #- '{underlyingQuote,rawEvidence,staleOrMissing}'
WHERE (current_mark_json #> '{underlyingQuote,rawEvidence}')
        ?| ARRAY['observedLive', 'staleOrMissing'];
