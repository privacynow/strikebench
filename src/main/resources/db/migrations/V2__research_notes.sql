-- Phase 5 research lab: the notebook — saved analyses (per user).
CREATE TABLE research_note (
  id         TEXT PRIMARY KEY,
  user_id    TEXT REFERENCES users(id),
  title      TEXT NOT NULL,
  body       TEXT NOT NULL,           -- freeform / markdown analysis
  tags       TEXT,
  created_at TEXT NOT NULL,           -- ISO-8601 (opaque string, like the paper-domain tables)
  updated_at TEXT NOT NULL
);
CREATE INDEX idx_research_note_user ON research_note(user_id, updated_at DESC);
