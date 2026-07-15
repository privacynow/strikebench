-- Remove aliases introduced by V49.0.1 after the immutable V49.1 guard has run.
-- On an in-order migration the owner_key columns are still the real pre-V50
-- columns, so user_id is absent and this cleanup intentionally does nothing.
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
    ) AND EXISTS (
      SELECT 1 FROM information_schema.columns c
      WHERE c.table_schema='public' AND c.table_name=target_table
        AND c.column_name='owner_key'
    ) THEN
      EXECUTE format('ALTER TABLE %I DROP COLUMN owner_key', target_table);
    END IF;
  END LOOP;
END $$;
