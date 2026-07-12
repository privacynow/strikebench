-- Adversarial-review hardening of the simulated market:
--   * replayable sessions: checkpoint (state), immutable event log (events), model version
--   * referential integrity: a simulation account always points at a real session
ALTER TABLE sim_session ADD COLUMN state JSONB;
ALTER TABLE sim_session ADD COLUMN events JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE sim_session ADD COLUMN model_version TEXT NOT NULL DEFAULT 'sim-1';
CREATE INDEX idx_accounts_world ON accounts(world_id) WHERE world_id IS NOT NULL;
ALTER TABLE accounts ADD CONSTRAINT fk_accounts_world
    FOREIGN KEY (world_id) REFERENCES sim_session(id);
