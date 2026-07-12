-- The observed-IV-history read (EvaluationService.ivHistory) scans option_bar for one symbol's
-- vendor-sourced, near-the-money IV rows grouped by snapshot date. Phase-4 bulk historical
-- ingest will make option_bar large and mostly vendor rows, so a PARTIAL index restricted to
-- exactly that predicate stays small and serves the query directly (symbol lookup + asof order),
-- rather than leaning on the broad (symbol, asof) index and filtering the rest at scan time.
CREATE INDEX IF NOT EXISTS idx_option_bar_iv_history
  ON option_bar (symbol, asof)
  WHERE iv IS NOT NULL AND iv_source = 'vendor' AND underlying IS NOT NULL;
