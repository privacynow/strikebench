-- V43 knew only three roots. Reclassify every persisted leg, lot, and realized match for the
-- shared broad-based index taxonomy, including listed weekly and p.m.-settled aliases.
UPDATE portfolio_transaction_leg
SET section_1256=1
WHERE instrument_type='OPTION'
  AND UPPER(symbol) IN ('SPX','SPXW','SPXPM','XSP','NDX','NDXP','VIX','VIXW','RUT','RUTW','DJX','OEX','XEO');

UPDATE portfolio_lot
SET section_1256=1
WHERE instrument_type='OPTION'
  AND UPPER(symbol) IN ('SPX','SPXW','SPXPM','XSP','NDX','NDXP','VIX','VIXW','RUT','RUTW','DJX','OEX','XEO');

UPDATE portfolio_lot_match m
SET section_1256=1,
    holding_term=CASE WHEN holding_term='ROLLED' THEN 'ROLLED' ELSE 'SECTION_1256' END
FROM portfolio_lot l
WHERE l.id=m.lot_id
  AND l.instrument_type='OPTION'
  AND UPPER(l.symbol) IN ('SPX','SPXW','SPXPM','XSP','NDX','NDXP','VIX','VIXW','RUT','RUTW','DJX','OEX','XEO');
