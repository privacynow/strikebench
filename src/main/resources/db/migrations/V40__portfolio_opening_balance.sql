ALTER TABLE portfolio_transaction
  DROP CONSTRAINT portfolio_transaction_event_type_check;

ALTER TABLE portfolio_transaction
  ADD CONSTRAINT portfolio_transaction_event_type_check CHECK (event_type IN
    ('OPENING_BALANCE','DEPOSIT','WITHDRAWAL','TRANSFER_IN','TRANSFER_OUT','INTEREST','DIVIDEND','FEE',
     'TRADE','EXPIRATION','ASSIGNMENT','EXERCISE','ADJUSTMENT'));

UPDATE portfolio_transaction
SET event_type='OPENING_BALANCE'
WHERE source='MANUAL'
  AND external_ref='opening-balance'
  AND notes='Opening cash';
