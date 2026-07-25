-- §7.5: the managed backtest no longer keeps a private exit dialect. Its three exit knobs are now
-- stated in the ONE management-policy vocabulary (ProtocolEvaluator.Policy), so the stored request
-- must not keep column names that describe the OLD denominators:
--   profit_target_pct (fraction of MAX PROFIT)  -> take_profit_fraction (fraction of the entry basis)
--   stop_fraction     (fraction of MAX LOSS)    -> stop_multiple        (multiple of the entry basis)
--   roll_dte          (calendar days to expiry) -> time_rule_sessions   (trading sessions to expiry)
-- Stored numbers are carried across unchanged; they are re-read as the policy's own units, and the
-- report now names the policy (or the declared ad-hoc deviation) that produced the exits.
ALTER TABLE backtests RENAME COLUMN profit_target_pct TO take_profit_fraction;
ALTER TABLE backtests RENAME COLUMN stop_fraction TO stop_multiple;
ALTER TABLE backtests RENAME COLUMN roll_dte TO time_rule_sessions;
