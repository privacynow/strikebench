#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=dev-market-snapshot.sh
source "$REPO_ROOT/scripts/dev-market-snapshot.sh"

different_global_schema="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
different_table_contract="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

# An unrelated global schema change is compatible when the explicit format-2 data surface matches.
resolved="$(resolve_snapshot_table_contract "$different_global_schema" \
  "$FORMAT2_TABLE_CONTRACT_SHA256" "$FORMAT2_TABLE_CONTRACT_SHA256")"
[[ "$resolved" == "$FORMAT2_TABLE_CONTRACT_SHA256" ]]

# The one recognized pre-field format-2 schema remains hydratable without rewriting its bundle.
resolved="$(resolve_snapshot_table_contract "$FORMAT2_LEGACY_SCHEMA_SHA256" "" \
  "$FORMAT2_TABLE_CONTRACT_SHA256")"
[[ "$resolved" == "$FORMAT2_TABLE_CONTRACT_SHA256" ]]

# A changed selected column/type contract remains a hard failure.
if (resolve_snapshot_table_contract "$different_global_schema" \
    "$different_table_contract" "$FORMAT2_TABLE_CONTRACT_SHA256") >/dev/null 2>&1; then
  printf 'expected changed table contract to fail\n' >&2
  exit 1
fi

# A manifest without an explicit contract is accepted only for the known legacy capture schema.
if (resolve_snapshot_table_contract "$different_global_schema" "" \
    "$FORMAT2_TABLE_CONTRACT_SHA256") >/dev/null 2>&1; then
  printf 'expected unrecognized legacy manifest to fail\n' >&2
  exit 1
fi

test_dir="$(mktemp -d "${TMPDIR:-/tmp}/strikebench-snapshot-contract.XXXXXX")"
trap 'rm -rf -- "$test_dir"' EXIT

{
  printf '%s\n' "$YAHOO_COVERAGE_HEADER"
  printf '%s\n' "$CANONICAL_YAHOO_SYMBOLS" | tr ',' '\n' | \
    awk -v from="$CANONICAL_YAHOO_MIN_FROM" -v to="$CANONICAL_YAHOO_MIN_TO" \
      '{ print $1 ",500," from "," to }'
} > "$test_dir/yahoo-good.csv"
assert_canonical_yahoo_coverage "$test_dir/yahoo-good.csv"
[[ "$(coverage_min_latest "$test_dir/yahoo-good.csv")" == "$CANONICAL_YAHOO_MIN_TO" ]]
[[ "$(coverage_max_latest "$test_dir/yahoo-good.csv")" == "$CANONICAL_YAHOO_MIN_TO" ]]
[[ "$(coverage_distribution "$test_dir/yahoo-good.csv" 4)" == "$CANONICAL_YAHOO_MIN_TO:105" ]]
[[ "$(coverage_distribution "$test_dir/yahoo-good.csv" 2)" == "500:105" ]]

# A newly listed symbol is complete from its actual first regular-way trading session; requiring
# pre-listing bars would reward fabricated history and make an honest snapshot impossible.
awk -F, 'BEGIN { OFS="," } $1=="SNDK" { $2=356; $3="2025-02-24" } { print }' \
  "$test_dir/yahoo-good.csv" > "$test_dir/yahoo-launch-limited.csv"
assert_canonical_yahoo_coverage "$test_dir/yahoo-launch-limited.csv"

awk -F, 'BEGIN { OFS="," } NR>1 && NR<=28 { $2=501; $4="2026-07-21" } { print }' \
  "$test_dir/yahoo-good.csv" > "$test_dir/yahoo-partial-latest.csv"
[[ "$(coverage_min_latest "$test_dir/yahoo-partial-latest.csv")" == "2026-07-20" ]]
[[ "$(coverage_max_latest "$test_dir/yahoo-partial-latest.csv")" == "2026-07-21" ]]
[[ "$(coverage_distribution "$test_dir/yahoo-partial-latest.csv" 4)" \
      == "2026-07-20:78,2026-07-21:27" ]]
[[ "$(coverage_distribution "$test_dir/yahoo-partial-latest.csv" 2)" == "500:78,501:27" ]]

awk -F, 'NR==1 || $1!="AMD"' "$test_dir/yahoo-good.csv" > "$test_dir/yahoo-missing.csv"
if (assert_canonical_yahoo_coverage "$test_dir/yahoo-missing.csv") >/dev/null 2>&1; then
  printf 'expected missing canonical Yahoo symbol to fail\n' >&2
  exit 1
fi

cp "$test_dir/yahoo-good.csv" "$test_dir/yahoo-prior.csv"
awk -F, 'BEGIN { OFS="," } $1=="AMD" { $2=499 } { print }' \
  "$test_dir/yahoo-good.csv" > "$test_dir/yahoo-regressed.csv"
if (assert_coverage_non_regressing "$test_dir/yahoo-prior.csv" \
    "$test_dir/yahoo-regressed.csv") >/dev/null 2>&1; then
  printf 'expected per-symbol Yahoo row regression to fail\n' >&2
  exit 1
fi
assert_coverage_non_regressing "$test_dir/yahoo-prior.csv" "$test_dir/yahoo-good.csv"

printf '%s\n' "$OPTION_COVERAGE_HEADER" \
  'AMD,100,2026-07-20,2026-07-21' \
  'NVDA,100,2026-07-20,2026-07-21' > "$test_dir/options-prior.csv"
printf '%s\n' "$OPTION_COVERAGE_HEADER" \
  'AMD,100,2026-07-20,2026-07-22' \
  'NVDA,100,2026-07-20,2026-07-21' > "$test_dir/options-good.csv"
# Aggregate growth must not hide the loss of one important symbol's stored option surface.
printf '%s\n' "$OPTION_COVERAGE_HEADER" \
  'AMD,99,2026-07-20,2026-07-21' \
  'NVDA,1000,2026-07-20,2026-07-22' > "$test_dir/options-regressed.csv"
assert_coverage_non_regressing "$test_dir/options-prior.csv" \
  "$test_dir/options-good.csv" "option"
if (assert_coverage_non_regressing "$test_dir/options-prior.csv" \
    "$test_dir/options-regressed.csv" "option") >/dev/null 2>&1; then
  printf 'expected per-symbol option regression to fail despite aggregate growth\n' >&2
  exit 1
fi

printf '%s\n' "$QUOTE_COVERAGE_HEADER" \
  'AMD,2026-07-21 19:00:00+00,2026-07-21 19:01:00+00,DELAYED,cboe' \
  'NVDA,2026-07-21 19:00:00+00,2026-07-21 19:01:00+00,DELAYED,cboe' \
  > "$test_dir/quotes-prior.csv"
printf '%s\n' "$QUOTE_COVERAGE_HEADER" \
  'AMD,2026-07-21 19:02:00+00,2026-07-21 19:03:00+00,DELAYED,cboe' \
  'NVDA,2026-07-21 19:00:00+00,2026-07-21 19:01:00+00,DELAYED,cboe' \
  > "$test_dir/quotes-good.csv"
printf '%s\n' "$QUOTE_COVERAGE_HEADER" \
  'AMD,2026-07-21 18:59:00+00,2026-07-21 19:04:00+00,REALTIME,cboe' \
  'NVDA,2026-07-21 19:05:00+00,2026-07-21 19:06:00+00,DELAYED,cboe' \
  > "$test_dir/quotes-regressed.csv"
assert_quote_non_regressing "$test_dir/quotes-prior.csv" "$test_dir/quotes-good.csv"
if (assert_quote_non_regressing "$test_dir/quotes-prior.csv" \
    "$test_dir/quotes-regressed.csv") >/dev/null 2>&1; then
  printf 'expected an older per-symbol quote to fail despite another symbol advancing\n' >&2
  exit 1
fi

printf '%s\n' "$QUOTE_COVERAGE_HEADER" \
  'AMD,2026-07-21 19:00:00+00,2026-07-21 19:04:00+00,STALE,cboe' \
  'NVDA,2026-07-21 19:05:00+00,2026-07-21 19:06:00+00,DELAYED,cboe' \
  > "$test_dir/quotes-weaker.csv"
if (assert_quote_non_regressing "$test_dir/quotes-prior.csv" \
    "$test_dir/quotes-weaker.csv") >/dev/null 2>&1; then
  printf 'expected same-time weaker quote freshness to fail\n' >&2
  exit 1
fi

printf '%s\n' "$QUOTE_COVERAGE_HEADER" \
  'AMD,2026-07-21 19:00:00+00,2026-07-21 18:59:00+00,DELAYED,cboe' \
  'NVDA,2026-07-21 19:05:00+00,2026-07-21 19:06:00+00,DELAYED,cboe' \
  > "$test_dir/quotes-older-capture.csv"
if (assert_quote_non_regressing "$test_dir/quotes-prior.csv" \
    "$test_dir/quotes-older-capture.csv") >/dev/null 2>&1; then
  printf 'expected same-time same-quality older quote capture to fail\n' >&2
  exit 1
fi

assert_symbol_list_contains "option snapshot" "AAPL,AMD,NVDA,QQQ,SMH,SPY" \
  "$IMPORTANT_OPTION_SYMBOLS"
if (assert_symbol_list_contains "quote snapshot" "AMD,NVDA,QQQ,SPY" \
    "$IMPORTANT_QUOTE_SYMBOLS") >/dev/null 2>&1; then
  printf 'expected missing important quote sentinels to fail\n' >&2
  exit 1
fi

printf '%s\n%s\n' "$BUDGET_HEADER" 'yahoo,2026-07-21,133,160,2026-07-21 20:00:00+00' \
  > "$test_dir/budget-prior.csv"
printf '%s\n%s\n' "$BUDGET_HEADER" 'yahoo,2026-07-21,134,160,2026-07-21 20:01:00+00' \
  > "$test_dir/budget-good.csv"
printf '%s\n%s\n' "$BUDGET_HEADER" 'yahoo,2026-07-21,132,160,2026-07-21 20:01:00+00' \
  > "$test_dir/budget-regressed.csv"
assert_budget_non_regressing "$test_dir/budget-prior.csv" "$test_dir/budget-good.csv"
if (assert_budget_non_regressing "$test_dir/budget-prior.csv" \
    "$test_dir/budget-regressed.csv") >/dev/null 2>&1; then
  printf 'expected provider budget regression to fail\n' >&2
  exit 1
fi

printf '%s\n%s\n' "$COOLDOWN_HEADER" 'yahoo_cooldown_until,1784667000000,2026-07-21 20:00:00+00' \
  > "$test_dir/cooldown-prior.csv"
printf '%s\n%s\n' "$COOLDOWN_HEADER" 'yahoo_cooldown_until,1784667001000,2026-07-21 20:01:00+00' \
  > "$test_dir/cooldown-good.csv"
printf '%s\n' "$COOLDOWN_HEADER" > "$test_dir/cooldown-regressed.csv"
assert_cooldown_non_regressing "$test_dir/cooldown-prior.csv" "$test_dir/cooldown-good.csv"
if (assert_cooldown_non_regressing "$test_dir/cooldown-prior.csv" \
    "$test_dir/cooldown-regressed.csv") >/dev/null 2>&1; then
  printf 'expected provider cooldown regression to fail\n' >&2
  exit 1
fi

printf 'dev-market-snapshot contract tests passed\n'
