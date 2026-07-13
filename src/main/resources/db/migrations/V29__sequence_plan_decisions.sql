-- A timestamp is not an identity or an ordering primitive: multiple decisions can share one clock
-- tick during a roll. Give every Plan a strict decision sequence and attach execution links to the
-- exact immutable decision they implement.
ALTER TABLE plan_decision ADD COLUMN decision_seq BIGINT;
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY plan_id ORDER BY created_at,id) AS seq
    FROM plan_decision
)
UPDATE plan_decision d SET decision_seq=r.seq FROM ranked r WHERE r.id=d.id;
ALTER TABLE plan_decision ALTER COLUMN decision_seq SET NOT NULL;
CREATE UNIQUE INDEX uq_plan_decision_sequence ON plan_decision(plan_id,decision_seq);

ALTER TABLE plan_link ADD COLUMN decision_id TEXT REFERENCES plan_decision(id) ON DELETE SET NULL;
UPDATE plan_link l SET decision_id=(
    SELECT d.id FROM plan_decision d
    WHERE d.plan_id=l.plan_id AND d.created_at<=l.created_at
    ORDER BY d.decision_seq DESC LIMIT 1
) WHERE l.role IN ('ENTRY','ADJUST','ROLL','PARTIAL_CLOSE','CLOSE','EXTERNAL');
CREATE INDEX idx_plan_link_decision ON plan_link(decision_id);
