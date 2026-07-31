-- The intent a candidate was actually SCREENED under. A scout run rewrites HEDGE/EXIT to
-- DIRECTIONAL before generation, so the run row's intent can differ from the candidate's;
-- restoring the run-level value substituted the wrong screening intent on every reload.
ALTER TABLE plan_candidate ADD COLUMN screening_intent TEXT;
