-- Leg.stock was a derived Java helper that Jackson accidentally persisted as a JSON field.
-- The strict reader correctly refused that non-canonical shape. Remove it once from existing
-- practice trades; the canonical writer now emits only the seven Leg record components.
UPDATE trades t
SET legs_json = cleaned.legs
FROM (
    SELECT t2.id,
           jsonb_agg(element.value - 'stock' ORDER BY element.ordinality) AS legs
    FROM trades t2
    CROSS JOIN LATERAL jsonb_array_elements(t2.legs_json)
        WITH ORDINALITY AS element(value, ordinality)
    WHERE EXISTS (
        SELECT 1
        FROM jsonb_array_elements(t2.legs_json) AS candidate(value)
        WHERE candidate.value ? 'stock'
    )
    GROUP BY t2.id
) cleaned
WHERE t.id = cleaned.id;

ALTER TABLE trades
    ADD CONSTRAINT ck_trades_legs_no_derived_stock
    CHECK (NOT jsonb_path_exists(legs_json, '$[*].stock'));
