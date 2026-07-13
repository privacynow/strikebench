ALTER TABLE plan_management_action DROP CONSTRAINT IF EXISTS plan_management_action_kind_check;
ALTER TABLE plan_management_action ADD CONSTRAINT plan_management_action_kind_check
    CHECK (kind IN ('MARK','ROLL','ADJUST','PARTIAL_CLOSE','CLOSE','SETTLE','VOID','REHEARSAL_RESULT'));
