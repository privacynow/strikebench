-- Tax basis can diverge from what the investor economically paid or received. Wash-sale
-- deferrals increase tax basis, and Section 1256 year-end marks reset tax basis without
-- closing the investor's economic position. Keep both facts explicitly.
ALTER TABLE portfolio_lot ADD COLUMN economic_original_open_amount_cents BIGINT;
ALTER TABLE portfolio_lot ADD COLUMN economic_remaining_open_amount_cents BIGINT;
ALTER TABLE portfolio_lot_match ADD COLUMN economic_open_amount_cents BIGINT;
ALTER TABLE portfolio_lot_match ADD COLUMN economic_close_amount_cents BIGINT;
ALTER TABLE portfolio_lot_match ADD COLUMN economic_realized_gain_cents BIGINT;

-- Remove recorded wash-sale deferrals from the economic basis of existing replacement lots.
UPDATE portfolio_lot l
SET economic_original_open_amount_cents = l.original_open_amount_cents
        - COALESCE((SELECT SUM(w.adjustment_cents) FROM portfolio_wash_sale_allocation w
                    WHERE w.replacement_lot_id=l.id), 0),
    economic_remaining_open_amount_cents = l.remaining_open_amount_cents
        - COALESCE((SELECT ROUND(SUM(w.adjustment_cents)::numeric * l.remaining_quantity
                                / NULLIF(l.original_quantity, 0))::bigint
                    FROM portfolio_wash_sale_allocation w WHERE w.replacement_lot_id=l.id), 0);

ALTER TABLE portfolio_lot ALTER COLUMN economic_original_open_amount_cents SET NOT NULL;
ALTER TABLE portfolio_lot ALTER COLUMN economic_remaining_open_amount_cents SET NOT NULL;

-- Allocate each economic opening amount by cumulative quantity. The cumulative form preserves
-- every cent across partial closes instead of independently rounding each match.
WITH ordered AS (
  SELECT m.id, l.side, l.economic_original_open_amount_cents economic_original,
         l.original_quantity,
         COALESCE(SUM(m.quantity) OVER (PARTITION BY m.lot_id ORDER BY m.closed_at,m.id
           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) prior_quantity,
         SUM(m.quantity) OVER (PARTITION BY m.lot_id ORDER BY m.closed_at,m.id
           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) cumulative_quantity,
         m.close_amount_cents, m.holding_term
  FROM portfolio_lot_match m JOIN portfolio_lot l ON l.id=m.lot_id
), allocated AS (
  SELECT id, side, close_amount_cents, holding_term,
         ROUND(economic_original::numeric * cumulative_quantity / original_quantity)::bigint
           - ROUND(economic_original::numeric * prior_quantity / original_quantity)::bigint economic_open
  FROM ordered
)
UPDATE portfolio_lot_match m
SET economic_open_amount_cents=a.economic_open,
    economic_close_amount_cents=CASE WHEN a.holding_term='ROLLED' THEN a.economic_open ELSE a.close_amount_cents END,
    economic_realized_gain_cents=CASE WHEN a.holding_term='ROLLED' THEN 0
      WHEN a.side='LONG' THEN a.close_amount_cents-a.economic_open
      ELSE a.economic_open-a.close_amount_cents END
FROM allocated a WHERE a.id=m.id;

UPDATE portfolio_lot l
SET economic_remaining_open_amount_cents = l.economic_original_open_amount_cents
      - COALESCE((SELECT SUM(m.economic_open_amount_cents) FROM portfolio_lot_match m
                  WHERE m.lot_id=l.id), 0);

-- Replay existing Section 1256 mark transactions in chronological order. Each synthetic close
-- realizes tax P/L but zero economic P/L; the synthetic replacement inherits economic basis.
DO $$
DECLARE mark_tx RECORD;
BEGIN
  FOR mark_tx IN
    SELECT id FROM portfolio_transaction WHERE event_type='MARK_TO_MARKET'
    ORDER BY occurred_at,record_seq,id
  LOOP
    UPDATE portfolio_lot_match
    SET economic_close_amount_cents=economic_open_amount_cents,
        economic_realized_gain_cents=0
    WHERE closing_transaction_id=mark_tx.id;

    WITH carried AS (
      SELECT n.id, SUM(m.economic_open_amount_cents) amount
      FROM portfolio_lot n
      JOIN portfolio_lot old ON old.portfolio_account_id=n.portfolio_account_id
        AND old.instrument_type=n.instrument_type AND old.side=n.side AND old.symbol=n.symbol
        AND old.option_type IS NOT DISTINCT FROM n.option_type
        AND old.strike IS NOT DISTINCT FROM n.strike
        AND old.expiration IS NOT DISTINCT FROM n.expiration
        AND old.multiplier=n.multiplier AND old.section_1256=n.section_1256
      JOIN portfolio_lot_match m ON m.lot_id=old.id AND m.closing_transaction_id=mark_tx.id
      WHERE n.opening_transaction_id=mark_tx.id
      GROUP BY n.id
    )
    UPDATE portfolio_lot n
    SET economic_original_open_amount_cents=c.amount,
        economic_remaining_open_amount_cents=c.amount
    FROM carried c WHERE c.id=n.id;

    WITH ordered AS (
      SELECT m.id, l.side, l.economic_original_open_amount_cents economic_original,
             l.original_quantity,
             COALESCE(SUM(m.quantity) OVER (PARTITION BY m.lot_id ORDER BY m.closed_at,m.id
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) prior_quantity,
             SUM(m.quantity) OVER (PARTITION BY m.lot_id ORDER BY m.closed_at,m.id
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) cumulative_quantity,
             m.close_amount_cents, m.holding_term
      FROM portfolio_lot_match m JOIN portfolio_lot l ON l.id=m.lot_id
      WHERE l.opening_transaction_id=mark_tx.id
    ), allocated AS (
      SELECT id, side, close_amount_cents, holding_term,
             ROUND(economic_original::numeric * cumulative_quantity / original_quantity)::bigint
               - ROUND(economic_original::numeric * prior_quantity / original_quantity)::bigint economic_open
      FROM ordered
    )
    UPDATE portfolio_lot_match m
    SET economic_open_amount_cents=a.economic_open,
        economic_close_amount_cents=CASE WHEN a.holding_term='ROLLED' THEN a.economic_open ELSE a.close_amount_cents END,
        economic_realized_gain_cents=CASE WHEN a.holding_term='ROLLED' THEN 0
          WHEN a.side='LONG' THEN a.close_amount_cents-a.economic_open
          ELSE a.economic_open-a.close_amount_cents END
    FROM allocated a WHERE a.id=m.id;

    UPDATE portfolio_lot l
    SET economic_remaining_open_amount_cents = l.economic_original_open_amount_cents
          - COALESCE((SELECT SUM(m.economic_open_amount_cents) FROM portfolio_lot_match m
                      WHERE m.lot_id=l.id), 0)
    WHERE l.opening_transaction_id=mark_tx.id;
  END LOOP;
END $$;

ALTER TABLE portfolio_lot_match ALTER COLUMN economic_open_amount_cents SET NOT NULL;
ALTER TABLE portfolio_lot_match ALTER COLUMN economic_close_amount_cents SET NOT NULL;
ALTER TABLE portfolio_lot_match ALTER COLUMN economic_realized_gain_cents SET NOT NULL;

ALTER TABLE portfolio_lot ADD CONSTRAINT portfolio_lot_economic_amounts_nonnegative CHECK
  (economic_original_open_amount_cents >= 0 AND economic_remaining_open_amount_cents >= 0);
