-- Backtests now persist the complete effective input object for every run. Reconstruct the
-- pre-V26 rows once, normalize the renamed analysis-mode key, and make the persisted object the
-- only runtime source. The scalar columns remain indexed report fields, not a fallback request.

ALTER TABLE public.backtests
    DROP CONSTRAINT backtests_effective_request_receipt_check;

UPDATE public.backtests
SET effective_request = jsonb_build_object(
        'engineKind', run_kind,
        'symbol', symbol,
        'strategy', strategy,
        'from', from_date::text,
        'to', to_date::text,
        'targetDte', target_dte,
        'entryEveryDays', entry_every_days,
        'qty', qty,
        'startingCashCents', starting_cash_cents
    ) || CASE run_kind
        WHEN 'SINGLE' THEN jsonb_build_object(
            'slippagePct', slippage_pct
        )
        ELSE jsonb_build_object(
            'maxConcurrent', max_concurrent,
            'shortDelta', short_delta,
            'widthPct', width_pct,
            'takeProfitFraction', take_profit_fraction,
            'stopMultiple', stop_multiple,
            'timeRuleSessions', time_rule_sessions
        )
    END
WHERE effective_request IS NULL;

UPDATE public.backtests
SET effective_request = (effective_request - 'analysisLane')
        || jsonb_build_object('analysisMode', effective_request->'analysisLane')
WHERE effective_request ? 'analysisLane'
  AND NOT effective_request ? 'analysisMode';

UPDATE public.backtests
SET effective_request = effective_request - 'analysisLane'
WHERE effective_request ? 'analysisLane'
  AND effective_request ? 'analysisMode';

-- Backtester.inputFingerprint hashes recursively key-sorted compact JSON. This temporary helper
-- produces the same encoding for the controlled ASCII keys in effective_request, allowing rows
-- changed above to retain an exact fingerprint of their canonical persisted input.
CREATE FUNCTION public._backtest_canonical_json(value jsonb) RETURNS text
    LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    kind text := jsonb_typeof(value);
    encoded text;
BEGIN
    IF kind = 'object' THEN
        SELECT '{' || COALESCE(string_agg(
                    to_jsonb(member.key)::text || ':'
                    || public._backtest_canonical_json(member.value),
                    ',' ORDER BY member.key COLLATE "C"), '') || '}'
        INTO encoded
        FROM jsonb_each(value) member;
        RETURN encoded;
    ELSIF kind = 'array' THEN
        SELECT '[' || COALESCE(string_agg(
                    public._backtest_canonical_json(member.value),
                    ',' ORDER BY member.ordinality), '') || ']'
        INTO encoded
        FROM jsonb_array_elements(value) WITH ORDINALITY member(value, ordinality);
        RETURN encoded;
    END IF;
    RETURN value::text;
END
$$;

UPDATE public.backtests
SET input_hash = encode(
        sha256(convert_to(public._backtest_canonical_json(effective_request), 'UTF8')),
        'hex');

DROP FUNCTION public._backtest_canonical_json(jsonb);

ALTER TABLE public.backtests
    ALTER COLUMN effective_request SET NOT NULL,
    ALTER COLUMN input_hash SET NOT NULL;

ALTER TABLE public.backtests
    ADD CONSTRAINT backtests_effective_request_shape_check CHECK (
        jsonb_typeof(effective_request) = 'object'
        AND effective_request ? 'engineKind'
        AND effective_request->>'engineKind' = run_kind
        AND effective_request ? 'symbol'
        AND effective_request->>'symbol' = symbol
        AND effective_request ? 'strategy'
        AND effective_request->>'strategy' = strategy
        AND effective_request ? 'from'
        AND effective_request->>'from' = from_date::text
        AND effective_request ? 'to'
        AND effective_request->>'to' = to_date::text
        AND NOT effective_request ? 'analysisLane'
        AND input_hash ~ '^[0-9a-f]{64}$'
    );
