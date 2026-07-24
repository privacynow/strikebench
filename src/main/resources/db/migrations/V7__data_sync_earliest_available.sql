-- M2-(a) range-aware absence: the earliest date a source can serve a symbol/interval, learned from
-- a provider "data doesn't exist for this range" (pre-history) response. Durable so the missing-range
-- planner clamps future requests to >= this boundary and never re-spends the allowance on an
-- impossible interval. NULL means "no pre-history boundary known".
ALTER TABLE public.data_sync_cursor
    ADD COLUMN earliest_available date;
