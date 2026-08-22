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
# This dist is overridden, so it is a foreign build as far as the installer is concerned. Stamp it with
# an identity that is deliberately NOT the checkout's: every transaction below then also proves that
# the journal, the lease and the post-start identity check all follow the staged bytes.
FOREIGN_BUILD_ID=facade00facade00facade00facade00facade00facade00facade00facade00
CHECKOUT_BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
if [ "$FOREIGN_BUILD_ID" = "$CHECKOUT_BUILD_ID" ]; then
  echo "the foreign dist identity must differ from the checkout identity or this file proves nothing" >&2
  exit 1
fi
printf 'mock helper\nBUILDID %s\n' "$FOREIGN_BUILD_ID" > "$TMP/dist/arm64-v8a/hapaneld-helper"
mkdir -p "$TMP/bin"
cat > "$TMP/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -u
command_text="$*"
v3_real_filesystem_reply() {
  [ "${MOCK_V3_REAL_FILESYSTEM:-}" = 1 ] || return 125
  real_root="${MOCK_STATE_DIR:?}/v3-real"
  if [ "${3:-}" = push ]; then
    destination=${5//\/system/$real_root/system}
    destination=${destination//\/vendor/$real_root/vendor}
    destination=${destination//\/data/$real_root/data}
    mkdir -p "${destination%/*}"
    cp "$4" "$destination"
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    return 0
  fi
  if { printf '%s' "$command_text" | grep -Fq inspect_manual_journal_v1 ||
       printf '%s' "$command_text" | grep -Fq 'restore LIVE_CANONICAL /data/local/hapaneld-helper 700' ||
       printf '%s' "$command_text" | grep -Fq 'echo ROLLBACK_FINALIZED'; } &&
     [ "${3:-}" = shell ]; then
    remote_command=${!#}
    remote_command=${remote_command//\/dev\/.hapaneld-helper-transaction.lock/$real_root/dev/.hapaneld-helper-transaction.lock}
    remote_command=${remote_command//\/system/$real_root/system}
    remote_command=${remote_command//\/vendor/$real_root/vendor}
    remote_command=${remote_command//\/data/$real_root/data}
    remote_command=$(printf '%s\n' "$remote_command" | sed 's/^\([[:space:]]*\)|\*/\1""|*/')
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    PATH="${MOCK_V1_COMMAND_DIR:?}:/usr/bin:/bin" bash -c "$remote_command"
    return $?
  fi
  return 125
}
v1_real_filesystem_reply() {
  [ "${MOCK_V1_REAL_FILESYSTEM:-}" = 1 ] || return 125
  v1_root="${MOCK_STATE_DIR:?}/v1-real"
  # For these cases adb-root mode makes the last argv item the exact shell block emitted by the
  # production installer. Execute that block after mapping Android's fixed filesystem roots into
  # the fixture. The only simulated event is a process cut in the mock mv used by the first restore.
  if [ "${3:-}" = push ]; then
    destination=${5//\/system/$v1_root/system}
    destination=${destination//\/vendor/$v1_root/vendor}
    destination=${destination//\/data/$v1_root/data}
    mkdir -p "${destination%/*}"
    cp "$4" "$destination"
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    return 0
  fi
  if { printf '%s' "$command_text" | grep -Fq inspect_manual_journal_v1 ||
       printf '%s' "$command_text" | grep -Fq 'publish_recorded OLD_BIN' ||
       printf '%s' "$command_text" | grep -Fq 'echo ROLLBACK_FINALIZED'; } &&
     [ "${3:-}" = shell ]; then
    remote_command=${!#}
    remote_command=${remote_command//\/dev\/.hapaneld-helper-transaction.lock/$v1_root/dev/.hapaneld-helper-transaction.lock}
    remote_command=${remote_command//\/system/$v1_root/system}
    remote_command=${remote_command//\/vendor/$v1_root/vendor}
    remote_command=${remote_command//\/data/$v1_root/data}
    # The installer intentionally targets Android mksh, which accepts an empty first case pattern.
    # Its host-side single-quoted block represents that as a leading `|`; spell the empty pattern
    # explicitly for bash without changing the command exercised by the fixture.
    remote_command=$(printf '%s\n' "$remote_command" | sed 's/^\([[:space:]]*\)|\*/\1""|*/')
    printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
    PATH="${MOCK_V1_COMMAND_DIR:?}:/usr/bin:/bin" bash -c "$remote_command"
    remote_status=$?
    if [ "$remote_status" -eq 0 ] && printf '%s' "$command_text" | grep -Fq 'echo ROLLBACK_FINALIZED'; then
      rm -f "${MOCK_STATE_DIR:?}/manual-helper-transaction"
    fi
    return "$remote_status"
  fi
  return 125
}
v3_real_filesystem_reply "$@"
v3_status=$?
[ "$v3_status" -eq 125 ] || exit "$v3_status"
v1_real_filesystem_reply "$@"
v1_status=$?
[ "$v1_status" -eq 125 ] || exit "$v1_status"
if [ "${MOCK_MANUAL_TRANSACTION_STATE:-}" = stale_v3 ] &&
   printf '%s' "$command_text" | grep -Fq inspect_manual_journal_v3; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'STALE_V3_TRANSACTION system %s %s %s %s\n' \
    "${MOCK_MANUAL_TRANSACTION_ID:?}" "${MOCK_MANUAL_TARGET_BUILD_ID:?}" \
    "${MOCK_MANUAL_TARGET_HELPER_SHA256:?}" "${MOCK_MANUAL_TARGET_SERVICE_SHA256:?}"
  exit 0
fi
if printf '%s' "$command_text" | grep -Fq ' --request GUARDCAPS'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE\n'
  exit 0
fi
if printf '%s' "$command_text" | grep -Fq ' --request GUARDSTATUS'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0\n'
  exit 0
fi
if [ "${MOCK_REPLACEMENT_SAFE:-ok}" = armed ] &&
   printf '%s' "$command_text" | grep -Fq -- '--replacement-safe' &&
   printf '%s' "$command_text" | grep -Fq 'echo INSTALL_OK'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'GUARD_ARMED_ROLLBACK\n'
  exit 1
fi
if [ "${MOCK_REPLACEMENT_SAFE:-ok}" = r1_custody ] &&
   printf '%s' "$command_text" | grep -Fq -- '--replacement-safe' &&
   printf '%s' "$command_text" | grep -Fq 'echo INSTALL_OK'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'REPLACEMENT_AUTHORITY_ACTIVE\n'
  exit 1
fi
if [ "${MOCK_APP_REPLACEMENT_INTERVAL:-}" = inflight ] &&
   printf '%s' "$command_text" | grep -Fq 'recorded_live LIVE_CANONICAL' &&
   printf '%s' "$command_text" | grep -Fq -- '--replacement-safe'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'REPLACEMENT_AUTHORITY_ACTIVE\n'
  exit 1
fi
if [ "${MOCK_APP_REPLACEMENT_INTERVAL:-}" = slow_crash ] &&
   printf '%s' "$command_text" | grep -Fq 'echo INSTALL_OK' &&
   printf '%s' "$command_text" | grep -Fq 'while pidof hapaneld-helper'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'R1_RETIREMENT_TIMEOUT\n'
  exit 1
fi
if [ "${MOCK_POST_RETIRE_LIVE_STATE:-}" = changed ] &&
   printf '%s' "$command_text" | grep -Fq 'echo LIVE_IDENTITY_CHANGED' &&
   printf '%s' "$command_text" | grep -Fq 'recorded_live LIVE_CANONICAL'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'LIVE_IDENTITY_CHANGED\n'
  exit 1
fi
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
if [ "${MOCK_ROLLBACK_RETIREMENT:-ok}" = fail ] &&
   printf '%s' "$command_text" | grep -Fq ROLLBACK_RESTARTED; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  exit 1
fi
if [ "${MOCK_APP_REPLACEMENT_INTERVAL:-}" = slow_crash ] &&
   printf '%s' "$command_text" | grep -Fq 'phase_state_known' &&
   printf '%s' "$command_text" | grep -Fq 'restore LIVE_CANONICAL'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  printf 'ROLLBACK_UNKNOWN\n'
  exit 0
fi
if [ -n "${MOCK_ROLLBACK_PUBLICATION_VERSION:-}" ] &&
   printf '%s' "$command_text" | grep -Fq 'ROLLBACK_RESTARTED' &&
   { printf '%s' "$command_text" | grep -Fq 'publish_recorded OLD_BIN' ||
     printf '%s' "$command_text" | grep -Fq 'restore LIVE_CANONICAL'; }; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  cut_state="${MOCK_STATE_DIR:?}/publication-cut-${MOCK_ROLLBACK_PUBLICATION_VERSION}"
  : > "${MOCK_STATE_DIR:?}/manual-helper-transaction"
  if [ ! -f "$cut_state" ]; then
    : > "$cut_state"
    printf 'ROLLBACK_PUBLICATION_CUT\n'
    exit 1
  fi
  printf 'ROLLBACK_RESTARTED\n'
  exit 0
fi
if printf '%s' "$command_text" | grep -Fq ROLLBACK_RESTARTED &&
   printf '%s' "$command_text" | grep -Fq '/data/local/.hapaneld-helper-manual-upgrade'; then
  printf 'adb %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
  printf 'ROLLBACK_RESTARTED\n'
  exit 0
fi
exec "${REAL_ADB_FIXTURE:?}" "$@"
EOF
chmod +x "$TMP/bin/adb"
mkdir -p "$TMP/v1-command-bin"
cat > "$TMP/v1-command-bin/mv" <<'EOF'
#!/usr/bin/env bash
set -eu
restore_publish=0
for argument in "$@"; do
  case "$argument" in *.restore) restore_publish=1 ;; esac
done
printf 'before %s\n' "$*" >> "${MOCK_STATE_DIR:?}/real-mv.log"
/bin/mv "$@"
printf 'after %s\n' "$*" >> "${MOCK_STATE_DIR:?}/real-mv.log"
if [ "$restore_publish" -eq 1 ] && [ "${MOCK_V1_CUT_PUBLICATION:-}" = 1 ] &&
   [ ! -f "${MOCK_STATE_DIR:?}/v1-real/publication-cut" ]; then
  : > "${MOCK_STATE_DIR:?}/v1-real/publication-cut"
  kill -KILL "$PPID"
fi
EOF
for command_name in mount start stop; do
  cat > "$TMP/v1-command-bin/$command_name" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
done
for command_name in pidof pkill; do
  cat > "$TMP/v1-command-bin/$command_name" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
done
chmod +x "$TMP/v1-command-bin/"*
export PATH="$TMP/bin:/usr/bin:/bin"
export REAL_ADB_FIXTURE="$FIXTURES/adb"
export MOCK_TARGET="panel.test:5555"
export MOCK_CALL_LOG="$TMP/calls.log"
export MOCK_STATE_DIR="$TMP/state"
export MOCK_V1_COMMAND_DIR="$TMP/v1-command-bin"
export MOCK_ROOT=1
export MOCK_ADB_ROOT=0
export MOCK_SU_DIALECT=join
export MOCK_ABI=arm64-v8a
export MOCK_SYSTEM_WRITABLE=1
export MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID"
export MOCK_HELPER_REQUEST_DELAY=1
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
export MOCK_HELPER_REQUEST_DELAY=0
export MOCK_MANUAL_INSTALL_DELAY=0
grep -q 'echo LEASE_OK' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]

: > "$MOCK_CALL_LOG"
export MOCK_MANUAL_LEASE_RENEW=fail
export MOCK_MANUAL_INSTALL_DELAY=2
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/renew-fail.out" 2>&1; then
  echo "installer unexpectedly committed after losing its transaction lease" >&2
  exit 1
fi
grep -q 'transaction lease could not be renewed; the prior helper was restored' "$TMP/renew-fail.out" || {
  cat "$TMP/renew-fail.out" >&2
  exit 1
}
grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
export MOCK_MANUAL_INSTALL_DELAY=0

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

export MOCK_ROLLBACK_PING=ok
export MOCK_ROLLBACK_RETIREMENT=fail
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/retirement-fail.out" 2>&1; then
  echo "installer unexpectedly finalized rollback before the old helper retired" >&2
  exit 1
fi
grep -q 'rollback could not be verified' "$TMP/retirement-fail.out"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
unset MOCK_ROLLBACK_RETIREMENT
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

export MOCK_MANUAL_TRANSACTION_STATE=stale_v3
: > "$MOCK_STATE_DIR/manual-helper-transaction"
: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/stale-v3.out" 2>&1
grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_MANUAL_TRANSACTION_STATE

v3_root="$MOCK_STATE_DIR/v3-real"
v3_transaction=abcdef0123456789abcdef0123456789
prepare_v3_real_prepared() {
  rm -rf "$v3_root"
  mkdir -p "$v3_root/dev" "$v3_root/data/local" "$v3_root/system/bin" \
    "$v3_root/system/etc/init" "$v3_root/vendor/etc/init" "$v3_root/data/adb/hapaneld" \
    "$v3_root/data/adb/service.d"
  printf 'v3 retained prior canonical helper\n' > "$v3_root/data/local/hapaneld-helper"
  chmod 700 "$v3_root/data/local/hapaneld-helper"
  cp "$v3_root/data/local/hapaneld-helper" \
    "$v3_root/data/local/.hapaneld-helper-manual-$v3_transaction.recovery-LIVE_CANONICAL"
  chmod 600 "$v3_root/data/local/.hapaneld-helper-manual-$v3_transaction.recovery-LIVE_CANONICAL"
  v3_old_helper="$(sha256sum "$v3_root/data/local/hapaneld-helper" | awk '{print $1}')"
  cat > "$v3_root/data/local/.hapaneld-helper-manual-upgrade" <<EOF
JOURNAL_VERSION=3
JOURNAL_SCOPE=HELPER_ONLY
REGISTRATION_KIND=system
TRANSACTION_ID=$v3_transaction
TARGET_BUILD_ID=$MOCK_HELPER_BUILD_ID
TARGET_HELPER_SHA256=$MOCK_MANUAL_TARGET_HELPER_SHA256
TARGET_SERVICE_SHA256=$MOCK_MANUAL_TARGET_SERVICE_SHA256
SWAP_PHASE=PREPARED
LEASE_BOOT_ID=00000000-0000-0000-0000-000000000000
LEASE_UNTIL_UPTIME=0
LIVE_CANONICAL=1
LIVE_CANONICAL_SHA256=$v3_old_helper
LIVE_SYSTEM_BIN=0
LIVE_SYSTEM_BIN_SHA256=-
LIVE_SYSTEM_RC=0
LIVE_SYSTEM_RC_SHA256=-
LIVE_VENDOR_RC=0
LIVE_VENDOR_RC_SHA256=-
LIVE_SYSTEMLESS_BIN=0
LIVE_SYSTEMLESS_BIN_SHA256=-
LIVE_SYSTEMLESS_SERVICE=0
LIVE_SYSTEMLESS_SERVICE_SHA256=-
LIVE_LEGACY_BIN=0
LIVE_LEGACY_BIN_SHA256=-
LIVE_LEGACY_RC=0
LIVE_LEGACY_RC_SHA256=-
EOF
  chmod 600 "$v3_root/data/local/.hapaneld-helper-manual-upgrade"
  : > "$MOCK_STATE_DIR/manual-helper-transaction"
  rm -f "$MOCK_STATE_DIR/real-mv.log"
  : > "$MOCK_CALL_LOG"
}

export MOCK_V3_REAL_FILESYSTEM=1
export MOCK_ADB_ROOT=1
for custody_state in active hung; do
  prepare_v3_real_prepared
  case "$custody_state" in
    active) : > "$v3_root/data/local/.hapaneld-helper.new" ;;
    hung)
      mkdir -p "$v3_root/data/local/.hapaneld-guard-db"
      : > "$v3_root/data/local/.hapaneld-guard-db/replacement.v1"
      ;;
  esac
  v3_marker_hash="$(sha256sum "$v3_root/data/local/.hapaneld-helper-manual-upgrade" | awk '{print $1}')"
  v3_live_identity="$(stat -c %d:%i "$v3_root/data/local/hapaneld-helper")"
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v3-$custody_state-custody.out" 2>&1; then
    echo "installer unexpectedly rolled back retained PREPARED v3 state under $custody_state R1 custody" >&2
    exit 1
  fi
  grep -q 'APK-coupled helper replacement custody blocks standalone journal recovery' \
    "$TMP/v3-$custody_state-custody.out"
  [ "$(sha256sum "$v3_root/data/local/.hapaneld-helper-manual-upgrade" | awk '{print $1}')" = "$v3_marker_hash" ]
  [ "$(stat -c %d:%i "$v3_root/data/local/hapaneld-helper")" = "$v3_live_identity" ]
  [ "$(sha256sum "$v3_root/data/local/hapaneld-helper" | awk '{print $1}')" = "$v3_old_helper" ]
  ! grep -q '^ROLLBACK_PHASE=' "$v3_root/data/local/.hapaneld-helper-manual-upgrade"
  [ ! -s "$MOCK_STATE_DIR/real-mv.log" ]
done

prepare_v3_real_prepared
ln -s /missing-r1-unrelated-target "$v3_root/system/bin/hapaneld-helper"
v3_marker_hash="$(sha256sum "$v3_root/data/local/.hapaneld-helper-manual-upgrade" | awk '{print $1}')"
for retry in first retry; do
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v3-broken-link-$retry.out" 2>&1; then
    echo "installer unexpectedly accepted a broken symlink as absent in retained PREPARED v3 state" >&2
    exit 1
  fi
  grep -q 'retained canonical helper-only journal could not be recovered safely' \
    "$TMP/v3-broken-link-$retry.out"
  [ "$(readlink "$v3_root/system/bin/hapaneld-helper")" = /missing-r1-unrelated-target ]
  [ "$(sha256sum "$v3_root/data/local/.hapaneld-helper-manual-upgrade" | awk '{print $1}')" = "$v3_marker_hash" ]
  ! grep -q '^ROLLBACK_PHASE=' "$v3_root/data/local/.hapaneld-helper-manual-upgrade"
  [ ! -s "$MOCK_STATE_DIR/real-mv.log" ]
done
unset MOCK_V3_REAL_FILESYSTEM
export MOCK_ADB_ROOT=0
rm -rf "$v3_root"
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

v1_root="$MOCK_STATE_DIR/v1-real"
mkdir -p "$v1_root/dev" "$v1_root/system/bin" "$v1_root/system/etc/init"
cp "$TMP/dist/arm64-v8a/hapaneld-helper" "$v1_root/system/bin/hapaneld-helper"
cat > "$v1_root/system/etc/init/hapaneld-helper.rc" <<'V1RC'
# ha-paneld root helper — boot-persistent control daemon (LED, screen backlight, buttons, sysctls).
#
# Installed to /system/etc/init/hapaneld-helper.rc (auto-imported by init at boot). Runs in the `su`
# domain so it can write the root-only sysfs nodes (sysfs_lights, backlight bl_power) on a
# userdebug panel — the same domain a manual `su 0 shell uses. `class main` auto-starts it during
# boot. See helper/install-daemon.sh and helper/README.md.
#
# DO NOT add `critical` — it causes a reboot-loop if the daemon crashes at boot (7-second cycle,
# recovery-mode only). Without it, Android init's built-in backoff disables the service after
# 4 rapid crashes rather than rebooting, which is the safe behaviour we want.
service hapaneld_helper /system/bin/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
V1RC
[ "$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc" | awk '{print $1}')" = \
  b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4 ]
printf 'v1 prior helper\n' > "$v1_root/system/bin/hapaneld-helper.hapaneld-manual-recovery"
printf 'v1 prior registration\n' > "$v1_root/system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery"
v1_target_helper="$(sha256sum "$v1_root/system/bin/hapaneld-helper" | awk '{print $1}')"
v1_old_helper="$(sha256sum "$v1_root/system/bin/hapaneld-helper.hapaneld-manual-recovery" | awk '{print $1}')"
v1_old_rc="$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery" | awk '{print $1}')"
cat > "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" <<EOF
JOURNAL_VERSION=1
JOURNAL_SCOPE=HELPER_ONLY
TARGET_BUILD_ID=$MOCK_HELPER_BUILD_ID
TARGET_HELPER_SHA256=$v1_target_helper
OLD_BIN=1
OLD_BIN_SHA256=$v1_old_helper
OLD_SERVICE=1
OLD_SERVICE_SHA256=$v1_old_rc
LEGACY_BIN=0
LEGACY_BIN_SHA256=-
LEGACY_SERVICE=0
LEGACY_SERVICE_SHA256=-
ALT_BIN=0
ALT_BIN_SHA256=-
ALT_SERVICE=0
ALT_SERVICE_SHA256=-
EOF
chmod 600 "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
: > "$MOCK_STATE_DIR/manual-helper-transaction"
export MOCK_V1_REAL_FILESYSTEM=1
export MOCK_V1_REAL_KIND=system
export MOCK_V1_CUT_PUBLICATION=1
export MOCK_ADB_ROOT=1
: > "$MOCK_CALL_LOG"
ln -s /missing-legacy-helper "$v1_root/system/bin/hapaneld-ledd"
v1_marker_hash="$(sha256sum "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" | awk '{print $1}')"
for retry in first retry; do
  rm -f "$MOCK_STATE_DIR/real-mv.log"
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-broken-link-$retry.out" 2>&1; then
    echo "installer unexpectedly accepted a broken symlink as absent in retained v1 state" >&2
    exit 1
  fi
  grep -q 'retained /system helper-only journal could not be recovered safely' \
    "$TMP/v1-broken-link-$retry.out"
  [ "$(readlink "$v1_root/system/bin/hapaneld-ledd")" = /missing-legacy-helper ]
  [ "$(sha256sum "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" | awk '{print $1}')" = "$v1_marker_hash" ]
  ! grep -q '^ROLLBACK_PHASE=' "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
  [ ! -s "$MOCK_STATE_DIR/real-mv.log" ]
done
rm -f "$v1_root/system/bin/hapaneld-ledd"
rm -f "$MOCK_STATE_DIR/real-mv.log"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-real-publication-cut.out" 2>&1; then
  echo "installer unexpectedly finalized a real v1 post-swap rollback cut during publication" >&2
  exit 1
fi
grep -q 'could not be recovered safely' "$TMP/v1-real-publication-cut.out" || {
  cat "$TMP/v1-real-publication-cut.out" >&2
  exit 1
}
grep -qx 'ROLLBACK_PHASE=PUBLISHING' "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
[ "$(sha256sum "$v1_root/system/bin/hapaneld-helper" | awk '{print $1}')" = "$v1_old_helper" ] || {
  ls -la "$v1_root/system/bin" >&2
  cat "$MOCK_STATE_DIR/real-mv.log" >&2
  cat "$TMP/v1-real-publication-cut.out" >&2
  exit 1
}
[ "$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc" | awk '{print $1}')" = \
  b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4 ]

: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-real-publication-retry.out" 2>&1
[ "$(sha256sum "$v1_root/system/bin/hapaneld-helper" | awk '{print $1}')" = "$v1_old_helper" ]
[ "$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc" | awk '{print $1}')" = "$v1_old_rc" ]
[ ! -f "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" ]
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
grep -q 'ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
rm -rf "$v1_root"

v2_transaction=0123456789abcdef0123456789abcdef
mkdir -p "$v1_root/dev" "$v1_root/system/bin" "$v1_root/system/etc/init"
cp "$TMP/dist/arm64-v8a/hapaneld-helper" "$v1_root/system/bin/hapaneld-helper"
cp "$ROOT/helper/hapaneld-helper.rc" "$v1_root/system/etc/init/hapaneld-helper.rc"
printf 'v2 prior helper\n' > "$v1_root/system/bin/hapaneld-helper.hapaneld-manual-recovery"
printf 'v2 prior registration\n' > "$v1_root/system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery"
v2_target_helper="$(sha256sum "$v1_root/system/bin/hapaneld-helper" | awk '{print $1}')"
v2_target_rc="$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc" | awk '{print $1}')"
v2_old_helper="$(sha256sum "$v1_root/system/bin/hapaneld-helper.hapaneld-manual-recovery" | awk '{print $1}')"
v2_old_rc="$(sha256sum "$v1_root/system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery" | awk '{print $1}')"
cat > "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" <<EOF
JOURNAL_VERSION=2
JOURNAL_SCOPE=HELPER_ONLY
TRANSACTION_ID=$v2_transaction
TARGET_BUILD_ID=$MOCK_HELPER_BUILD_ID
TARGET_HELPER_SHA256=$v2_target_helper
TARGET_SERVICE_SHA256=$v2_target_rc
LEASE_BOOT_ID=00000000-0000-0000-0000-000000000000
LEASE_UNTIL_UPTIME=0
OLD_BIN=1
OLD_BIN_SHA256=$v2_old_helper
OLD_SERVICE=1
OLD_SERVICE_SHA256=$v2_old_rc
LEGACY_BIN=0
LEGACY_BIN_SHA256=-
LEGACY_SERVICE=0
LEGACY_SERVICE_SHA256=-
ALT_BIN=0
ALT_BIN_SHA256=-
ALT_SERVICE=0
ALT_SERVICE_SHA256=-
EOF
chmod 600 "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
ln -s /missing-v2-legacy-helper "$v1_root/system/bin/hapaneld-ledd"
: > "$MOCK_STATE_DIR/manual-helper-transaction"
v2_marker_hash="$(sha256sum "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" | awk '{print $1}')"
for retry in first retry; do
  rm -f "$MOCK_STATE_DIR/real-mv.log"
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v2-broken-link-$retry.out" 2>&1; then
    echo "installer unexpectedly accepted a broken symlink as absent in retained v2 state" >&2
    exit 1
  fi
  grep -q 'retained /system helper-only journal could not be recovered safely' \
    "$TMP/v2-broken-link-$retry.out"
  [ "$(readlink "$v1_root/system/bin/hapaneld-ledd")" = /missing-v2-legacy-helper ]
  [ "$(sha256sum "$v1_root/system/bin/.hapaneld-helper-manual-upgrade" | awk '{print $1}')" = "$v2_marker_hash" ]
  ! grep -q '^ROLLBACK_PHASE=' "$v1_root/system/bin/.hapaneld-helper-manual-upgrade"
  [ ! -s "$MOCK_STATE_DIR/real-mv.log" ]
done
rm -rf "$v1_root"
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

mkdir -p "$v1_root/dev" "$v1_root/data/adb/hapaneld" "$v1_root/data/adb/service.d"
cp "$TMP/dist/arm64-v8a/hapaneld-helper" "$v1_root/data/adb/hapaneld/hapaneld-helper"
cat > "$v1_root/data/adb/service.d/hapaneld-helper.sh" <<'V1SVC'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
V1SVC
[ "$(sha256sum "$v1_root/data/adb/service.d/hapaneld-helper.sh" | awk '{print $1}')" = \
  60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493 ]
printf 'v1 systemless prior helper\n' > "$v1_root/data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery"
printf 'v1 systemless prior registration\n' > "$v1_root/data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery"
v1_target_helper="$(sha256sum "$v1_root/data/adb/hapaneld/hapaneld-helper" | awk '{print $1}')"
v1_old_helper="$(sha256sum "$v1_root/data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery" | awk '{print $1}')"
v1_old_rc="$(sha256sum "$v1_root/data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery" | awk '{print $1}')"
cat > "$v1_root/data/adb/hapaneld/.helper-manual-upgrade.marker" <<EOF
JOURNAL_VERSION=1
JOURNAL_SCOPE=HELPER_ONLY
TARGET_BUILD_ID=$MOCK_HELPER_BUILD_ID
TARGET_HELPER_SHA256=$v1_target_helper
OLD_BIN=1
OLD_BIN_SHA256=$v1_old_helper
OLD_SERVICE=1
OLD_SERVICE_SHA256=$v1_old_rc
EOF
chmod 600 "$v1_root/data/adb/hapaneld/.helper-manual-upgrade.marker"
: > "$MOCK_STATE_DIR/manual-helper-transaction"
export MOCK_V1_REAL_KIND=systemless
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-systemless-real-publication-cut.out" 2>&1; then
  echo "installer unexpectedly finalized a real systemless v1 rollback cut during publication" >&2
  exit 1
fi
grep -q 'could not be recovered safely' "$TMP/v1-systemless-real-publication-cut.out" || {
  cat "$TMP/v1-systemless-real-publication-cut.out" >&2
  exit 1
}
grep -qx 'ROLLBACK_PHASE=PUBLISHING' "$v1_root/data/adb/hapaneld/.helper-manual-upgrade.marker"
[ "$(sha256sum "$v1_root/data/adb/hapaneld/hapaneld-helper" | awk '{print $1}')" = "$v1_old_helper" ]
[ "$(sha256sum "$v1_root/data/adb/service.d/hapaneld-helper.sh" | awk '{print $1}')" = \
  60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493 ]

: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/v1-systemless-real-publication-retry.out" 2>&1
[ "$(sha256sum "$v1_root/data/adb/hapaneld/hapaneld-helper" | awk '{print $1}')" = "$v1_old_helper" ]
[ "$(sha256sum "$v1_root/data/adb/service.d/hapaneld-helper.sh" | awk '{print $1}')" = "$v1_old_rc" ]
[ ! -f "$v1_root/data/adb/hapaneld/.helper-manual-upgrade.marker" ]
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
grep -q 'ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
unset MOCK_V1_REAL_FILESYSTEM MOCK_V1_REAL_KIND MOCK_V1_CUT_PUBLICATION
export MOCK_ADB_ROOT=0
rm -rf "$v1_root"

for publication_version in v2 v3; do
  export MOCK_ROLLBACK_PUBLICATION_VERSION="$publication_version"
  case "$publication_version" in
    v2)
      export MOCK_MANUAL_TRANSACTION_STATE=stale
      export MOCK_MANUAL_TRANSACTION_ID=0123456789abcdef0123456789abcdef
      export MOCK_MANUAL_TARGET_SERVICE_SHA256="$(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"
      ;;
    v3)
      export MOCK_MANUAL_TRANSACTION_STATE=stale_v3
      export MOCK_MANUAL_TRANSACTION_ID=0123456789abcdef0123456789abcdef
      export MOCK_MANUAL_TARGET_SERVICE_SHA256="$(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"
      ;;
  esac
  : > "$MOCK_STATE_DIR/manual-helper-transaction"
  rm -f "$MOCK_STATE_DIR/publication-cut-$publication_version"
  : > "$MOCK_CALL_LOG"
  if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/$publication_version-publication-cut.out" 2>&1; then
    echo "installer unexpectedly finalized a $publication_version rollback cut during publication" >&2
    cat "$TMP/$publication_version-publication-cut.out" >&2
    tail -80 "$MOCK_CALL_LOG" >&2
    exit 1
  fi
  grep -q 'could not be recovered safely' "$TMP/$publication_version-publication-cut.out"
  [ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
  [ -f "$MOCK_STATE_DIR/publication-cut-$publication_version" ]
  ! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"

  : > "$MOCK_CALL_LOG"
  bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/$publication_version-publication-retry.out" 2>&1
  grep -q '\.restore' "$MOCK_CALL_LOG"
  grep -q 'ROLLBACK_PHASE=PUBLISHING' "$MOCK_CALL_LOG"
  grep -q 'sync_path' "$MOCK_CALL_LOG"
  grep -q 'mv -f' "$MOCK_CALL_LOG"
  grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
  grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
  [ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
done
unset MOCK_ROLLBACK_PUBLICATION_VERSION MOCK_MANUAL_TRANSACTION_STATE
rm -f "$MOCK_STATE_DIR"/publication-cut-v2 "$MOCK_STATE_DIR"/publication-cut-v3
export MOCK_MANUAL_TRANSACTION_ID=0123456789abcdef0123456789abcdef
export MOCK_MANUAL_TARGET_SERVICE_SHA256="$(sha256sum "$ROOT/helper/hapaneld-helper.rc" | awk '{print $1}')"

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

export MOCK_SYSTEM_WRITABLE=0
export MOCK_SYSTEMLESS_RUNNER=1
: > "$MOCK_CALL_LOG"
bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/systemless.out" 2>&1
grep -q 'verified service.d registration' "$TMP/systemless.out"
grep -q '/data/local/hapaneld-helper --supervise' "$MOCK_CALL_LOG"
grep -q '/data/adb/service.d/hapaneld-helper.sh.manual-' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
export MOCK_SYSTEM_WRITABLE=1

export MOCK_REPLACEMENT_SAFE=armed
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/guard-armed.out" 2>&1; then
  echo "installer unexpectedly renamed the live helper while Guard DB authority was armed" >&2
  exit 1
fi
grep -q 'Guard DB authority is armed; the prior helper topology was restored' "$TMP/guard-armed.out"
grep -q -- '--replacement-safe' "$MOCK_CALL_LOG"
! grep -q 'mv -f .* /data/local/hapaneld-helper' "$MOCK_CALL_LOG"
grep -q 'ROLLBACK_RESTARTED' "$MOCK_CALL_LOG"
grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ ! -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_REPLACEMENT_SAFE

export MOCK_REPLACEMENT_SAFE=r1_custody
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/replacement-custody.out" 2>&1; then
  echo "installer unexpectedly rolled back across active R1 custody" >&2
  exit 1
fi
grep -q 'APK-coupled helper replacement custody refused standalone helper replacement' "$TMP/replacement-custody.out"
grep -q -- '--replacement-safe' "$MOCK_CALL_LOG"
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_REPLACEMENT_SAFE
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_APP_REPLACEMENT_INTERVAL=inflight
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/inflight-r1.out" 2>&1; then
  echo "installer unexpectedly rolled back across an in-flight APK-coupled helper replacement" >&2
  exit 1
fi
grep -q 'APK-coupled helper replacement custody refused standalone helper replacement' "$TMP/inflight-r1.out"
grep -q 'recorded_live LIVE_CANONICAL' "$MOCK_CALL_LOG"
grep -q -- '--replacement-safe' "$MOCK_CALL_LOG"
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_APP_REPLACEMENT_INTERVAL
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_APP_REPLACEMENT_INTERVAL=slow_crash
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/slow-r1-rollback.out" 2>&1; then
  echo "installer unexpectedly restored a stale v3 snapshot over a slow app R1 swap" >&2
  exit 1
fi
grep -q 'rollback could not be verified' "$TMP/slow-r1-rollback.out"
grep -q 'R1_RETIREMENT_TIMEOUT' "$TMP/slow-r1-rollback.out"
grep -q 'ROLLBACK_UNKNOWN' "$MOCK_CALL_LOG"
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_APP_REPLACEMENT_INTERVAL
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

export MOCK_POST_RETIRE_LIVE_STATE=changed
: > "$MOCK_CALL_LOG"
if bash "$INSTALLER" "$MOCK_TARGET" >"$TMP/post-retire-race.out" 2>&1; then
  echo "installer unexpectedly overwrote a helper replacement completed after its snapshot" >&2
  exit 1
fi
grep -q 'live helper topology changed after the standalone snapshot' "$TMP/post-retire-race.out"
grep -q 'LIVE_IDENTITY_CHANGED' "$MOCK_CALL_LOG"
! grep -q 'echo ROLLBACK_FINALIZED' "$MOCK_CALL_LOG"
[ -f "$MOCK_STATE_DIR/manual-helper-transaction" ]
unset MOCK_POST_RETIRE_LIVE_STATE
rm -f "$MOCK_STATE_DIR/manual-helper-transaction"

echo "standalone installer transaction tests passed"
