-- Preserve the decision-grade explanation that produced each ranked Plan candidate.
-- The candidate's executable package remains normalized in the existing tables; this receipt
-- carries the immutable score, evidence, risk, capital, management, and explanation snapshot
-- needed to audit the ranking after a restart.

ALTER TABLE plan_candidate ADD COLUMN evaluation_snapshot TEXT;
