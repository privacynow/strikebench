-- Every persisted owner is one real users.id. Earlier generations used NULL, __local__, local,
-- user:<id>, owner_id, and owner_key for the same concept.
INSERT INTO users(id,name,created_at,updated_at)
VALUES ('local','Local user',now()::text,now()::text),
       ('system','System data',now()::text,now()::text)
ON CONFLICT(id) DO NOTHING;

WITH raw_owner(id) AS (
  SELECT user_id FROM accounts
  UNION ALL SELECT user_id FROM strategy_evaluation
  UNION ALL SELECT user_id FROM recommendation
  UNION ALL SELECT user_id FROM research_note
  UNION ALL SELECT user_id FROM data_job
  UNION ALL SELECT user_id FROM dataset
  UNION ALL SELECT user_id FROM sim_session
  UNION ALL SELECT user_id FROM workspace
  UNION ALL SELECT user_id FROM plans
  UNION ALL SELECT owner_id FROM portfolio_account
  UNION ALL SELECT owner_key FROM data_sync_cursor
  UNION ALL SELECT owner_key FROM data_quarantine
  UNION ALL SELECT owner_key FROM data_sync_schedule
  UNION ALL SELECT owner_key FROM plan_create_request
), canonical_owner(id) AS (
  SELECT DISTINCT CASE
    WHEN id IS NULL OR btrim(id)='' OR id='__local__' THEN 'local'
    WHEN id LIKE 'user:%' THEN substring(id FROM 6)
    ELSE id END
  FROM raw_owner
)
INSERT INTO users(id,name,created_at,updated_at)
SELECT id,id,now()::text,now()::text FROM canonical_owner WHERE id IS NOT NULL
ON CONFLICT(id) DO NOTHING;

UPDATE accounts SET user_id='local' WHERE user_id IS NULL OR btrim(user_id)='' OR user_id='__local__';
UPDATE strategy_evaluation SET user_id='local' WHERE user_id IS NULL OR btrim(user_id)='' OR user_id='__local__';
UPDATE recommendation SET user_id='local' WHERE user_id IS NULL OR btrim(user_id)='' OR user_id='__local__';
UPDATE research_note SET user_id='local' WHERE user_id IS NULL OR btrim(user_id)='' OR user_id='__local__';
UPDATE data_job SET user_id='local' WHERE user_id IS NULL OR btrim(user_id)='' OR user_id='__local__';
UPDATE dataset SET user_id=CASE WHEN id='observed' THEN 'system' ELSE 'local' END
  WHERE user_id IS NULL OR btrim(user_id)='' OR user_id='__local__';
UPDATE sim_session SET user_id='local' WHERE user_id IS NULL OR btrim(user_id)='' OR user_id='__local__';
UPDATE workspace SET user_id='local' WHERE btrim(user_id)='' OR user_id='__local__';
UPDATE plans SET user_id='local' WHERE user_id IS NULL OR btrim(user_id)='' OR user_id='__local__';

UPDATE accounts SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';
UPDATE strategy_evaluation SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';
UPDATE recommendation SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';
UPDATE research_note SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';
UPDATE data_job SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';
UPDATE dataset SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';
UPDATE sim_session SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';
UPDATE workspace SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';
UPDATE plans SET user_id=substring(user_id FROM 6) WHERE user_id LIKE 'user:%';

INSERT INTO settings(k,v,updated_at)
SELECT 'active_world:local',v,updated_at FROM settings
WHERE k IN ('active_world:null','active_world:__local__')
ORDER BY CASE WHEN k='active_world:__local__' THEN 0 ELSE 1 END
LIMIT 1
ON CONFLICT(k) DO NOTHING;
DELETE FROM settings WHERE k IN ('active_world:null','active_world:__local__');

ALTER TABLE portfolio_account RENAME COLUMN owner_id TO user_id;
UPDATE portfolio_account SET user_id=CASE
  WHEN user_id IS NULL OR btrim(user_id)='' OR user_id='__local__' THEN 'local'
  WHEN user_id LIKE 'user:%' THEN substring(user_id FROM 6) ELSE user_id END;

ALTER TABLE data_sync_cursor RENAME COLUMN owner_key TO user_id;
ALTER TABLE data_quarantine RENAME COLUMN owner_key TO user_id;
ALTER TABLE data_sync_schedule RENAME COLUMN owner_key TO user_id;
ALTER TABLE plan_create_request RENAME COLUMN owner_key TO user_id;

UPDATE data_sync_cursor SET user_id=CASE
  WHEN user_id IS NULL OR btrim(user_id)='' OR user_id='__local__' THEN 'local'
  WHEN user_id LIKE 'user:%' THEN substring(user_id FROM 6) ELSE user_id END;
UPDATE data_quarantine SET user_id=CASE
  WHEN user_id IS NULL OR btrim(user_id)='' OR user_id='__local__' THEN 'local'
  WHEN user_id LIKE 'user:%' THEN substring(user_id FROM 6) ELSE user_id END;
UPDATE data_sync_schedule SET user_id=CASE
  WHEN user_id IS NULL OR btrim(user_id)='' OR user_id='__local__' THEN 'local'
  WHEN user_id LIKE 'user:%' THEN substring(user_id FROM 6) ELSE user_id END;
UPDATE plan_create_request SET user_id=CASE
  WHEN user_id IS NULL OR btrim(user_id)='' OR user_id='__local__' THEN 'local'
  WHEN user_id LIKE 'user:%' THEN substring(user_id FROM 6) ELSE user_id END;

ALTER TABLE accounts ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE strategy_evaluation ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE recommendation ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE research_note ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE data_job ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE dataset ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE sim_session ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE plans ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE accounts ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE strategy_evaluation ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE recommendation ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE research_note ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE data_job ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE dataset ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE sim_session ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE workspace ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE plans ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE portfolio_account ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE data_sync_cursor ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE data_quarantine ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE data_sync_schedule ALTER COLUMN user_id SET DEFAULT 'local';
ALTER TABLE plan_create_request ALTER COLUMN user_id SET DEFAULT 'local';

ALTER TABLE data_job ADD CONSTRAINT fk_data_job_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE dataset ADD CONSTRAINT fk_dataset_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE sim_session ADD CONSTRAINT fk_sim_session_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE workspace ADD CONSTRAINT fk_workspace_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE portfolio_account ADD CONSTRAINT fk_portfolio_account_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE data_sync_cursor ADD CONSTRAINT fk_data_sync_cursor_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE data_quarantine ADD CONSTRAINT fk_data_quarantine_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE data_sync_schedule ADD CONSTRAINT fk_data_sync_schedule_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE plan_create_request ADD CONSTRAINT fk_plan_create_request_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE plans ADD CONSTRAINT fk_plans_world FOREIGN KEY(world_id) REFERENCES sim_session(id);

DROP INDEX uq_plans_owner_request;
ALTER TABLE plans DROP COLUMN client_request_id;
ALTER TABLE plans DROP COLUMN create_input_hash;
