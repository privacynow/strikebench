ALTER TABLE portfolio_lot ADD COLUMN multiplier INTEGER NOT NULL DEFAULT 1 CHECK (multiplier > 0);

UPDATE portfolio_lot l
SET multiplier = tl.multiplier
FROM portfolio_transaction_leg tl
WHERE tl.transaction_id=l.opening_transaction_id
  AND tl.leg_no=l.opening_leg_no;
