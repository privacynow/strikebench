-- Scouted candidates belong to the originating Plan but may describe another underlying.
-- Preserve that identity and the signal thesis explicitly so a linked sibling Plan can be
-- recreated without consulting mutable browser state.
ALTER TABLE plan_candidate ADD COLUMN underlying_symbol TEXT;
ALTER TABLE plan_candidate ADD COLUMN scout_thesis TEXT;
CREATE INDEX idx_plan_candidate_symbol ON plan_candidate(plan_id, underlying_symbol, source_kind);
CREATE UNIQUE INDEX idx_plan_link_related_unique
    ON plan_link(plan_id, role, related_plan_id) WHERE related_plan_id IS NOT NULL;
