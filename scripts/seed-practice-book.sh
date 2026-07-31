#!/usr/bin/env bash
set -euo pipefail

# Seed a small, honestly labelled Practice book through the same Plan -> recommendation ->
# ensemble -> outcome -> preview -> trade APIs used by the Desk. This never inserts rows directly
# and never creates browser-local positions. Re-running is idempotent for symbols that already
# have an ACTIVE Practice package; interrupted runs reuse only a mutable ACTIVE seed Plan. Once a
# seeded decision is frozen or closed, the next run starts a separately identified seed cycle.

base_url="${STRIKEBENCH_URL:-http://127.0.0.1:7070}"
symbols_csv="${PRACTICE_SEED_SYMBOLS:-AMD,NVDA,MU,AAPL,SPY}"
families_csv="${PRACTICE_SEED_FAMILIES:-CASH_SECURED_PUT,IRON_CONDOR,CREDIT_PUT_SPREAD,IRON_BUTTERFLY,CREDIT_CALL_SPREAD}"
horizon_days="${PRACTICE_SEED_HORIZON_DAYS:-45}"
pause_seconds="${PRACTICE_SEED_PAUSE_SECONDS:-2}"
run_stamp="$(date -u +%Y%m%d-%H%M%S)"
seed_cycle="${PRACTICE_SEED_CYCLE_ID:-$run_stamp}"
receipt_dir="${STRIKEBENCH_SEED_RECEIPT_DIR:-.tmp/practice-book-seed-${run_stamp}}"
mkdir -p "$receipt_dir"

IFS=',' read -r -a symbols <<< "$symbols_csv"
IFS=',' read -r -a families <<< "$families_csv"

request() {
  local method="$1" path="$2" output="$3" body="${4:-}"
  local status
  if [[ -n "$body" ]]; then
    status="$(curl -sS --max-time 90 -X "$method" "$base_url$path" \
      -H 'content-type: application/json' --data "$body" \
      -o "$receipt_dir/$output" -w '%{http_code}')"
  else
    status="$(curl -sS --max-time 90 -X "$method" "$base_url$path" \
      -o "$receipt_dir/$output" -w '%{http_code}')"
  fi
  printf '%s' "$status"
}

safe_name() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-'; }

config_status="$(request GET /api/config config.json)"
if [[ ! "$config_status" =~ ^2 ]]; then
  jq -n --arg status "$config_status" --arg receiptDir "$receipt_dir" \
    '{ok:false,error:"StrikeBench is not ready",httpStatus:$status,receiptDir:$receiptDir}'
  exit 1
fi
world="$(jq -r '.world // "unknown"' "$receipt_dir/config.json")"
lane="$(jq -r '.marketLane // "unknown"' "$receipt_dir/config.json")"

active_status="$(request GET '/api/trades?status=ACTIVE&page=0&size=100' active-before.json)"
[[ "$active_status" =~ ^2 ]] || { jq . "$receipt_dir/active-before.json"; exit 1; }

opened=0
skipped=0
failed=0
summary_lines="$receipt_dir/seed-results.jsonl"
: > "$summary_lines"

for index in "${!symbols[@]}"; do
  symbol="$(printf '%s' "${symbols[$index]}" | tr '[:lower:]' '[:upper:]' | tr -d '[:space:]')"
  [[ -n "$symbol" ]] || continue
  preferred="${families[$((index % ${#families[@]}))]:-}"
  preferred="$(printf '%s' "$preferred" | tr '[:lower:]' '[:upper:]' | tr -d '[:space:]')"
  slug="$(safe_name "$symbol")"

  if jq -e --arg symbol "$symbol" '.trades[]? | select(.symbol == $symbol and .status == "ACTIVE")' \
      "$receipt_dir/active-before.json" >/dev/null; then
    jq -cn --arg symbol "$symbol" --arg preferred "$preferred" \
      '{symbol:$symbol,status:"SKIPPED",reason:"ACTIVE_PRACTICE_PACKAGE_EXISTS",preferredFamily:$preferred}' \
      >> "$summary_lines"
    skipped=$((skipped + 1))
    continue
  fi

  # Include closed and archived seed history so cycle numbers never reuse an append-only request id.
  plans_status="$(request GET '/api/plans?openOnly=false' "${slug}-plans.json")"
  if [[ ! "$plans_status" =~ ^2 ]]; then
    jq -cn --arg symbol "$symbol" --arg status "$plans_status" \
      '{symbol:$symbol,status:"FAILED",step:"PLANS",httpStatus:$status}' >> "$summary_lines"
    failed=$((failed + 1)); continue
  fi
  title_prefix="Practice bloom seed · $symbol"
  plan_id="$(jq -r --arg symbol "$symbol" --arg prefix "$title_prefix" \
    '[.plans[]? | select(.symbol == $symbol
      and ((.title // "") | startswith($prefix))
      and .status == "ACTIVE" and .assumptionsEditable == true)]
      | sort_by(.updatedAt) | last | .id // empty' \
    "$receipt_dir/${slug}-plans.json")"
  if [[ -z "$plan_id" ]]; then
    cycle_number="$(jq -r --arg symbol "$symbol" --arg prefix "$title_prefix" \
      '([.plans[]? | select(.symbol == $symbol and ((.title // "") | startswith($prefix)))] | length) + 1' \
      "$receipt_dir/${slug}-plans.json")"
    cycle_slug="$(safe_name "$seed_cycle")"
    [[ -n "$cycle_slug" ]] || cycle_slug="$run_stamp"
    title="$title_prefix · cycle $cycle_number"
    # The cycle number makes the relationship legible in the Plan library; the run identity keeps
    # the append-only idempotency ledger from replaying a permanently frozen Plan after closure.
    # Supplying PRACTICE_SEED_CYCLE_ID makes an orchestrated retry use the same identity.
    request_id="practice-bloom-seed-v2-${world}-${symbol}-c${cycle_number}-${cycle_slug}"
    create_body="$(jq -cn --arg id "$request_id" --arg symbol "$symbol" --arg title "$title" \
      --argjson horizon "$horizon_days" \
      '{clientRequestId:$id,symbol:$symbol,intent:"INCOME",title:$title,thesis:"neutral",horizonDays:$horizon,riskMode:"conservative"}')"
    create_status="$(request POST /api/plans "${slug}-plan.json" "$create_body")"
    if [[ ! "$create_status" =~ ^2 ]]; then
      jq -cn --arg symbol "$symbol" --arg status "$create_status" \
        '{symbol:$symbol,status:"FAILED",step:"PLAN_CREATE",httpStatus:$status}' >> "$summary_lines"
      failed=$((failed + 1)); continue
    fi
    plan_id="$(jq -r .id "$receipt_dir/${slug}-plan.json")"
  else
    request GET "/api/plans/$plan_id" "${slug}-plan.json" >/dev/null
  fi

  strategy_status="$(request POST "/api/plans/$plan_id/strategy/run" "${slug}-strategy.json" \
    '{"allow0dte":false}')"
  if [[ ! "$strategy_status" =~ ^2 ]]; then
    jq -cn --arg symbol "$symbol" --arg status "$strategy_status" \
      '{symbol:$symbol,status:"FAILED",step:"STRATEGY",httpStatus:$status}' >> "$summary_lines"
    failed=$((failed + 1)); continue
  fi

  candidate_ids="$(jq -r --arg preferred "$preferred" '
    [.strategy.result.candidates[]?
      | select((.evaluation.assessment.mechanics.eligible // true) == true)
      | . + {seedFamily: ((.strategy // .family // .displayName // "") | ascii_upcase)}]
    | sort_by(if (.seedFamily | contains($preferred)) then 0 else 1 end)
    | .[].id' "$receipt_dir/${slug}-strategy.json")"
  plan_version="$(jq -r '.plan.version' "$receipt_dir/${slug}-strategy.json")"
  if [[ -z "$candidate_ids" ]]; then
    jq -cn --arg symbol "$symbol" --arg preferred "$preferred" \
      '{symbol:$symbol,status:"FAILED",step:"STRATEGY",reason:"NO_MECHANICALLY_ELIGIBLE_CANDIDATE",preferredFamily:$preferred}' \
      >> "$summary_lines"
    failed=$((failed + 1)); continue
  fi

  placed=false
  while IFS= read -r candidate_id; do
    [[ -n "$candidate_id" ]] || continue
    select_body="$(jq -cn --arg id "$candidate_id" --argjson version "$plan_version" \
      '{candidateId:$id,expectedVersion:$version}')"
    select_status="$(request PUT "/api/plans/$plan_id/strategy/select" "${slug}-selection.json" "$select_body")"
    [[ "$select_status" =~ ^2 ]] || break
    plan_version="$(jq -r '.plan.version' "$receipt_dir/${slug}-selection.json")"

    ensemble_body="$(jq -cn --argjson version "$plan_version" '{expectedVersion:$version}')"
    ensemble_status="$(request POST "/api/plans/$plan_id/outcomes/ensemble" "${slug}-ensemble.json" "$ensemble_body")"
    [[ "$ensemble_status" =~ ^2 ]] || continue
    ensemble_id="$(jq -r '.ensemble.id' "$receipt_dir/${slug}-ensemble.json")"
    outcome_body="$(jq -cn --argjson version "$plan_version" --arg ensemble "$ensemble_id" \
      '{expectedVersion:$version,basis:"PARAMETRIC",ensembleId:$ensemble}')"
    outcome_status="$(request POST "/api/plans/$plan_id/outcomes/run" "${slug}-outcome.json" "$outcome_body")"
    [[ "$outcome_status" =~ ^2 ]] || continue

    preview_body="$(jq -cn --argjson version "$plan_version" \
      '{expectedVersion:$version,qty:1,orderInstruction:{type:"MARKET",timeInForce:"DAY"}}')"
    preview_status="$(request POST "/api/plans/$plan_id/decision/preview" "${slug}-preview.json" "$preview_body")"
    [[ "$preview_status" =~ ^2 ]] || continue
    if ! jq -e '(.preview.ok == true) and ((.order.executability // "") == "IMMEDIATE")' \
        "$receipt_dir/${slug}-preview.json" >/dev/null; then
      continue
    fi

    trade_body="$(jq -c --argjson version "$plan_version" '
      {expectedVersion:$version,qty:1,
       orderInstruction:{type:"MARKET",timeInForce:"DAY"},
       acknowledgedRisks:[.requiredAcks[]?.id]}
      + (if .ackToken then {ackToken:.ackToken} else {} end)' \
      "$receipt_dir/${slug}-preview.json")"
    trade_status="$(request POST "/api/plans/$plan_id/decision/trade" "${slug}-trade.json" "$trade_body")"
    if [[ "$trade_status" =~ ^2 ]]; then
      family="$(jq -r --arg id "$candidate_id" \
        '.strategy.result.candidates[]? | select(.id == $id) | (.displayName // .strategy // .family // "strategy")' \
        "$receipt_dir/${slug}-strategy.json" | head -1)"
      jq -cn --arg symbol "$symbol" --arg family "$family" --arg planId "$plan_id" \
        --arg tradeId "$(jq -r '.trade.id' "$receipt_dir/${slug}-trade.json")" \
        --arg ensembleId "$ensemble_id" --arg lane "$lane" \
        '{symbol:$symbol,status:"OPENED",family:$family,planId:$planId,tradeId:$tradeId,ensembleId:$ensembleId,lane:$lane}' \
        >> "$summary_lines"
      opened=$((opened + 1)); placed=true; break
    fi
  done <<< "$candidate_ids"

  if [[ "$placed" != true ]]; then
    jq -cn --arg symbol "$symbol" --arg preferred "$preferred" --arg lane "$lane" \
      '{symbol:$symbol,status:"NOT_OPENED",reason:"NO_CURRENTLY_EXECUTABLE_PACKAGE",preferredFamily:$preferred,lane:$lane}' \
      >> "$summary_lines"
    failed=$((failed + 1))
  fi
  sleep "$pause_seconds"
done

jq -s --arg receiptDir "$receipt_dir" --arg world "$world" --arg lane "$lane" \
  --argjson opened "$opened" --argjson skipped "$skipped" --argjson failed "$failed" \
  '{ok:($failed == 0),world:$world,lane:$lane,opened:$opened,skipped:$skipped,notOpened:$failed,
    receiptDir:$receiptDir,results:.}' "$summary_lines" | tee "$receipt_dir/summary.json"

if [[ "$failed" -gt 0 ]]; then
  printf '\nSome packages were not opened. In OBSERVED this is expected outside an executable market; switch explicitly to a simulated world or rerun at market open.\n' >&2
  exit 2
fi
