#!/usr/bin/env bash
# Read-only capture of the local server's current observed-market readiness.
#
# Usage:
#   scripts/live-market-probe.sh
#   SYMBOL=SPY OUT_DIR=/tmp/strikebench-spy scripts/live-market-probe.sh
#   BASE_URL=http://127.0.0.1:7093 scripts/live-market-probe.sh
#
# The script never starts, stops, or mutates the application. It writes every raw
# API response and HTTP status to OUT_DIR. Transport errors and non-2xx responses
# are preserved and make the probe fail after all reads have been attempted.
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

fetch() {
  local name="$1"
  local path="$2"
  local url="${BASE_URL%/}${path}"
  local status
  local curl_exit=0
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
  else
    printf '%s: HTTP %s\n' "$name" "$status"
  fi
}

printf 'base_url=%s\nsymbol=%s\ncaptured_at=%s\n' \
  "$BASE_URL" "$SYMBOL" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$OUT_DIR/manifest.txt"
: > "$OUT_DIR/http-status.txt"

fetch health /api/health
fetch config /api/config
fetch status /api/status
fetch world /api/world
fetch market-engine /api/market/engine
fetch quote "/api/quotes?symbols=${SYMBOL}"
fetch research "/api/research/${SYMBOL}"
fetch history "/api/research/${SYMBOL}/history?range=6m"
fetch news "/api/research/${SYMBOL}/news"
fetch expirations "/api/research/${SYMBOL}/expirations"

expiration="$(jq -r '.expirations[0] // empty' "$OUT_DIR/expirations.json" 2>/dev/null || true)"
if [[ "$expiration" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  printf 'selected_expiration=%s\n' "$expiration" >> "$OUT_DIR/manifest.txt"
  fetch chain "/api/research/${SYMBOL}/chain?expiration=${expiration}"
else
  printf 'selected_expiration=unavailable\n' >> "$OUT_DIR/manifest.txt"
  echo "chain: skipped (no active expiration returned)"
fi

echo "Saved raw responses to $OUT_DIR"
if (( PROBE_FAILURES > 0 )); then
  printf 'Live provider probe failed closed: %s request(s) had a transport or non-2xx failure.\n' \
    "$PROBE_FAILURES" >&2
  exit 1
fi
