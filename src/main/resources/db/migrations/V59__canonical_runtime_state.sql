-- Normalize retained development data into the one current runtime contract. Runtime code reads
-- only owner-scoped dataset state and accepts only the current daily-history job kind.
INSERT INTO settings(k, v, updated_at)
SELECT 'active_dataset:local', v, updated_at
FROM settings
WHERE k='active_dataset'
ON CONFLICT (k) DO NOTHING;

DELETE FROM settings WHERE k='active_dataset';

UPDATE data_job
SET kind='sync_underlying',
    params=CASE
        WHEN params IS NULL THEN jsonb_build_object('source', 'auto')
        WHEN params ? 'source' THEN params
        ELSE params || jsonb_build_object('source', 'auto')
    END
WHERE kind='backfill_underlying';
