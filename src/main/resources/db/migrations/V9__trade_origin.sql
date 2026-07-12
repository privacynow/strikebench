-- The learning-loop lane: trades recorded from OUTSIDE the paper account (real broker fills)
-- live beside paper trades with identical evaluation/marks/resolution, but NEVER touch paper
-- cash, the ledger, or reserves. origin: PAPER (default) | EXTERNAL.
ALTER TABLE trades ADD COLUMN origin TEXT NOT NULL DEFAULT 'PAPER';
