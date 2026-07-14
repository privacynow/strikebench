ALTER TABLE portfolio_transaction
  ADD COLUMN record_seq BIGINT;

WITH ordered AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) AS seq
  FROM portfolio_transaction
)
UPDATE portfolio_transaction tx
SET record_seq=ordered.seq
FROM ordered
WHERE ordered.id=tx.id;

CREATE SEQUENCE portfolio_transaction_record_seq_seq;
SELECT setval('portfolio_transaction_record_seq_seq',
  COALESCE((SELECT MAX(record_seq) FROM portfolio_transaction), 0) + 1, false);

ALTER SEQUENCE portfolio_transaction_record_seq_seq
  OWNED BY portfolio_transaction.record_seq;

ALTER TABLE portfolio_transaction
  ALTER COLUMN record_seq SET DEFAULT nextval('portfolio_transaction_record_seq_seq'),
  ALTER COLUMN record_seq SET NOT NULL;

CREATE UNIQUE INDEX idx_portfolio_tx_record_seq
  ON portfolio_transaction(record_seq);
