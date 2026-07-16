#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURES="$ROOT/scripts/tests/fixtures"
INSTALLER="$ROOT/helper/install-daemon.sh"
TMP="$(mktemp -d)"
first_pid=""
cleanup() {
  [ -z "$first_pid" ] || kill "$first_pid" >/dev/null 2>&1 || true
  [ -z "$first_pid" ] || wait "$first_pid" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$TMP/dist/arm64-v8a"
printf 'mock helper\n' > "$TMP/dist/arm64-v8a/hapaneld-helper"
mkdir -p "$TMP/bin"
cat > "$TMP/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -u
command_text="$*"
if [ "${MOCK_MANUAL_TRANSACTION_STATE:-}" = stale ] &&
   printf '%s' "$command_text" | grep -Fq inspect_manual_journal; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'STALE_SYSTEM_TRANSACTION %s %s %s %s\n' \
    "${MOCK_MANUAL_TRANSACTION_ID:?}" "${MOCK_MANUAL_TARGET_BUILD_ID:?}" \
    "${MOCK_MANUAL_TARGET_HELPER_SHA256:?}" "${MOCK_MANUAL_TARGET_SERVICE_SHA256:?}"
  exit 0
fi
if [ "${MOCK_MANUAL_LIVE_STATE:-}" = UNKNOWN ] &&
   printf '%s' "$command_text" | grep -Fq 'echo ROLLBACK_UNKNOWN' &&
   printf '%s' "$command_text" | grep -Fq '.hapaneld-helper-manual-upgrade'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'ROLLBACK_UNKNOWN\n'
  exit 0
fi
if [ "${MOCK_MANUAL_COMMIT_LIVE_STATE:-}" = UNKNOWN ] &&
   printf '%s' "$command_text" | grep -Fq 'echo COMMIT_OK' &&
   printf '%s' "$command_text" | grep -Fq '.hapaneld-helper-manual-upgrade'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  exit 1
fi
exec "${REAL_ADB_FIXTURE:?}" "$@"
EOF
chmod +x "$TMP/bin/adb"
export PATH="$TMP/bin:/usr/bin:/bin"
export REAL_ADB_FIXTURE="$FIXTURES/adb"
export MOCK_TARGET="panel.test:5555"
export MOCK_CALL_LOG="$TMP/calls.log"
export MOCK_STATE_DIR="$TMP/state"
export MOCK_ROOT=1
export MOCK_ADB_ROOT=0
export MOCK_SU_DIALECT=join
export MOCK_ABI=arm64-v8a
export MOCK_SYSTEM_WRITABLE=1
export MOCK_HELPER_BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
export MOCK_HELPER_REQUEST_DELAY=2
export MOCK_MANUAL_INSTALL_DELAY=2
export HAPANELD_HELPER_DIST_DIR="$TMP/dist"
export HAPANELD_MANUAL_LEASE_SECONDS=3
export HAPANELD_MANUAL_LEASE_RENEW_SECONDS=1
mkdir -p "$MOCK_STATE_DIR"
: > "$MOCK_CALL_LOG"

bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/first.out" 2>&1 &
first_pid=$!
for _ in 1 2 3 4 5; do
  [ -f "$MOCK_STATE_DIR/manual-helper-transaction" ] && break
  /bin/sleep 1
done
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]

if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/second.out" 2>&1; then
  echo "concurrent standalone installer unexpectedly acquired an active transaction" >&2
  exit 1
fi
grep -q 'another standalone root-helper installer owns an active transaction lease' "$TMP/second.out"

wait "$first_pid"
first_pid=""
grep -q 'echo LEASE_OK' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]

: > "$MOCK_CALL_LOG"
export MOCK_MANUAL_LEASE_RENEW=fail
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/renew-fail.out" 2>&1; then
  echo "installer unexpectedly committed after losing its transaction lease" >&2
  exit 1
fi
grep -q 'transaction lease could not be renewed; the prior helper was restored' "$TMP/renew-fail.out"
grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]

export MOCK_MANUAL_LEASE_RENEW=ok
export MOCK_HELPER_CAPABILITY=fail
export MOCK_ROLLBACK_PING=fail
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/ping-fail.out" 2>&1; then
  echo "installer unexpectedly finalized rollback after restored-helper PING failed" >&2
  exit 1
fi
grep -q 'rollback could not be verified' "$TMP/ping-fail.out"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_HELPER_CAPABILITY=ok
export MOCK_ROLLBACK_PING=ok
export MOCK_MANUAL_TRANSACTION_STATE=stale
export MOCK_MANUAL_LIVE_STATE=UNKNOWN
export MOCK_MANUAL_TRANSACTION_ID=0123456789abcdef0123456789abcdef
export MOCK_MANUAL_TARGET_BUILD_ID="$MOCK_HELPER_BUILD_ID"
export MOCK_MANUAL_TARGET_HELPER_SHA256="$(sha256sum "$TMP/dist/arm64-v8a/hapaneld-helper" | awk '{print $1}')"
export MOCK_MANUAL_TARGET_SERVICE_SHA256="$(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"
: > "$MOCK_STATE_DIR/manual-helper-transaction"
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/superseded-live.out" 2>&1; then
  echo "installer unexpectedly rolled back a stale journal over superseded live helper state" >&2
  exit 1
fi
grep -q 'retained /system helper-only journal could not be recovered safely' "$TMP/superseded-live.out"
grep -q 'ROLLBACK_UNKNOWN' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
unset MOCK_MANUAL_TRANSACTION_STATE MOCK_MANUAL_LIVE_STATE
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_MANUAL_COMMIT_LIVE_STATE=UNKNOWN
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/commit-unknown.out" 2>&1; then
  echo "installer unexpectedly committed after its live persistence state changed" >&2
  exit 1
fi
grep -q 'durable commit point could not be confirmed' "$TMP/commit-unknown.out" || {
  cat "$TMP/commit-unknown.out" >&2
  exit 1
}
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
grep -q 'echo COMMIT_OK' "$MOCK_CALL_LOG"
unset MOCK_MANUAL_COMMIT_LIVE_STATE
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

echo "standalone installer transaction tests passed"
