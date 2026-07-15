-- V49.1 was added after some installations had already applied V50, which renamed
-- owner_key to user_id. Supply a generated compatibility alias only for that
-- already-canonical schema so the immutable collision guard can run out of order.
DO $$
DECLARE
  target_table text;
BEGIN
  FOREACH target_table IN ARRAY ARRAY[
    'data_sync_schedule', 'data_sync_cursor', 'plan_create_request'
  ] LOOP
    IF EXISTS (
      SELECT 1 FROM information_schema.columns c
      WHERE c.table_schema='public' AND c.table_name=target_table
        AND c.column_name='user_id'
    ) AND NOT EXISTS (
      SELECT 1 FROM information_schema.columns c
      WHERE c.table_schema='public' AND c.table_name=target_table
        AND c.column_name='owner_key'
    ) THEN
      EXECUTE format(
        'ALTER TABLE %I ADD COLUMN owner_key text GENERATED ALWAYS AS (user_id) STORED',
        target_table
      );
    END IF;
  END LOOP;
END $$;
