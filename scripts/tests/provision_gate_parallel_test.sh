#!/usr/bin/env bash
# Focused contract tests for provision_gate_parallel.sh; no real provisioning cases run here.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="$SCRIPT_DIR/provision_gate_parallel.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passes=0
failures=0
pass() { passes=$((passes + 1)); printf 'ok %d - %s\n' "$passes" "$1"; }
fail() { failures=$((failures + 1)); printf 'not ok - %s\n' "$1" >&2; }
assert_true() { if "$@"; then pass "$description"; else fail "$description"; fi; }

FAKE_RUNNER="$TMP/fake-provision-test.sh"
cat > "$FAKE_RUNNER" <<'FAKE'
#!/usr/bin/env bash
set -u
name="${PROVISION_TEST_SCOPE#shard-}"
case "$name" in
  database-host) cases=1 ;; database-runtime) cases=1 ;; install-export) cases=1 ;;
  install-runtime) cases=1 ;; helper-transaction) cases=1 ;; release-integrity) cases=1 ;;
  renderer-seeding) cases=1 ;; install-finish) cases=1 ;;
  backup) cases=1 ;; publication) cases=1 ;; database-authority) cases=1 ;;
  fleet-installer) cases=1 ;; host-reclamation) cases=1 ;; git-bash) cases=1 ;;
  *) exit 2 ;;
esac
printf 'tmpdir=%s\n' "$TMPDIR"
if [ -n "${FAKE_STATE_DIR:-}" ]; then
  while ! mkdir "$FAKE_STATE_DIR/lock" 2>/dev/null; do /bin/sleep 0.01; done
  active="$(cat "$FAKE_STATE_DIR/active" 2>/dev/null || printf 0)"
  active=$((active + 1)); printf '%s\n' "$active" > "$FAKE_STATE_DIR/active"
  maximum="$(cat "$FAKE_STATE_DIR/maximum" 2>/dev/null || printf 0)"
  [ "$active" -le "$maximum" ] || printf '%s\n' "$active" > "$FAKE_STATE_DIR/maximum"
  rmdir "$FAKE_STATE_DIR/lock"
  /bin/sleep "${FAKE_SLEEP_SECONDS:-0}"
  while ! mkdir "$FAKE_STATE_DIR/lock" 2>/dev/null; do /bin/sleep 0.01; done
  active="$(cat "$FAKE_STATE_DIR/active")"; printf '%s\n' "$((active - 1))" > "$FAKE_STATE_DIR/active"
  rmdir "$FAKE_STATE_DIR/lock"
fi
if [ "${FAKE_BLOCK_SCOPE:-}" = "$name" ]; then
  /bin/sleep 300 &
  blocked_child=$!
  printf '%s\n' "$blocked_child" > "${FAKE_BLOCK_PID_FILE:?}"
  wait "$blocked_child"
  exit $?
fi
if [ "${FAKE_MALFORMED_SCOPE:-}" = "$name" ]; then printf 'ok 1 - missing plan\n'; exit 0; fi
if [ "${FAKE_ZERO_SCOPE:-}" = "$name" ]; then printf '1..0\n'; exit 0; fi
if [ "${FAKE_DUPLICATE_NUMBER_SCOPE:-}" = "$name" ]; then
  printf 'ok 1 - first\nok 1 - second\n1..2\n'; exit 0
fi
if [ "${FAKE_DUPLICATE_IDENTITY_SCOPE:-}" = "$name" ]; then
  printf 'ok 1 - repeated\nok 2 - repeated\n1..2\n'; exit 0
fi
if [ "${FAKE_MULTIPLE_PLAN_SCOPE:-}" = "$name" ]; then
  printf 'ok 1 - duplicate plan\n1..1\n1..1\n'; exit 0
fi
if [ "${FAKE_MALFORMED_RESULT_SCOPE:-}" = "$name" ]; then
  printf 'not two numeric fields\n' > "$TMPDIR/worker-result-override"
fi
if [ "${FAKE_MISSING_RESULT_SCOPE:-}" = "$name" ]; then
  : > "$TMPDIR/skip-worker-result"
fi
if [ "${FAKE_NONZERO_SCOPE:-}" = "$name" ]; then
  printf 'ok 1 - interrupted worker\n1..1\n'; exit 143
fi
if [ "${FAKE_FAIL_SCOPE:-}" = "$name" ]; then
  printf 'not ok - injected failure\n1..1\n'; exit 1
fi
printf 'ok 1 - %s\n1..1\n' "$name"
FAKE
chmod 755 "$FAKE_RUNNER"

STATE="$TMP/state"; mkdir "$STATE"
OUT="$TMP/pass-results"
PASS_LOG="$TMP/pass.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" PROVISION_GATE_EXPECTED_TOTAL=14 \
FAKE_STATE_DIR="$STATE" FAKE_SLEEP_SECONDS=0.05 \
  bash "$WRAPPER" --jobs 2 --output "$OUT" > "$PASS_LOG" 2>&1
status=$?
description="the complete fake gate passes"; assert_true test "$status" -eq 0
description="the aggregate pins all 14 shard cases"; assert_true grep -q '^AGGREGATE PASS shards=14 cases=14 failures=0 ' "$PASS_LOG"
description="a successful full gate emits exactly one compatible totals marker"; assert_true test "$(grep -c '^PROVISION_GATE_TOTALS=' "$PASS_LOG")" -eq 1
description="the full-gate totals marker is coherent"; assert_true grep -qx 'PROVISION_GATE_TOTALS=shards=14/14;tests=14/14;failures=0' "$PASS_LOG"
order="$(awk '/^SHARD / {printf "%s ", $2}' "$PASS_LOG")"
description="per-shard reports retain deterministic manifest order"; assert_true test "$order" = "database-host database-runtime install-export install-runtime helper-transaction release-integrity renderer-seeding install-finish backup publication database-authority fleet-installer host-reclamation git-bash "
unique_tmp="$(grep -h '^tmpdir=' "$OUT"/*/tap.log | sort -u | wc -l | tr -d ' ')"
description="every shard receives isolated temporary state"; assert_true test "$unique_tmp" -eq 14
description="the jobs limit permits the requested concurrency"; assert_true test "$(cat "$STATE/maximum")" -eq 2

FAIL_LOG="$TMP/fail.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_FAIL_SCOPE=backup \
  bash "$WRAPPER" -j 2 --output "$TMP/fail-results" database-host backup > "$FAIL_LOG" 2>&1
status=$?
description="one red shard fails the aggregate"; assert_true test "$status" -ne 0
description="the red shard reports its TAP failure and status"; assert_true grep -q '^SHARD backup FAIL cases=1 failures=1 status=1 ' "$FAIL_LOG"
description="a red shard produces a fail-closed aggregate"; assert_true grep -q '^AGGREGATE FAIL shards=2 cases=2 failures=1 ' "$FAIL_LOG"
description="a failed gate emits no passing totals marker"; assert_true test "$(grep -c '^PROVISION_GATE_TOTALS=' "$FAIL_LOG" || true)" -eq 0

FOCUSED_LOG="$TMP/focused.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" \
  bash "$WRAPPER" -j 2 --output "$TMP/focused-results" database-host backup > "$FOCUSED_LOG" 2>&1
status=$?
description="a focused fake gate passes"; assert_true test "$status" -eq 0
description="a focused gate emits coherent positive selected totals"; assert_true grep -qx 'PROVISION_GATE_TOTALS=shards=2/2;tests=2/2;failures=0' "$FOCUSED_LOG"

MALFORMED_LOG="$TMP/malformed.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MALFORMED_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/malformed-results" publication > "$MALFORMED_LOG" 2>&1
status=$?
description="missing TAP plan fails closed"; assert_true test "$status" -ne 0
description="malformed TAP is identified as a failed shard"; assert_true grep -q '^SHARD publication FAIL cases=0 ' "$MALFORMED_LOG"

ZERO_LOG="$TMP/zero.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_ZERO_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/zero-results" publication > "$ZERO_LOG" 2>&1
status=$?
description="a focused shard with zero tests fails closed"; assert_true test "$status" -ne 0
description="the zero-test shard is reported as failed"; assert_true grep -q '^SHARD publication FAIL cases=0 ' "$ZERO_LOG"

DUPLICATE_NUMBER_LOG="$TMP/duplicate-number.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_DUPLICATE_NUMBER_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/duplicate-number-results" publication > "$DUPLICATE_NUMBER_LOG" 2>&1
status=$?
description="duplicate TAP numbering fails closed"; assert_true test "$status" -ne 0

DUPLICATE_IDENTITY_LOG="$TMP/duplicate-identity.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_DUPLICATE_IDENTITY_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/duplicate-identity-results" publication > "$DUPLICATE_IDENTITY_LOG" 2>&1
status=$?
description="repeated TAP descriptions remain valid when test numbers are unique"; assert_true test "$status" -eq 0

MULTIPLE_PLAN_LOG="$TMP/multiple-plan.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MULTIPLE_PLAN_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/multiple-plan-results" publication > "$MULTIPLE_PLAN_LOG" 2>&1
status=$?
description="multiple TAP plans fail closed"; assert_true test "$status" -ne 0

MALFORMED_RESULT_LOG="$TMP/malformed-result.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MALFORMED_RESULT_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/malformed-result-results" publication > "$MALFORMED_RESULT_LOG" 2>&1
status=$?
description="malformed worker metadata fails closed"; assert_true test "$status" -ne 0

MISSING_RESULT_LOG="$TMP/missing-result.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MISSING_RESULT_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/missing-result-results" publication > "$MISSING_RESULT_LOG" 2>&1
status=$?
description="missing worker metadata fails closed"; assert_true test "$status" -ne 0

NONZERO_LOG="$TMP/nonzero.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_NONZERO_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/nonzero-results" publication > "$NONZERO_LOG" 2>&1
status=$?
description="an interrupted nonzero worker fails closed"; assert_true test "$status" -ne 0
description="the interrupted status is retained in the shard report"; assert_true grep -q '^SHARD publication FAIL cases=1 failures=0 status=143 ' "$NONZERO_LOG"

AGGREGATE_MISMATCH_LOG="$TMP/aggregate-mismatch.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" PROVISION_GATE_EXPECTED_TOTAL=15 \
  bash "$WRAPPER" --output "$TMP/aggregate-mismatch-results" > "$AGGREGATE_MISMATCH_LOG" 2>&1
status=$?
description="a complete-set aggregate count mismatch fails closed"; assert_true test "$status" -ne 0
description="the exact expected and actual aggregate are reported"; assert_true grep -q '^CONTRACT FAIL expected_cases=15 actual_cases=14$' "$AGGREGATE_MISMATCH_LOG"
description="an aggregate mismatch emits no passing totals marker"; assert_true test "$(grep -c '^PROVISION_GATE_TOTALS=' "$AGGREGATE_MISMATCH_LOG" || true)" -eq 0

TERM_LOG="$TMP/term.log"
TERM_CHILD_PID_FILE="$TMP/term-child.pid"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_BLOCK_SCOPE=publication \
FAKE_BLOCK_PID_FILE="$TERM_CHILD_PID_FILE" \
  bash "$WRAPPER" --output "$TMP/term-results" publication > "$TERM_LOG" 2>&1 &
term_wrapper_pid=$!
term_ready=0
term_attempt=0
while [ "$term_attempt" -lt 200 ]; do
  if [ -s "$TERM_CHILD_PID_FILE" ]; then term_ready=1; break; fi
  /bin/sleep 0.01
  term_attempt=$((term_attempt + 1))
done
description="the blocked fixture exposes a live descendant before TERM"; assert_true test "$term_ready" -eq 1
kill -TERM "$term_wrapper_pid"
if wait "$term_wrapper_pid"; then term_status=0; else term_status=$?; fi
description="TERM exits with conventional status 143"; assert_true test "$term_status" -eq 143
term_child_pid="$(cat "$TERM_CHILD_PID_FILE")"
term_child_gone=0
term_attempt=0
while [ "$term_attempt" -lt 100 ]; do
  if ! kill -0 "$term_child_pid" 2>/dev/null; then term_child_gone=1; break; fi
  /bin/sleep 0.02
  term_attempt=$((term_attempt + 1))
done
description="TERM reaps the blocked shard descendant process group"; assert_true test "$term_child_gone" -eq 1
description="an interrupted gate emits no passing totals marker"; assert_true test "$(grep -c '^PROVISION_GATE_TOTALS=' "$TERM_LOG" || true)" -eq 0

PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" bash "$WRAPPER" -j 0 database-host > "$TMP/jobs.log" 2>&1
status=$?
description="a zero jobs limit is rejected"; assert_true test "$status" -eq 2
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" bash "$WRAPPER" unknown > "$TMP/unknown.log" 2>&1
status=$?
description="an unknown shard is rejected before execution"; assert_true test "$status" -eq 2
for retired_shard in shizuku helper-install device-sweep; do
  PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" bash "$WRAPPER" "$retired_shard" \
    > "$TMP/retired-$retired_shard.log" 2>&1
  status=$?
  description="unsafe dependent shard $retired_shard is no longer selectable"; assert_true test "$status" -eq 2
done

printf '1..%d\n' "$((passes + failures))"
[ "$failures" -eq 0 ]
