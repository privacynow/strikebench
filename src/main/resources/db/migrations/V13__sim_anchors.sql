-- Weekend-handoff holistic review: anchor provenance is a FIRST-CLASS durable record, not prose
-- in a transient create response. One JSON document per session: per-symbol source, source
-- timestamp, freshness, age, bid/ask, previous close, resolved price, tier, exclusions, and the
-- calibration basis — everything needed to audit what this world was anchored to.
ALTER TABLE sim_session ADD COLUMN anchors JSONB;
