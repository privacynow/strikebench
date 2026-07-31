CREATE TABLE portfolio_manual_entry_request (
    user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_request_id text NOT NULL,
    request_hash text NOT NULL,
    account_id text NOT NULL REFERENCES portfolio_account(id) ON DELETE CASCADE,
    transaction_id text NOT NULL REFERENCES portfolio_transaction(id) ON DELETE CASCADE,
    account_created integer NOT NULL,
    occurred_on date NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, client_request_id),
    UNIQUE (transaction_id),
    CONSTRAINT portfolio_manual_entry_request_id_check
        CHECK (length(btrim(client_request_id)) BETWEEN 1 AND 120),
    CONSTRAINT portfolio_manual_entry_request_hash_check
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT portfolio_manual_entry_request_account_created_check
        CHECK (account_created IN (0, 1))
);

CREATE INDEX idx_portfolio_manual_entry_request_account
    ON portfolio_manual_entry_request(account_id, created_at DESC);
