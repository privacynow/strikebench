-- V50 canonicalizes legacy owner spellings. A grown database can contain both the
-- canonical owner and a legacy alias, so resolve identity-key collisions before
-- V50 updates the owner columns in place.

-- Workspace is one coherent client snapshot. Keep the most recently written
-- snapshot; rev breaks timestamp ties, then the canonical spelling wins.
WITH ranked AS (
  SELECT ctid AS row_id,
         row_number() OVER (
           PARTITION BY CASE
             WHEN user_id IS NULL OR btrim(user_id)='' OR user_id='__local__' THEN 'local'
             WHEN user_id LIKE 'user:%' THEN substring(user_id FROM 6)
             ELSE user_id END
           ORDER BY updated_at DESC, rev DESC, (user_id='local') DESC, user_id
         ) AS owner_rank
  FROM workspace
)
DELETE FROM workspace w
USING ranked r
WHERE w.ctid=r.row_id AND r.owner_rank > 1;

-- A schedule is also a coherent snapshot. Mixing fields from two versions can
-- pair stale symbols with a newer provider, so retain the latest whole row.
WITH ranked AS (
  SELECT ctid AS row_id,
         row_number() OVER (
           PARTITION BY CASE
             WHEN owner_key IS NULL OR btrim(owner_key)='' OR owner_key='__local__' THEN 'local'
             WHEN owner_key LIKE 'user:%' THEN substring(owner_key FROM 6)
             ELSE owner_key END
           ORDER BY updated_at DESC, (owner_key='local') DESC, owner_key
         ) AS owner_rank
  FROM data_sync_schedule
)
DELETE FROM data_sync_schedule s
USING ranked r
WHERE s.ctid=r.row_id AND r.owner_rank > 1;

-- Cursor aliases can each hold useful coverage. Keep the latest attempt's
-- status/note, but merge the durable coverage envelope before dropping aliases.
WITH ranked AS (
  SELECT ctid AS row_id,
         row_number() OVER (
           PARTITION BY
             CASE
               WHEN owner_key IS NULL OR btrim(owner_key)='' OR owner_key='__local__' THEN 'local'
               WHEN owner_key LIKE 'user:%' THEN substring(owner_key FROM 6)
               ELSE owner_key END,
             source_key, symbol, domain, interval_key
           ORDER BY updated_at DESC, last_attempt_at DESC NULLS LAST,
                    (owner_key='local') DESC, owner_key
         ) AS owner_rank,
         min(requested_from) OVER owner_rows AS merged_requested_from,
         max(requested_to) OVER owner_rows AS merged_requested_to,
         max(last_success_date) OVER owner_rows AS merged_last_success_date,
         max(next_allowed_at) OVER owner_rows AS merged_next_allowed_at,
         max(rows_written) OVER owner_rows AS merged_rows_written
  FROM data_sync_cursor
  WINDOW owner_rows AS (
    PARTITION BY
      CASE
        WHEN owner_key IS NULL OR btrim(owner_key)='' OR owner_key='__local__' THEN 'local'
        WHEN owner_key LIKE 'user:%' THEN substring(owner_key FROM 6)
        ELSE owner_key END,
      source_key, symbol, domain, interval_key
  )
)
UPDATE data_sync_cursor c
SET requested_from=r.merged_requested_from,
    requested_to=r.merged_requested_to,
    last_success_date=r.merged_last_success_date,
    next_allowed_at=r.merged_next_allowed_at,
    rows_written=r.merged_rows_written
FROM ranked r
WHERE c.ctid=r.row_id AND r.owner_rank=1;

WITH ranked AS (
  SELECT ctid AS row_id,
         row_number() OVER (
           PARTITION BY
             CASE
               WHEN owner_key IS NULL OR btrim(owner_key)='' OR owner_key='__local__' THEN 'local'
               WHEN owner_key LIKE 'user:%' THEN substring(owner_key FROM 6)
               ELSE owner_key END,
             source_key, symbol, domain, interval_key
           ORDER BY updated_at DESC, last_attempt_at DESC NULLS LAST,
                    (owner_key='local') DESC, owner_key
         ) AS owner_rank
  FROM data_sync_cursor
)
DELETE FROM data_sync_cursor c
USING ranked r
WHERE c.ctid=r.row_id AND r.owner_rank > 1;

-- A client request id is immutable. Preserve the first accepted request so an
-- alias collision cannot retarget an idempotency key to a later Plan.
WITH ranked AS (
  SELECT ctid AS row_id,
         row_number() OVER (
           PARTITION BY
             CASE
               WHEN owner_key IS NULL OR btrim(owner_key)='' OR owner_key='__local__' THEN 'local'
               WHEN owner_key LIKE 'user:%' THEN substring(owner_key FROM 6)
               ELSE owner_key END,
             client_request_id
           ORDER BY created_at ASC, (owner_key='local') DESC, plan_id
         ) AS owner_rank
  FROM plan_create_request
)
DELETE FROM plan_create_request p
USING ranked r
WHERE p.ctid=r.row_id AND r.owner_rank > 1;
