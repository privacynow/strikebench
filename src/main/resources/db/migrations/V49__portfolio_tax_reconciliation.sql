CREATE TABLE portfolio_tax_reconciliation (
  portfolio_account_id                 TEXT NOT NULL REFERENCES portfolio_account(id),
  tax_year                             INTEGER NOT NULL CHECK (tax_year BETWEEN 1970 AND 9999),
  status                               TEXT NOT NULL CHECK (status IN ('DRAFT','RECONCILED')),
  form_reference                       TEXT,
  broker_short_term_gain_cents         BIGINT,
  broker_long_term_gain_cents          BIGINT,
  broker_wash_adjustment_cents          BIGINT CHECK (broker_wash_adjustment_cents IS NULL OR broker_wash_adjustment_cents >= 0),
  broker_section_1256_gain_cents        BIGINT,
  broker_interest_cents                BIGINT,
  broker_ordinary_dividend_cents       BIGINT,
  broker_qualified_dividend_cents      BIGINT,
  broker_capital_gain_distribution_cents BIGINT,
  notes                                TEXT,
  created_at                           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                           TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (portfolio_account_id, tax_year)
);
