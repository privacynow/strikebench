-- Block S: the simulated market. A session is a reproducible world (seed + model + config);
-- a SIMULATION ACCOUNT is bound to exactly one world and is the ONLY lane that may fill, mark
-- and settle against it. Observed tables remain immutable from simulation.
CREATE TABLE sim_session (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    user_id     TEXT,
    config      JSONB NOT NULL,           -- symbols/betas/scenario/model/vol/seed/startSimTime
    status      TEXT NOT NULL DEFAULT 'CREATED', -- CREATED|RUNNING|PAUSED|FINISHED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
ALTER TABLE accounts ADD COLUMN world_id TEXT; -- NULL = observed lanes; set = simulation account
-- The account-type lane: SIMULATION accounts trade only their world. (Postgres check constraints
-- can't be altered in place — drop and recreate with the new lane.)
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_type_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_type_check CHECK (type IN ('PAPER','LIVE','SIMULATION'));
