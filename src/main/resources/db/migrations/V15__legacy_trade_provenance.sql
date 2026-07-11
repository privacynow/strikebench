-- V14 separated market-data lanes but its final compatibility update promoted every
-- unclassified legacy trade to OBSERVED. Absence of provenance is not observed evidence.
-- Keep OBSERVED only when the immutable entry snapshot itself carries an observed marker;
-- ambiguous legacy records stay visible but cannot be represented as observed examples.
UPDATE trades
SET data_provenance='UNKNOWN', data_age=NULL, data_source=NULL
WHERE data_provenance='OBSERVED'
  AND coalesce(entry_snapshot_json, '') NOT LIKE '%"provenance":"OBSERVED"%'
  AND coalesce(entry_snapshot_json, '') NOT LIKE '%"provenance":"BROKER"%'
  AND coalesce(entry_snapshot_json, '') NOT LIKE '%"freshness":"REALTIME"%'
  AND coalesce(entry_snapshot_json, '') NOT LIKE '%"freshness":"DELAYED"%'
  AND coalesce(entry_snapshot_json, '') NOT LIKE '%"freshness":"EOD"%'
  AND coalesce(entry_snapshot_json, '') NOT LIKE '%"freshness":"STALE"%';
