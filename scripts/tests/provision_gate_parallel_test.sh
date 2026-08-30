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
  renderer-seeding) cases=1 ;; shizuku) cases=1 ;; install-finish) cases=1 ;;
  backup) cases=1 ;; publication) cases=1 ;; database-authority) cases=1 ;;
  fleet-installer) cases=1 ;; helper-install) cases=1 ;; host-reclamation) cases=1 ;;
  device-sweep) cases=1 ;; git-bash) cases=1 ;;
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
if [ "${FAKE_MALFORMED_SCOPE:-}" = "$name" ]; then printf 'ok 1 - missing plan\n'; exit 0; fi
if [ "${FAKE_FAIL_SCOPE:-}" = "$name" ]; then
  printf 'not ok - injected failure\n1..1\n'; exit 1
fi
printf 'ok 1 - %s\n1..1\n' "$name"
FAKE
chmod 755 "$FAKE_RUNNER"

STATE="$TMP/state"; mkdir "$STATE"
OUT="$TMP/pass-results"
PASS_LOG="$TMP/pass.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" PROVISION_GATE_EXPECTED_TOTAL=17 \
FAKE_STATE_DIR="$STATE" FAKE_SLEEP_SECONDS=0.05 \
  bash "$WRAPPER" --jobs 2 --output "$OUT" > "$PASS_LOG" 2>&1
status=$?
description="the complete fake gate passes"; assert_true test "$status" -eq 0
description="the aggregate pins all 17 shard cases"; assert_true grep -q '^AGGREGATE PASS shards=17 cases=17 failures=0 ' "$PASS_LOG"
order="$(awk '/^SHARD / {printf "%s ", $2}' "$PASS_LOG")"
description="per-shard reports retain deterministic manifest order"; assert_true test "$order" = "database-host database-runtime install-export install-runtime helper-transaction release-integrity renderer-seeding shizuku install-finish backup publication database-authority fleet-installer helper-install host-reclamation device-sweep git-bash "
unique_tmp="$(grep -h '^tmpdir=' "$OUT"/*/tap.log | sort -u | wc -l | tr -d ' ')"
description="every shard receives isolated temporary state"; assert_true test "$unique_tmp" -eq 17
description="the jobs limit permits the requested concurrency"; assert_true test "$(cat "$STATE/maximum")" -eq 2

FAIL_LOG="$TMP/fail.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_FAIL_SCOPE=backup \
  bash "$WRAPPER" -j 2 --output "$TMP/fail-results" database-host backup > "$FAIL_LOG" 2>&1
status=$?
description="one red shard fails the aggregate"; assert_true test "$status" -ne 0
description="the red shard reports its TAP failure and status"; assert_true grep -q '^SHARD backup FAIL cases=1 failures=1 status=1 ' "$FAIL_LOG"
description="a red shard produces a fail-closed aggregate"; assert_true grep -q '^AGGREGATE FAIL shards=2 cases=2 failures=1 ' "$FAIL_LOG"

MALFORMED_LOG="$TMP/malformed.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MALFORMED_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/malformed-results" publication > "$MALFORMED_LOG" 2>&1
status=$?
description="missing TAP plan fails closed"; assert_true test "$status" -ne 0
description="malformed TAP is identified as a failed shard"; assert_true grep -q '^SHARD publication FAIL cases=0 ' "$MALFORMED_LOG"

PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" bash "$WRAPPER" -j 0 database-host > "$TMP/jobs.log" 2>&1
status=$?
description="a zero jobs limit is rejected"; assert_true test "$status" -eq 2
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" bash "$WRAPPER" unknown > "$TMP/unknown.log" 2>&1
status=$?
description="an unknown shard is rejected before execution"; assert_true test "$status" -eq 2

printf '1..%d\n' "$((passes + failures))"
[ "$failures" -eq 0 ]
