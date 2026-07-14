-- Collections that are queried, ordered, and integrity-checked are rows, not JSON-in-TEXT blobs.

CREATE TABLE portfolio_valuation_missing_mark (
  valuation_id TEXT NOT NULL REFERENCES portfolio_valuation(id) ON DELETE CASCADE,
  ordinal      INTEGER NOT NULL CHECK (ordinal >= 0),
  mark_label   TEXT NOT NULL CHECK (btrim(mark_label) <> ''),
  PRIMARY KEY (valuation_id, ordinal)
);

INSERT INTO portfolio_valuation_missing_mark(valuation_id,ordinal,mark_label)
SELECT v.id,(item.ordinality - 1)::integer,item.value
FROM portfolio_valuation v
CROSS JOIN LATERAL jsonb_array_elements_text(v.missing_marks::jsonb) WITH ORDINALITY AS item(value,ordinality);

ALTER TABLE portfolio_valuation DROP COLUMN missing_marks;

CREATE TABLE sim_session_event (
  sim_session_id TEXT NOT NULL REFERENCES sim_session(id) ON DELETE CASCADE,
  event_index    INTEGER NOT NULL CHECK (event_index >= 0),
  quantum        BIGINT NOT NULL CHECK (quantum >= 0),
  kind           TEXT NOT NULL CHECK (kind IN ('MOVE','VOL','SPEED')),
  symbol         TEXT,
  value          DOUBLE PRECISION NOT NULL,
  PRIMARY KEY (sim_session_id,event_index),
  CHECK ((kind='MOVE' AND symbol IS NOT NULL) OR (kind IN ('VOL','SPEED') AND symbol IS NULL))
);

INSERT INTO sim_session_event(sim_session_id,event_index,quantum,kind,symbol,value)
SELECT s.id,(item.ordinality - 1)::integer,(item.value->>'quantum')::bigint,
       item.value->>'kind',item.value->>'symbol',(item.value->>'value')::double precision
FROM sim_session s
CROSS JOIN LATERAL jsonb_array_elements(s.events) WITH ORDINALITY AS item(value,ordinality);

ALTER TABLE sim_session DROP COLUMN events;
