-- #12: persist the option-only opening net (Candidate.optionNetPremiumCents) alongside the
-- stock-inclusive entry_net_cents, so a restored Decide fan can show a covered call's true
-- "Net credit" instead of its stock-inclusive "Net debit". Additive, nullable; NULL falls back
-- to entry_net_cents at display time (non-stock structures have option_net == entry_net anyway).
ALTER TABLE plan_candidate ADD COLUMN option_net_cents bigint;
