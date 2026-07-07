-- V3: app-level settings (key/value). First user: the globally selected trading universe.

CREATE TABLE settings (
  k          TEXT PRIMARY KEY,
  v          TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
