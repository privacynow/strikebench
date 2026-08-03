#!/usr/bin/env bash
# Read-only capture of the local server's current observed-market readiness.
#
# Usage:
#   scripts/live-market-probe.sh
#   SYMBOL=SPY OUT_DIR=/tmp/strikebench-spy scripts/live-market-probe.sh
#   BASE_URL=http://127.0.0.1:7093 scripts/live-market-probe.sh
#
# The script never starts, stops, or mutates the application. It writes every raw
# API response and HTTP status to OUT_DIR. Transport errors, non-2xx responses, malformed JSON,
# and response-schema violations are preserved and make the probe fail after all reads finish.
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:7070}"
SYMBOL="${SYMBOL:-NVDA}"
SYMBOL="$(printf '%s' "$SYMBOL" | tr '[:lower:]' '[:upper:]')"
OUT_DIR="${OUT_DIR:-/tmp/strikebench-live-probe-$(date +%Y%m%d-%H%M%S)}"

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to select the first returned expiration" >&2
  exit 2
fi
if [[ ! "$SYMBOL" =~ ^[A-Z.]{1,16}$ ]]; then
  echo "SYMBOL must be an uppercase ticker-like value" >&2
  exit 2
fi

umask 077
mkdir -p "$OUT_DIR"
PROBE_FAILURES=0
LAST_FETCH_VALID=0
expiration=''

fetch() {
  local name="$1"
  local path="$2"
  local jq_filter="$3"
  local expected_shape="$4"
  local url="${BASE_URL%/}${path}"
  local status
  local curl_exit=0
  LAST_FETCH_VALID=0
  status="$(curl --silent --show-error --location \
    --connect-timeout 4 --max-time 75 \
    --header 'Accept: application/json' \
    --output "$OUT_DIR/$name.json" --write-out '%{http_code}' "$url")" || curl_exit=$?
  printf '%s %s\n' "${status:-000}" "$path" >> "$OUT_DIR/http-status.txt"
  if (( curl_exit != 0 )); then
    printf '%s: transport failure (curl exit %s; HTTP %s)\n' \
      "$name" "$curl_exit" "${status:-000}" >&2
    PROBE_FAILURES=$((PROBE_FAILURES + 1))
  elif [[ ! "${status:-000}" =~ ^2[0-9][0-9]$ ]]; then
    printf '%s: HTTP %s (probe failure)\n' "$name" "${status:-000}" >&2
    PROBE_FAILURES=$((PROBE_FAILURES + 1))
  elif ! jq -e --arg symbol "$SYMBOL" --arg expiration "$expiration" \
      "$jq_filter" "$OUT_DIR/$name.json" > /dev/null 2> "$OUT_DIR/$name.validation.txt"; then
    printf '%s: HTTP %s but response does not match %s\n' "$name" "$status" "$expected_shape" >&2
    PROBE_FAILURES=$((PROBE_FAILURES + 1))
  else
    rm -f "$OUT_DIR/$name.validation.txt"
    LAST_FETCH_VALID=1
    printf '%s: HTTP %s · %s\n' "$name" "$status" "$expected_shape"
  fi
}

printf 'base_url=%s\nsymbol=%s\ncaptured_at=%s\n' \
  "$BASE_URL" "$SYMBOL" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$OUT_DIR/manifest.txt"
: > "$OUT_DIR/http-status.txt"

fetch health /api/health \
  '(.ok == true) and ((.startedAt | type) == "string") and ((.jarChangedSinceBoot | type) == "boolean")' \
  'Health(ok, startedAt, jarChangedSinceBoot)'
fetch config /api/config \
  '(.fixturesOnly == false) and (.world == "observed") and (.marketMode == "OBSERVED")' \
  'observed non-fixture Config'
fetch status /api/status \
  '(.ok == true) and ((.asOf | type) == "string") and ((.domains | type) == "object")' \
  'typed market Status'
fetch world /api/world \
  '(.world == "observed") and ((.revision | type) == "number") and ((.workspace | type) == "object")' \
  'observed world transition'
fetch market-engine /api/market/engine \
  '(.enabled == true) and (.running == true) and ((.symbols | type) == "array")' \
  'running MarketDataEngine status'
fetch quote "/api/quotes?symbols=${SYMBOL}" \
  '(.marketMode == "OBSERVED") and ((.quotes | type) == "array") and
   any(.quotes[]; (.symbol == $symbol) and (.priced == true)
     and ((.displayPrice | type) == "number") and ((.source | type) == "string")
     and ((.freshness | type) == "string"))' \
  'priced observed QuoteView for requested symbol'
fetch research "/api/research/${SYMBOL}" \
  '(.symbol == $symbol) and (.marketMode == "OBSERVED") and (.quote.symbol == $symbol)
   and (.quote.priced == true) and ((.quote.displayPrice | type) == "number")
   and ((.evidence | type) == "object") and ((.expirations | type) == "array")' \
  'typed observed ResearchDetail'
fetch history "/api/research/${SYMBOL}/history?range=6m" \
  '(.symbol == $symbol) and (.range == "6m") and ((.candles | type) == "array")
   and ((.candles | length) > 0) and ((.source | type) == "string")
   and ((.freshness | type) == "string") and ((.coverage | type) == "object")' \
  'nonempty typed observed History'
fetch news "/api/research/${SYMBOL}/news" \
  '(.symbol == $symbol) and ((.items | type) == "array")
   and ((.aggregate | type) == "object") and ((.evidence | type) == "string")' \
  'typed ResearchNews'
fetch expirations "/api/research/${SYMBOL}/expirations" \
  '(.symbol == $symbol) and (.asOfDate | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
   and ((.expirations | type) == "array") and ((.expirations | length) > 0)
   and all(.expirations[];
     (.date | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
     and ((.tradingSessions | type) == "number")
     and ((.calendarDays | type) == "number"))' \
  'nonempty typed ExpirationDistance list'

if (( LAST_FETCH_VALID == 1 )); then
  expiration="$(jq -r '.expirations[0].date // empty' "$OUT_DIR/expirations.json")"
fi
if [[ "$expiration" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  printf 'selected_expiration=%s\n' "$expiration" >> "$OUT_DIR/manifest.txt"
  fetch chain "/api/research/${SYMBOL}/chain?expiration=${expiration}" \
    '(.underlying == $symbol) and (.expiration == $expiration)
     and ((.underlyingPrice | type) == "number") and ((.calls | type) == "array")
     and ((.puts | type) == "array") and (((.calls | length) + (.puts | length)) > 0)
     and ((.source | type) == "string") and ((.freshness | type) == "string")' \
    'matching nonempty OptionChain'
else
  printf 'selected_expiration=unavailable\n' >> "$OUT_DIR/manifest.txt"
  echo "chain: not attempted because no validated active expiration was returned"
fi

echo "Saved raw responses to $OUT_DIR"
if (( PROBE_FAILURES > 0 )); then
  printf 'Live provider probe failed closed: %s response(s) failed transport, HTTP, or semantic validation.\n' \
    "$PROBE_FAILURES" >&2
  exit 1
fi
