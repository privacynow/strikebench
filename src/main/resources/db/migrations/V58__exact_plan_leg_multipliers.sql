-- An adjusted option contract (or exact-share stock package) must retain its deliverable
-- through Strategy reload and the frozen decision receipt. Normalize current development rows
-- once, then leave the current schema with no compatibility default.
ALTER TABLE plan_candidate_leg
    ADD COLUMN multiplier INTEGER;

UPDATE plan_candidate_leg SET multiplier=100;

ALTER TABLE plan_candidate_leg
    ALTER COLUMN multiplier SET NOT NULL,
    ADD CHECK (multiplier BETWEEN 1 AND 10000);

ALTER TABLE plan_decision_leg
    ADD COLUMN multiplier INTEGER;

UPDATE plan_decision_leg SET multiplier=100;

ALTER TABLE plan_decision_leg
    ALTER COLUMN multiplier SET NOT NULL,
    ADD CHECK (multiplier BETWEEN 1 AND 10000);

-- Every retained trade becomes a current exact-deliverable record. Runtime readers do not
-- accept the previous implicit convention.
UPDATE trades t
SET legs_json = (
    SELECT COALESCE(jsonb_agg(
        CASE WHEN leg.value ? 'multiplier' THEN leg.value
             ELSE leg.value || jsonb_build_object('multiplier', 100) END
        ORDER BY leg.ordinality), '[]'::jsonb) AS legs
    FROM jsonb_array_elements(t.legs_json) WITH ORDINALITY AS leg(value, ordinality)
)
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements(t.legs_json) value
    WHERE NOT value ? 'multiplier'
);

UPDATE trades
SET entry_snapshot_json = (entry_snapshot_json - 'heldShareContextLots')
        || jsonb_build_object('heldShareContextShares',
            ((entry_snapshot_json->>'heldShareContextLots')::bigint * 100))
WHERE entry_snapshot_json ? 'heldShareContextLots'
  AND NOT entry_snapshot_json ? 'heldShareContextShares';

UPDATE trades t
SET entry_snapshot_json = jsonb_set(t.entry_snapshot_json, '{legs}', (
        SELECT COALESCE(jsonb_agg(
            CASE WHEN leg.value ? 'multiplier' THEN leg.value
                 ELSE leg.value || jsonb_build_object('multiplier', 100) END
            ORDER BY leg.ordinality), '[]'::jsonb) AS legs
        FROM jsonb_array_elements(t.entry_snapshot_json->'legs') WITH ORDINALITY AS leg(value, ordinality)
    ), false)
WHERE jsonb_typeof(t.entry_snapshot_json->'legs')='array'
  AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(t.entry_snapshot_json->'legs') value
      WHERE NOT value ? 'multiplier'
  );
