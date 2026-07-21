#!/usr/bin/env bash
# Preserve a small, evidence-honest observed-market corpus across destructive local
# database recreation. This is a development helper, not an application fallback.
#
# Usage:
#   scripts/dev-market-snapshot.sh capture
#   scripts/dev-market-snapshot.sh verify
#   scripts/dev-market-snapshot.sh hydrate
#
# The application must initialize the current schema before capture/hydrate. Ordinary
# Docker/Postgres restarts retain the pgdata volume and need no hydration.
set -euo pipefail

ACTION="${1:-}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE_DIR="${BUNDLE_DIR:-$REPO_ROOT/.tmp/dev-market-snapshot}"
DB_CONTAINER="${DB_CONTAINER:-strikebench-db}"
DB_NAME="${DB_NAME:-strikebench_dev}"
DB_USER="${DB_USER:-strikebench}"
SNAPSHOT_ALLOW_REGRESSION="${SNAPSHOT_ALLOW_REGRESSION:-false}"

# Format 2's exported data surface is intentionally narrower than the application's global
# schema. The hash below pins the exact selected columns, PostgreSQL types, and nullability.
# It is populated from snapshot_table_contract_descriptor(), not from the schema.sql file.
FORMAT2_TABLE_CONTRACT_SHA256="20b709be297cc50393b1e11b144f04eef514d30a386c233265018c9a82778864"
# The only format-2 bundle captured before the table-contract field was introduced. Its global
# schema fingerprint remains useful provenance; this narrow bridge does not trust arbitrary
# legacy manifests and still requires the exact format-2 CSV headers and current table contract.
FORMAT2_LEGACY_SCHEMA_SHA256="539513403cb7dc00b8e683068936ef4259aa4d38b451494bd8f12f926d2f1aa6"
FORMAT3_OPERATIONAL_CONTRACT_SHA256="218aca3340d852c41ac8f4ad0300fe5b34e6a35fe22ba814a793da4ad6eef6d5"

# This is the product's curated observed-history universe, not a request list. Capture refuses to
# replace a healthy bundle when any member or its established two-year coverage disappears. Extend
# this list additively when the canonical universe grows; do not silently shrink it.
CANONICAL_YAHOO_LEGACY_SYMBOLS="AAPL,ABBV,ADBE,AEP,AMD,AMGN,AMZN,ARM,AVAV,AVGO,BA,BAC,C,CAT,CL,CMCSA,COP,COST,CRM,CVS,CVX,D,DE,DIA,DIS,DUK,EEM,EOG,ETN,GD,GE,GIS,GLD,GOOGL,GS,HD,HII,HON,IBM,INTC,ITA,IWM,JNJ,JPM,KO,LHX,LLY,LMT,LOW,MA,MCD,MDLZ,META,MRK,MS,MSFT,MU,NEE,NFLX,NKE,NOC,NOW,NVDA,ORCL,OXY,PEP,PFE,PG,PSX,QCOM,QQQ,RTX,SBUX,SLB,SLV,SMH,SO,SPY,T,TGT,TLT,TMO,TMUS,TSLA,TSM,UNH,UNP,UPS,V,VZ,WFC,WMT,XLC,XLE,XLF,XLI,XLK,XLP,XLU,XLV,XLY,XOM"
CANONICAL_YAHOO_SYMBOLS="$CANONICAL_YAHOO_LEGACY_SYMBOLS,SNDK,STX,WDC"
CANONICAL_YAHOO_MIN_FROM="2024-07-22"
CANONICAL_YAHOO_MIN_TO="2026-07-20"
# SNDK began regular-way Nasdaq trading on 2025-02-24 after the Western Digital separation.
# Its honest coverage floor is therefore its first session, not fabricated pre-listing bars.
CANONICAL_YAHOO_LAUNCH_MINIMUMS="SNDK:2025-02-24"
IMPORTANT_OPTION_SYMBOLS="AMD,NVDA,QQQ,SMH,SPY"
IMPORTANT_QUOTE_SYMBOLS="AMD,NVDA,QQQ,SMH,SPY,XLC,XLE,XLF,XLI,XLK,XLP,XLU,XLV,XLY"

UNDERLYING_HEADER="symbol,d,open,high,low,close,volume,source,observed,created_at,dataset_id,adjusted,quality_rank,bar_kind"
OPTION_HEADER="symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,theta,vega,open_interest,volume,underlying,source,bid_ask_observed,iv_source,greeks_source,created_at,dataset_id"
MARKET_HEADER="symbol,description,last,bid,ask,prev_close,optionable,source,freshness,as_of,captured_at"
BUDGET_HEADER="source_key,period_key,used_count,limit_count,updated_at"
COOLDOWN_HEADER="k,v,updated_at"
YAHOO_COVERAGE_HEADER="symbol,row_count,from_date,to_date"
OPTION_COVERAGE_HEADER="symbol,row_count,from_date,to_date"
QUOTE_COVERAGE_HEADER="symbol,as_of,captured_at,freshness,source"

die() {
  printf 'dev-market-snapshot: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf 'usage: %s {capture|verify|hydrate}\n' "$0" >&2
  exit 2
}

require_tools() {
  command -v docker >/dev/null 2>&1 || die "docker is required"
  command -v shasum >/dev/null 2>&1 || die "shasum is required"
  [[ "$DB_NAME" == "strikebench_dev" ]] \
    || die "refusing database '$DB_NAME'; this helper is restricted to strikebench_dev"
  docker inspect "$DB_CONTAINER" >/dev/null 2>&1 \
    || die "Postgres container '$DB_CONTAINER' is not available"
}

psql_exec() {
  docker exec -i "$DB_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"
}

scalar() {
  psql_exec -qAt -c "$1"
}

assert_dev_database() {
  local actual fingerprint
  actual="$(scalar 'SELECT current_database()')"
  [[ "$actual" == "strikebench_dev" ]] \
    || die "connected to '$actual', not strikebench_dev"
  fingerprint="$(scalar "SELECT schema_sha256 FROM public.strikebench_schema")"
  [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] \
    || die "the current StrikeBench schema has not been initialized"
  printf '%s' "$fingerprint"
}

manifest_value() {
  local key="$1"
  bundle_manifest_value "$BUNDLE_DIR" "$key"
}

bundle_manifest_value() {
  local bundle="$1" key="$2"
  sed -n "s/^${key}=//p" "$bundle/manifest.txt" | tail -n 1
}

file_hash() {
  shasum -a 256 "$1" | awk '{print $1}'
}

snapshot_table_contract_descriptor() {
  # Desired positions are the explicit COPY/INSERT order, not physical table ordinals. Added or
  # reordered columns outside this list therefore do not invalidate a safe market-data hydrate.
  psql_exec -qAt <<'SQL'
WITH requested(table_name, desired_position, column_name) AS (VALUES
  ('underlying_bar',1,'symbol'),('underlying_bar',2,'d'),
  ('underlying_bar',3,'open'),('underlying_bar',4,'high'),
  ('underlying_bar',5,'low'),('underlying_bar',6,'close'),
  ('underlying_bar',7,'volume'),('underlying_bar',8,'source'),
  ('underlying_bar',9,'observed'),('underlying_bar',10,'created_at'),
  ('underlying_bar',11,'dataset_id'),('underlying_bar',12,'adjusted'),
  ('underlying_bar',13,'quality_rank'),('underlying_bar',14,'bar_kind'),
  ('option_bar',1,'symbol'),('option_bar',2,'asof'),
  ('option_bar',3,'expiration'),('option_bar',4,'strike'),
  ('option_bar',5,'opt_type'),('option_bar',6,'bid'),
  ('option_bar',7,'ask'),('option_bar',8,'last'),
  ('option_bar',9,'mark'),('option_bar',10,'iv'),
  ('option_bar',11,'delta'),('option_bar',12,'gamma'),
  ('option_bar',13,'theta'),('option_bar',14,'vega'),
  ('option_bar',15,'open_interest'),('option_bar',16,'volume'),
  ('option_bar',17,'underlying'),('option_bar',18,'source'),
  ('option_bar',19,'bid_ask_observed'),('option_bar',20,'iv_source'),
  ('option_bar',21,'greeks_source'),('option_bar',22,'created_at'),
  ('option_bar',23,'dataset_id'),
  ('market_snapshot',1,'symbol'),('market_snapshot',2,'description'),
  ('market_snapshot',3,'last'),('market_snapshot',4,'bid'),
  ('market_snapshot',5,'ask'),('market_snapshot',6,'prev_close'),
  ('market_snapshot',7,'optionable'),('market_snapshot',8,'source'),
  ('market_snapshot',9,'freshness'),('market_snapshot',10,'as_of'),
  ('market_snapshot',11,'captured_at')
)
SELECT requested.table_name || '|' || lpad(requested.desired_position::text,2,'0') || '|'
       || requested.column_name || '|'
       || coalesce(pg_catalog.format_type(attribute.atttypid,attribute.atttypmod),'MISSING') || '|'
       || CASE WHEN attribute.attnotnull THEN 'NOT_NULL' ELSE 'NULLABLE' END
  FROM requested
  LEFT JOIN pg_catalog.pg_namespace namespace ON namespace.nspname='public'
  LEFT JOIN pg_catalog.pg_class relation
    ON relation.relnamespace=namespace.oid AND relation.relname=requested.table_name
  LEFT JOIN pg_catalog.pg_attribute attribute
    ON attribute.attrelid=relation.oid AND attribute.attname=requested.column_name
   AND attribute.attnum>0 AND NOT attribute.attisdropped
 ORDER BY CASE requested.table_name
            WHEN 'underlying_bar' THEN 1 WHEN 'option_bar' THEN 2 ELSE 3 END,
          requested.desired_position;
SQL
}

snapshot_table_contract_fingerprint() {
  local descriptor lines
  descriptor="$(snapshot_table_contract_descriptor)"
  [[ "$descriptor" != *'|MISSING|'* ]] || die "the running schema is missing a format-2 snapshot column"
  lines="$(printf '%s\n' "$descriptor" | wc -l | tr -d ' ')"
  [[ "$lines" == "48" ]] || die "the running schema does not expose all 48 format-2 snapshot columns"
  printf '%s' "$descriptor" | shasum -a 256 | awk '{print $1}'
}

snapshot_operational_contract_descriptor() {
  psql_exec -qAt <<'SQL'
WITH requested(table_name, desired_position, column_name) AS (VALUES
  ('provider_request_budget',1,'source_key'),('provider_request_budget',2,'period_key'),
  ('provider_request_budget',3,'used_count'),('provider_request_budget',4,'limit_count'),
  ('provider_request_budget',5,'updated_at'),
  ('settings',1,'k'),('settings',2,'v'),('settings',3,'updated_at')
)
SELECT requested.table_name || '|' || lpad(requested.desired_position::text,2,'0') || '|'
       || requested.column_name || '|'
       || coalesce(pg_catalog.format_type(attribute.atttypid,attribute.atttypmod),'MISSING') || '|'
       || CASE WHEN attribute.attnotnull THEN 'NOT_NULL' ELSE 'NULLABLE' END
  FROM requested
  LEFT JOIN pg_catalog.pg_namespace namespace ON namespace.nspname='public'
  LEFT JOIN pg_catalog.pg_class relation
    ON relation.relnamespace=namespace.oid AND relation.relname=requested.table_name
  LEFT JOIN pg_catalog.pg_attribute attribute
    ON attribute.attrelid=relation.oid AND attribute.attname=requested.column_name
   AND attribute.attnum>0 AND NOT attribute.attisdropped
 ORDER BY CASE requested.table_name WHEN 'provider_request_budget' THEN 1 ELSE 2 END,
          requested.desired_position;
SQL
}

snapshot_operational_contract_fingerprint() {
  local descriptor lines
  descriptor="$(snapshot_operational_contract_descriptor)"
  [[ "$descriptor" != *'|MISSING|'* ]] || die "the running schema is missing a format-3 operational-state column"
  lines="$(printf '%s\n' "$descriptor" | wc -l | tr -d ' ')"
  [[ "$lines" == "8" ]] || die "the running schema does not expose all 8 format-3 operational-state columns"
  printf '%s' "$descriptor" | shasum -a 256 | awk '{print $1}'
}

assert_csv_headers() {
  assert_csv_headers_in "$BUNDLE_DIR" "$(manifest_value format)"
}

assert_csv_headers_in() {
  local bundle="$1" format="$2" actual
  IFS= read -r actual < "$bundle/underlying_bar.csv" \
    || die "underlying_bar.csv is empty"
  [[ "$actual" == "$UNDERLYING_HEADER" ]] || die "underlying_bar.csv does not have the format-2 column contract"
  IFS= read -r actual < "$bundle/option_bar.csv" \
    || die "option_bar.csv is empty"
  [[ "$actual" == "$OPTION_HEADER" ]] || die "option_bar.csv does not have the format-2 column contract"
  IFS= read -r actual < "$bundle/market_snapshot.csv" \
    || die "market_snapshot.csv is empty"
  [[ "$actual" == "$MARKET_HEADER" ]] || die "market_snapshot.csv does not have the format-2 column contract"
  if [[ "$format" == "3" ]]; then
    IFS= read -r actual < "$bundle/provider_request_budget.csv" \
      || die "provider_request_budget.csv is empty"
    [[ "$actual" == "$BUDGET_HEADER" ]] || die "provider_request_budget.csv does not have the format-3 column contract"
    IFS= read -r actual < "$bundle/provider_cooldown.csv" \
      || die "provider_cooldown.csv is empty"
    [[ "$actual" == "$COOLDOWN_HEADER" ]] || die "provider_cooldown.csv does not have the format-3 column contract"
    IFS= read -r actual < "$bundle/yahoo_coverage.csv" \
      || die "yahoo_coverage.csv is empty"
    [[ "$actual" == "$YAHOO_COVERAGE_HEADER" ]] || die "yahoo_coverage.csv does not have the format-3 coverage contract"
    IFS= read -r actual < "$bundle/option_coverage.csv" \
      || die "option_coverage.csv is empty"
    [[ "$actual" == "$OPTION_COVERAGE_HEADER" ]] || die "option_coverage.csv does not have the format-3 coverage contract"
    IFS= read -r actual < "$bundle/quote_coverage.csv" \
      || die "quote_coverage.csv is empty"
    [[ "$actual" == "$QUOTE_COVERAGE_HEADER" ]] || die "quote_coverage.csv does not have the format-3 coverage contract"
  fi
}

resolve_snapshot_table_contract() {
  local manifest_schema="$1" manifest_contract="$2" current_contract="$3"
  [[ "$manifest_schema" =~ ^[0-9a-f]{64}$ ]] || die "invalid schema fingerprint in manifest"
  [[ "$current_contract" =~ ^[0-9a-f]{64}$ ]] || die "invalid running snapshot table contract"
  if [[ -z "$manifest_contract" ]]; then
    [[ "$manifest_schema" == "$FORMAT2_LEGACY_SCHEMA_SHA256" ]] \
      || die "legacy format-2 manifest has no recognized table-contract provenance; recapture it"
    manifest_contract="$FORMAT2_TABLE_CONTRACT_SHA256"
  fi
  [[ "$manifest_contract" =~ ^[0-9a-f]{64}$ ]] \
    || die "invalid snapshot table-contract fingerprint in manifest"
  [[ "$manifest_contract" == "$current_contract" ]] \
    || die "snapshot market-data table contract does not match the running development schema"
  printf '%s' "$manifest_contract"
}

assert_uint() {
  [[ "$2" =~ ^[0-9]+$ ]] || die "manifest field '$1' is not an unsigned integer"
}

csv_record_count() {
  awk 'NR>1 { count++ } END { print count+0 }' "$1"
}

csv_first_column_list() {
  awk -F, 'NR>1 { values=values (values?",":"") $1 } END { print values }' "$1"
}

coverage_min_latest() {
  awk -F, 'NR>1 && (minimum=="" || $4<minimum) { minimum=$4 } END { print minimum }' "$1"
}

coverage_max_latest() {
  awk -F, 'NR>1 && (maximum=="" || $4>maximum) { maximum=$4 } END { print maximum }' "$1"
}

coverage_distribution() {
  local coverage="$1" column="$2"
  awk -F, -v column="$column" 'NR>1 { count[$column]++ } END {
    for (value in count) print value "," count[value]
  }' "$coverage" | LC_ALL=C sort -t, -k1,1 | \
    awk -F, '{ values=values (values?",":"") $1 ":" $2 } END { print values }'
}

verify_bundle() {
  verify_bundle_in "$BUNDLE_DIR"
}

verify_bundle_in() {
  local bundle="$1" manifest_schema current_schema manifest_contract current_contract
  local operational_contract format file expected actual key regression_override
  for file in manifest.txt underlying_bar.csv option_bar.csv market_snapshot.csv; do
    [[ -f "$bundle/$file" ]] || die "missing bundle file: $bundle/$file"
  done
  format="$(bundle_manifest_value "$bundle" format)"
  [[ "$format" == "2" || "$format" == "3" ]] || die "unsupported snapshot bundle format"
  [[ "$(bundle_manifest_value "$bundle" database)" == "strikebench_dev" ]] \
    || die "snapshot was not captured from strikebench_dev"

  manifest_schema="$(bundle_manifest_value "$bundle" schema_sha256)"
  current_schema="$(assert_dev_database)"
  [[ "$current_schema" =~ ^[0-9a-f]{64}$ ]] || die "invalid running schema fingerprint"

  assert_csv_headers_in "$bundle" "$format"
  current_contract="$(snapshot_table_contract_fingerprint)"
  [[ "$current_contract" == "$FORMAT2_TABLE_CONTRACT_SHA256" ]] \
    || die "the running market-data tables no longer match snapshot format 2"
  manifest_contract="$(bundle_manifest_value "$bundle" snapshot_table_contract_sha256)"
  resolve_snapshot_table_contract "$manifest_schema" "$manifest_contract" "$current_contract" >/dev/null
  if [[ "$format" == "3" ]]; then
    regression_override="$(bundle_manifest_value "$bundle" regression_override)"
    regression_override="${regression_override:-false}"
    [[ "$regression_override" == "true" || "$regression_override" == "false" ]] \
      || die "snapshot regression_override must be true or false"
    operational_contract="$(snapshot_operational_contract_fingerprint)"
    [[ "$operational_contract" == "$FORMAT3_OPERATIONAL_CONTRACT_SHA256" ]] \
      || die "the running operational-state tables no longer match snapshot format 3"
    [[ "$(bundle_manifest_value "$bundle" operational_table_contract_sha256)" == "$operational_contract" ]] \
      || die "snapshot operational-state contract does not match the running development schema"
    for file in provider_request_budget.csv provider_cooldown.csv yahoo_coverage.csv \
                option_coverage.csv quote_coverage.csv; do
      [[ -f "$bundle/$file" ]] || die "missing bundle file: $bundle/$file"
    done
  fi

  for key in underlying_rows yahoo_rows option_rows market_snapshot_rows \
             underlying_symbol_count yahoo_symbol_count option_symbol_count market_snapshot_symbol_count; do
    assert_uint "$key" "$(bundle_manifest_value "$bundle" "$key")"
  done
  (( $(bundle_manifest_value "$bundle" underlying_rows) > 0 )) || die "snapshot has no observed underlying rows"
  (( $(bundle_manifest_value "$bundle" yahoo_rows) > 0 )) || die "snapshot has no Yahoo history rows"
  (( $(bundle_manifest_value "$bundle" option_rows) > 0 )) || die "snapshot has no observed option-chain rows"
  (( $(bundle_manifest_value "$bundle" market_snapshot_rows) > 0 )) || die "snapshot has no observed quote snapshots"
  (( $(bundle_manifest_value "$bundle" underlying_symbol_count) > 0 )) || die "snapshot has no underlying symbols"
  (( $(bundle_manifest_value "$bundle" yahoo_symbol_count) > 0 )) || die "snapshot has no Yahoo-covered symbols"
  (( $(bundle_manifest_value "$bundle" option_symbol_count) > 0 )) || die "snapshot has no option symbols"
  (( $(bundle_manifest_value "$bundle" market_snapshot_symbol_count) > 0 )) || die "snapshot has no quote symbols"

  if [[ "$format" == "3" ]]; then
    for key in provider_request_budget_rows provider_cooldown_rows; do
      assert_uint "$key" "$(bundle_manifest_value "$bundle" "$key")"
    done
    [[ "$(awk -F, 'NR>1 { total+=$2 } END { print total+0 }' "$bundle/yahoo_coverage.csv")" \
          == "$(bundle_manifest_value "$bundle" yahoo_rows)" ]] \
      || die "Yahoo coverage totals do not match the manifest"
    [[ "$(awk -F, 'NR>1 { total+=$2 } END { print total+0 }' "$bundle/option_coverage.csv")" \
          == "$(bundle_manifest_value "$bundle" option_rows)" ]] \
      || die "option coverage totals do not match the manifest"
    [[ "$(csv_record_count "$bundle/quote_coverage.csv")" \
          == "$(bundle_manifest_value "$bundle" market_snapshot_rows)" ]] \
      || die "quote coverage totals do not match the manifest"
    [[ "$(csv_record_count "$bundle/provider_request_budget.csv")" \
          == "$(bundle_manifest_value "$bundle" provider_request_budget_rows)" ]] \
      || die "provider request-budget totals do not match the manifest"
    [[ "$(csv_record_count "$bundle/provider_cooldown.csv")" \
          == "$(bundle_manifest_value "$bundle" provider_cooldown_rows)" ]] \
      || die "provider cooldown totals do not match the manifest"
    [[ "$(csv_record_count "$bundle/yahoo_coverage.csv")" \
          == "$(bundle_manifest_value "$bundle" yahoo_symbol_count)" ]] \
      || die "Yahoo symbol coverage does not match the manifest"
    [[ "$(csv_first_column_list "$bundle/yahoo_coverage.csv")" \
          == "$(bundle_manifest_value "$bundle" yahoo_symbols)" ]] \
      || die "Yahoo symbol identities do not match the manifest"
    [[ "$(csv_record_count "$bundle/option_coverage.csv")" \
          == "$(bundle_manifest_value "$bundle" option_symbol_count)" ]] \
      || die "option symbol coverage does not match the manifest"
    [[ "$(csv_first_column_list "$bundle/option_coverage.csv")" \
          == "$(bundle_manifest_value "$bundle" option_symbols)" ]] \
      || die "option symbol identities do not match the manifest"
    [[ "$(csv_first_column_list "$bundle/quote_coverage.csv")" \
          == "$(bundle_manifest_value "$bundle" market_snapshot_symbols)" ]] \
      || die "quote symbol identities do not match the manifest"
    if [[ -z "$(bundle_manifest_value "$bundle" yahoo_complete_through)" ]]; then
      printf 'WARNING: early format-3 manifest lacks explicit per-symbol Yahoo coverage facts; recapture it.\n' >&2
    else
      [[ "$(bundle_manifest_value "$bundle" yahoo_complete_through)" \
            == "$(coverage_min_latest "$bundle/yahoo_coverage.csv")" ]] \
        || die "Yahoo coherent-through date does not match per-symbol coverage"
      [[ "$(bundle_manifest_value "$bundle" yahoo_latest_session)" \
            == "$(coverage_max_latest "$bundle/yahoo_coverage.csv")" ]] \
        || die "Yahoo latest-session date does not match per-symbol coverage"
      [[ "$(bundle_manifest_value "$bundle" yahoo_latest_session_distribution)" \
            == "$(coverage_distribution "$bundle/yahoo_coverage.csv" 4)" ]] \
        || die "Yahoo latest-session distribution does not match per-symbol coverage"
      [[ "$(bundle_manifest_value "$bundle" yahoo_row_count_distribution)" \
            == "$(coverage_distribution "$bundle/yahoo_coverage.csv" 2)" ]] \
        || die "Yahoo row-count distribution does not match per-symbol coverage"
    fi
    awk -F, 'NR>1 && ($1=="" || $2 !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/ \
                    || $3 !~ /^[0-9]+$/ || $4 !~ /^[0-9]+$/ || $3<0 || $4<=0 || $3>$4) { bad=1 }
               END { exit bad }' "$bundle/provider_request_budget.csv" \
      || die "provider request-budget bundle contains invalid state"
    awk -F, 'NR>1 {
                 if ($1 !~ /^[a-z0-9_-]+_cooldown_until$/ || $2 !~ /^[0-9]+$/ \
                     || length($2)<10 || length($2)>16) bad=1
               } END { exit bad }' "$bundle/provider_cooldown.csv" \
      || die "provider cooldown bundle contains invalid state"
  fi

  for file in underlying_bar.csv option_bar.csv market_snapshot.csv; do
    expected="$(bundle_manifest_value "$bundle" "${file%.csv}_sha256")"
    [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || die "missing hash for $file"
    actual="$(file_hash "$bundle/$file")"
    [[ "$actual" == "$expected" ]] || die "$file does not match its manifest hash"
  done
  if [[ "$format" == "3" ]]; then
    for file in provider_request_budget.csv provider_cooldown.csv yahoo_coverage.csv \
                option_coverage.csv quote_coverage.csv; do
      expected="$(bundle_manifest_value "$bundle" "${file%.csv}_sha256")"
      [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || die "missing hash for $file"
      actual="$(file_hash "$bundle/$file")"
      [[ "$actual" == "$expected" ]] || die "$file does not match its manifest hash"
    done
    if [[ "$regression_override" == "true" ]]; then
      printf 'WARNING: this bundle was captured with the explicit regression override.\n' >&2
    else
      local declared_yahoo_contract required_yahoo_symbols
      declared_yahoo_contract="$(bundle_manifest_value "$bundle" canonical_yahoo_symbols)"
      if [[ -n "$declared_yahoo_contract" ]]; then
        assert_symbol_list_contains "canonical Yahoo contract" \
          "$declared_yahoo_contract" "$CANONICAL_YAHOO_SYMBOLS"
        required_yahoo_symbols="$CANONICAL_YAHOO_SYMBOLS"
      else
        # Format-3 bundles captured before the storage-theme expansion remain safely hydratable.
        # Every new capture writes the explicit contract below and must contain the expanded set.
        required_yahoo_symbols="$CANONICAL_YAHOO_LEGACY_SYMBOLS"
      fi
      assert_canonical_yahoo_coverage "$bundle/yahoo_coverage.csv" "$required_yahoo_symbols"
      assert_symbol_list_contains "option snapshot" \
        "$(bundle_manifest_value "$bundle" option_symbols)" "$IMPORTANT_OPTION_SYMBOLS"
      assert_symbol_list_contains "quote snapshot" \
        "$(bundle_manifest_value "$bundle" market_snapshot_symbols)" "$IMPORTANT_QUOTE_SYMBOLS"
    fi
  fi
}

assert_symbol_list_contains() {
  local label="$1" actual="$2" required="$3" missing
  missing="$(awk -v actual="$actual" -v required="$required" 'BEGIN {
    split(actual, have, ","); for (i in have) present[have[i]]=1;
    n=split(required, want, ",");
    for (i=1; i<=n; i++) if (!present[want[i]]) out=out (out?",":"") want[i];
    print out;
  }')"
  [[ -z "$missing" ]] || die "$label is missing required symbols: $missing"
}

assert_canonical_yahoo_coverage() {
  local coverage="$1" required="${2:-$CANONICAL_YAHOO_SYMBOLS}" required_count
  required_count="$(printf '%s\n' "$required" | awk -F, '{ print NF }')"
  awk -F, -v required="$required" \
      -v minimum_from="$CANONICAL_YAHOO_MIN_FROM" -v minimum_to="$CANONICAL_YAHOO_MIN_TO" \
      -v launch_minimums="$CANONICAL_YAHOO_LAUNCH_MINIMUMS" '
    BEGIN {
      count=split(launch_minimums, pairs, ",");
      for (i=1; i<=count; i++) {
        split(pairs[i], pair, ":");
        if (pair[1] != "" && pair[2] != "") launch[pair[1]]=pair[2];
      }
    }
    NR > 1 { rows[$1]=$2+0; from[$1]=$3; to[$1]=$4 }
    END {
      n=split(required, symbols, ","); failed=0;
      for (i=1; i<=n; i++) {
        s=symbols[i];
        if (!(s in rows)) { print "missing canonical Yahoo symbol " s > "/dev/stderr"; failed=1; continue }
        expected_from=(s in launch && launch[s] > minimum_from) ? launch[s] : minimum_from;
        if (rows[s] < 2 || from[s] > expected_from || to[s] < minimum_to) {
          print "regressed Yahoo coverage for " s ": rows=" rows[s] ", " from[s] ".." to[s] > "/dev/stderr";
          failed=1;
        }
      }
      exit failed;
    }
  ' "$coverage" || die "capture does not retain the canonical ${required_count}-symbol Yahoo history surface"
}

legacy_yahoo_coverage() {
  local bundle="$1" output="$2"
  {
    printf '%s\n' "$YAHOO_COVERAGE_HEADER"
    awk -F, 'NR>1 && $8=="yahoo" {
      symbol=$1; day=$2; count[symbol]++;
      if (!(symbol in first) || day < first[symbol]) first[symbol]=day;
      if (!(symbol in last) || day > last[symbol]) last[symbol]=day;
    } END { for (symbol in count) print symbol "," count[symbol] "," first[symbol] "," last[symbol] }' \
      "$bundle/underlying_bar.csv" | LC_ALL=C sort -t, -k1,1
  } > "$output"
}

assert_coverage_non_regressing() {
  local baseline="$1" candidate="$2" label="${3:-Yahoo}"
  awk -F, -v label="$label" '
    NR==FNR { if (FNR>1) { oldRows[$1]=$2+0; oldFrom[$1]=$3; oldTo[$1]=$4 } next }
    FNR>1 { newRows[$1]=$2+0; newFrom[$1]=$3; newTo[$1]=$4 }
    END {
      failed=0;
      for (s in oldRows) {
        if (!(s in newRows)) { print "lost " label " symbol " s > "/dev/stderr"; failed=1; continue }
        if (newRows[s] < oldRows[s] || newFrom[s] > oldFrom[s] || newTo[s] < oldTo[s]) {
          print label " coverage regressed for " s ": " oldRows[s] " " oldFrom[s] ".." oldTo[s]
                " -> " newRows[s] " " newFrom[s] ".." newTo[s] > "/dev/stderr";
          failed=1;
        }
      }
      exit failed;
    }
  ' "$baseline" "$candidate" || die "capture would replace the bundle with less $label history"
}

assert_quote_non_regressing() {
  local baseline="$1" candidate="$2"
  awk -F, '
    function freshnessRank(value) {
      return value=="REALTIME" ? 4 : value=="DELAYED" ? 3 : value=="EOD" ? 2 : value=="STALE" ? 1 : 0;
    }
    NR==FNR {
      if (FNR>1) {
        oldAsOf[$1]=$2; oldCaptured[$1]=$3; oldFreshness[$1]=$4;
      }
      next;
    }
    FNR>1 {
      newAsOf[$1]=$2; newCaptured[$1]=$3; newFreshness[$1]=$4;
    }
    END {
      failed=0;
      for (s in oldAsOf) {
        if (!(s in newAsOf)) {
          print "lost quote symbol " s > "/dev/stderr"; failed=1; continue;
        }
        if (newAsOf[s] < oldAsOf[s] || (newAsOf[s] == oldAsOf[s] &&
            (freshnessRank(newFreshness[s]) < freshnessRank(oldFreshness[s]) ||
             (freshnessRank(newFreshness[s]) == freshnessRank(oldFreshness[s]) &&
              newCaptured[s] < oldCaptured[s])))) {
          print "quote coverage regressed for " s ": " oldAsOf[s] " " oldFreshness[s]
                " -> " newAsOf[s] " " newFreshness[s] > "/dev/stderr";
          failed=1;
        }
      }
      exit failed;
    }
  ' "$baseline" "$candidate" || die "capture would replace the bundle with older or weaker quote coverage"
}

assert_budget_non_regressing() {
  local baseline="$1" candidate="$2"
  awk -F, '
    NR==FNR { if (FNR>1) old[$1 "|" $2]=$3+0; next }
    FNR>1 { fresh[$1 "|" $2]=$3+0 }
    END {
      failed=0;
      for (key in old) if (!(key in fresh) || fresh[key] < old[key]) {
        print "provider request usage regressed for " key > "/dev/stderr"; failed=1;
      }
      exit failed;
    }
  ' "$baseline" "$candidate" || die "capture would forget already-consumed provider requests"
}

assert_cooldown_non_regressing() {
  local baseline="$1" candidate="$2"
  awk -F, '
    NR==FNR { if (FNR>1) old[$1]=$2+0; next }
    FNR>1 { fresh[$1]=$2+0 }
    END {
      failed=0;
      for (key in old) if (!(key in fresh) || fresh[key] < old[key]) {
        print "provider cooldown state regressed for " key > "/dev/stderr"; failed=1;
      }
      exit failed;
    }
  ' "$baseline" "$candidate" || die "capture would forget a persisted provider cooldown"
}

assert_capture_non_regressing() {
  local candidate="$1" baseline="${2:-}" prior_yahoo=""
  local candidate_options candidate_quotes prior_format
  candidate_options="$(bundle_manifest_value "$candidate" option_symbols)"
  candidate_quotes="$(bundle_manifest_value "$candidate" market_snapshot_symbols)"
  assert_canonical_yahoo_coverage "$candidate/yahoo_coverage.csv"
  assert_symbol_list_contains "option snapshot" "$candidate_options" "$IMPORTANT_OPTION_SYMBOLS"
  assert_symbol_list_contains "quote snapshot" "$candidate_quotes" "$IMPORTANT_QUOTE_SYMBOLS"

  [[ -n "$baseline" ]] || return 0
  prior_format="$(bundle_manifest_value "$baseline" format)"
  prior_yahoo="$(mktemp "${candidate}.prior-yahoo.XXXXXX")"
  if [[ "$prior_format" == "3" ]]; then
    cp "$baseline/yahoo_coverage.csv" "$prior_yahoo"
  else
    legacy_yahoo_coverage "$baseline" "$prior_yahoo"
  fi
  assert_coverage_non_regressing "$prior_yahoo" "$candidate/yahoo_coverage.csv"
  rm -f "$prior_yahoo"

  assert_symbol_list_contains "option snapshot" "$candidate_options" \
    "$(bundle_manifest_value "$baseline" option_symbols)"
  assert_symbol_list_contains "quote snapshot" "$candidate_quotes" \
    "$(bundle_manifest_value "$baseline" market_snapshot_symbols)"
  (( $(bundle_manifest_value "$candidate" option_rows) >= $(bundle_manifest_value "$baseline" option_rows) )) \
    || die "capture would discard observed option rows from the prior good bundle"
  [[ "$(bundle_manifest_value "$candidate" option_to)" < "$(bundle_manifest_value "$baseline" option_to)" ]] \
    && die "capture would move option coverage backward"
  if [[ "$prior_format" == "3" ]]; then
    assert_coverage_non_regressing "$baseline/option_coverage.csv" \
      "$candidate/option_coverage.csv" "option"
    assert_quote_non_regressing "$baseline/quote_coverage.csv" \
      "$candidate/quote_coverage.csv"
    assert_budget_non_regressing "$baseline/provider_request_budget.csv" \
      "$candidate/provider_request_budget.csv"
    assert_cooldown_non_regressing "$baseline/provider_cooldown.csv" \
      "$candidate/provider_cooldown.csv"
  fi
}

capture() {
  local schema table_contract operational_contract work container_dir facts_header
  local captured_at underlying_rows yahoo_rows option_rows market_rows budget_rows cooldown_rows
  local underlying_from underlying_to option_from option_to underlying_sources market_sources
  local underlying_symbol_count underlying_symbols yahoo_symbol_count yahoo_symbols
  local option_symbol_count option_symbols market_symbol_count market_symbols baseline archive version_root version_id
  local yahoo_complete_through yahoo_latest_session yahoo_latest_session_distribution yahoo_row_count_distribution
  schema="$(assert_dev_database)"
  [[ "$SNAPSHOT_ALLOW_REGRESSION" == "true" || "$SNAPSHOT_ALLOW_REGRESSION" == "false" ]] \
    || die "SNAPSHOT_ALLOW_REGRESSION must be true or false"
  table_contract="$(snapshot_table_contract_fingerprint)"
  [[ "$table_contract" == "$FORMAT2_TABLE_CONTRACT_SHA256" ]] \
    || die "the running market-data tables no longer match snapshot format 2"
  operational_contract="$(snapshot_operational_contract_fingerprint)"
  [[ "$operational_contract" == "$FORMAT3_OPERATIONAL_CONTRACT_SHA256" ]] \
    || die "the running operational-state tables no longer match snapshot format 3"

  baseline=""
  if [[ -e "$BUNDLE_DIR" || -L "$BUNDLE_DIR" ]]; then
    verify_bundle_in "$BUNDLE_DIR"
    baseline="$BUNDLE_DIR"
  fi
  mkdir -p "$(dirname "$BUNDLE_DIR")"
  work="$(mktemp -d "${BUNDLE_DIR}.capture.XXXXXX")"
  container_dir="/tmp/strikebench-dev-market-capture-$$"
  trap 'rm -rf "${work:-}"; docker exec "$DB_CONTAINER" rm -rf -- "${container_dir:-}" >/dev/null 2>&1 || true' EXIT
  docker exec "$DB_CONTAINER" mkdir -p "$container_dir"

  # All exports and manifest facts share one read-only repeatable-read database snapshot. A writer
  # may continue after this transaction starts, but no file can observe a different point in time.
  psql_exec -q <<SQL
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT symbol,d,open,high,low,close,volume,source,observed,created_at,dataset_id,adjusted,quality_rank,bar_kind FROM public.underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot') ORDER BY symbol,d,source) TO '$container_dir/underlying_bar.csv' WITH (FORMAT CSV, HEADER TRUE)
\copy (SELECT symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,theta,vega,open_interest,volume,underlying,source,bid_ask_observed,iv_source,greeks_source,created_at,dataset_id FROM public.option_bar WHERE dataset_id='observed' AND source='snapshot' ORDER BY symbol,asof,expiration,strike,opt_type) TO '$container_dir/option_bar.csv' WITH (FORMAT CSV, HEADER TRUE)
\copy (SELECT symbol,description,last,bid,ask,prev_close,optionable,source,freshness,as_of,captured_at FROM public.market_snapshot WHERE last IS NOT NULL AND coalesce(lower(source),'') NOT LIKE '%fixture%' AND coalesce(lower(source),'') NOT LIKE '%demo%' AND coalesce(lower(source),'') NOT LIKE '%simulat%' AND coalesce(lower(source),'') NOT LIKE '%model%' AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED') ORDER BY symbol) TO '$container_dir/market_snapshot.csv' WITH (FORMAT CSV, HEADER TRUE)
\copy (SELECT source_key,period_key,used_count,limit_count,updated_at FROM public.provider_request_budget ORDER BY source_key,period_key) TO '$container_dir/provider_request_budget.csv' WITH (FORMAT CSV, HEADER TRUE)
\copy (SELECT k,v,updated_at FROM public.settings WHERE k ~ '^[a-z0-9_-]+_cooldown_until$' ORDER BY k) TO '$container_dir/provider_cooldown.csv' WITH (FORMAT CSV, HEADER TRUE)
\copy (SELECT symbol,count(*) AS row_count,min(d) AS from_date,max(d) AS to_date FROM public.underlying_bar WHERE dataset_id='observed' AND observed=1 AND source='yahoo' GROUP BY symbol ORDER BY symbol) TO '$container_dir/yahoo_coverage.csv' WITH (FORMAT CSV, HEADER TRUE)
\copy (SELECT symbol,count(*) AS row_count,min(asof) AS from_date,max(asof) AS to_date FROM public.option_bar WHERE dataset_id='observed' AND source='snapshot' GROUP BY symbol ORDER BY symbol) TO '$container_dir/option_coverage.csv' WITH (FORMAT CSV, HEADER TRUE)
\copy (SELECT symbol,as_of,captured_at,freshness,source FROM public.market_snapshot WHERE last IS NOT NULL AND coalesce(lower(source),'') NOT LIKE '%fixture%' AND coalesce(lower(source),'') NOT LIKE '%demo%' AND coalesce(lower(source),'') NOT LIKE '%simulat%' AND coalesce(lower(source),'') NOT LIKE '%model%' AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED') ORDER BY symbol) TO '$container_dir/quote_coverage.csv' WITH (FORMAT CSV, HEADER TRUE)
\copy (SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS captured_at,(SELECT count(*) FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')) AS underlying_rows,(SELECT count(*) FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source='yahoo') AS yahoo_rows,(SELECT count(*) FROM option_bar WHERE dataset_id='observed' AND source='snapshot') AS option_rows,(SELECT count(*) FROM market_snapshot WHERE last IS NOT NULL AND coalesce(lower(source),'') NOT LIKE '%fixture%' AND coalesce(lower(source),'') NOT LIKE '%demo%' AND coalesce(lower(source),'') NOT LIKE '%simulat%' AND coalesce(lower(source),'') NOT LIKE '%model%' AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')) AS market_rows,(SELECT count(*) FROM provider_request_budget) AS budget_rows,(SELECT count(*) FROM settings WHERE k ~ '^[a-z0-9_-]+_cooldown_until$') AS cooldown_rows,(SELECT coalesce(min(d)::text,'') FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')) AS underlying_from,(SELECT coalesce(max(d)::text,'') FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')) AS underlying_to,(SELECT coalesce(min(asof)::text,'') FROM option_bar WHERE dataset_id='observed' AND source='snapshot') AS option_from,(SELECT coalesce(max(asof)::text,'') FROM option_bar WHERE dataset_id='observed' AND source='snapshot') AS option_to,(SELECT coalesce(string_agg(DISTINCT source,',' ORDER BY source),'') FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')) AS underlying_sources,(SELECT coalesce(string_agg(DISTINCT source,',' ORDER BY source),'') FROM market_snapshot WHERE last IS NOT NULL AND coalesce(lower(source),'') NOT LIKE '%fixture%' AND coalesce(lower(source),'') NOT LIKE '%demo%' AND coalesce(lower(source),'') NOT LIKE '%simulat%' AND coalesce(lower(source),'') NOT LIKE '%model%' AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')) AS market_sources,(SELECT count(DISTINCT symbol) FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')) AS underlying_symbol_count,(SELECT coalesce(string_agg(DISTINCT symbol,',' ORDER BY symbol),'') FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')) AS underlying_symbols,(SELECT count(DISTINCT symbol) FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source='yahoo') AS yahoo_symbol_count,(SELECT coalesce(string_agg(DISTINCT symbol,',' ORDER BY symbol),'') FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source='yahoo') AS yahoo_symbols,(SELECT count(DISTINCT symbol) FROM option_bar WHERE dataset_id='observed' AND source='snapshot') AS option_symbol_count,(SELECT coalesce(string_agg(DISTINCT symbol,',' ORDER BY symbol),'') FROM option_bar WHERE dataset_id='observed' AND source='snapshot') AS option_symbols,(SELECT count(DISTINCT symbol) FROM market_snapshot WHERE last IS NOT NULL AND coalesce(lower(source),'') NOT LIKE '%fixture%' AND coalesce(lower(source),'') NOT LIKE '%demo%' AND coalesce(lower(source),'') NOT LIKE '%simulat%' AND coalesce(lower(source),'') NOT LIKE '%model%' AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')) AS market_symbol_count,(SELECT coalesce(string_agg(DISTINCT symbol,',' ORDER BY symbol),'') FROM market_snapshot WHERE last IS NOT NULL AND coalesce(lower(source),'') NOT LIKE '%fixture%' AND coalesce(lower(source),'') NOT LIKE '%demo%' AND coalesce(lower(source),'') NOT LIKE '%simulat%' AND coalesce(lower(source),'') NOT LIKE '%model%' AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')) AS market_symbols) TO '$container_dir/facts.psv' WITH (FORMAT CSV, HEADER TRUE, DELIMITER '|')
COMMIT;
SQL

  for file in underlying_bar.csv option_bar.csv market_snapshot.csv provider_request_budget.csv \
              provider_cooldown.csv yahoo_coverage.csv option_coverage.csv quote_coverage.csv facts.psv; do
    docker cp "$DB_CONTAINER:$container_dir/$file" "$work/$file" >/dev/null
  done
  docker exec "$DB_CONTAINER" rm -rf -- "$container_dir"

  IFS= read -r facts_header < "$work/facts.psv"
  [[ "$facts_header" == captured_at'|'underlying_rows'|'yahoo_rows'|'option_rows'|'market_rows'|'budget_rows'|'cooldown_rows'|'underlying_from'|'underlying_to'|'option_from'|'option_to'|'underlying_sources'|'market_sources'|'underlying_symbol_count'|'underlying_symbols'|'yahoo_symbol_count'|'yahoo_symbols'|'option_symbol_count'|'option_symbols'|'market_symbol_count'|'market_symbols ]] \
    || die "repeatable-read capture facts do not have the expected contract"
  IFS='|' read -r captured_at underlying_rows yahoo_rows option_rows market_rows budget_rows cooldown_rows \
    underlying_from underlying_to option_from option_to underlying_sources market_sources \
    underlying_symbol_count underlying_symbols yahoo_symbol_count yahoo_symbols option_symbol_count \
    option_symbols market_symbol_count market_symbols < <(tail -n 1 "$work/facts.psv")
  rm -f "$work/facts.psv"
  yahoo_complete_through="$(coverage_min_latest "$work/yahoo_coverage.csv")"
  yahoo_latest_session="$(coverage_max_latest "$work/yahoo_coverage.csv")"
  yahoo_latest_session_distribution="$(coverage_distribution "$work/yahoo_coverage.csv" 4)"
  yahoo_row_count_distribution="$(coverage_distribution "$work/yahoo_coverage.csv" 2)"
  assert_uint underlying_rows "$underlying_rows"
  assert_uint yahoo_rows "$yahoo_rows"
  assert_uint option_rows "$option_rows"
  assert_uint market_snapshot_rows "$market_rows"
  assert_uint provider_request_budget_rows "$budget_rows"
  assert_uint provider_cooldown_rows "$cooldown_rows"
  (( underlying_rows > 0 )) || die "no observed Yahoo/snapshot underlying rows are available"
  (( yahoo_rows > 0 )) || die "no Yahoo history is available; run sync_underlying with source yahoo first"
  (( option_rows > 0 )) || die "no observed forward option snapshot is available; run snapshot_now first"
  (( market_rows > 0 )) || die "no observed quote snapshots are available"

  {
    printf 'format=3\n'
    printf 'database=strikebench_dev\n'
    printf 'schema_sha256=%s\n' "$schema"
    printf 'snapshot_table_contract_sha256=%s\n' "$table_contract"
    printf 'operational_table_contract_sha256=%s\n' "$operational_contract"
    printf 'regression_override=%s\n' "$SNAPSHOT_ALLOW_REGRESSION"
    printf 'captured_at=%s\n' "$captured_at"
    printf 'underlying_rows=%s\n' "$underlying_rows"
    printf 'yahoo_rows=%s\n' "$yahoo_rows"
    printf 'underlying_from=%s\n' "$underlying_from"
    printf 'underlying_to=%s\n' "$underlying_to"
    printf 'underlying_sources=%s\n' "$underlying_sources"
    printf 'underlying_symbol_count=%s\n' "$underlying_symbol_count"
    printf 'underlying_symbols=%s\n' "$underlying_symbols"
    printf 'yahoo_symbol_count=%s\n' "$yahoo_symbol_count"
    printf 'yahoo_symbols=%s\n' "$yahoo_symbols"
    printf 'canonical_yahoo_symbols=%s\n' "$CANONICAL_YAHOO_SYMBOLS"
    printf 'yahoo_complete_through=%s\n' "$yahoo_complete_through"
    printf 'yahoo_latest_session=%s\n' "$yahoo_latest_session"
    printf 'yahoo_latest_session_distribution=%s\n' "$yahoo_latest_session_distribution"
    printf 'yahoo_row_count_distribution=%s\n' "$yahoo_row_count_distribution"
    printf 'option_rows=%s\n' "$option_rows"
    printf 'option_from=%s\n' "$option_from"
    printf 'option_to=%s\n' "$option_to"
    printf 'option_sources=snapshot\n'
    printf 'option_symbol_count=%s\n' "$option_symbol_count"
    printf 'option_symbols=%s\n' "$option_symbols"
    printf 'market_snapshot_rows=%s\n' "$market_rows"
    printf 'market_snapshot_sources=%s\n' "$market_sources"
    printf 'market_snapshot_symbol_count=%s\n' "$market_symbol_count"
    printf 'market_snapshot_symbols=%s\n' "$market_symbols"
    printf 'provider_request_budget_rows=%s\n' "$budget_rows"
    printf 'provider_cooldown_rows=%s\n' "$cooldown_rows"
    printf 'underlying_bar_sha256=%s\n' "$(file_hash "$work/underlying_bar.csv")"
    printf 'option_bar_sha256=%s\n' "$(file_hash "$work/option_bar.csv")"
    printf 'market_snapshot_sha256=%s\n' "$(file_hash "$work/market_snapshot.csv")"
    printf 'provider_request_budget_sha256=%s\n' "$(file_hash "$work/provider_request_budget.csv")"
    printf 'provider_cooldown_sha256=%s\n' "$(file_hash "$work/provider_cooldown.csv")"
    printf 'yahoo_coverage_sha256=%s\n' "$(file_hash "$work/yahoo_coverage.csv")"
    printf 'option_coverage_sha256=%s\n' "$(file_hash "$work/option_coverage.csv")"
    printf 'quote_coverage_sha256=%s\n' "$(file_hash "$work/quote_coverage.csv")"
  } > "$work/manifest.txt"

  verify_bundle_in "$work"
  if [[ "$SNAPSHOT_ALLOW_REGRESSION" == "true" ]]; then
    printf 'WARNING: SNAPSHOT_ALLOW_REGRESSION=true; capture regression guards were explicitly bypassed.\n' >&2
  elif [[ "$SNAPSHOT_ALLOW_REGRESSION" == "false" ]]; then
    assert_capture_non_regressing "$work" "$baseline"
  fi

  # Install only a complete verified directory. The prior good bundle is versioned instead of
  # overwritten, so an interrupted or mistaken recapture remains immediately recoverable.
  if [[ -n "$baseline" ]]; then
    version_root="${BUNDLE_DIR}.versions"
    mkdir -p "$version_root"
    version_id="$(bundle_manifest_value "$BUNDLE_DIR" captured_at | tr -cd '0-9TZ')-$(file_hash "$BUNDLE_DIR/manifest.txt" | cut -c1-12)"
    archive="$version_root/$version_id"
    [[ ! -e "$archive" ]] || archive="$archive-$$"
    mv "$BUNDLE_DIR" "$archive"
    if ! mv "$work" "$BUNDLE_DIR"; then
      mv "$archive" "$BUNDLE_DIR"
      die "could not install the verified capture; the prior bundle was restored"
    fi
    printf 'Previous verified bundle retained at %s\n' "$archive"
  else
    mv "$work" "$BUNDLE_DIR"
  fi
  trap - EXIT
  printf 'Captured %s Yahoo/snapshot underlying rows, %s option rows, and %s quote snapshots in %s\n' \
    "$underlying_rows" "$option_rows" "$market_rows" "$BUNDLE_DIR"
}

hydrate() {
  local container_dir expected_underlying expected_options expected_market expected_budget expected_cooldown
  local format compat_dir=""
  verify_bundle
  format="$(manifest_value format)"
  expected_underlying="$(manifest_value underlying_rows)"
  expected_options="$(manifest_value option_rows)"
  expected_market="$(manifest_value market_snapshot_rows)"
  expected_budget=0
  expected_cooldown=0
  if [[ "$format" == "3" ]]; then
    expected_budget="$(manifest_value provider_request_budget_rows)"
    expected_cooldown="$(manifest_value provider_cooldown_rows)"
  else
    compat_dir="$(mktemp -d "${BUNDLE_DIR}.hydrate-legacy.XXXXXX")"
    printf '%s\n' "$BUDGET_HEADER" > "$compat_dir/provider_request_budget.csv"
    printf '%s\n' "$COOLDOWN_HEADER" > "$compat_dir/provider_cooldown.csv"
  fi
  container_dir="/tmp/strikebench-dev-market-snapshot-$$"
  docker exec "$DB_CONTAINER" mkdir -p "$container_dir"
  trap 'docker exec "$DB_CONTAINER" rm -rf -- "$container_dir" >/dev/null 2>&1 || true; [[ -z "${compat_dir:-}" ]] || rm -rf -- "$compat_dir"' EXIT
  docker cp "$BUNDLE_DIR/underlying_bar.csv" "$DB_CONTAINER:$container_dir/underlying_bar.csv" >/dev/null
  docker cp "$BUNDLE_DIR/option_bar.csv" "$DB_CONTAINER:$container_dir/option_bar.csv" >/dev/null
  docker cp "$BUNDLE_DIR/market_snapshot.csv" "$DB_CONTAINER:$container_dir/market_snapshot.csv" >/dev/null
  if [[ "$format" == "3" ]]; then
    docker cp "$BUNDLE_DIR/provider_request_budget.csv" "$DB_CONTAINER:$container_dir/provider_request_budget.csv" >/dev/null
    docker cp "$BUNDLE_DIR/provider_cooldown.csv" "$DB_CONTAINER:$container_dir/provider_cooldown.csv" >/dev/null
  else
    docker cp "$compat_dir/provider_request_budget.csv" "$DB_CONTAINER:$container_dir/provider_request_budget.csv" >/dev/null
    docker cp "$compat_dir/provider_cooldown.csv" "$DB_CONTAINER:$container_dir/provider_cooldown.csv" >/dev/null
  fi

  psql_exec -q <<SQL
BEGIN;

CREATE TEMP TABLE dev_underlying ON COMMIT DROP AS
  SELECT symbol,d,open,high,low,close,volume,source,observed,created_at,dataset_id,
         adjusted,quality_rank,bar_kind
    FROM public.underlying_bar WITH NO DATA;
COPY dev_underlying(symbol,d,open,high,low,close,volume,source,observed,created_at,dataset_id,
                    adjusted,quality_rank,bar_kind)
  FROM '$container_dir/underlying_bar.csv' WITH (FORMAT CSV, HEADER TRUE);

CREATE TEMP TABLE dev_options ON COMMIT DROP AS
  SELECT symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,theta,vega,
         open_interest,volume,underlying,source,bid_ask_observed,iv_source,greeks_source,
         created_at,dataset_id
    FROM public.option_bar WITH NO DATA;
COPY dev_options(symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,theta,vega,
                 open_interest,volume,underlying,source,bid_ask_observed,iv_source,greeks_source,
                 created_at,dataset_id)
  FROM '$container_dir/option_bar.csv' WITH (FORMAT CSV, HEADER TRUE);

CREATE TEMP TABLE dev_quotes ON COMMIT DROP AS
  SELECT symbol,description,last,bid,ask,prev_close,optionable,source,freshness,as_of,captured_at
    FROM public.market_snapshot WITH NO DATA;
COPY dev_quotes(symbol,description,last,bid,ask,prev_close,optionable,source,freshness,as_of,captured_at)
  FROM '$container_dir/market_snapshot.csv' WITH (FORMAT CSV, HEADER TRUE);

CREATE TEMP TABLE dev_budget ON COMMIT DROP AS
  SELECT source_key,period_key,used_count,limit_count,updated_at
    FROM public.provider_request_budget WITH NO DATA;
COPY dev_budget(source_key,period_key,used_count,limit_count,updated_at)
  FROM '$container_dir/provider_request_budget.csv' WITH (FORMAT CSV, HEADER TRUE);

CREATE TEMP TABLE dev_cooldown ON COMMIT DROP AS
  SELECT k,v,updated_at FROM public.settings WITH NO DATA;
COPY dev_cooldown(k,v,updated_at)
  FROM '$container_dir/provider_cooldown.csv' WITH (FORMAT CSV, HEADER TRUE);

DO \$\$
BEGIN
  IF (SELECT count(*) FROM dev_underlying) <> $expected_underlying THEN
    RAISE EXCEPTION 'underlying snapshot row count does not match manifest';
  END IF;
  IF EXISTS (SELECT 1 FROM dev_underlying
              WHERE dataset_id <> 'observed' OR observed <> 1 OR source NOT IN ('yahoo','snapshot')
                 OR close IS NULL OR close <= 0) THEN
    RAISE EXCEPTION 'underlying snapshot contains non-observed, unexpected-source, or invalid rows';
  END IF;
  IF (SELECT count(*) FROM dev_options) <> $expected_options THEN
    RAISE EXCEPTION 'option snapshot row count does not match manifest';
  END IF;
  IF EXISTS (SELECT 1 FROM dev_options
              WHERE dataset_id <> 'observed' OR source <> 'snapshot'
                 OR bid_ask_observed NOT IN (0,1)
                 OR coalesce(iv_source,'vendor') <> 'vendor'
                 OR coalesce(greeks_source,'vendor') <> 'vendor') THEN
    RAISE EXCEPTION 'option snapshot contains non-observed or modeled provenance';
  END IF;
  IF (SELECT count(*) FROM dev_quotes) <> $expected_market THEN
    RAISE EXCEPTION 'quote snapshot row count does not match manifest';
  END IF;
  IF EXISTS (SELECT 1 FROM dev_quotes
              WHERE last IS NULL OR as_of IS NULL OR source IS NULL OR btrim(source) = ''
                 OR lower(source) LIKE '%fixture%' OR lower(source) LIKE '%demo%'
                 OR lower(source) LIKE '%simulat%' OR lower(source) LIKE '%model%'
                 OR coalesce(freshness,'') IN ('FIXTURE','SIMULATED','MODELED')) THEN
    RAISE EXCEPTION 'quote snapshot contains missing or generated provenance';
  END IF;
  IF (SELECT count(*) FROM dev_budget) <> $expected_budget THEN
    RAISE EXCEPTION 'provider request-budget row count does not match manifest';
  END IF;
  IF EXISTS (SELECT 1 FROM dev_budget
              WHERE source_key IS NULL OR btrim(source_key) = '' OR used_count < 0
                 OR limit_count <= 0 OR period_key IS NULL OR used_count > limit_count) THEN
    RAISE EXCEPTION 'provider request-budget snapshot contains invalid state';
  END IF;
  IF (SELECT count(*) FROM dev_cooldown) <> $expected_cooldown THEN
    RAISE EXCEPTION 'provider cooldown row count does not match manifest';
  END IF;
  IF EXISTS (SELECT 1 FROM dev_cooldown
              WHERE k !~ '^[a-z0-9_-]+_cooldown_until$' OR v !~ '^[0-9]{10,16}$') THEN
    RAISE EXCEPTION 'provider cooldown snapshot contains an invalid key or epoch';
  END IF;
END \$\$;

INSERT INTO public.underlying_bar(symbol,d,open,high,low,close,volume,source,observed,created_at,
                                  dataset_id,adjusted,quality_rank,bar_kind)
SELECT symbol,d,open,high,low,close,volume,source,observed,created_at,dataset_id,
       adjusted,quality_rank,bar_kind FROM dev_underlying
ON CONFLICT (symbol,d,source,dataset_id) DO UPDATE SET
  open=excluded.open,high=excluded.high,low=excluded.low,close=excluded.close,
  volume=excluded.volume,observed=excluded.observed,created_at=excluded.created_at,
  adjusted=excluded.adjusted,quality_rank=excluded.quality_rank,bar_kind=excluded.bar_kind
WHERE excluded.quality_rank > underlying_bar.quality_rank
   OR (excluded.quality_rank = underlying_bar.quality_rank
       AND excluded.created_at > underlying_bar.created_at);

INSERT INTO public.option_bar(symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,
                              theta,vega,open_interest,volume,underlying,source,bid_ask_observed,
                              iv_source,greeks_source,created_at,dataset_id)
SELECT symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,theta,vega,
       open_interest,volume,underlying,source,bid_ask_observed,iv_source,greeks_source,created_at,dataset_id
  FROM dev_options
ON CONFLICT (symbol,asof,expiration,strike,opt_type,source,dataset_id) DO UPDATE SET
  bid=excluded.bid,ask=excluded.ask,last=excluded.last,mark=excluded.mark,iv=excluded.iv,
  delta=excluded.delta,gamma=excluded.gamma,theta=excluded.theta,vega=excluded.vega,
  open_interest=excluded.open_interest,volume=excluded.volume,underlying=excluded.underlying,
  bid_ask_observed=excluded.bid_ask_observed,iv_source=excluded.iv_source,
  greeks_source=excluded.greeks_source,created_at=excluded.created_at
WHERE (excluded.bid_ask_observed * 100
       + CASE WHEN excluded.iv_source='vendor' THEN 10 ELSE 0 END
       + CASE WHEN excluded.greeks_source='vendor' THEN 1 ELSE 0 END)
      > (option_bar.bid_ask_observed * 100
         + CASE WHEN option_bar.iv_source='vendor' THEN 10 ELSE 0 END
         + CASE WHEN option_bar.greeks_source='vendor' THEN 1 ELSE 0 END)
   OR ((excluded.bid_ask_observed * 100
        + CASE WHEN excluded.iv_source='vendor' THEN 10 ELSE 0 END
        + CASE WHEN excluded.greeks_source='vendor' THEN 1 ELSE 0 END)
       = (option_bar.bid_ask_observed * 100
          + CASE WHEN option_bar.iv_source='vendor' THEN 10 ELSE 0 END
          + CASE WHEN option_bar.greeks_source='vendor' THEN 1 ELSE 0 END)
       AND excluded.created_at > option_bar.created_at);

INSERT INTO public.market_snapshot(symbol,description,last,bid,ask,prev_close,optionable,source,
                                   freshness,as_of,captured_at)
SELECT symbol,description,last,bid,ask,prev_close,optionable,source,freshness,as_of,captured_at
  FROM dev_quotes
ON CONFLICT (symbol) DO UPDATE SET
  description=excluded.description,last=excluded.last,bid=excluded.bid,ask=excluded.ask,
  prev_close=excluded.prev_close,optionable=excluded.optionable,source=excluded.source,
  freshness=excluded.freshness,as_of=excluded.as_of,captured_at=excluded.captured_at
WHERE market_snapshot.as_of IS NULL
   OR excluded.as_of > market_snapshot.as_of
   OR (excluded.as_of = market_snapshot.as_of
       AND (CASE excluded.freshness
              WHEN 'REALTIME' THEN 4 WHEN 'DELAYED' THEN 3 WHEN 'EOD' THEN 2 WHEN 'STALE' THEN 1 ELSE 0 END)
           >= (CASE market_snapshot.freshness
                 WHEN 'REALTIME' THEN 4 WHEN 'DELAYED' THEN 3 WHEN 'EOD' THEN 2 WHEN 'STALE' THEN 1 ELSE 0 END)
       AND excluded.captured_at > market_snapshot.captured_at);

INSERT INTO public.provider_request_budget(source_key,period_key,used_count,limit_count,updated_at)
SELECT source_key,period_key,used_count,limit_count,updated_at FROM dev_budget
ON CONFLICT (source_key,period_key) DO UPDATE SET
  used_count=GREATEST(provider_request_budget.used_count,excluded.used_count),
  limit_count=LEAST(provider_request_budget.limit_count,excluded.limit_count),
  updated_at=GREATEST(provider_request_budget.updated_at,excluded.updated_at);

INSERT INTO public.settings(k,v,updated_at)
SELECT k,v,updated_at FROM dev_cooldown
ON CONFLICT (k) DO UPDATE SET
  v=GREATEST(settings.v::numeric,excluded.v::numeric)::bigint::text,
  updated_at=GREATEST(settings.updated_at,excluded.updated_at);

COMMIT;
SQL

  docker exec "$DB_CONTAINER" rm -rf -- "$container_dir"
  if [[ -n "$compat_dir" ]]; then
    rm -rf -- "$compat_dir"
  fi
  trap - EXIT
  printf 'Hydrated %s underlying rows, %s option rows, and %s quote snapshots into strikebench_dev.\n' \
    "$expected_underlying" "$expected_options" "$expected_market"
  if [[ "$format" == "3" ]]; then
    printf 'Preserved %s provider request-budget rows and %s persisted cooldown rows without lowering either state.\n' \
      "$expected_budget" "$expected_cooldown"
  fi
  printf 'Bundle schema provenance: %s; hydrated schema: %s (matching versioned data surfaces).\n' \
    "$(manifest_value schema_sha256)" "$(assert_dev_database)"
  printf 'Restart the application so in-memory caches load the saved quotes as STALE and re-read stored history.\n'
}

main() {
  case "$ACTION" in
    capture)
      require_tools
      capture
      ;;
    verify)
      require_tools
      verify_bundle
      printf 'Snapshot bundle is intact and data-surface compatible (format %s): %s\n' \
        "$(manifest_value format)" "$BUNDLE_DIR"
      printf 'Captured schema: %s; running schema: %s\n' \
        "$(manifest_value schema_sha256)" "$(assert_dev_database)"
      ;;
    hydrate)
      require_tools
      hydrate
      ;;
    *) usage ;;
  esac
}

if [[ "${BASH_SOURCE[0]:-}" == "$0" ]]; then
  main
fi
