#!/usr/bin/env bash
# Run the complete provisioning contract as deterministic, isolated parallel shards.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="${PROVISION_GATE_SHARD_RUNNER:-$SCRIPT_DIR/provision_test.sh}"
EXPECTED_TOTAL="${PROVISION_GATE_EXPECTED_TOTAL:-2332}"
JOBS=4
OUTPUT_DIR=""
TEMP_OUTPUT=0

ALL_SHARDS=(
  database-host
  database-runtime
  install-export
  install-runtime
  helper-transaction
  release-integrity
  renderer-seeding
  shizuku
  install-finish
  backup
  publication
  database-authority
  fleet-installer
  helper-install
  host-reclamation
  device-sweep
  git-bash
)

usage() {
  cat <<'EOF'
Usage: provision_gate_parallel.sh [-j JOBS] [--output DIR] [SHARD ...]

Runs all provisioning shards by default. A named subset may be supplied for a
focused gate. Valid shards:
  database-host database-runtime install-export install-runtime
  helper-transaction release-integrity renderer-seeding shizuku install-finish
  backup publication database-authority fleet-installer helper-install
  host-reclamation device-sweep git-bash
EOF
}

requested=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    -j|--jobs)
      [ "$#" -ge 2 ] || { echo "missing value for $1" >&2; exit 2; }
      JOBS="$2"; shift 2 ;;
    --output)
      [ "$#" -ge 2 ] || { echo "missing value for --output" >&2; exit 2; }
      OUTPUT_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; while [ "$#" -gt 0 ]; do requested+=("$1"); shift; done ;;
    -*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) requested+=("$1"); shift ;;
  esac
done

case "$JOBS" in ''|*[!0-9]*|0) echo "jobs must be a positive integer" >&2; exit 2 ;; esac
case "$EXPECTED_TOTAL" in ''|*[!0-9]*) echo "expected total must be a non-negative integer" >&2; exit 2 ;; esac
[ -f "$RUNNER" ] || { echo "provision shard runner not found: $RUNNER" >&2; exit 2; }

if [ "${#requested[@]}" -eq 0 ]; then
  requested=("${ALL_SHARDS[@]}")
fi

seen=" "
for shard in "${requested[@]}"; do
  case " ${ALL_SHARDS[*]} " in
    *" $shard "*) ;;
    *) echo "unknown provisioning shard: $shard" >&2; exit 2 ;;
  esac
  case "$seen" in
    *" $shard "*) echo "duplicate provisioning shard: $shard" >&2; exit 2 ;;
  esac
  seen="$seen$shard "
done

complete_set=1
[ "${#requested[@]}" -eq "${#ALL_SHARDS[@]}" ] || complete_set=0
if [ "$complete_set" -eq 1 ]; then
  for shard in "${ALL_SHARDS[@]}"; do
    case "$seen" in *" $shard "*) ;; *) complete_set=0; break ;; esac
  done
fi

if [ -n "$OUTPUT_DIR" ]; then
  if [ -e "$OUTPUT_DIR" ] && [ -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
    echo "output directory is not empty: $OUTPUT_DIR" >&2
    exit 2
  fi
  mkdir -p "$OUTPUT_DIR" || exit 2
else
  OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/hapaneld-provision-gate.XXXXXX")" || exit 2
  TEMP_OUTPUT=1
fi

pids=()
terminate_active_groups() {
  local pid attempt alive
  for pid in "${pids[@]}"; do
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  done
  attempt=0
  while [ "$attempt" -lt 40 ]; do
    alive=0
    for pid in "${pids[@]}"; do
      if kill -0 -- "-$pid" 2>/dev/null; then alive=1; break; fi
    done
    [ "$alive" -eq 1 ] || break
    /bin/sleep 0.05
    attempt=$((attempt + 1))
  done
  for pid in "${pids[@]}"; do
    kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
  done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  pids=()
}

remove_temporary_output() {
  if [ "$TEMP_OUTPUT" -eq 1 ]; then rm -rf "$OUTPUT_DIR"; fi
}

cleanup_parallel_gate() {
  local status=$?
  trap - EXIT INT TERM
  terminate_active_groups
  remove_temporary_output
  exit "$status"
}

handle_parallel_signal() {
  local status="$1"
  trap - EXIT INT TERM
  terminate_active_groups
  remove_temporary_output
  exit "$status"
}

trap cleanup_parallel_gate EXIT
trap 'handle_parallel_signal 130' INT
trap 'handle_parallel_signal 143' TERM

run_shard() {
  local shard="$1" shard_dir="$OUTPUT_DIR/$1" start end status
  mkdir -p "$shard_dir/tmp" || exit 2
  start="$(date +%s)"
  PROVISION_TEST_SCOPE="shard-$shard" TMPDIR="$shard_dir/tmp" \
    bash "$RUNNER" > "$shard_dir/tap.log" 2>&1
  status=$?
  end="$(date +%s)"
  # The fake runner uses these two marker files to exercise corrupt/missing worker
  # metadata. A production shard receives an unpredictable private TMPDIR and never
  # creates either marker.
  if [ -f "$shard_dir/tmp/skip-worker-result" ]; then
    rm -f "$shard_dir/result"
  elif [ -f "$shard_dir/tmp/worker-result-override" ]; then
    cp "$shard_dir/tmp/worker-result-override" "$shard_dir/result"
  else
    printf '%s %s\n' "$status" "$((end - start))" > "$shard_dir/result"
  fi
  exit 0
}

wait_oldest() {
  local pid="${pids[0]}"
  wait "$pid" 2>/dev/null || true
  pids=("${pids[@]:1}")
}

gate_start="$(date +%s)"
# Monitor mode gives every background shard its own process group. The group
# leader is the run_shard subshell in $!, so signal cleanup reaches the runner
# and every process it started rather than abandoning grandchildren.
set -m
for shard in "${requested[@]}"; do
  while [ "${#pids[@]}" -ge "$JOBS" ]; do wait_oldest; done
  run_shard "$shard" &
  pids+=("$!")
done
while [ "${#pids[@]}" -gt 0 ]; do wait_oldest; done
set +m

aggregate_cases=0
aggregate_failures=0
completed_shards=0
passed_tests=0
verdict=PASS
for shard in "${requested[@]}"; do
  shard_dir="$OUTPUT_DIR/$shard"
  log="$shard_dir/tap.log"
  result="$shard_dir/result"
  status=125
  wall=0
  metadata_valid=0
  if [ -f "$result" ] && [ "$(wc -l < "$result" | tr -d ' ')" -eq 1 ] &&
     grep -Eq '^[0-9]+ [0-9]+$' "$result"; then
    read -r status wall < "$result"
    metadata_valid=1
  fi
  plan_count="$(grep -Ec '^1\.\.[0-9]+$' "$log" 2>/dev/null || true)"
  plan_line="$(grep -E '^1\.\.[0-9]+$' "$log" 2>/dev/null | tail -1 || true)"
  cases="${plan_line#1..}"
  oks="$(grep -Ec '^ok [0-9]+ - ' "$log" 2>/dev/null || true)"
  failures="$(grep -Ec '^not ok([[:space:]]|$)' "$log" 2>/dev/null || true)"
  tap_valid=0
  if [ "$plan_count" -eq 1 ] && [ -n "$plan_line" ] && [ "$cases" -gt 0 ] 2>/dev/null &&
     awk -v plan="$cases" '
       BEGIN { expected = 1; tests = 0; invalid = 0 }
       /^ok [0-9]+ - / {
         number = $2 + 0
         identity = $0
         sub(/^ok [0-9]+ - /, "", identity)
         if (number != expected || identity == "" || seen[identity]++) invalid = 1
         expected++
         tests++
         next
       }
       /^not ok([[:space:]]|$)/ { invalid = 1 }
       END { exit !(!invalid && tests == plan && expected == plan + 1) }
     ' "$log"; then
    tap_valid=1
  fi
  shard_verdict=PASS
  if [ "$plan_count" -ne 1 ] || [ -z "$plan_line" ]; then
    cases=0
    shard_verdict=FAIL
  elif [ "$metadata_valid" -ne 1 ] || [ "$tap_valid" -ne 1 ] ||
       [ "$status" -ne 0 ] || [ "$failures" -ne 0 ] || [ "$oks" -ne "$cases" ]; then
    shard_verdict=FAIL
  fi
  aggregate_cases=$((aggregate_cases + cases))
  aggregate_failures=$((aggregate_failures + failures))
  if [ "$shard_verdict" = FAIL ]; then
    verdict=FAIL
  else
    completed_shards=$((completed_shards + 1))
    passed_tests=$((passed_tests + oks))
  fi
  printf 'SHARD %s %s cases=%d failures=%d status=%d wall=%ss\n' \
    "$shard" "$shard_verdict" "$cases" "$failures" "$status" "$wall"
done

if [ "$complete_set" -eq 1 ] && [ "$aggregate_cases" -ne "$EXPECTED_TOTAL" ]; then
  verdict=FAIL
  printf 'CONTRACT FAIL expected_cases=%d actual_cases=%d\n' "$EXPECTED_TOTAL" "$aggregate_cases"
fi
gate_end="$(date +%s)"
printf 'AGGREGATE %s shards=%d cases=%d failures=%d wall=%ss\n' \
  "$verdict" "${#requested[@]}" "$aggregate_cases" "$aggregate_failures" "$((gate_end - gate_start))"
if [ "$verdict" = PASS ]; then
  printf 'PROVISION_GATE_TOTALS=shards=%d/%d;tests=%d/%d;failures=%d\n' \
    "$completed_shards" "${#requested[@]}" "$passed_tests" "$aggregate_cases" "$aggregate_failures"
fi
if [ -n "${PROVISION_GATE_REPORT_OUTPUT:-}" ]; then printf 'RESULTS %s\n' "$OUTPUT_DIR"; fi

[ "$verdict" = PASS ]
