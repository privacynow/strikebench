ALTER TABLE plan_context_revision
    ADD COLUMN avoid_earnings integer NOT NULL DEFAULT 1
        CHECK (avoid_earnings IN (0, 1));

COMMENT ON COLUMN plan_context_revision.avoid_earnings IS
    'Plan-owned declaration: true excludes packages whose expiration crosses the canonical earnings event receipt.';
