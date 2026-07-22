ALTER TABLE public.portfolio_valuation
    ADD COLUMN pending_debit_cents bigint,
    ADD COLUMN broker_reserve_cents bigint,
    ADD COLUMN broker_buying_power_cents bigint,
    ADD COLUMN collateral_income_annual_rate_pct double precision,
    ADD COLUMN collateral_income_cents bigint;

ALTER TABLE public.portfolio_valuation
    ADD CONSTRAINT portfolio_valuation_pending_debit_nonnegative
        CHECK (pending_debit_cents IS NULL OR pending_debit_cents >= 0),
    ADD CONSTRAINT portfolio_valuation_broker_reserve_nonnegative
        CHECK (broker_reserve_cents IS NULL OR broker_reserve_cents >= 0),
    ADD CONSTRAINT portfolio_valuation_collateral_rate_nonnegative
        CHECK (collateral_income_annual_rate_pct IS NULL
            OR (collateral_income_annual_rate_pct >= 0 AND collateral_income_annual_rate_pct <= 100)),
    ADD CONSTRAINT portfolio_valuation_collateral_income_nonnegative
        CHECK (collateral_income_cents IS NULL OR collateral_income_cents >= 0),
    ADD CONSTRAINT portfolio_valuation_broker_liquidity_authority
        CHECK ((pending_debit_cents IS NULL
                AND broker_reserve_cents IS NULL
                AND broker_buying_power_cents IS NULL
                AND collateral_income_annual_rate_pct IS NULL
                AND collateral_income_cents IS NULL)
            OR source = 'BROKER');
