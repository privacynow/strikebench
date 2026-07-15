-- A Plan records the furthest meaningful workflow step reached. The page currently being
-- viewed belongs in the URL and must not mutate durable state or bump optimistic versions.
ALTER TABLE plans RENAME COLUMN active_stage TO furthest_stage;
