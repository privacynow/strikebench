-- A close-only history is useful for returns/HV, but it must not masquerade as observed intraday
-- range evidence. Preserve that distinction through storage and the candle-series response.
ALTER TABLE underlying_bar ADD COLUMN bar_kind TEXT NOT NULL DEFAULT 'OHLCV'
  CHECK (bar_kind IN ('OHLCV','OHLC','CLOSE_ONLY'));
