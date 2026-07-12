-- Persistent per-user workspace: the client-owned UX state blob (route, working symbol,
-- draft forms, working idea). Opaque to the server on purpose — the UI schema moves fast;
-- the server just versions and returns it. 'local' is the anonymous single-user key.
CREATE TABLE workspace (
  user_id    TEXT PRIMARY KEY,
  state      JSONB NOT NULL,
  rev        BIGINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
