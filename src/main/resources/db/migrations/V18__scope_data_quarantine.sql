-- Quarantine diagnostics are owner-visible even though accepted observed bars are app-wide.
-- Existing local rows belong to the local owner; future writes always set owner_key explicitly.
ALTER TABLE data_quarantine ADD COLUMN owner_key TEXT NOT NULL DEFAULT 'local';
CREATE INDEX idx_data_quarantine_owner_created ON data_quarantine (owner_key, created_at DESC);
