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
  sed -n "s/^${key}=//p" "$BUNDLE_DIR/manifest.txt" | tail -n 1
}

file_hash() {
  shasum -a 256 "$1" | awk '{print $1}'
}

assert_uint() {
  [[ "$2" =~ ^[0-9]+$ ]] || die "manifest field '$1' is not an unsigned integer"
}

verify_bundle() {
  local manifest_schema current_schema file expected actual key
  for file in manifest.txt underlying_bar.csv option_bar.csv market_snapshot.csv; do
    [[ -f "$BUNDLE_DIR/$file" ]] || die "missing bundle file: $BUNDLE_DIR/$file"
  done
  [[ "$(manifest_value format)" == "1" ]] || die "unsupported snapshot bundle format"
  [[ "$(manifest_value database)" == "strikebench_dev" ]] \
    || die "snapshot was not captured from strikebench_dev"

  manifest_schema="$(manifest_value schema_sha256)"
  [[ "$manifest_schema" =~ ^[0-9a-f]{64}$ ]] || die "invalid schema fingerprint in manifest"
  current_schema="$(assert_dev_database)"
  [[ "$manifest_schema" == "$current_schema" ]] \
    || die "snapshot schema does not match the running development schema; recapture it"

  for key in underlying_rows yahoo_rows option_rows market_snapshot_rows; do
    assert_uint "$key" "$(manifest_value "$key")"
  done
  (( $(manifest_value underlying_rows) > 0 )) || die "snapshot has no observed underlying rows"
  (( $(manifest_value yahoo_rows) > 0 )) || die "snapshot has no Yahoo history rows"
  (( $(manifest_value option_rows) > 0 )) || die "snapshot has no observed option-chain rows"
  (( $(manifest_value market_snapshot_rows) > 0 )) || die "snapshot has no observed quote snapshots"

  for file in underlying_bar.csv option_bar.csv market_snapshot.csv; do
    expected="$(manifest_value "${file%.csv}_sha256")"
    [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || die "missing hash for $file"
    actual="$(file_hash "$BUNDLE_DIR/$file")"
    [[ "$actual" == "$expected" ]] || die "$file does not match its manifest hash"
  done
}

capture() {
  local schema work captured_at underlying_rows yahoo_rows option_rows market_rows
  local underlying_from underlying_to option_from option_to underlying_sources market_sources
  schema="$(assert_dev_database)"
  mkdir -p "$(dirname "$BUNDLE_DIR")"
  work="$(mktemp -d "${BUNDLE_DIR}.capture.XXXXXX")"
  trap 'rm -rf "${work:-}"' EXIT

  psql_exec -qAt -c "COPY (
    SELECT symbol,d,open,high,low,close,volume,source,observed,created_at,dataset_id,
           adjusted,quality_rank,bar_kind
      FROM public.underlying_bar
     WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')
     ORDER BY symbol,d,source
  ) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)" > "$work/underlying_bar.csv"

  psql_exec -qAt -c "COPY (
    SELECT symbol,asof,expiration,strike,opt_type,bid,ask,last,mark,iv,delta,gamma,theta,vega,
           open_interest,volume,underlying,source,bid_ask_observed,iv_source,greeks_source,
           created_at,dataset_id
      FROM public.option_bar
     WHERE dataset_id='observed' AND source='snapshot'
     ORDER BY symbol,asof,expiration,strike,opt_type
  ) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)" > "$work/option_bar.csv"

  psql_exec -qAt -c "COPY (
    SELECT symbol,description,last,bid,ask,prev_close,optionable,source,freshness,as_of,captured_at
      FROM public.market_snapshot
     WHERE last IS NOT NULL
       AND coalesce(lower(source),'') NOT LIKE '%fixture%'
       AND coalesce(lower(source),'') NOT LIKE '%demo%'
       AND coalesce(lower(source),'') NOT LIKE '%simulat%'
       AND coalesce(lower(source),'') NOT LIKE '%model%'
       AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')
     ORDER BY symbol
  ) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)" > "$work/market_snapshot.csv"

  underlying_rows="$(scalar "SELECT count(*) FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')")"
  yahoo_rows="$(scalar "SELECT count(*) FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source='yahoo'")"
  option_rows="$(scalar "SELECT count(*) FROM option_bar WHERE dataset_id='observed' AND source='snapshot'")"
  market_rows="$(scalar "SELECT count(*) FROM market_snapshot WHERE last IS NOT NULL AND coalesce(lower(source),'') NOT LIKE '%fixture%' AND coalesce(lower(source),'') NOT LIKE '%demo%' AND coalesce(lower(source),'') NOT LIKE '%simulat%' AND coalesce(lower(source),'') NOT LIKE '%model%' AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')")"
  assert_uint underlying_rows "$underlying_rows"
  assert_uint yahoo_rows "$yahoo_rows"
  assert_uint option_rows "$option_rows"
  assert_uint market_snapshot_rows "$market_rows"
  (( underlying_rows > 0 )) || die "no observed Yahoo/snapshot underlying rows are available"
  (( yahoo_rows > 0 )) || die "no Yahoo history is available; run sync_underlying with source yahoo first"
  (( option_rows > 0 )) || die "no observed forward option snapshot is available; run snapshot_now first"
  (( market_rows > 0 )) || die "no observed quote snapshots are available"

  captured_at="$(scalar "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')")"
  underlying_from="$(scalar "SELECT coalesce(min(d)::text,'') FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')")"
  underlying_to="$(scalar "SELECT coalesce(max(d)::text,'') FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')")"
  option_from="$(scalar "SELECT coalesce(min(asof)::text,'') FROM option_bar WHERE dataset_id='observed' AND source='snapshot'")"
  option_to="$(scalar "SELECT coalesce(max(asof)::text,'') FROM option_bar WHERE dataset_id='observed' AND source='snapshot'")"
  underlying_sources="$(scalar "SELECT coalesce(string_agg(DISTINCT source,',' ORDER BY source),'') FROM underlying_bar WHERE dataset_id='observed' AND observed=1 AND source IN ('yahoo','snapshot')")"
  market_sources="$(scalar "SELECT coalesce(string_agg(DISTINCT source,',' ORDER BY source),'') FROM market_snapshot WHERE last IS NOT NULL AND coalesce(lower(source),'') NOT LIKE '%fixture%' AND coalesce(lower(source),'') NOT LIKE '%demo%' AND coalesce(lower(source),'') NOT LIKE '%simulat%' AND coalesce(lower(source),'') NOT LIKE '%model%' AND coalesce(freshness,'') NOT IN ('FIXTURE','SIMULATED','MODELED')")"

  {
    printf 'format=1\n'
    printf 'database=strikebench_dev\n'
    printf 'schema_sha256=%s\n' "$schema"
    printf 'captured_at=%s\n' "$captured_at"
    printf 'underlying_rows=%s\n' "$underlying_rows"
    printf 'yahoo_rows=%s\n' "$yahoo_rows"
    printf 'underlying_from=%s\n' "$underlying_from"
    printf 'underlying_to=%s\n' "$underlying_to"
    printf 'underlying_sources=%s\n' "$underlying_sources"
    printf 'option_rows=%s\n' "$option_rows"
    printf 'option_from=%s\n' "$option_from"
    printf 'option_to=%s\n' "$option_to"
    printf 'option_sources=snapshot\n'
    printf 'market_snapshot_rows=%s\n' "$market_rows"
    printf 'market_snapshot_sources=%s\n' "$market_sources"
    printf 'underlying_bar_sha256=%s\n' "$(file_hash "$work/underlying_bar.csv")"
    printf 'option_bar_sha256=%s\n' "$(file_hash "$work/option_bar.csv")"
    printf 'market_snapshot_sha256=%s\n' "$(file_hash "$work/market_snapshot.csv")"
  } > "$work/manifest.txt"

  mkdir -p "$BUNDLE_DIR"
  for file in underlying_bar.csv option_bar.csv market_snapshot.csv manifest.txt; do
    mv -f "$work/$file" "$BUNDLE_DIR/$file"
  done
  rmdir "$work"
  trap - EXIT
  printf 'Captured %s Yahoo/snapshot underlying rows, %s option rows, and %s quote snapshots in %s\n' \
    "$underlying_rows" "$option_rows" "$market_rows" "$BUNDLE_DIR"
}

hydrate() {
  local container_dir expected_underlying expected_options expected_market
  verify_bundle
  expected_underlying="$(manifest_value underlying_rows)"
  expected_options="$(manifest_value option_rows)"
  expected_market="$(manifest_value market_snapshot_rows)"
  container_dir="/tmp/strikebench-dev-market-snapshot-$$"
  docker exec "$DB_CONTAINER" mkdir -p "$container_dir"
  trap 'docker exec "$DB_CONTAINER" rm -rf -- "$container_dir" >/dev/null 2>&1 || true' EXIT
  docker cp "$BUNDLE_DIR/underlying_bar.csv" "$DB_CONTAINER:$container_dir/underlying_bar.csv" >/dev/null
  docker cp "$BUNDLE_DIR/option_bar.csv" "$DB_CONTAINER:$container_dir/option_bar.csv" >/dev/null
  docker cp "$BUNDLE_DIR/market_snapshot.csv" "$DB_CONTAINER:$container_dir/market_snapshot.csv" >/dev/null

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
              WHERE last IS NULL OR source IS NULL OR btrim(source) = ''
                 OR lower(source) LIKE '%fixture%' OR lower(source) LIKE '%demo%'
                 OR lower(source) LIKE '%simulat%' OR lower(source) LIKE '%model%'
                 OR coalesce(freshness,'') IN ('FIXTURE','SIMULATED','MODELED')) THEN
    RAISE EXCEPTION 'quote snapshot contains missing or generated provenance';
  END IF;
END \$\$;

INSERT INTO public.underlying_bar(symbol,d,open,high,low,close,volume,source,observed,created_at,
                                  dataset_id,adjusted,quality_rank,bar_kind)
SELECT symbol,d,open,high,low,close,volume,source,observed,created_at,dataset_id,
       adjusted,quality_rank,bar_kind FROM dev_underlying
ON CONFLICT (symbol,d,source,dataset_id) DO UPDATE SET
  open=excluded.open,high=excluded.high,low=excluded.low,close=excluded.close,
  volume=excluded.volume,observed=excluded.observed,created_at=excluded.created_at,
  adjusted=excluded.adjusted,quality_rank=excluded.quality_rank,bar_kind=excluded.bar_kind;

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
  greeks_source=excluded.greeks_source,created_at=excluded.created_at;

INSERT INTO public.market_snapshot(symbol,description,last,bid,ask,prev_close,optionable,source,
                                   freshness,as_of,captured_at)
SELECT symbol,description,last,bid,ask,prev_close,optionable,source,freshness,as_of,captured_at
  FROM dev_quotes
ON CONFLICT (symbol) DO UPDATE SET
  description=excluded.description,last=excluded.last,bid=excluded.bid,ask=excluded.ask,
  prev_close=excluded.prev_close,optionable=excluded.optionable,source=excluded.source,
  freshness=excluded.freshness,as_of=excluded.as_of,captured_at=excluded.captured_at;

COMMIT;
SQL

  docker exec "$DB_CONTAINER" rm -rf -- "$container_dir"
  trap - EXIT
  printf 'Hydrated %s underlying rows, %s option rows, and %s quote snapshots into strikebench_dev.\n' \
    "$expected_underlying" "$expected_options" "$expected_market"
  printf 'Restart the application so in-memory caches load the saved quotes as STALE and re-read stored history.\n'
}

case "$ACTION" in
  capture)
    require_tools
    capture
    ;;
  verify)
    require_tools
    verify_bundle
    printf 'Snapshot bundle is intact and matches the running development schema: %s\n' "$BUNDLE_DIR"
    ;;
  hydrate)
    require_tools
    hydrate
    ;;
  *) usage ;;
esac
