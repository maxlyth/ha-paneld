#!/usr/bin/env bash
# Black-box regression tests for the novice-facing provisioning contract.
# All adb and HTTP interactions are faked; this script never contacts a panel or the network.
set -u

PROVISION_TEST_SCOPE="${PROVISION_TEST_SCOPE:-all}"
case "$PROVISION_TEST_SCOPE" in
  core|all) ;;
  *) echo "PROVISION_TEST_SCOPE must be core or all" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVISION="$ROOT/scripts/provision.sh"
UPDATE_FLEET="$ROOT/scripts/update-fleet.sh"
FIXTURES="$ROOT/scripts/tests/fixtures"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

export PATH="$FIXTURES:/usr/bin:/bin"
export MOCK_TARGET="panel.test:5555"
export MOCK_CALL_LOG="$TMP/calls.log"
export HAPANELD_HELPER_PROBE="$FIXTURES/helper-probe"
export MOCK_HELPER_BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
export PROVISION_TEST_CURL="$FIXTURES/curl"

# The checked-in curl fixture predates Hardened mode. Keep its ordinary behavior intact while
# modelling the successful HTTP 202 transport response returned for protected operations awaiting
# local approval. Bash exports this function to the provisioner subprocess in place of curl(1).
curl() {
  local arg output_file="" want_output=0 want_status=0 status
  case "$*" in
    *'/api/v1/config/export?include_secrets=1'*)
      if [ "${MOCK_EXPORT:-ok}" != ok ]; then
        for arg in "$@"; do
          if [ "$want_output" = 1 ]; then output_file="$arg"; want_output=0; continue; fi
          case "$arg" in
            -o|--output) want_output=1 ;;
            -w|--write-out) want_status=1 ;;
          esac
        done
        if [ -n "$output_file" ]; then
          case "$MOCK_EXPORT" in
            approval) printf '{"ok":false,"error":"approval-required","approval_id":"backup-test"}\n' > "$output_file" ;;
            malformed-approval) printf '{"ok":false,"error":"unexpected"}\n' > "$output_file" ;;
            unexpected-2xx) printf '{"status":"created"}\n' > "$output_file" ;;
          esac
        else
          case "$MOCK_EXPORT" in
            approval) printf '{"ok":false,"error":"approval-required","approval_id":"backup-test"}\n' ;;
            malformed-approval) printf '{"ok":false,"error":"unexpected"}\n' ;;
            unexpected-2xx) printf '{"status":"created"}\n' ;;
          esac
        fi
        if [ "$want_status" = 1 ]; then
          case "$MOCK_EXPORT" in
            approval|malformed-approval) printf '202' ;;
            unexpected-2xx) printf '201' ;;
          esac
        fi
        return 0
      fi
      command "$PROVISION_TEST_CURL" "$@"; status=$?
      [ "$status" -ne 0 ] || printf '200'
      return "$status"
      ;;
    *'/api/v1/config/import'*)
      if [ "${MOCK_RESTORE:-ok}" != ok ]; then
        if [ "$MOCK_RESTORE" = timeout ]; then
          command "$PROVISION_TEST_CURL" "$@"
          return $?
        fi
        case "$MOCK_RESTORE" in
          approval)
            printf '{"ok":false,"error":"approval-required","approval_id":"import-test"}\n202'
            ;;
          malformed-approval)
            printf '{"ok":false,"error":"unexpected"}\n202'
            ;;
          unexpected-2xx)
            printf '{"status":"created"}\n201'
            ;;
        esac
        return 0
      fi
      command "$PROVISION_TEST_CURL" "$@"; status=$?
      [ "$status" -ne 0 ] || printf '200'
      return "$status"
      ;;
  esac
  command "$PROVISION_TEST_CURL" "$@"
}
export -f curl
MOCK_HELPER_DIST="$TMP/helper-dist"
mkdir -p "$MOCK_HELPER_DIST/armeabi-v7a" "$MOCK_HELPER_DIST/arm64-v8a"
printf 'mock arm helper\nBUILDID %s\n' "$MOCK_HELPER_BUILD_ID" > "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper"
printf 'mock arm64 helper\nBUILDID %s\n' "$MOCK_HELPER_BUILD_ID" > "$MOCK_HELPER_DIST/arm64-v8a/hapaneld-helper"
export HAPANELD_HELPER_DIST_DIR="$MOCK_HELPER_DIST"

passes=0
failures=0
LAST_OUTPUT=""
LAST_STATUS=0

run_provision() {
  local unsigned_ack=()
  [ "${RUN_UNSIGNED_ACK:-1}" != 1 ] || unsigned_ack=(--allow-unsigned-helper)
  : > "$MOCK_CALL_LOG"
  rm -f "$TMP/diag-attempts" "$TMP/write-settings-granted" "$TMP/accessibility-services" "$TMP/accessibility-enabled"
  # The slow-health probe counter is per-run state; leaving it behind made one test's outcome
  # depend on how many health probes an earlier test happened to make.
  rm -f "$TMP/plan-attempts" "$TMP/storage-status-attempts" "$TMP/health-probes"
  rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"
  rm -f "$TMP/package-stopped"
  if [ "${MOCK_STALE_TRANSACTION:-0}" = 1 ]; then : > "$TMP/stale-helper-transaction"; fi
  if [ -n "${MOCK_INSTALLED_APK_SOURCE:-}" ]; then
    cp "$MOCK_INSTALLED_APK_SOURCE" "$TMP/installed-apk"
  else
    printf 'previous installed apk\n' > "$TMP/installed-apk"
  fi
  LAST_OUTPUT="$TMP/output.txt"
  MOCK_HEALTH="${MOCK_HEALTH:-ok}" \
  MOCK_HEALTH_READY_AFTER="${MOCK_HEALTH_READY_AFTER:-3}" \
  MOCK_HEALTH_HANG_SECONDS="${MOCK_HEALTH_HANG_SECONDS:-3}" \
  APP_LAUNCH_PROBE_SECONDS="${APP_LAUNCH_PROBE_SECONDS:-1}" \
  APP_HEALTH_TIMEOUT_SECONDS="${APP_HEALTH_TIMEOUT_SECONDS:-3}" \
  MOCK_STOPPED_STATE="${MOCK_STOPPED_STATE:-0}" \
  MOCK_LAUNCHER_START="${MOCK_LAUNCHER_START:-ok}" \
  MOCK_LAUNCHER_PID_FILE="${MOCK_LAUNCHER_PID_FILE:-}" \
  MOCK_DIRECT_START="${MOCK_DIRECT_START:-ok}" \
  MOCK_STORAGE_HEALTH="${MOCK_STORAGE_HEALTH:-healthy}" \
  MOCK_POWER_SAFETY="${MOCK_POWER_SAFETY:-safe}" \
  MOCK_VERIFY="${MOCK_VERIFY:-ok}" \
  MOCK_EXPORT="${MOCK_EXPORT:-ok}" \
  MOCK_CONFIG="${MOCK_CONFIG:-ok}" \
  MOCK_RESTORE="${MOCK_RESTORE:-ok}" \
  MOCK_ADB_STATE="${MOCK_ADB_STATE:-device}" \
  MOCK_HA_LOGIN="${MOCK_HA_LOGIN:-ok}" \
  MOCK_HA_TOKEN="${MOCK_HA_TOKEN:-ok}" \
  MOCK_PLAN="${MOCK_PLAN:-ok}" \
  MOCK_SETUP="${MOCK_SETUP:-complete}" \
  MOCK_PM_CLEAR="${MOCK_PM_CLEAR:-ok}" \
  MOCK_DATA_CAPACITY="${MOCK_DATA_CAPACITY:-valid}" \
  MOCK_DATA_AVAIL_KB="${MOCK_DATA_AVAIL_KB:-1048576}" \
  MOCK_DB_SNAPSHOT="${MOCK_DB_SNAPSHOT:-ok}" \
  MOCK_DB_SIDECARS="${MOCK_DB_SIDECARS:-present}" \
  MOCK_WEBVIEW_VERSION="${MOCK_WEBVIEW_VERSION:-150.0.0.0}" \
  MOCK_GH_FAIL="${MOCK_GH_FAIL:-0}" \
  MOCK_GITHUB_API="${MOCK_GITHUB_API:-fail}" \
  MOCK_RELEASE_CERT="${MOCK_RELEASE_CERT:-ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339}" \
  MOCK_RELEASE_PACKAGE="${MOCK_RELEASE_PACKAGE:-io.github.maxlyth.hapaneld}" \
  MOCK_RELEASE_VERIFY_FAIL="${MOCK_RELEASE_VERIFY_FAIL:-0}" \
  MOCK_RELEASE_PROOF_DOWNLOAD="${MOCK_RELEASE_PROOF_DOWNLOAD:-ok}" \
  MOCK_RELEASE_CHECKSUM="${MOCK_RELEASE_CHECKSUM:-ok}" \
  MOCK_RELEASE_SIGNATURE_FAIL="${MOCK_RELEASE_SIGNATURE_FAIL:-0}" \
  MOCK_LATE_ADB_STATUS="${MOCK_LATE_ADB_STATUS:-0}" \
  MOCK_LATE_ADB_PHASE="${MOCK_LATE_ADB_PHASE:-identity}" \
  MOCK_PRODUCT_IDENTITY="${MOCK_PRODUCT_IDENTITY:-}" \
  MOCK_ADB_ROOT="${MOCK_ADB_ROOT:-0}" \
  MOCK_HELPER_PROOF_DOWNLOAD="${MOCK_HELPER_PROOF_DOWNLOAD:-ok}" \
  MOCK_HELPER_CHECKSUM="${MOCK_HELPER_CHECKSUM:-ok}" \
  MOCK_HELPER_SIGNATURE_FAIL="${MOCK_HELPER_SIGNATURE_FAIL:-0}" \
  MOCK_ROOT="${MOCK_ROOT:-1}" \
  MOCK_ABI="${MOCK_ABI:-arm64-v8a}" \
  MOCK_SYSTEM_WRITABLE="${MOCK_SYSTEM_WRITABLE:-1}" \
  MOCK_SYSTEM_CAPACITY="${MOCK_SYSTEM_CAPACITY:-valid}" \
  MOCK_SYSTEM_AVAIL_KB="${MOCK_SYSTEM_AVAIL_KB:-1048576}" \
  MOCK_DEVICE_AWK="${MOCK_DEVICE_AWK:-present}" \
  MOCK_SYSTEMLESS_RUNNER="${MOCK_SYSTEMLESS_RUNNER:-1}" \
  MOCK_VENDOR_INIT_RW="${MOCK_VENDOR_INIT_RW:-1}" \
  MOCK_VENDOR_RC_STATE="${MOCK_VENDOR_RC_STATE:-missing}" \
  MOCK_HELPER_INSTALL="${MOCK_HELPER_INSTALL:-ok}" \
  MOCK_HELPER_COMMIT="${MOCK_HELPER_COMMIT:-ok}" \
  MOCK_COMMIT_LIVE_STATE="${MOCK_COMMIT_LIVE_STATE:-TARGET}" \
  MOCK_TRANSACTION_BUSY="${MOCK_TRANSACTION_BUSY:-0}" \
  MOCK_TRANSACTION_TAMPER="${MOCK_TRANSACTION_TAMPER:-0}" \
  MOCK_TRANSACTION_TOKEN_MISMATCH="${MOCK_TRANSACTION_TOKEN_MISMATCH:-0}" \
  MOCK_TOKEN_MISMATCH_ACTION="${MOCK_TOKEN_MISMATCH_ACTION:-}" \
  MOCK_HELPER_START="${MOCK_HELPER_START:-ok}" \
  MOCK_HELPER_CAPABILITY="${MOCK_HELPER_CAPABILITY:-ok}" \
  MOCK_ROLLBACK_PING="${MOCK_ROLLBACK_PING:-ok}" \
  MOCK_ROLLBACK_RESULT="${MOCK_ROLLBACK_RESULT:-ok}" \
  MOCK_ROLLBACK_RETIREMENT="${MOCK_ROLLBACK_RETIREMENT:-ok}" \
  MOCK_HELPER_BUILD_ID="${MOCK_HELPER_BUILD_ID}" \
  MOCK_HELPER_BUILD_ID_MATCH="${MOCK_HELPER_BUILD_ID_MATCH:-ok}" \
  MOCK_RELEASE_PROVISION_BUILD_ID="${MOCK_RELEASE_PROVISION_BUILD_ID:-$MOCK_HELPER_BUILD_ID}" \
  MOCK_APK_INSTALL="${MOCK_APK_INSTALL:-ok}" \
  MOCK_APK_QUERY="${MOCK_APK_QUERY:-ok}" \
  HAPANELD_SKIP_AUTO_EXPORT="${HAPANELD_SKIP_AUTO_EXPORT:-1}" \
  HAPANELD_CONFIG_BACKUP_DIR="${HAPANELD_CONFIG_BACKUP_DIR:-$TMP/auto-backups}" \
  MOCK_MANUAL_STALE="${MOCK_MANUAL_STALE:-0}" \
  MOCK_STALE_TRANSACTION_KIND="${MOCK_STALE_TRANSACTION_KIND:-system}" \
  MOCK_ACTIVE_TRANSACTION="${MOCK_ACTIVE_TRANSACTION:-0}" \
  MOCK_STALE_LIVE_STATE="${MOCK_STALE_LIVE_STATE:-TARGET}" \
  MOCK_STALE_APK_SHA256="${MOCK_STALE_APK_SHA256:-$(/usr/bin/sha256sum "$TMP/installed-apk" | awk '{print $1}')}" \
  MOCK_STALE_BUILD_ID="${MOCK_STALE_BUILD_ID:-$MOCK_HELPER_BUILD_ID}" \
  MOCK_SU_DIALECT="${MOCK_SU_DIALECT:-join}" \
  MOCK_SHIZUKU_START="${MOCK_SHIZUKU_START:-ok}" \
  MOCK_SHIZUKU_START_SCRIPT="${MOCK_SHIZUKU_START_SCRIPT:-ok}" \
  MOCK_SHIZUKU_INSPECT="${MOCK_SHIZUKU_INSPECT:-ok}" \
  MOCK_SHIZUKU_INSPECT_PID_FILE="${MOCK_SHIZUKU_INSPECT_PID_FILE:-}" \
  MOCK_SHIZUKU_INSTALL="${MOCK_SHIZUKU_INSTALL:-ok}" \
  MOCK_SHIZUKU_INSTALL_PID_FILE="${MOCK_SHIZUKU_INSTALL_PID_FILE:-}" \
  SHIZUKU_INSPECT_TIMEOUT_SECONDS="${SHIZUKU_INSPECT_TIMEOUT_SECONDS:-20}" \
  SHIZUKU_INSTALL_TIMEOUT_SECONDS="${SHIZUKU_INSTALL_TIMEOUT_SECONDS:-180}" \
  ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS="${ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS:-60}" \
  MOCK_OPENSSL_MISSING="${MOCK_OPENSSL_MISSING:-0}" \
  MOCK_OPENSSL_DIGEST_FAIL="${MOCK_OPENSSL_DIGEST_FAIL:-0}" \
  HAPANELD_HELPER_DIST_DIR="${HAPANELD_HELPER_DIST_DIR:-$MOCK_HELPER_DIST}" \
  MOCK_STATE_DIR="$TMP" \
  PROVISIONING_PLAN_TIMEOUT_SECONDS="${PROVISIONING_PLAN_TIMEOUT_SECONDS:-2}" \
  HA_AUTH_CONNECT_TIMEOUT_SECONDS="${HA_AUTH_CONNECT_TIMEOUT_SECONDS:-1}" \
  HA_AUTH_TIMEOUT_SECONDS="${HA_AUTH_TIMEOUT_SECONDS:-1}" \
  PANEL_POST_CONNECT_TIMEOUT_SECONDS="${PANEL_POST_CONNECT_TIMEOUT_SECONDS:-1}" \
  PANEL_POST_TIMEOUT_SECONDS="${PANEL_POST_TIMEOUT_SECONDS:-1}" \
  PANEL_RESTORE_TIMEOUT_SECONDS="${PANEL_RESTORE_TIMEOUT_SECONDS:-1}" \
  APK_INSTALL_TIMEOUT_SECONDS="${APK_INSTALL_TIMEOUT_SECONDS:-30}" \
  STORAGE_HEALTH_VERIFY_ATTEMPTS="${STORAGE_HEALTH_VERIFY_ATTEMPTS:-3}" \
  STORAGE_HEALTH_VERIFY_POLL_SECONDS="${STORAGE_HEALTH_VERIFY_POLL_SECONDS:-0}" \
  MOCK_APK_INSTALL_PID_FILE="${MOCK_APK_INSTALL_PID_FILE:-}" \
    bash "$PROVISION" "$@" "${unsigned_ack[@]}" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}

pass() {
  passes=$((passes + 1))
  printf 'ok %d - %s\n' "$passes" "$1"
}

fail_test() {
  failures=$((failures + 1))
  printf 'not ok - %s\n' "$1" >&2
  if [ -f "$LAST_OUTPUT" ]; then
    sed 's/^/  | /' "$LAST_OUTPUT" >&2
  fi
}

assert_status() {
  expected="$1"
  description="$2"
  if [ "$LAST_STATUS" -eq "$expected" ]; then pass "$description"
  else fail_test "$description (expected status $expected, got $LAST_STATUS)"; fi
}

assert_success() {
  description="$1"
  if [ "$LAST_STATUS" -eq 0 ]; then pass "$description"
  else fail_test "$description (status $LAST_STATUS)"; fi
}

assert_failure() {
  description="$1"
  if [ "$LAST_STATUS" -ne 0 ]; then pass "$description"
  else fail_test "$description (unexpected status 0)"; fi
}

assert_contains() {
  pattern="$1"
  description="$2"
  if grep -Eqi -- "$pattern" "$LAST_OUTPUT"; then pass "$description"
  else fail_test "$description (missing pattern: $pattern)"; fi
}

assert_not_contains() {
  pattern="$1"
  file="$2"
  description="$3"
  if grep -Eqi -- "$pattern" "$file"; then fail_test "$description (unexpected pattern: $pattern)"
  else pass "$description"; fi
}

assert_log_contains() {
  pattern="$1"
  description="$2"
  if grep -Eqi -- "$pattern" "$MOCK_CALL_LOG"; then pass "$description"
  else fail_test "$description (missing call pattern: $pattern)"; fi
}

CAPACITY_PROBE_SOURCE="$(sed -n '
  /# HAPANELD_CAPACITY_PROBE_BEGIN/,/# HAPANELD_CAPACITY_PROBE_END/ {
    /# HAPANELD_CAPACITY_PROBE_BEGIN/d
    /# HAPANELD_CAPACITY_PROBE_END/d
    p
  }
' "$PROVISION")"

run_capacity_probe_fixture() {
  local state="$1" df_output="" prelude
  case "$state" in
    valid)
      df_output='Filesystem 1024-blocks Used Available Capacity Mounted on
/dev/block/platform/soc/by-name/system 2064192 1965516 98676 96% /system'
      ;;
    malformed)
      df_output='Filesystem 1024-blocks Used Available Capacity Mounted on
/dev/block/platform/soc/by-name/system 2064192 1965516 unknown 96% /system'
      ;;
    ambiguous)
      df_output='Filesystem 1024-blocks Used Available Capacity Mounted on
/dev/block/dm-0 2064192 1965516 98676 96% /system
/dev/block/dm-1 2064192 1965516 98676 96% /system'
      ;;
    missing) ;;
  esac
  case "$state" in
    missing-df)
      prelude='sed() { /bin/sed "$@"; }'
      ;;
    missing-sed)
      prelude='df() {
        [ "$#" -eq 3 ] && [ "$1" = -P ] && [ "$2" = -k ] && [ "$3" = /system ] || return 2
        printf "%s\n" "$CAPACITY_DF_OUTPUT"
      }'
      ;;
    *)
      prelude='df() {
        [ "$#" -eq 3 ] && [ "$1" = -P ] && [ "$2" = -k ] && [ "$3" = /system ] || return 2
        printf "%s\n" "$CAPACITY_DF_OUTPUT"
      }
      sed() { /bin/sed "$@"; }'
      ;;
  esac
  CAPACITY_DF_OUTPUT="$df_output" PATH="$TMP/no-device-tools" /bin/sh -c "$prelude
$CAPACITY_PROBE_SOURCE"
}

make_local_apk() {
  apk_path="$1"
  arm_helper="$2"
  arm64_helper="$3"
  /usr/bin/python3 - "$apk_path" "$arm_helper" "$arm64_helper" <<'PY'
import sys
import zipfile

apk, arm, arm64 = sys.argv[1:]
with zipfile.ZipFile(apk, "w") as archive:
    archive.writestr("AndroidManifest.xml", b"test manifest\n")
    archive.write(arm, "assets/hapaneld-helper-arm")
    archive.write(arm64, "assets/hapaneld-helper-arm64")
PY
}

APK="$TMP/ha-paneld.apk"
make_local_apk "$APK" \
  "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper" \
  "$MOCK_HELPER_DIST/arm64-v8a/hapaneld-helper"
RELEASE_APK="$TMP/ha-paneld-v0.9.2-rc3-manual-setup-required.apk"
printf 'test release apk\n' > "$RELEASE_APK"
HELPER_RELEASE_APK="$TMP/ha-paneld-v0.9.4-rc1-manual-setup-required.apk"
printf 'test helper release apk\n' > "$HELPER_RELEASE_APK"
NO_SIGNER_FIXTURES="$TMP/fixtures-without-apksigner"
mkdir -p "$NO_SIGNER_FIXTURES"
for fixture in "$FIXTURES"/*; do
  [ "$(basename "$fixture")" = apksigner ] || ln -s "$fixture" "$NO_SIGNER_FIXTURES/$(basename "$fixture")"
done
NO_GH_FIXTURES="$TMP/fixtures-without-gh"
mkdir -p "$NO_GH_FIXTURES"
for fixture in "$FIXTURES"/*; do
  [ "$(basename "$fixture")" = gh ] || ln -s "$fixture" "$NO_GH_FIXTURES/$(basename "$fixture")"
done

# Export is a recovery operation. It must be possible before resolving or installing an APK.
EXPORT="$TMP/panel-backup.json"
# A host-only logging request has one durable meaning on fresh and upgraded panels: explicit TCP.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --log-host collector.test
assert_success "log host without protocol provisions successfully"
if grep -Fq -- 'log_ship_protocol=syslog-tcp' "$MOCK_CALL_LOG"; then
  pass "log host without protocol persists the TCP default"
else
  fail_test "log host without protocol persists the TCP default"
fi

run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --log-host collector.test --log-proto syslog-udp
assert_success "explicit UDP log transport provisions successfully"
if grep -Fq -- 'log_ship_protocol=syslog-udp' "$MOCK_CALL_LOG"; then
  pass "explicit UDP log transport remains explicit"
else
  fail_test "explicit UDP log transport remains explicit"
fi

run_provision "$MOCK_TARGET" --log-proto invalid
assert_failure "invalid log transport is rejected before panel contact"
assert_contains 'syslog-tcp is the default' "invalid transport guidance names the TCP default"

run_provision "$MOCK_TARGET" --export "$EXPORT"
assert_success "export-only succeeds"
if [ -s "$EXPORT" ]; then pass "export-only writes a non-empty bundle"; else fail_test "export-only writes a non-empty bundle"; fi
if [ "$(stat -c '%a' "$EXPORT")" = 600 ]; then pass "secret export is owner-readable only"; else fail_test "secret export is owner-readable only"; fi
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "export-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start|monkey -p io\.github\.maxlyth\.hapaneld))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "export-only performs no panel mutation"

FAILED_EXPORT="$TMP/failed-backup.json"
MOCK_EXPORT=fail run_provision "$MOCK_TARGET" --export "$FAILED_EXPORT" --apk "$APK"
assert_failure "failed pre-install backup returns nonzero"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed pre-install backup stops before APK install"
if [ ! -e "$FAILED_EXPORT" ]; then pass "failed backup leaves no misleading output file"; else fail_test "failed backup leaves no misleading output file"; fi

APPROVAL_EXPORT="$TMP/approval-required-backup.json"
MOCK_EXPORT=approval run_provision "$MOCK_TARGET" --export "$APPROVAL_EXPORT" --apk "$APK"
assert_failure "approval-required backup returns nonzero"
assert_contains 'config export requires approval on the panel' "approval-required config export is not reported as successful"
assert_contains 'Review approvals.*approve the config export.*retry the identical --export command' "approval-required config export gives the on-panel approval and retry path"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "approval-required backup stops before APK install"
if [ ! -e "$APPROVAL_EXPORT" ]; then pass "approval response is not retained as a backup"; else fail_test "approval response is not retained as a backup"; fi

for export_case in malformed-approval unexpected-2xx; do
  REJECTED_EXPORT="$TMP/rejected-${export_case}-backup.json"
  MOCK_EXPORT="$export_case" run_provision "$MOCK_TARGET" --export "$REJECTED_EXPORT" --apk "$APK"
  assert_failure "$export_case backup response returns nonzero"
  assert_contains 'config export returned unexpected HTTP' "$export_case config export response is rejected"
  assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "$export_case backup response stops before APK install"
  if [ ! -e "$REJECTED_EXPORT" ]; then pass "$export_case response is not retained as a backup"; else fail_test "$export_case response is not retained as a backup"; fi
done

# The emergency upgrade guard is opt-out only for the transaction tests above. A normal existing-panel
# install must create a unique persistent export before any helper or APK mutation and fail closed if
# that export cannot be obtained.
rm -rf "$TMP/auto-backups"
HAPANELD_SKIP_AUTO_EXPORT=0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "ordinary upgrade creates an automatic config backup"
auto_backup_count="$(find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.json' | wc -l | tr -d ' ')"
if [ "$auto_backup_count" = 1 ]; then pass "automatic upgrade backup is unique and persistent"; else fail_test "automatic upgrade backup is unique and persistent"; fi
auto_backup="$(find "$TMP/auto-backups" -maxdepth 1 -type f -name '*.json' | head -1)"
if [ -n "$auto_backup" ] && [ -s "$auto_backup" ] && [ "$(stat -c '%a' "$auto_backup")" = 600 ]; then
  pass "automatic upgrade backup is non-empty and owner-only"
else
  fail_test "automatic upgrade backup is non-empty and owner-only"
fi
export_line="$(grep -n '/api/v1/config/export?include_secrets=1' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
install_line="$(grep -n '^adb .* install' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$export_line" ] && [ -n "$install_line" ] && [ "$export_line" -lt "$install_line" ]; then
  pass "automatic config backup precedes APK mutation"
else
  fail_test "automatic config backup precedes APK mutation"
fi

MOCK_EXPORT=fail HAPANELD_SKIP_AUTO_EXPORT=0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_failure "automatic backup failure blocks an upgrade"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "automatic backup failure stops before APK mutation"

SYMLINK_TARGET="$TMP/symlink-target.json"
SYMLINK_EXPORT="$TMP/symlink-backup.json"
printf 'do not replace\n' > "$SYMLINK_TARGET"
ln -s "$SYMLINK_TARGET" "$SYMLINK_EXPORT"
run_provision "$MOCK_TARGET" --export "$SYMLINK_EXPORT"
assert_failure "secret export refuses a symlink destination"
assert_contains 'refusing to replace a symlink' "symlink refusal explains the safe destination requirement"
if [ "$(cat "$SYMLINK_TARGET")" = 'do not replace' ]; then pass "symlink target remains untouched"; else fail_test "symlink target remains untouched"; fi

# Verification is explicitly read-only and must not even attempt installation.
run_provision "$MOCK_TARGET" --verify
assert_success "verify-only succeeds for a healthy panel"
assert_contains 'Detected panel: Test Panel' "verify-only displays the app-owned hardware profile guidance"
assert_contains 'storage health: healthy' "verify-only reports healthy storage"
assert_contains 'panel power safety: safe' "verify-only reports the app-owned power classification"
assert_log_contains '^curl .* /api/v1/status$|^curl .*http://panel\.test:8888/api/v1/status$' "verify-only reads the shared storage-health status"
assert_log_contains '^curl .* /api/v1/power-safety/state$|^curl .*http://panel\.test:8888/api/v1/power-safety/state$' "verify-only reads the one-token app-owned power state"
assert_log_contains '^curl .* /api/v1/provisioning/plan\.txt$|^curl .*http://panel\.test:8888/api/v1/provisioning/plan\.txt$' "verify-only reads the provisioning plan"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "verify-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start|monkey -p io\.github\.maxlyth\.hapaneld))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "verify-only performs no panel mutation"

MOCK_POWER_SAFETY=caution run_provision "$MOCK_TARGET" --verify
assert_success "a caution power classification remains advisory"
assert_contains 'panel power safety: caution.*only one observed power guard' "power caution explains the bounded risk"
assert_contains 'Use Repair when offered.*explicitly hidden' "power caution distinguishes repairable from healthy app-only states"
assert_not_contains '^adb .* shell settings put|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "power warning verification never repairs settings"

MOCK_POWER_SAFETY=unknown run_provision "$MOCK_TARGET" --verify
assert_success "unknown power probes remain an explicit warning"
assert_contains 'panel power safety: unknown.*did not establish an effective guard' "unknown power probes are not reported safe"

MOCK_POWER_SAFETY=at_risk run_provision "$MOCK_TARGET" --verify
assert_failure "an at-risk power classification fails verification"
assert_contains 'panel power safety: at risk.*screen-off can leave this panel unreachable' "at-risk power explains the reachability failure"
assert_contains 'use Repair when offered.*manual guidance' "at-risk power gives capability-aware recovery guidance"
assert_not_contains '^adb .* shell settings put|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "at-risk verification reports without mutating power settings"

MOCK_POWER_SAFETY=missing run_provision "$MOCK_TARGET" --verify
assert_success "a legacy build without power status remains verifiable"
assert_contains 'panel power safety: status unavailable' "legacy power status is not invented"
assert_contains 'power-safety/state.*no repair was attempted' "legacy power status remains read-only"

MOCK_POWER_SAFETY=malformed run_provision "$MOCK_TARGET" --verify
assert_success "malformed power status remains an explicit advisory warning"
assert_contains 'panel power safety: unrecognised status; not treating it as safe' "malformed power status is not reported safe"

for malformed_safe in truncated-safe garbage-safe duplicate-safe control-safe nul-safe; do
  MOCK_POWER_SAFETY="$malformed_safe" run_provision "$MOCK_TARGET" --verify
  assert_success "$malformed_safe power status remains an explicit advisory warning"
  assert_contains 'panel power safety: unrecognised status; not treating it as safe' "$malformed_safe power status is never reported safe"
  assert_not_contains 'panel power safety: safe' "$LAST_OUTPUT" "$malformed_safe power status cannot produce a green result"
done

MOCK_STORAGE_HEALTH=unchecked run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts storage health before the first scheduled check"
assert_contains 'storage health: not checked yet' "unchecked storage health is explained without failing verification"

MOCK_STORAGE_HEALTH=legacy run_provision "$MOCK_TARGET" --verify
assert_success "verify-only remains compatible with panels before storage-health status"
assert_not_contains 'storage health:' "$LAST_OUTPUT" "legacy verification does not invent a storage-health result"

MOCK_STORAGE_HEALTH=legacy-json run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a successful legacy status response without storage health"
assert_not_contains 'storage health:' "$LAST_OUTPUT" "legacy status JSON remains advisory during read-only verification"

MOCK_STORAGE_HEALTH=transport-fail run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects storage-status transport failure"
assert_contains 'status endpoint could not be reached' "read-only transport failure is not mistaken for a legacy response"

MOCK_STORAGE_HEALTH=missing-state run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects a current storage-health object with no state"
assert_contains 'storage health: malformed status response' "missing current-build storage state is identified"

MOCK_STORAGE_HEALTH=malformed run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects malformed current status JSON"
assert_contains 'storage health: malformed status response' "malformed current-build status is identified"

MOCK_STORAGE_HEALTH=future-state run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only rejects an unknown current storage-health state"
assert_contains "storage health: unrecognised state 'future-state'" "unknown current-build storage state is identified"

MOCK_STORAGE_HEALTH=warning run_provision "$MOCK_TARGET" --verify
assert_success "storage warning remains advisory"
assert_contains 'storage health: warning.*pressure is elevated' "storage warning clearly identifies pressure"
assert_contains 'Review panel free space and WAL/database growth.*then check' "storage warning provides a recovery action"

MOCK_STORAGE_HEALTH=critical run_provision "$MOCK_TARGET" --verify
assert_failure "critical storage makes verification fail"
assert_contains 'storage health: critical.*pressure is critical' "critical storage identifies the failing condition"
assert_contains 'Recover panel headroom or address WAL growth before writes fail.*re-run verification' "critical storage failure provides a recovery action"

MOCK_STORAGE_HEALTH=database_failure run_provision "$MOCK_TARGET" --verify
assert_failure "database failure makes verification fail"
assert_contains 'storage health: database failure.*SQLite writes or health checks failed' "database failure identifies the failing condition"
assert_contains 'Preserve ha-paneld\.db.*inspect.*/api/v1/diag.*re-run verification' "database failure protects the database and provides a recovery action"

# Mutating runs gate known danger before install, then require a complete current-build contract
# after install. The latter is bounded because package replacement and app startup are asynchronous.
MOCK_STORAGE_HEALTH=critical run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "critical storage blocks provisioning before mutation"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "critical storage blocks the APK install"

MOCK_STORAGE_HEALTH=database_failure run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "database failure blocks provisioning before mutation"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "database failure blocks the APK install"

MOCK_STORAGE_HEALTH=missing-state run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a malformed installed-app storage contract blocks provisioning before mutation"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "malformed storage status blocks the APK install"

MOCK_STORAGE_HEALTH=future-state run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unknown installed-app storage state blocks provisioning before mutation"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "unknown storage state blocks the APK install"

MOCK_STORAGE_HEALTH=unchecked-then-healthy run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "post-install verification polls until storage health becomes healthy"
assert_contains 'storage health check is not ready; waiting for a bounded retry' "post-install storage polling explains the wait"
if [ "$(grep -Ec '/api/v1/status$' "$MOCK_CALL_LOG")" -ge 3 ]; then
  pass "post-install storage verification retries the shared status endpoint"
else
  fail_test "post-install storage verification retries the shared status endpoint"
fi

MOCK_STORAGE_HEALTH=always-unchecked run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "post-install verification fails when storage health never completes"
assert_contains 'not checked after bounded post-install retries' "bounded unchecked failure names the incomplete health check"

MOCK_STORAGE_HEALTH=transport-fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an installed package with unavailable storage status blocks provisioning"
assert_contains "installed app's storage-health status could not be reached" "installed-package transport failure names the unavailable authority"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "installed-package transport failure stops before APK install"

MOCK_STORAGE_HEALTH=legacy-json run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "post-install verification rejects a current build with missing storage status"
assert_contains 'installed app did not return the required status contract' "current-build missing status names the required contract"

# Pre-plan releases remain verifiable. A 404 is an explicit compatibility result, not a reason to
# discard the established health, permissions and diagnostics checks.
MOCK_PLAN=missing run_provision "$MOCK_TARGET" --verify
assert_success "verify-only accepts a legacy panel without the provisioning-plan endpoint"
assert_contains 'older ha-paneld.*legacy verification checks' "legacy verify explains why profile guidance is unavailable"
assert_contains 'root helper daemon: running' "legacy verify retains the broad helper status"
assert_not_contains '^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "legacy verify remains GET-only"
unset MOCK_PLAN

# A current app that owns the endpoint but remains in 503 is not a legacy compatibility case.
MOCK_PLAN=unstable PROVISIONING_PLAN_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --verify
assert_failure "verify-only fails while current profile activation remains unstable"
assert_contains 'could not complete profile verification' "unstable verify explains that profile verification is incomplete"
assert_not_contains '^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "unstable verify remains GET-only"
unset MOCK_PLAN PROVISIONING_PLAN_TIMEOUT_SECONDS

MOCK_PLAN=control run_provision "$MOCK_TARGET" --verify
assert_success "verify strips terminal controls from remote provisioning guidance"
if LC_ALL=C grep -q $'\033' "$LAST_OUTPUT"; then fail_test "remote provisioning guidance contains no terminal escape bytes"
else pass "remote provisioning guidance contains no terminal escape bytes"; fi
assert_contains 'Detected panel: Hostile.*forged title' "sanitized provisioning guidance retains readable text"
unset MOCK_PLAN

# A normal local install must work with only portable shell facilities. The fixture PATH deliberately
# supplies failing seq and GNU sort -V implementations; invoking either makes this test fail.
RUN_UNSIGNED_ACK=0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_failure "unsigned local APK cannot install a privileged helper without developer acknowledgement"
assert_contains 're-run with --allow-unsigned-helper' "unsigned helper refusal names the explicit developer flag"
assert_not_contains '^adb .* (push .*/hapaneld-helper|install( |$))' "$MOCK_CALL_LOG" "unsigned helper refusal happens before privileged or package installation"

RUN_UNSIGNED_ACK=0 MOCK_ROOT=0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "unrooted local install does not require irrelevant helper acknowledgement"
assert_contains 'no root path available.*continuing without the root helper' "unrooted local install explains why no privileged bytes were used"

run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "successful install completes without seq or GNU sort -V"
if grep -Eq '^adb .* install( |$)' "$MOCK_CALL_LOG"; then pass "successful install invokes adb install"
else fail_test "successful install invokes adb install"; fi
assert_contains 'provisioned' "successful install reports completion"
assert_contains 'inspecting version.*installed ha-paneld package' "install reports installed-version inspection before mutation"
assert_contains 'inspecting access.*root route.*helper compatibility' "install reports privilege and helper inspection"
assert_contains 'Detected panel: Test Panel' "successful install identifies the resolved panel profile"
assert_not_contains '/api/v1/tame' "$MOCK_CALL_LOG" "ordinary install never auto-applies profile recommendations"
start_line="$(grep -nE '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$start_line" ] && [ -n "$plan_line" ] && [ "$start_line" -lt "$plan_line" ]; then
  pass "profile guidance is read only after the newly installed app is launched"
else
  fail_test "profile guidance is read only after the newly installed app is launched"
fi

# Package replacement on API 27 can leave ha-paneld stopped even though a direct component start was
# requested. Model that state explicitly: only Android's normal package LAUNCHER route clears it, and
# health plus the ordinary post-install verification must then become reachable.
MOCK_STOPPED_STATE=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "normal package launcher clears the post-install stopped state"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "stopped-state recovery uses Android's normal launcher route"
assert_not_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "$MOCK_CALL_LOG" "successful launcher recovery does not bypass the normal package route"
install_line="$(grep -nE '^adb .* install -r -g .*ha-paneld\.apk$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
launcher_line="$(grep -nE '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$install_line" ] && [ -n "$launcher_line" ] && [ -n "$plan_line" ] && \
   [ "$install_line" -lt "$launcher_line" ] && [ "$launcher_line" -lt "$plan_line" ]; then
  pass "stopped-state recovery launches after install and before verified app-owned guidance"
else
  fail_test "stopped-state recovery launches after install and before verified app-owned guidance"
fi

# A zero launcher exit is not recovery evidence. While the package remains stopped, health must stay
# unavailable and force the distinct direct route, whose successful start makes health reachable.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "ineffective zero-exit launcher advances to direct stopped-state recovery"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "zero-exit recovery tries the normal launcher first"
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "unhealthy zero-exit launcher advances to the direct route"
if [ ! -e "$TMP/package-stopped" ]; then pass "direct fallback actually clears stopped state after an ineffective launcher"
else fail_test "direct fallback actually clears stopped state after an ineffective launcher"; fi

# Launcher failure while stopped must recover the package rather than merely proving that a fallback
# command was invoked. Health and the fixture's stopped-state authority both have to converge.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "launcher failure while stopped recovers through the direct route"
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "launcher command failure invokes the direct component fallback"
if [ ! -e "$TMP/package-stopped" ]; then pass "direct fallback actually clears stopped state after launcher failure"
else fail_test "direct fallback actually clears stopped state after launcher failure"; fi

# The primary launcher is a host ADB command and must not be able to strand provisioning before the
# bounded health state machine. Its deadline must reap the blocked fixture and continue via direct start.
LAUNCHER_PID_FILE="$TMP/blocked-launcher.pid"
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=block MOCK_LAUNCHER_PID_FILE="$LAUNCHER_PID_FILE" \
  APP_LAUNCH_COMMAND_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "blocked launcher reaches its host deadline and recovers through direct start"
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "launcher deadline advances to the direct route"
blocked_launcher_pid="$(cat "$LAUNCHER_PID_FILE" 2>/dev/null || true)"
if [ -n "$blocked_launcher_pid" ] && ! kill -0 "$blocked_launcher_pid" 2>/dev/null; then
  pass "launcher deadline reaps the blocked host ADB process"
else
  fail_test "launcher deadline reaps the blocked host ADB process"
fi

# A settling profile returns 503. The portable client retries it and then displays the final plan.
MOCK_PLAN=transient run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "install waits through a transient provisioning-plan 503"
assert_contains 'Detected panel: Test Panel' "transient plan readiness eventually renders guidance"
plan_calls="$(grep -Ec '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" || true)"
if [ "$plan_calls" -ge 2 ]; then pass "transient plan readiness is retried"
else fail_test "transient plan readiness is retried"; fi
unset MOCK_PLAN

# A release-paired provisioner cannot claim completion if the just-installed app lacks or never
# stabilises the endpoint that owns profile interpretation.
MOCK_PLAN=missing MOCK_WEBVIEW_VERSION=80.0.0.0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_failure "new install fails when its paired provisioning-plan endpoint is missing"
assert_contains 'does not provide.*paired provisioning-plan endpoint|cannot be verified as complete' "missing paired endpoint explains the release contract"
assert_contains 'Root helper: needed only' "missing plan retains conservative helper guidance"
assert_contains 'Do not infer this from the SoC' "missing plan does not infer root authority from chipset"
assert_contains 'system WebView is very old' "missing plan retains the legacy WebView guidance"
assert_not_contains '/api/v1/tame' "$MOCK_CALL_LOG" "missing plan never falls back to automatic taming"
unset MOCK_PLAN MOCK_WEBVIEW_VERSION

MOCK_PLAN=unstable PROVISIONING_PLAN_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --apk "$APK"
assert_failure "new install fails when profile activation remains 503"
assert_contains 'stayed.*starting|did not finish profile activation' "permanently unstable plan gives a retryable recovery reason"
unset MOCK_PLAN PROVISIONING_PLAN_TIMEOUT_SECONDS

# Recommendations are information, not exit-status gates or implicit consent. The plan replaces the
# blanket helper/WebView messages while it is available.
MOCK_PLAN=recommendations MOCK_WEBVIEW_VERSION=80.0.0.0 run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "unsatisfied profile recommendations do not fail an ordinary install"
assert_contains 'Recommended: install the root helper' "app-owned helper recommendation is displayed"
assert_contains 'Recommended: update System WebView' "app-owned WebView recommendation is displayed"
assert_not_contains 'Root helper: needed only|system WebView is very old' "$LAST_OUTPUT" \
  "available plan suppresses duplicate blanket helper and WebView guidance"
assert_not_contains '/api/v1/tame|action=recommended' "$MOCK_CALL_LOG" "recommendations cause no hidden mutation"
unset MOCK_PLAN MOCK_WEBVIEW_VERSION

# Config/import may change observations. The core-rendered plan is fetched again only after those
# mutations complete, so its final guidance reflects the resulting panel state.
run_provision "$MOCK_TARGET" --apk "$APK" --id refreshed-panel
assert_success "configured install refreshes profile guidance"
config_line="$(grep -nE '^curl .* -X POST .*api/v1/config$' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
last_plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
plan_calls="$(grep -Ec '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" || true)"
if [ "$plan_calls" -ge 2 ] && [ -n "$config_line" ] && [ -n "$last_plan_line" ] && [ "$config_line" -lt "$last_plan_line" ]; then
  pass "profile guidance is rendered again after configuration"
else
  fail_test "profile guidance is rendered again after configuration"
fi

RESTORE_REFRESH="$TMP/restore-refresh.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{}}\n' > "$RESTORE_REFRESH"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE_REFRESH"
assert_success "restored install refreshes profile guidance"
restore_line="$(grep -nE '^curl .*api/v1/config/import' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
last_plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | tail -1 | cut -d: -f1)"
if [ -n "$restore_line" ] && [ -n "$last_plan_line" ] && [ "$restore_line" -lt "$last_plan_line" ]; then
  pass "profile guidance is rendered again after restore"
else
  fail_test "profile guidance is rendered again after restore"
fi

# Vendor-strip detection is advisory and runs after verification. A transient late adb failure must not
# overwrite a genuinely successful result (the field report returned adb's 255 after printing success).
MOCK_LATE_ADB_STATUS=255 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "late optional vendor probe cannot turn a verified install into exit 255"
assert_contains 'provisioned and verified' "late optional vendor probe preserves the successful result"
assert_log_contains 'shell getprop ro\.product\.brand$' "late optional vendor probe failure was exercised"

MOCK_LATE_ADB_STATUS=255 MOCK_LATE_ADB_PHASE=persistence MOCK_PRODUCT_IDENTITY=Tuya MOCK_ADB_ROOT=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "late vendor-strip safety probe cannot turn a verified install into exit 255"
assert_contains 'vendor-strip skipped' "failed persistence probe keeps vendor stripping fail-closed"
assert_log_contains '^adb .* push .*/hapaneld-helper /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "local rooted provisioning stages the helper extracted from the APK under its transaction nonce"
assert_log_contains '^adb .* push .* /data/local/tmp/hapaneld-helper-[0-9a-f]{32}\.txn$' "local rooted provisioning stages its authenticated transaction under the same nonce"
helper_push_line="$(grep -nE '^adb .* push .*/hapaneld-helper /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
local_app_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$helper_push_line" ] && [ -n "$local_app_install_line" ] && [ "$helper_push_line" -lt "$local_app_install_line" ]; then
  pass "root helper is installed before the APK is replaced"
else
  fail_test "root helper is installed before the APK is replaced"
fi

HAPANELD_HELPER_PROBE= run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "join-style su validates the daemon through an authenticated root-side client"
assert_log_contains '/system/bin/hapaneld-helper --request COMPANIONCAPS' "capability validation runs as root instead of Android shell uid"
assert_log_contains '/system/bin/hapaneld-helper --request BUILDID' "build validation uses the same authenticated root-side client"
assert_not_contains ' forward |/dev/tcp/' "$MOCK_CALL_LOG" "daemon validation does not weaken peer authentication with adb forwarding"

MOCK_ROOT=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "non-root provisioning continues without a root helper"
assert_contains 'no root path available.*continuing without the root helper' "non-root helper skip is explicit"
assert_not_contains '/data/local/tmp/hapaneld-helper' "$MOCK_CALL_LOG" "non-root provisioning never stages a privileged binary"

MOCK_ROOT=0 MOCK_ABI=x86_64 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "unsupported ABI is harmless on a genuinely unrooted panel"
assert_contains 'no root path available.*continuing without the root helper' "unrooted unsupported ABI remains on the reduced-capability path"

MOCK_ABI=x86_64 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "unsupported ABI fails before changing a rooted panel"
assert_contains 'rooted panel reports unsupported ABI.*x86_64' "unsupported rooted ABI gives the supported asset set"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "unsupported rooted ABI stops before helper staging or APK replacement"

STALE_HELPER_DIST="$TMP/stale-helper-dist"
mkdir -p "$STALE_HELPER_DIST/arm64-v8a"
printf 'stale helper that must never be staged\nBUILDID %064d\n' 0 > "$STALE_HELPER_DIST/arm64-v8a/hapaneld-helper"
HAPANELD_HELPER_DIST_DIR="$STALE_HELPER_DIST" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "stale helper dist cannot override the helper embedded in a local APK"
assert_not_contains "$STALE_HELPER_DIST" "$MOCK_CALL_LOG" "local provisioning never stages stale helper dist bytes"

ARM64_ONLY_HELPER="$TMP/arm64-only-helper"
printf 'exact APK arm64 selection\nBUILDID %s\n' "$MOCK_HELPER_BUILD_ID" > "$ARM64_ONLY_HELPER"
WRONG_ABI_HELPER="$TMP/wrong-abi-helper"
printf 'wrong ABI helper with invalid identity\n' > "$WRONG_ABI_HELPER"
ABI_APK="$TMP/abi-specific.apk"
make_local_apk "$ABI_APK" \
  "$WRONG_ABI_HELPER" \
  "$ARM64_ONLY_HELPER"
run_provision "$MOCK_TARGET" --apk "$ABI_APK" --no-tame
assert_success "local provisioning selects the exact embedded helper for the panel ABI"
assert_log_contains '^adb .* push .*/hapaneld-helper /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "arm64 panel stages the selected APK helper bytes"

MISSING_HELPER_APK="$TMP/missing-helper.apk"
/usr/bin/python3 - "$MISSING_HELPER_APK" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("AndroidManifest.xml", b"test manifest\n")
PY
run_provision "$MOCK_TARGET" --apk "$MISSING_HELPER_APK" --no-tame
assert_failure "rooted local provisioning fails before mutation when the APK helper is missing"
assert_contains 'local APK does not contain its arm64-v8a root helper' "missing embedded helper gives exact build recovery"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "missing embedded helper leaves the panel untouched"

INVALID_ID_HELPER="$TMP/invalid-id-helper"
printf 'helper without a valid build identity\n' > "$INVALID_ID_HELPER"
INVALID_ID_APK="$TMP/invalid-id.apk"
make_local_apk "$INVALID_ID_APK" \
  "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper" \
  "$INVALID_ID_HELPER"
run_provision "$MOCK_TARGET" --apk "$INVALID_ID_APK" --no-tame
assert_failure "rooted local provisioning rejects an embedded helper with invalid identity"
assert_contains 'embedded in the local APK has an invalid build identity' "invalid embedded identity gives exact recovery"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid embedded identity fails before panel mutation"

MOCK_SYSTEM_WRITABLE=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "rooted systemless provisioning installs the helper through service.d"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-systemless' "systemless helper path uses the same transactional installer"
if grep -Fq '/system/bin/stop hapaneld_ledd' "$PROVISION" && grep -Fq '/system/bin/pkill -x hapaneld-ledd' "$PROVISION"; then
  pass "systemless boot service retires the legacy daemon before binding the helper socket"
else
  fail_test "systemless boot service retires the legacy daemon before binding the helper socket"
fi

HAPANELD_HELPER_PROBE= MOCK_SYSTEM_WRITABLE=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "systemless validation invokes the exact newly installed helper path"
assert_log_contains 'exec /data/adb/hapaneld/hapaneld-helper --request COMPANIONCAPS' "systemless validation cannot select a stale /system helper"
assert_not_contains 'exec /system/bin/hapaneld-helper --request' "$MOCK_CALL_LOG" "systemless validation never probes the alternate install location"

MOCK_SYSTEM_WRITABLE=0 MOCK_SYSTEMLESS_RUNNER=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "read-only system without a verified service.d runner fails closed"
assert_contains 'read-only /system and no verified systemless boot-service runner' "missing persistence mechanism names the migration blocker"
assert_contains 'Magisk, KernelSU, or APatch' "missing persistence mechanism gives supported recovery choices"
assert_not_contains '/data/adb/service\.d/hapaneld-helper\.sh\.new|^adb .* install( |$)' "$MOCK_CALL_LOG" "unverified service.d path never installs a helper or replaces the APK"

# Stock NSPanel Pro firmware can have a writable but full /system. The zero-byte writability probe
# is not enough: capacity must be established before choosing a transactional install authority.
MOCK_SYSTEM_AVAIL_KB=12 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "writable /system without helper capacity installs through the hybrid layout"
assert_contains '/system has 12KB free; [0-9]+KB is required' "hybrid selection explains the capacity-driven layout"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "full /system selects the hybrid transactional installer"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)([^a-z]|$)' "$MOCK_CALL_LOG" "hybrid selection invokes no second boot authority"

# Android 8.1 images commonly provide df and sed but no standalone awk. Capacity detection must use
# only the portable device-side tools and still choose the correct transaction layout.
capacity_fixture_output="$(run_capacity_probe_fixture valid)"
if [ "$capacity_fixture_output" = 'SYSTEM_AVAIL_KB=98676' ]; then
  pass "the exact device parser accepts Android 8.1 df output without awk"
else
  fail_test "the exact device parser accepts Android 8.1 df output without awk"
fi
for capacity_state in missing missing-df missing-sed malformed ambiguous; do
  capacity_fixture_output="$(run_capacity_probe_fixture "$capacity_state")"
  if [ "$capacity_fixture_output" = 'SYSTEM_CAPACITY_UNKNOWN' ]; then
    pass "the exact device parser rejects $capacity_state capacity output"
  else
    fail_test "the exact device parser rejects $capacity_state capacity output"
  fi
done

MOCK_DEVICE_AWK=missing MOCK_SYSTEM_AVAIL_KB=12 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an awk-less Android shell still determines writable-system capacity"
assert_log_contains 'df -P -k /system' "capacity probing requests one portable POSIX-format row"
assert_not_contains 'df[^|]*/system[^|]*\|[[:space:]]*awk' "$MOCK_CALL_LOG" \
  "capacity probing has no device-side awk dependency"

for capacity_state in missing missing-df missing-sed malformed ambiguous; do
  MOCK_SYSTEM_CAPACITY="$capacity_state" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
  assert_failure "$capacity_state /system capacity fails closed"
  assert_contains 'system capacity could not be determined safely' \
    "$capacity_state capacity gives a safe retry reason"
  assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" \
    "$capacity_state capacity stops before a helper transaction or APK replacement"
done

MOCK_SYSTEM_AVAIL_KB=12 MOCK_VENDOR_INIT_RW=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "full /system without writable vendor init authority fails closed"
assert_contains '/vendor/etc/init.*not writable|writable.*vendor.*init' "vendor-blocked hybrid names the unavailable boot authority"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" \
  "unwritable vendor init stops before a helper transaction or APK replacement"

MOCK_SYSTEM_AVAIL_KB=1048576 MOCK_VENDOR_RC_STATE=managed MOCK_VENDOR_INIT_RW=0 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an established hybrid layout fails closed when its vendor authority becomes unwritable"
assert_contains '/vendor/etc/init.*not writable|writable.*vendor.*init' "unwritable managed hybrid gives a direct recovery reason"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" \
  "unwritable managed hybrid stops before a helper transaction or APK replacement"

MOCK_SYSTEM_AVAIL_KB=12 MOCK_VENDOR_RC_STATE=unexpected run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unexpected vendor startup file is never overwritten"
assert_contains 'unexpected existing /vendor/etc/init/hapaneld-helper\.rc' "unexpected vendor authority is identified"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless|hybrid)|^adb .* install( |$)' "$MOCK_CALL_LOG" \
  "unexpected vendor authority stops before a helper transaction or APK replacement"

MOCK_SYSTEM_AVAIL_KB=1048576 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "ample writable /system keeps the normal system layout"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-system' "ample capacity selects the system transactional installer"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "$MOCK_CALL_LOG" "ample capacity does not create a new hybrid layout"

MOCK_SYSTEM_AVAIL_KB=1023 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "capacity immediately below the transactional floor selects hybrid"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "1023KB remains below the minimum safe transactional headroom"

MOCK_SYSTEM_AVAIL_KB=1024 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "capacity at the transactional floor keeps the system layout"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-system' "1024KB meets the minimum safe transactional headroom"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "$MOCK_CALL_LOG" "the exact capacity floor does not route to hybrid"

MOCK_SYSTEM_AVAIL_KB=1048576 MOCK_VENDOR_RC_STATE=managed \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an established hybrid layout remains sticky when /system later has space"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "sticky hybrid upgrades through its existing authority"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-system([^l]|$)' "$MOCK_CALL_LOG" "sticky hybrid does not migrate implicitly to system"

HAPANELD_HELPER_PROBE= MOCK_SYSTEM_AVAIL_KB=12 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "hybrid validation invokes the exact newly installed data helper"
assert_log_contains 'exec /data/adb/hapaneld/hapaneld-helper --request COMPANIONCAPS' "hybrid validation probes the data helper"
assert_not_contains 'exec /system/bin/hapaneld-helper --request' "$MOCK_CALL_LOG" "hybrid validation never probes the alternate system helper"

MOCK_MANUAL_STALE=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "provisioning routes an interrupted standalone helper journal back to its owning installer"
assert_contains 'incomplete standalone root-helper installation must be recovered first' "manual-to-provision handoff names the cross-tool boundary"
assert_contains 'helper/install-daemon\.sh' "manual-to-provision handoff gives the exact recovery command"
assert_not_contains '^adb .* push .* /data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "manual-to-provision handoff stops before privileged staging or APK replacement"

MOCK_TRANSACTION_TAMPER=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a transaction substituted after adb staging fails before root execution"
assert_contains 'could not be promoted into protected storage' "substituted transaction names the protected-storage boundary"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)|^adb .* install( |$)' "$MOCK_CALL_LOG" "substituted transaction executes no privileged installer and replaces no APK"

MOCK_TRANSACTION_BUSY=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a live standalone-installer lock blocks provisioner mutation"
assert_contains 'another root-helper transaction is active' "cross-tool lock contention fails closed"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-(system|systemless)|^adb .* install( |$)' "$MOCK_CALL_LOG" "cross-tool contention performs no rollback or APK replacement"

MOCK_STALE_TRANSACTION=1 MOCK_ACTIVE_TRANSACTION=1 \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "provisioner B cannot recover provisioner A while A's durable lease is active"
assert_contains 'another provisioner still owns the active root-helper transaction' "interleaved provisioner reports the live owner"
assert_not_contains 'helper-transaction-[0-9a-f]+.*(status|rollback|commit)-(system|systemless)|^adb .* install( |$)' "$MOCK_CALL_LOG" "interleaved provisioner cannot inspect destructively, roll back, commit, or replace A's APK"

# Starting with v0.9.4, official releases carry ABI-specific helper assets authenticated by the same
# release key as the APK and provisioner. The helper proof and device-side staging must complete
# before the APK is replaced.
run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "current release installs its sealed root-helper asset"
assert_contains 'root helper.*downloading ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a' "release provisioning reports the helper download stage"
assert_contains 'authenticating helper.*v0\.9\.4-rc1.*arm64-v8a' "release provisioning reports helper proof inspection"
assert_contains 'helper compatibility.*authenticated provisioner.*build identity' "release provisioning reports paired helper identity inspection"
assert_log_contains '^curl .*releases/download/v0\.9\.4-rc1/ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a -o ' "release provisioning downloads the exact ABI helper asset"
assert_log_contains '^curl .*ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a\.sha256 -o ' "release provisioning downloads the helper checksum"
assert_log_contains '^curl .*ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a\.sha256\.sig -o ' "release provisioning downloads the helper checksum signature"
assert_log_contains '^openssl dgst -sha256 -verify .* -signature .*/helper\.sha256\.sig .*/helper\.sha256$' "release provisioning authenticates the helper checksum"
assert_contains 'authenticated.*v0\.9\.4-rc1 helper.*arm64-v8a' "release provisioning reports helper authentication"
release_helper_signature_line="$(grep -nE '^openssl dgst -sha256 -verify .*helper\.sha256' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
release_helper_push_line="$(grep -nE '^adb .* push .*ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
release_app_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$release_helper_signature_line" ] && [ -n "$release_helper_push_line" ] && [ -n "$release_app_install_line" ] && \
   [ "$release_helper_signature_line" -lt "$release_helper_push_line" ] && [ "$release_helper_push_line" -lt "$release_app_install_line" ]; then
  pass "release helper is authenticated, staged, then followed by APK installation"
else
  fail_test "release helper is authenticated, staged, then followed by APK installation"
fi

MOCK_ABI=armeabi-v7a \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "current release selects the 32-bit helper on armeabi-v7a panels"
assert_log_contains '^curl .*releases/download/v0\.9\.4-rc1/ha-paneld-helper-v0\.9\.4-rc1-armeabi-v7a -o ' "32-bit provisioning downloads only the matching helper asset"
assert_not_contains 'ha-paneld-helper-v0\.9\.4-rc1-arm64-v8a' "$MOCK_CALL_LOG" "32-bit provisioning does not fetch the arm64 helper"

MOCK_HELPER_PROOF_DOWNLOAD=checksum_fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "missing helper checksum fails closed"
assert_contains 'could not download the signed helper checksum' "missing helper proof names the incomplete release"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "missing helper proof stops before helper staging or APK replacement"

MOCK_HELPER_SIGNATURE_FAIL=1 \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "invalid helper checksum signature fails closed"
assert_contains 'helper checksum signature is invalid' "invalid helper signature names the authentication failure"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid helper signature stops before helper staging or APK replacement"

MOCK_HELPER_CHECKSUM=mismatch \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "helper checksum mismatch fails closed"
assert_contains 'helper checksum does not match its signed record' "helper checksum mismatch names the integrity failure"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "helper mismatch stops before helper staging or APK replacement"

MOCK_HELPER_START=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "helper start failure leaves the previous APK installed"
assert_contains 'new root helper failed its capability check; the prior helper was restored' "helper start failure names the rollback outcome"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "helper start failure stops before APK replacement"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "system helper capability failure invokes its rollback journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*finalize-rollback-system' "verified rollback reaches its identity-gated cleanup point"
if [ ! -f "$TMP/active-helper-transaction" ]; then
  pass "verified provisioner rollback removes its retained recovery state"
else
  fail_test "verified provisioner rollback removes its retained recovery state"
fi

MOCK_HELPER_CAPABILITY=fail MOCK_ROLLBACK_PING=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "failed restored-helper PING leaves rollback unverified"
assert_contains 'rollback could not be verified' "failed rollback PING does not claim restoration"
if [ -f "$TMP/active-helper-transaction" ]; then
  pass "failed provisioner rollback verification retains its durable recovery state"
else
  fail_test "failed provisioner rollback verification retains its durable recovery state"
fi
assert_not_contains 'finalize-rollback-system' "$MOCK_CALL_LOG" "unverified rollback never reaches destructive finalization"
rm -f "$TMP/active-helper-transaction"

MOCK_HELPER_CAPABILITY=fail MOCK_ROLLBACK_RETIREMENT=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "unterminated prior helper leaves rollback unverified"
assert_contains 'rollback could not be verified' "retirement timeout does not claim restoration"
if [ -f "$TMP/active-helper-transaction" ]; then
  pass "retirement timeout retains durable recovery state"
else
  fail_test "retirement timeout retains durable recovery state"
fi
assert_not_contains 'finalize-rollback-system' "$MOCK_CALL_LOG" "retirement timeout never finalizes rollback"
rm -f "$TMP/active-helper-transaction"

HAPANELD_HELPER_PROBE= MOCK_HELPER_CAPABILITY=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "rollback verification does not require the restored legacy helper to implement client mode"
assert_log_contains '/data/adb/hapaneld/\.helper-probe-[0-9a-f]+ --request PING' "rollback PING uses the retained authenticated new client"
assert_not_contains 'exec /system/bin/hapaneld-helper --request PING' "$MOCK_CALL_LOG" "rollback never executes the restored legacy daemon as a client"

MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "helper staging failure leaves the previous APK installed"
assert_contains 'root-helper install failed' "helper staging failure names the incomplete migration"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "failed system transaction preserves or restores the prior helper"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed helper staging does not replace the APK"

MOCK_SYSTEM_WRITABLE=0 MOCK_HELPER_CAPABILITY=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "systemless helper capability failure leaves the previous APK installed"
assert_contains 'new root helper failed its capability check; the prior helper was restored' "systemless capability failure reports verified rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "systemless capability failure invokes its rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "systemless capability failure stops before APK replacement"

MOCK_SYSTEM_WRITABLE=0 MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "systemless helper install failure leaves the previous APK installed"
assert_contains 'systemless root-helper install failed; the prior helper was preserved or restored' "systemless install failure reports its rollback outcome"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "failed systemless transaction invokes its rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed systemless transaction does not replace the APK"

MOCK_SYSTEM_AVAIL_KB=12 MOCK_HELPER_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "hybrid helper install failure leaves the previous APK installed"
assert_contains 'hybrid root-helper install failed; the prior helper was preserved or restored' "hybrid install failure reports its rollback outcome"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "failed hybrid transaction invokes its rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed hybrid transaction does not replace the APK"

MOCK_SYSTEM_AVAIL_KB=12 MOCK_HELPER_CAPABILITY=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "hybrid helper capability failure leaves the previous APK installed"
assert_contains 'new root helper failed its capability check; the prior helper was restored' "hybrid capability failure reports verified rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "hybrid capability failure invokes its rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "hybrid capability failure stops before APK replacement"

MOCK_HELPER_BUILD_ID_MATCH=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "helper build-identity mismatch leaves the previous APK installed"
assert_contains 'failed its exact build-identity check; the prior helper was restored' "helper identity mismatch reports verified rollback"
assert_log_contains 'helper-probe BUILDID' "provisioning probes the running daemon build identity"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "helper identity mismatch invokes the rollback journal"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "helper identity mismatch stops before APK replacement"

MOCK_HELPER_CAPABILITY=fail MOCK_ROLLBACK_RESULT=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "missing or invalid recovery snapshot fails closed"
assert_contains 'rollback could not be verified' "unverifiable recovery is reported without claiming restoration"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "unverifiable recovery still stops before APK replacement"

MOCK_APK_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "APK package-manager failure rolls back the already-verified new helper"
assert_contains 'prior root helper was restored and verified' "APK failure reports helper rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "APK failure invokes the retained helper rollback journal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*commit-system' "$MOCK_CALL_LOG" "APK failure never commits helper recovery"

MOCK_SYSTEM_WRITABLE=0 MOCK_APK_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "APK failure also rolls back a systemless helper upgrade"
assert_contains 'prior root helper was restored and verified' "systemless APK failure reports helper rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "systemless APK failure invokes the retained rollback journal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*commit-systemless' "$MOCK_CALL_LOG" "systemless APK failure never commits helper recovery"

MOCK_SYSTEM_AVAIL_KB=12 MOCK_APK_INSTALL=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "APK failure also rolls back a hybrid helper upgrade"
assert_contains 'prior root helper was restored and verified' "hybrid APK failure reports helper rollback"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "hybrid APK failure invokes the retained rollback journal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*commit-hybrid' "$MOCK_CALL_LOG" "hybrid APK failure never commits helper recovery"

APK_INSTALL_PID_FILE="$TMP/apk-install-hang.pid"
MOCK_APK_INSTALL=ignore_term MOCK_APK_INSTALL_PID_FILE="$APK_INSTALL_PID_FILE" \
APK_INSTALL_TIMEOUT_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "stuck main APK install returns nonzero at its host deadline"
assert_contains 'install did not finish within the 1s safety deadline' "main APK timeout names the bounded failed step"
apk_install_pid="$(cat "$APK_INSTALL_PID_FILE" 2>/dev/null || true)"
if [ -n "$apk_install_pid" ] && ! kill -0 "$apk_install_pid" 2>/dev/null; then
  pass "main APK timeout reaps the blocked adb install"
else
  fail_test "main APK timeout reaps the blocked adb install"
fi
if [ "$(grep -Ec '^adb .* install( |$)' "$MOCK_CALL_LOG")" -eq 1 ]; then
  pass "ambiguous main APK timeout is not retried with different install flags"
else
  fail_test "ambiguous main APK timeout is not retried with different install flags"
fi
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "main APK timeout reconciles and rolls back the retained helper transaction"

HYBRID_APK_INSTALL_PID_FILE="$TMP/hybrid-apk-install-hang.pid"
MOCK_SYSTEM_AVAIL_KB=12 MOCK_APK_INSTALL=ignore_term MOCK_APK_INSTALL_PID_FILE="$HYBRID_APK_INSTALL_PID_FILE" \
APK_INSTALL_TIMEOUT_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "stuck APK install on the hybrid path returns nonzero at its host deadline"
assert_contains 'install did not finish within the 1s safety deadline' "hybrid APK timeout retains the bounded failure reason"
hybrid_apk_install_pid="$(cat "$HYBRID_APK_INSTALL_PID_FILE" 2>/dev/null || true)"
if [ -n "$hybrid_apk_install_pid" ] && ! kill -0 "$hybrid_apk_install_pid" 2>/dev/null; then
  pass "hybrid APK timeout reaps the blocked adb install"
else
  fail_test "hybrid APK timeout reaps the blocked adb install"
fi
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "hybrid APK timeout reconciles and rolls back its retained transaction"

MOCK_APK_INSTALL=grant_flag_unsupported \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "exact unsupported grant-all diagnostic retries a plain install"
if [ "$(grep -Ec '^adb .* install( |$)' "$MOCK_CALL_LOG")" -eq 2 ]; then
  pass "grant-all fallback performs exactly one classified plain retry"
else
  fail_test "grant-all fallback performs exactly one classified plain retry"
fi

MOCK_APK_INSTALL=ambiguous_commit \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "transport loss after package-manager commit is reconciled from exact installed APK bytes"
assert_contains 'exact target APK bytes are installed; completing the helper transaction' "ambiguous install success explains the exact-byte reconciliation"
assert_log_contains '^adb .* shell pm path io\.github\.maxlyth\.hapaneld$' "ambiguous install outcome queries the installed package path"
assert_log_contains '^adb .* pull /data/app/io\.github\.maxlyth\.hapaneld/base\.apk ' "ambiguous install outcome authenticates the installed base APK"
assert_log_contains 'helper-transaction-[0-9a-f]+.*commit-system' "confirmed package-manager commit also commits helper recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "confirmed package-manager commit never rolls the matching helper back"
if [ "$(grep -Ec '^adb .* install( |$)' "$MOCK_CALL_LOG")" -eq 1 ]; then
  pass "ambiguous committed install is reconciled without replay"
else
  fail_test "ambiguous committed install is reconciled without replay"
fi

MOCK_SYSTEM_AVAIL_KB=12 MOCK_APK_INSTALL=ambiguous_commit \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "hybrid transport loss after package-manager commit reconciles exact installed APK bytes"
assert_log_contains 'helper-transaction-[0-9a-f]+.*commit-hybrid' "confirmed package-manager commit commits hybrid recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "$MOCK_CALL_LOG" "confirmed hybrid package-manager commit is never rolled back"
if [ "$(grep -Ec '^adb .* install( |$)' "$MOCK_CALL_LOG")" -eq 1 ]; then
  pass "ambiguous committed hybrid install is reconciled without replay"
else
  fail_test "ambiguous committed hybrid install is reconciled without replay"
fi

MOCK_HELPER_COMMIT=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "unconfirmed helper journal commit fails without an unsafe rollback"
assert_contains 'helper recovery journal could not be committed' "unconfirmed commit names the retained recovery state"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "unconfirmed post-APK commit never claims or attempts rollback"
commit_attempts="$(grep -Ec 'helper-transaction-[0-9a-f]+.*commit-system' "$MOCK_CALL_LOG" || true)"
if [ "$commit_attempts" -eq 2 ]; then
  pass "helper commit is retried idempotently before failing"
else
  fail_test "helper commit is retried idempotently before failing"
fi

MOCK_COMMIT_LIVE_STATE=UNKNOWN \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "commit-time persistence mutation retains the active helper recovery journal"
assert_contains 'helper recovery journal could not be committed' "commit-time UNKNOWN state fails closed after APK installation"
if [ -f "$TMP/active-helper-transaction" ]; then
  pass "commit-time UNKNOWN state preserves active recovery evidence"
else
  fail_test "commit-time UNKNOWN state preserves active recovery evidence"
fi
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "commit-time UNKNOWN state never performs an unsafe post-APK rollback"

MOCK_TOKEN_MISMATCH_ACTION=commit \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a mismatched transaction token cannot commit another provisioner's journal"
assert_contains 'helper recovery journal could not be committed' "commit token mismatch retains the recovery journal"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "commit token mismatch never falls back to another owner's rollback"

MOCK_HELPER_START=fail MOCK_TOKEN_MISMATCH_ACTION=rollback \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "a mismatched transaction token cannot roll back another provisioner's journal"
assert_contains 'rollback could not be verified' "rollback token mismatch is reported without claiming restoration"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "rollback token mismatch leaves the APK untouched"

MOCK_APK_INSTALL=fail MOCK_APK_QUERY=fail \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "unqueryable package-manager outcome retains helper recovery without rollback"
assert_contains 'adb install outcome is ambiguous; helper recovery was retained without rollback' "unqueryable install outcome gives the safe retry path"
assert_not_contains 'hapaneld-helper\.txn (rollback|commit)-system' "$MOCK_CALL_LOG" "unqueryable install outcome neither rolls back nor commits the helper journal"

# TERM must interrupt the shell's wait for package-manager completion, stop and reap both local
# subprocesses, and prevent the detached lease heartbeat from extending ownership indefinitely.
: > "$MOCK_CALL_LOG"
rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"
printf 'previous installed apk\n' > "$TMP/installed-apk"
blocked_install_pid_file="$TMP/blocked-install.pid"
blocked_guard_pid_file="$TMP/blocked-guard.pid"
blocked_guard_sleep_pid_file="$TMP/blocked-guard-sleep.pid"
blocked_output="$TMP/blocked-provision-output.txt"
lease_guard_tmp="$TMP/lease-guard-tmp"
mkdir -p "$lease_guard_tmp"
MOCK_APK_INSTALL=block \
MOCK_APK_INSTALL_PID_FILE="$blocked_install_pid_file" \
MOCK_STATE_DIR="$TMP" \
ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS=0.05 \
ROOT_HELPER_LEASE_GUARD_PID_FILE="$blocked_guard_pid_file" \
ROOT_HELPER_LEASE_GUARD_SLEEP_PID_FILE="$blocked_guard_sleep_pid_file" \
TMPDIR="$lease_guard_tmp" \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame \
    > "$blocked_output" 2>&1 &
blocked_provision_pid=$!
blocked_ready=0
blocked_wait_attempt=0
while [ "$blocked_wait_attempt" -lt 100 ]; do
  if [ -s "$blocked_install_pid_file" ]; then blocked_ready=1; break; fi
  /bin/sleep 0.05
  blocked_wait_attempt=$((blocked_wait_attempt + 1))
done
if [ "$blocked_ready" -eq 1 ]; then
  pass "blocked package install exposes its exact subprocess lifecycle"
else
  LAST_OUTPUT="$blocked_output"
  fail_test "blocked package install exposes its exact subprocess lifecycle"
fi
/bin/sleep 0.2
lease_count_before_term="$(grep -Ec 'helper-transaction-[0-9a-f]+.*lease-system' "$MOCK_CALL_LOG" || true)"
kill -TERM "$blocked_provision_pid" 2>/dev/null || true
if wait "$blocked_provision_pid"; then blocked_status=0; else blocked_status=$?; fi
if [ "$blocked_status" -eq 143 ]; then
  pass "TERM exits a provisioner blocked in adb install with signal status"
else
  LAST_OUTPUT="$blocked_output"
  fail_test "TERM exits a provisioner blocked in adb install with signal status (got $blocked_status)"
fi
blocked_install_pid="$(cat "$blocked_install_pid_file" 2>/dev/null || true)"
if [ -n "$blocked_install_pid" ] && ! kill -0 "$blocked_install_pid" 2>/dev/null; then
  pass "TERM reaps the blocked adb install subprocess"
else
  fail_test "TERM reaps the blocked adb install subprocess"
fi
blocked_guard_pid="$(cat "$blocked_guard_pid_file" 2>/dev/null || true)"
blocked_guard_sleep_pid="$(cat "$blocked_guard_sleep_pid_file" 2>/dev/null || true)"
if [ -n "$blocked_guard_pid" ] && [ -n "$blocked_guard_sleep_pid" ] && \
   ! kill -0 "$blocked_guard_pid" 2>/dev/null && ! kill -0 "$blocked_guard_sleep_pid" 2>/dev/null; then
  pass "TERM reaps the lease guard and its current sleep child"
else
  fail_test "TERM reaps the lease guard and its current sleep child"
fi
lease_count_at_exit="$(grep -Ec 'helper-transaction-[0-9a-f]+.*lease-system' "$MOCK_CALL_LOG" || true)"
/bin/sleep 0.2
lease_count_after_term="$(grep -Ec 'helper-transaction-[0-9a-f]+.*lease-system' "$MOCK_CALL_LOG" || true)"
if [ "$lease_count_before_term" -ge 2 ] && [ "$lease_count_after_term" -eq "$lease_count_at_exit" ]; then
  pass "TERM leaves no orphan lease guard renewing after parent exit"
else
  fail_test "TERM leaves no orphan lease guard renewing after parent exit ($lease_count_at_exit at exit, $lease_count_after_term later)"
fi
if [ -z "$(find "$lease_guard_tmp" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  pass "TERM removes lease-guard and adb-capture temporary files"
else
  fail_test "TERM removes lease-guard and adb-capture temporary files"
fi

helper_release_apk_sha="$(/usr/bin/sha256sum "$HELPER_RELEASE_APK" | awk '{print $1}')"
MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$HELPER_RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "rerun commits a stale helper journal when the exact target APK is already installed"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-system' "stale transaction reads its durable target identity"
assert_log_contains '^adb .* pull /data/app/io\.github\.maxlyth\.hapaneld/base\.apk ' "stale transaction authenticates the installed APK bytes"
assert_log_contains 'helper-probe COMPANIONCAPS' "stale committed transaction rechecks the privileged protocol"
assert_log_contains 'helper-probe BUILDID' "stale committed transaction rechecks the recorded helper build"
assert_log_contains 'helper-transaction-[0-9a-f]+.*commit-system' "stale committed transaction discards obsolete recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "stale committed transaction does not restore the superseded helper"

MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$HELPER_RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
MOCK_COMMIT_LIVE_STATE=UNKNOWN \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "stale reconciliation refuses to discard recovery after persistence changes before commit"
assert_contains 'incomplete prior root-helper and APK upgrade could not be reconciled safely' "stale commit-time UNKNOWN state names the retained reconciliation"
if [ -f "$TMP/stale-helper-transaction" ]; then
  pass "stale commit-time UNKNOWN state preserves its durable journal"
else
  fail_test "stale commit-time UNKNOWN state preserves its durable journal"
fi
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "stale exact-APK UNKNOWN state is not rolled back destructively"

MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "rerun rolls back a stale helper journal when the target APK was never installed"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "stale pre-APK transaction restores the prior helper before retrying"
rollback_line="$(grep -nE 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
retry_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$rollback_line" ] && [ -n "$retry_install_line" ] && [ "$rollback_line" -lt "$retry_install_line" ]; then
  pass "stale pre-APK recovery completes before the new package install"
else
  fail_test "stale pre-APK recovery completes before the new package install"
fi

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=systemless \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "writable-system provisioning recovers a stale systemless journal before changing paths"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-systemless' "systemless-to-system transition reads the retained systemless journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "systemless-to-system transition restores the owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-system' "systemless-to-system transition installs only after recovery"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=systemless \
MOCK_SYSTEM_AVAIL_KB=12 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "hybrid provisioning recovers a stale systemless journal before changing authority"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-systemless' "systemless-to-hybrid transition reads the retained systemless journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-systemless' "systemless-to-hybrid transition restores the owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "systemless-to-hybrid transition selects one new boot authority after recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)([^a-z]|$)' "$MOCK_CALL_LOG" "systemless-to-hybrid transition invokes no duplicate installer"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=system \
MOCK_SYSTEM_AVAIL_KB=12 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "hybrid provisioning recovers a stale system journal before changing authority"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-system' "system-to-hybrid transition reads the retained system journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "system-to-hybrid transition restores the owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "system-to-hybrid transition selects one new boot authority after recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)([^a-z]|$)' "$MOCK_CALL_LOG" "system-to-hybrid transition invokes no duplicate installer"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=hybrid \
MOCK_SYSTEM_AVAIL_KB=1048576 \
MOCK_VENDOR_RC_STATE=managed \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "sticky hybrid provisioning recovers its stale journal before retrying"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-hybrid' "hybrid reconciliation reads the retained hybrid journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-hybrid' "hybrid reconciliation restores its owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-hybrid' "hybrid reconciliation retains the existing boot authority"
assert_not_contains 'helper-transaction-[0-9a-f]+.*install-(system|systemless)([^a-z]|$)' "$MOCK_CALL_LOG" "sticky hybrid reconciliation invokes no duplicate installer"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=system \
MOCK_SYSTEM_WRITABLE=0 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "systemless provisioning recovers a stale /system journal before changing paths"
assert_log_contains 'helper-transaction-[0-9a-f]+.*status-system' "system-to-systemless transition reads the retained /system journal"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "system-to-systemless transition restores the owning transaction"
assert_log_contains 'helper-transaction-[0-9a-f]+.*install-systemless' "system-to-systemless transition installs only after recovery"

MOCK_STALE_TRANSACTION=1 \
MOCK_STALE_TRANSACTION_KIND=both \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "simultaneous root-helper journals fail closed"
assert_contains 'more than one root-helper recovery journal is present' "multi-journal failure explains the ambiguity"
assert_not_contains 'hapaneld-helper\.txn (rollback|commit)-(system|systemless|hybrid)' "$MOCK_CALL_LOG" "multi-journal ambiguity preserves all recovery records"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "dual-journal ambiguity stops before APK replacement"

MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
MOCK_STALE_LIVE_STATE=PRE_SWAP \
MOCK_STALE_BUILD_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "power loss after journal publication but before helper swap is recoverable"
assert_log_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "pre-swap crash recovery restores idempotently without requiring the target helper to be running"

MOCK_STALE_TRANSACTION=1 \
MOCK_INSTALLED_APK_SOURCE="$RELEASE_APK" \
MOCK_STALE_APK_SHA256="$helper_release_apk_sha" \
MOCK_STALE_LIVE_STATE=UNKNOWN \
MOCK_STALE_BUILD_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "stale journal with a superseded running helper fails without destructive rollback"
assert_contains 'could not be reconciled safely' "superseded stale helper reports the ambiguous compatibility state"
assert_contains 'No rollback was attempted' "superseded stale helper states the non-destructive outcome"
assert_not_contains 'hapaneld-helper\.txn (rollback|commit)-system' "$MOCK_CALL_LOG" "superseded stale helper leaves recovery evidence intact"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "superseded stale helper stops before another APK replacement"

release_metadata_build_id=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
MOCK_HELPER_BUILD_ID="$release_metadata_build_id" \
MOCK_RELEASE_PROVISION_BUILD_ID="$release_metadata_build_id" \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "moving-checkout provisioning uses the authenticated release helper identity"
assert_log_contains 'ha-paneld-provision-v0\.9\.4-rc1\.sh -o ' "moving-checkout provisioning downloads the matching versioned provisioner"
assert_log_contains 'ha-paneld-provision-v0\.9\.4-rc1\.sh\.sha256 -o ' "moving-checkout provisioning downloads the provisioner checksum"
assert_log_contains 'ha-paneld-provision-v0\.9\.4-rc1\.sh\.sha256\.sig -o ' "moving-checkout provisioning downloads the provisioner signature"
assert_log_contains "helper-probe BUILDID" "moving-checkout provisioning checks the release-recorded build identity"
assert_not_contains "$release_metadata_build_id" "$ROOT/helper/source-id.sh" "release metadata test identity differs from the current checkout identity"

MOCK_RELEASE_PROVISION_BUILD_ID=missing \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_failure "signed provisioner without an exact helper identity fails closed"
assert_contains 'expected root-helper build identity is unavailable' "missing release helper identity names the packaging failure"
assert_not_contains '/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "missing release helper identity stops before privileged staging or APK replacement"

MOCK_SU_DIALECT=shc \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "root-helper transaction works through an exec-style vendor su dialect"
assert_log_contains 'su 0 sh -c .*helper-transaction-[0-9a-f]+.*install-system' "vendor su executes only the staged transaction path"

caps_line="$(grep -n '^helper-probe COMPANIONCAPS$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
build_id_line="$(grep -n '^helper-probe BUILDID$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
app_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
commit_line="$(grep -nE 'helper-transaction-[0-9a-f]+.*commit-system' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$caps_line" ] && [ -n "$build_id_line" ] && [ -n "$app_line" ] && [ -n "$commit_line" ] && \
   [ "$caps_line" -lt "$build_id_line" ] && [ "$build_id_line" -lt "$app_line" ] && [ "$app_line" -lt "$commit_line" ]; then
  pass "exact helper capability and build identity succeed before APK replacement and recovery commits afterward"
else
  fail_test "exact helper capability and build identity succeed before APK replacement and recovery commits afterward"
fi

# Official release assets are authenticated before the first install, launch, or privilege grant
# whenever Android Build-Tools are present. The fixtures expose both apksigner and aapt.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "local APK with the pinned release signer and package is accepted"
assert_contains 'verified.*local signed APK' "local APK verification reports its signer"
assert_log_contains '^apksigner verify --print-certs ' "local APK verification invokes apksigner"
assert_log_contains '^aapt dump badging ' "local APK verification inspects the package name"

MOCK_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "self-built local APK with one developer signer remains installable"

MOCK_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$APK" --require-release-signer --no-tame
assert_failure "official-signer policy rejects a debug or foreign local APK"
assert_contains 'release APK signer mismatch' "local signer failure names the trust violation"
assert_contains 'This run requires the official release signer' "local signer failure states the official-signer requirement"
assert_not_contains 'config/export|ha-paneld-db-snapshot|/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "local signer failure stops before backup, helper staging, or APK replacement"

MOCK_ADDITIONAL_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "multi-signed local APK fails closed"
assert_contains 'release APK signer count mismatch' "multi-signed local APK names the signer-count violation"
assert_not_contains 'config/export|ha-paneld-db-snapshot|/data/local/tmp/hapaneld-helper|^adb .* install( |$)' "$MOCK_CALL_LOG" "multi-signed local APK stops before backup, helper staging, or APK replacement"

run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_success "release APK with the pinned signer and package is accepted"
assert_contains 'authenticated.*v0\.9\.2-rc3' "release verification reports the signed checksum authentication"
assert_contains 'verified.*v0\.9\.2-rc3' "release verification reports the authenticated tag"
assert_log_contains '^curl .*https://github\.com/maxlyth/ha-paneld/releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk\.sha256 -o ' "release verification downloads the canonical checksum asset"
assert_log_contains '^curl .*https://github\.com/maxlyth/ha-paneld/releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk\.sha256\.sig -o ' "release verification downloads the canonical detached signature"
assert_log_contains '^openssl dgst -sha256 -verify .* -signature .*/release\.sha256\.sig .*/release\.sha256$' "release verification authenticates the checksum record"
assert_log_contains '^openssl dgst -sha256 -r .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' "release verification hashes the downloaded APK"
assert_log_contains '^apksigner verify --print-certs .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' "release verification invokes apksigner"
assert_log_contains '^aapt dump badging .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk$' "release verification inspects the package name"
signer_line="$(grep -nE '^apksigner verify --print-certs ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
package_line="$(grep -nE '^aapt dump badging ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
signature_line="$(grep -nE '^openssl dgst -sha256 -verify ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
checksum_line="$(grep -nE '^openssl dgst -sha256 -r ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
app_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$signature_line" ] && [ -n "$checksum_line" ] && [ -n "$signer_line" ] && [ -n "$package_line" ] && [ -n "$app_install_line" ] && \
   [ "$signature_line" -lt "$checksum_line" ] && [ "$checksum_line" -lt "$signer_line" ] && \
   [ "$signer_line" -lt "$app_install_line" ] && [ "$package_line" -lt "$app_install_line" ]; then
  pass "release proof, signer, and package are verified before adb install"
else
  fail_test "release proof, signer, and package are verified before adb install"
fi

# Platform Tools deliberately do not include apksigner. The signed checksum remains the required
# publisher-authentication path, while the additional APK structure inspection is optional.
PATH="$NO_SIGNER_FIXTURES:/usr/bin:/bin" ANDROID_HOME= ANDROID_SDK_ROOT= \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_success "release install remains usable without optional Android Build-Tools"
assert_contains 'optional APK structure inspection was skipped' "missing apksigner accurately describes the skipped secondary check"
assert_contains 'authenticated by the signed checksum' "missing apksigner still reports the authenticated release path"
assert_log_contains '^openssl dgst -sha256 -verify ' "no-apksigner path still authenticates the checksum signature"
assert_log_contains '^adb .* install( |$)' "authenticated no-apksigner path still installs"

MOCK_OPENSSL_MISSING=1 \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "official release install fails closed when OpenSSL is unavailable"
assert_contains 'OpenSSL is required to authenticate this official ha-paneld release' "missing OpenSSL names the required trust check"
assert_contains 'Windows:.*Git Bash or WSL' "missing OpenSSL gives novice-friendly Windows guidance"
assert_contains 'macOS:.*xcode-select' "missing OpenSSL gives novice-friendly macOS guidance"
assert_contains 'Debian/Ubuntu:.*apt install openssl' "missing OpenSSL gives novice-friendly Linux guidance"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "missing OpenSSL stops before APK install"
assert_not_contains '^adb .* shell (am start|monkey -p io\.github\.maxlyth\.hapaneld|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "missing OpenSSL stops before launch or grants"

MOCK_RELEASE_PROOF_DOWNLOAD=checksum_fail \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "missing release checksum asset fails closed"
assert_contains 'could not download the signed checksum' "missing checksum asset names the incomplete release"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "missing checksum asset stops before APK install"

MOCK_RELEASE_PROOF_DOWNLOAD=signature_fail \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "missing release checksum signature fails closed"
assert_contains 'could not download the checksum signature' "missing signature asset names the incomplete release"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "missing signature asset stops before APK install"

MOCK_RELEASE_SIGNATURE_FAIL=1 \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "invalid release checksum signature fails closed"
assert_contains 'checksum signature is invalid' "invalid signature names the authentication failure"
assert_contains 'Nothing was installed, started, or privileged' "invalid signature states the safe outcome"
assert_not_contains '^openssl dgst -sha256 -r ' "$MOCK_CALL_LOG" "invalid signature is rejected before trusting or hashing the APK"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid signature stops before APK install"

MOCK_RELEASE_CHECKSUM=malformed \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "malformed but signed checksum record fails closed"
assert_contains 'signed checksum record.*is malformed' "malformed checksum names the release metadata error"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "malformed checksum stops before APK install"

MOCK_RELEASE_CHECKSUM=mismatch \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "APK checksum mismatch fails closed"
assert_contains 'APK checksum does not match its signed record' "checksum mismatch names the integrity failure"
assert_contains 'Nothing was installed, started, or privileged' "checksum mismatch states the safe outcome"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "checksum mismatch stops before APK install"

MOCK_OPENSSL_DIGEST_FAIL=1 \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "local APK digest failure stops release installation"
assert_contains 'OpenSSL could not calculate the downloaded APK checksum' "digest failure gives a direct recovery path"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "digest failure stops before APK install"

MOCK_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "release APK with a foreign signer fails closed"
assert_contains 'release APK signer mismatch' "foreign signer failure names the trust violation"
assert_contains 'Nothing was installed, started, or privileged' "foreign signer failure states the safe outcome"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "foreign signer is rejected before APK install"
assert_not_contains '^adb .* shell (am start|monkey -p io\.github\.maxlyth\.hapaneld|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "foreign signer is rejected before launch or grants"

MOCK_RELEASE_PACKAGE=example.foreign \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "release APK with a foreign package name fails closed"
assert_contains 'release APK package mismatch' "foreign package failure names the trust violation"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "foreign package is rejected before APK install"
assert_not_contains '^adb .* shell (am start|monkey -p io\.github\.maxlyth\.hapaneld|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "foreign package is rejected before launch or grants"

run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag '../../main' --no-tame
assert_status 2 "invalid internal release tag is rejected as a usage error"
assert_contains 'invalid release tag' "invalid internal release tag gives a direct correction"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid release tag is rejected before APK install"

if [ "$PROVISION_TEST_SCOPE" = all ]; then
# A panel without the manager must receive the exact pinned APK, verify it before installation, and
# start the authenticated installed native starter. The fake checksum tool makes this deterministic without network
# access while the call log proves the security-sensitive ordering.
run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "missing Shizuku manager is bootstrapped"
assert_contains 'Shizuku.*inspecting the installed manager and signer' "Shizuku bootstrap reports its package and signer inspection"
assert_log_contains 'curl .*shizuku-v13\.6\.0\.r1086\.2650830c-release\.apk.*-o .*/shizuku\.apk' "Shizuku bootstrap downloads the curated manager"
assert_log_contains '^sha256sum .*/shizuku\.apk$' "Shizuku bootstrap verifies the downloaded manager"
assert_log_contains '^adb .* install -r .*/shizuku\.apk$' "Shizuku bootstrap installs the verified manager"
download_line="$(grep -nE 'curl .*shizuku-v13\.6\.0\.r1086\.2650830c-release\.apk' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
checksum_line="$(grep -nE '^sha256sum .*/shizuku\.apk$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
install_line="$(grep -nE '^adb .* install -r .*/shizuku\.apk$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$download_line" ] && [ -n "$checksum_line" ] && [ -n "$install_line" ] && \
   [ "$download_line" -lt "$checksum_line" ] && [ "$checksum_line" -lt "$install_line" ]; then
  pass "Shizuku manager is downloaded, checksummed, then installed in order"
else
  fail_test "Shizuku manager is downloaded, checksummed, then installed in order"
fi
assert_log_contains '^adb .* shell monkey -p moe\.shizuku\.privileged\.api 1$' "Shizuku bootstrap launches the authenticated manager"
assert_log_contains '^adb .* shell test -x /data/app/.*/lib/arm64/libshizuku\.so$' "Shizuku bootstrap verifies the installed native starter"
assert_log_contains '^adb .* shell /data/app/.*/lib/arm64/libshizuku\.so$' "Shizuku bootstrap starts the service without external-storage shell code"
assert_not_contains '/storage/emulated/.*/start\.sh' "$MOCK_CALL_LOG" "Shizuku bootstrap never executes mutable external-storage script code"
assert_log_contains '^adb .* shell pm grant moe\.shizuku\.privileged\.api android\.permission\.WRITE_SECURE_SETTINGS$' "Shizuku bootstrap enables supported restart setup"

MOCK_SHIZUKU_INSPECT=block SHIZUKU_INSPECT_TIMEOUT_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "stuck Shizuku manager inspection returns nonzero at its host deadline"
assert_contains 'Shizuku manager inspection timed out after 1s' "Shizuku inspection timeout names the bounded failed step"
assert_contains 'Restore adb/package-manager responsiveness' "Shizuku inspection timeout gives an actionable recovery path"
assert_not_contains '^adb .* install -r -g .*ha-paneld.*\.apk$' "$MOCK_CALL_LOG" "timed-out Shizuku inspection leaves ha-paneld untouched"

# A stuck Shizuku package-manager operation must remain inside the root-helper ownership window,
# reject a competing provisioner, then be killed and reaped at its host deadline without replacing
# ha-paneld or leaving a lease heartbeat behind.
: > "$MOCK_CALL_LOG"
rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"
printf 'previous installed apk\n' > "$TMP/installed-apk"
SHIZUKU_INSTALL_PID_FILE="$TMP/shizuku-install-hang.pid"
SHIZUKU_INSTALL_OUTPUT="$TMP/shizuku-install-hang.out"
MOCK_SHIZUKU_INSTALL=block \
MOCK_SHIZUKU_INSTALL_PID_FILE="$SHIZUKU_INSTALL_PID_FILE" \
MOCK_STATE_DIR="$TMP" \
SHIZUKU_INSTALL_TIMEOUT_SECONDS=4 \
ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS=0.05 \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --shizuku --no-tame \
    > "$SHIZUKU_INSTALL_OUTPUT" 2>&1 &
shizuku_owner_pid=$!
shizuku_install_ready=0
for _ in {1..100}; do
  if [ -s "$SHIZUKU_INSTALL_PID_FILE" ]; then shizuku_install_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$shizuku_install_ready" -eq 1 ]; then
  pass "blocked Shizuku package install exposes its subprocess lifecycle"
else
  LAST_OUTPUT="$SHIZUKU_INSTALL_OUTPUT"
  fail_test "blocked Shizuku package install exposes its subprocess lifecycle"
fi
touch "$TMP/stale-helper-transaction"
COMPETING_OUTPUT="$TMP/shizuku-competing-provision.out"
MOCK_ACTIVE_TRANSACTION=1 MOCK_STATE_DIR="$TMP" \
  bash "$PROVISION" "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame \
    > "$COMPETING_OUTPUT" 2>&1
competing_status=$?
if [ "$competing_status" -ne 0 ] && grep -Eq 'active root-helper transaction|still owns' "$COMPETING_OUTPUT"; then
  pass "competing provisioner cannot recover a helper transaction during Shizuku installation"
else
  LAST_OUTPUT="$COMPETING_OUTPUT"
  fail_test "competing provisioner cannot recover a helper transaction during Shizuku installation"
fi
if wait "$shizuku_owner_pid"; then shizuku_owner_status=0; else shizuku_owner_status=$?; fi
LAST_OUTPUT="$SHIZUKU_INSTALL_OUTPUT"
if [ "$shizuku_owner_status" -ne 0 ]; then
  pass "stuck Shizuku package install returns nonzero at its host deadline"
else
  fail_test "stuck Shizuku package install returns nonzero at its host deadline"
fi
assert_contains 'Shizuku installation timed out after 4s' "Shizuku package timeout names the bounded failed step"
shizuku_install_pid="$(cat "$SHIZUKU_INSTALL_PID_FILE" 2>/dev/null || true)"
if [ -n "$shizuku_install_pid" ] && ! kill -0 "$shizuku_install_pid" 2>/dev/null; then
  pass "Shizuku package timeout reaps the blocked adb install"
else
  fail_test "Shizuku package timeout reaps the blocked adb install"
fi
if [ "$(grep -Ec 'helper-transaction-[0-9a-f]+.*lease-system' "$MOCK_CALL_LOG" || true)" -ge 2 ]; then
  pass "root-helper lease remains renewed throughout Shizuku package installation"
else
  fail_test "root-helper lease remains renewed throughout Shizuku package installation"
fi
assert_not_contains '^adb .* install -r -g .*ha-paneld.*\.apk$' "$MOCK_CALL_LOG" "timed-out Shizuku package install leaves ha-paneld untouched"
rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"

# A corrupt or substituted download must stop before the manager package or service is touched.
MOCK_SHIZUKU_DOWNLOAD_SHA=0000000000000000000000000000000000000000000000000000000000000000 \
  run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "Shizuku checksum mismatch returns nonzero"
assert_contains 'Shizuku download checksum mismatch' "Shizuku checksum failure names the integrity problem"
assert_contains 'Nothing was installed' "Shizuku checksum failure states the safe outcome"
assert_not_contains '^adb .* install -r .*/shizuku\.apk$' "$MOCK_CALL_LOG" "checksum failure blocks Shizuku installation"
assert_not_contains '^adb .* shell (monkey -p moe\.shizuku|sh .*/moe\.shizuku.*start\.sh|pm grant moe\.shizuku)' "$MOCK_CALL_LOG" "checksum failure blocks Shizuku launch and rearm"
assert_not_contains '^adb .* install -r -g .*ha-paneld\.apk$' "$MOCK_CALL_LOG" "fatal Shizuku bootstrap failure happens before replacing ha-paneld"

# Export composes with Shizuku setup: the verified backup must finish before any package mutation.
COMBINED_EXPORT="$TMP/panel-backup-with-shizuku.json"
run_provision "$MOCK_TARGET" --export "$COMBINED_EXPORT" --apk "$APK" --shizuku --no-tame
assert_success "export and Shizuku setup compose in one provisioning run"
assert_log_contains '^adb .* install -r .*/shizuku\.apk$' "combined export continues into Shizuku bootstrap"
export_line="$(grep -nE 'config/export' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
combined_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$export_line" ] && [ -n "$combined_install_line" ] && [ "$export_line" -lt "$combined_install_line" ]; then
  pass "combined export completes before package installation"
else
  fail_test "combined export completes before package installation"
fi

# A manager service-start failure must complete and launch the core agent for recovery, but the
# requested enhanced-access setup and any enclosing fleet operation must still fail truthfully.
MOCK_SHIZUKU_START=fail run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "Shizuku service-start failure returns nonzero"
assert_contains 'service did not start' "Shizuku start failure names the incomplete step"
assert_log_contains '^adb .* install -r -g .*ha-paneld\.apk$' "Shizuku start failure still installs the core agent"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "Shizuku start failure still launches the core agent"

# A stuck device-side script must be terminated at a host deadline. It has the same recoverable
# semantics as any other service-start failure: install and relaunch the core agent, then return
# nonzero to both an individual caller and an enclosing fleet run.
SHIZUKU_HANG_PID_FILE="$TMP/shizuku-hang.pid"
MOCK_SHIZUKU_START=hang MOCK_SHIZUKU_HANG_PID_FILE="$SHIZUKU_HANG_PID_FILE" \
  SHIZUKU_START_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "stuck Shizuku service start returns nonzero at its host deadline"
assert_contains 'service start timed out after 1s' "Shizuku timeout reports the bounded failed step"
assert_log_contains '^adb .* install -r -g .*ha-paneld\.apk$' "Shizuku timeout still installs the core agent"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "Shizuku timeout still launches the core agent"
if [ -s "$SHIZUKU_HANG_PID_FILE" ] && ! kill -0 "$(cat "$SHIZUKU_HANG_PID_FILE")" 2>/dev/null; then
  pass "Shizuku timeout leaves no service-start worker behind"
else
  fail_test "Shizuku timeout leaves no service-start worker behind"
fi

# Hosts without GNU coreutils use Bash job control to isolate the host invocation in a process
# group. Force that fallback and retain a real child below the adb test double to prove both local
# processes are gone when provisioning resumes.
SHIZUKU_FALLBACK_PID_FILE="$TMP/shizuku-fallback-hang.pids"
MOCK_TIMEOUT_NON_GNU=1 MOCK_SHIZUKU_START=hang_with_child \
  MOCK_SHIZUKU_HANG_PID_FILE="$SHIZUKU_FALLBACK_PID_FILE" SHIZUKU_START_TIMEOUT_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "portable fallback bounds a stuck Shizuku service start"
assert_contains 'service start timed out after 1s' "portable fallback reports the bounded failed step"
if [ -s "$SHIZUKU_FALLBACK_PID_FILE" ]; then
  read -r fallback_parent_pid fallback_child_pid < "$SHIZUKU_FALLBACK_PID_FILE"
else
  fallback_parent_pid=""
  fallback_child_pid=""
fi
if [ -n "$fallback_parent_pid" ] && [ -n "$fallback_child_pid" ] && \
   ! kill -0 "$fallback_parent_pid" 2>/dev/null && ! kill -0 "$fallback_child_pid" 2>/dev/null; then
  pass "portable fallback leaves no service-start worker or child behind"
else
  fail_test "portable fallback leaves no service-start worker or child behind"
fi
assert_log_contains '^adb .* install -r -g .*ha-paneld\.apk$' "portable fallback timeout still installs the core agent"
assert_log_contains '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "portable fallback timeout still launches the core agent"

# Re-running --shizuku against the trusted curated manager (or a trusted newer manager) must not try
# to downgrade it. The manager stays locally approved; provisioning only restarts its service.
MOCK_SHIZUKU_VERSION_CODE=1086 MOCK_SHIZUKU_TRUSTED=1 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "trusted current Shizuku provisioning is idempotent"
assert_not_contains 'install -r .*shizuku\.apk' "$MOCK_CALL_LOG" "current Shizuku manager is not reinstalled"
assert_log_contains '^adb .* shell /data/app/.*/lib/arm64/libshizuku\.so$' "current trusted Shizuku is rearmed through its authenticated native starter"
assert_contains 'Configure.*toolbar overflow menu.*Enhanced access.*Enable' "Shizuku approval names the actual on-panel path"

MOCK_SHIZUKU_VERSION_CODE=1087 MOCK_SHIZUKU_TRUSTED=1 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "trusted newer Shizuku provisioning is idempotent"
assert_not_contains 'install -r .*shizuku\.apk' "$MOCK_CALL_LOG" "newer Shizuku manager is not downgraded"

SDK_ROOT="$TMP/android-sdk"
mkdir -p "$SDK_ROOT/build-tools/35.0.0"
ln -s "$FIXTURES/apksigner" "$SDK_ROOT/build-tools/35.0.0/apksigner"
PATH="$NO_SIGNER_FIXTURES:/usr/bin:/bin" ANDROID_HOME= ANDROID_SDK_ROOT="$SDK_ROOT" \
  MOCK_SHIZUKU_VERSION_CODE=1087 MOCK_SHIZUKU_TRUSTED=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "Shizuku signer verification discovers apksigner through ANDROID_SDK_ROOT"
assert_log_contains '^apksigner verify --print-certs .*/installed-shizuku\.apk$' "ANDROID_SDK_ROOT apksigner verifies an installed newer manager"

# A same-or-newer manager with an unverifiable signer must fail closed and tell the operator how to
# recover. In particular, it must not download over or start an untrusted package.
MOCK_SHIZUKU_VERSION_CODE=1087 MOCK_SHIZUKU_TRUSTED=0 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "untrusted installed Shizuku manager fails closed"
assert_contains 'installed Shizuku manager cannot be trusted' "untrusted Shizuku failure names the trust boundary"
assert_contains 'remove the manager and re-run' "untrusted Shizuku failure gives a recovery path"
assert_not_contains 'shizuku-v13\.6\.0\.r1086.*-o .*/shizuku\.apk' "$MOCK_CALL_LOG" "untrusted newer Shizuku is not overwritten by a download"
assert_not_contains '^adb .* shell (monkey -p moe\.shizuku|sh .*/moe\.shizuku.*start\.sh|pm grant moe\.shizuku)' "$MOCK_CALL_LOG" "untrusted Shizuku is never launched or rearmed"
fi

# A launched app that never answers is not provisioned, even if adb install itself succeeded.
MOCK_HEALTH=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "launch timeout returns nonzero"
assert_contains '(did not start|not answering|launch|health)' "launch timeout explains what failed"
launcher_attempts="$(grep -Ec '^adb .* shell monkey -p io\.github\.maxlyth\.hapaneld -c android\.intent\.category\.LAUNCHER 1$' "$MOCK_CALL_LOG" || true)"
direct_attempts="$(grep -Ec '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "$MOCK_CALL_LOG" || true)"
if [ "$launcher_attempts" -eq 1 ] && [ "$direct_attempts" -eq 1 ]; then
  pass "launch timeout performs one launcher attempt and one distinct direct fallback"
else
  fail_test "launch timeout uses exactly one launcher and one direct fallback (got $launcher_attempts/$direct_attempts)"
fi
unset MOCK_HEALTH

# Some panels answer /health before the heavier diagnostics endpoint finishes root/capability probes.
MOCK_VERIFY=transient run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "healthy panel survives one transient slow diagnostics response"
assert_log_contains 'appops get io.github.maxlyth.hapaneld WRITE_SETTINGS' "post-install WRITE_SETTINGS verification reads Android's authority"
assert_log_contains 'settings get secure enabled_accessibility_services' "post-install accessibility verification reads Android's authority"
unset MOCK_VERIFY

# Package replacement can start the app before adb finishes granting permissions. The app deliberately
# serves one complete last-known diagnostics report while it refreshes in the background. Final
# verification reads back the two Android settings it just changed instead of adding a second cache retry.
MOCK_VERIFY=stale run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "post-install verification ignores a stale permission snapshot"
if [ "$(grep -c '/api/v1/diag' "$MOCK_CALL_LOG")" -eq 1 ]; then
  pass "post-install verification does not poll the diagnostics cache"
else
  fail_test "post-install verification does not poll the diagnostics cache"
fi
unset MOCK_VERIFY

# A populated service list is not sufficient when Android's master accessibility switch is still off.
MOCK_A11Y_MASTER_FAIL=1 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "post-install verification rejects a disabled accessibility master switch"
assert_contains 'accessibility enabled' "disabled accessibility master switch names the failed item"
assert_contains 'Settings.*Accessibility.*ha-paneld' "disabled accessibility master switch gives manual recovery"
assert_log_contains 'settings get secure accessibility_enabled' "final verification reads Android's accessibility master authority"

# Likewise, the final permission/HTTP checklist is a success gate rather than advisory output.
MOCK_VERIFY=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "final verification failure returns nonzero"
assert_contains '(verification|verify|incomplete|not reachable)' "verification failure gives recovery context"
unset MOCK_VERIFY

# Required configuration is part of provisioning success, not an advisory best-effort step.
MOCK_CONFIG=fail run_provision "$MOCK_TARGET" --apk "$APK" --id test-panel --no-tame
assert_failure "configuration failure returns nonzero"
assert_contains '(config.*not applied|provisioning incomplete)' "configuration failure explains what remains incomplete"
assert_not_contains '/api/v1/tame' "$MOCK_CALL_LOG" "required config failure suppresses optional vendor mutation"
unset MOCK_CONFIG

RESTORE="$TMP/restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{}}\n' > "$RESTORE"

# Restore input is validated before adb preflight or any mutation. The CLI imports config JSON only,
# and enforces the same 1 MiB request envelope as the server.
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$TMP/missing-restore.json" --no-tame
assert_failure "missing restore input fails before panel contact"
assert_contains 'config import file not found' "missing restore input names the preflight failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "missing restore input performs no panel or network operation"

EMPTY_RESTORE="$TMP/empty-restore.json"
: > "$EMPTY_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$EMPTY_RESTORE" --no-tame
assert_failure "empty restore input fails before panel contact"
assert_contains 'config import file is empty' "empty restore input names the preflight failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "empty restore input performs no panel or network operation"

SYMLINK_RESTORE="$TMP/symlink-restore.json"
ln -s "$RESTORE" "$SYMLINK_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$SYMLINK_RESTORE" --no-tame
assert_failure "symlink restore input fails before panel contact"
assert_contains 'regular file, not a symbolic link' "symlink restore input names the preflight failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "symlink restore input performs no panel or network operation"

OVERSIZED_RESTORE="$TMP/oversized-restore.json"
truncate -s 1048577 "$OVERSIZED_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$OVERSIZED_RESTORE" --no-tame
assert_failure "oversized restore input fails before panel contact"
assert_contains 'config import file is too large.*maximum 1048576' "oversized restore input states the config envelope"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "oversized restore input performs no panel or network operation"

MALFORMED_RESTORE="$TMP/malformed-restore.json"
printf '{"kind":' > "$MALFORMED_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$MALFORMED_RESTORE" --no-tame
assert_failure "malformed restore input fails before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "malformed restore input names the structural failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "malformed restore input performs no panel or network operation"

WRONG_KIND_RESTORE="$TMP/wrong-kind-restore.json"
printf '{"kind":"ha-paneld-backup","schema":1,"values":{}}\n' > "$WRONG_KIND_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$WRONG_KIND_RESTORE" --no-tame
assert_failure "full backup passed to config restore fails before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "wrong-kind restore names the config-only contract"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "wrong-kind restore performs no panel or network operation"

NON_STRING_RESTORE="$TMP/non-string-restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{"keep_awake":true}}\n' > "$NON_STRING_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$NON_STRING_RESTORE" --no-tame
assert_failure "non-string config values fail before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "non-string config values name the structural failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "non-string config values perform no panel or network operation"

INVALID_METADATA_RESTORE="$TMP/invalid-metadata-restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"exported_at":false,"values":{}}\n' > "$INVALID_METADATA_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$INVALID_METADATA_RESTORE" --no-tame
assert_failure "invalid config-export metadata fails before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "invalid metadata names the structural failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "invalid metadata performs no panel or network operation"

EXTRA_FIELD_RESTORE="$TMP/extra-field-restore.json"
printf '{"kind":"ha-paneld-config","schema":1,"values":{},"extra":false}\n' > "$EXTRA_FIELD_RESTORE"
run_provision "$MOCK_TARGET" --apk "$APK" --restore "$EXTRA_FIELD_RESTORE" --no-tame
assert_failure "unsupported config-export fields fail before panel contact"
assert_contains 'not a valid ha-paneld config JSON export' "unsupported field names the structural failure"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "unsupported field performs no panel or network operation"

MOCK_RESTORE=fail run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
assert_failure "restore failure returns nonzero"
assert_contains '(import failed|provisioning incomplete)' "restore failure explains what remains incomplete"
unset MOCK_RESTORE

MOCK_CONFIG=timeout run_provision "$MOCK_TARGET" --apk "$APK" --id test-panel --no-tame
assert_failure "stalled config POST returns nonzero"
assert_log_contains 'curl .*--connect-timeout 1 --max-time 1 .* -X POST .*api/v1/config' "config POST carries connect and total deadlines"
unset MOCK_CONFIG

MOCK_RESTORE=timeout run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
assert_failure "stalled config import returns nonzero"
assert_log_contains 'curl .*--connect-timeout 1 --max-time 1 .*api/v1/config/import' "config import carries connect and total deadlines"
unset MOCK_RESTORE

MOCK_RESTORE=approval run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
assert_failure "approval-required restore returns nonzero"
assert_contains 'config import requires approval on the panel; no settings were imported' "approval-required import is not reported as successful"
assert_contains 'Review approvals.*approve the config import.*retry the identical command' "approval-required import gives the on-panel approval and retry path"
unset MOCK_RESTORE

for restore_case in malformed-approval unexpected-2xx; do
  MOCK_RESTORE="$restore_case" run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
  assert_failure "$restore_case import response returns nonzero"
  assert_contains 'import failed:.*unexpected HTTP' "$restore_case import response is rejected"
done
unset MOCK_RESTORE

for mode in flow_fail rejected token_missing timeout; do
  HA_PASSWORD_SENTINEL='ha-login-secret-7f2c91'
  MOCK_HA_LOGIN="$mode" run_provision "$MOCK_TARGET" --apk "$APK" --builtin --ha-url https://ha.test --ha-user owner --ha-pass "$HA_PASSWORD_SENTINEL" --no-tame
  assert_failure "HA login $mode returns nonzero"
  assert_contains '(login|token).*failed|login rejected|no usable|provisioning incomplete' "HA login $mode explains the authentication failure"
  assert_not_contains "$HA_PASSWORD_SENTINEL" "$MOCK_CALL_LOG" "HA login $mode keeps the password out of descendant argv"
  assert_not_contains "$HA_PASSWORD_SENTINEL" "$LAST_OUTPUT" "HA login $mode keeps the password out of output"
  if grep -E '/api/v1/config.*dashboard_package=builtin|dashboard_package=builtin.*/api/v1/config' "$MOCK_CALL_LOG" >/dev/null; then
    fail_test "HA login $mode does not activate the built-in renderer"
  else
    pass "HA login $mode does not activate the built-in renderer"
  fi
done
assert_log_contains 'curl .*--connect-timeout 1 .*--max-time 1 .*https://ha\.test/auth/login_flow' "HA authentication POST carries connect and total deadlines"
unset MOCK_HA_LOGIN

HA_TOKEN_SENTINEL='ha-token-secret-419ad8'
MOCK_HA_TOKEN=invalid run_provision "$MOCK_TARGET" --apk "$APK" --builtin --ha-url https://ha.test --ha-token "$HA_TOKEN_SENTINEL" --no-tame
assert_failure "invalid long-lived HA token returns nonzero"
assert_contains '(rejected the token|long-lived access token|provisioning incomplete)' "invalid long-lived HA token explains authentication recovery"
assert_not_contains "$HA_TOKEN_SENTINEL" "$MOCK_CALL_LOG" "HA token validation keeps the token out of descendant argv"
assert_not_contains "$HA_TOKEN_SENTINEL" "$LAST_OUTPUT" "HA token validation keeps the token out of output"
if grep -E '/api/v1/config.*dashboard_package=builtin|dashboard_package=builtin.*/api/v1/config' "$MOCK_CALL_LOG" >/dev/null; then
  fail_test "invalid long-lived HA token does not activate the built-in renderer"
else
  pass "invalid long-lived HA token does not activate the built-in renderer"
fi
unset MOCK_HA_TOKEN

MQTT_PASSWORD_SENTINEL='mqtt-secret-20ec73'
run_provision "$MOCK_TARGET" --apk "$APK" --mqtt-pass "$MQTT_PASSWORD_SENTINEL" --no-tame
assert_success "legacy MQTT password input is normalized before descendant commands"
assert_not_contains "$MQTT_PASSWORD_SENTINEL" "$MOCK_CALL_LOG" "MQTT password stays out of curl, adb and helper argv"
assert_not_contains "$MQTT_PASSWORD_SENTINEL" "$LAST_OUTPUT" "MQTT password stays out of provisioning output"
assert_log_contains 'curl .*--data-urlencode mqtt_password@.*/mqtt-password .*api/v1/config' "MQTT password reaches config through a private file"

HA_TOKEN_FILE="$TMP/ha-token.txt"
printf '%s\n' 'file-token-secret-a4b781' > "$HA_TOKEN_FILE"
chmod 600 "$HA_TOKEN_FILE"
MOCK_HA_TOKEN=invalid run_provision "$MOCK_TARGET" --apk "$APK" --builtin --ha-url https://ha.test --ha-token-file "$HA_TOKEN_FILE" --no-tame
assert_failure "Home Assistant token file is accepted and validated"
assert_not_contains 'file-token-secret-a4b781' "$MOCK_CALL_LOG" "token-file content stays out of descendant argv"

EMPTY_SECRET_FILE="$TMP/empty-secret.txt"
: > "$EMPTY_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$EMPTY_SECRET_FILE"
assert_failure "empty credential file fails before panel contact"
assert_contains 'MQTT password file is empty' "empty credential file names the unsafe input"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "empty credential file performs no panel or network operation"

NEWLINE_SECRET_FILE="$TMP/newline-secret.txt"
printf '\r\n' > "$NEWLINE_SECRET_FILE"
chmod 600 "$NEWLINE_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$NEWLINE_SECRET_FILE"
assert_failure "line-ending-only credential file fails before panel contact"
assert_contains 'contains no credential text' "line-ending-only credential names the empty normalized value"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "line-ending-only credential performs no panel or network operation"

MULTILINE_SECRET_FILE="$TMP/multiline-secret.txt"
printf 'credential\n\n' > "$MULTILINE_SECRET_FILE"
chmod 600 "$MULTILINE_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$MULTILINE_SECRET_FILE"
assert_failure "credential with extra trailing blank line fails before panel contact"
assert_contains 'contains more than one text line' "extra trailing blank line violates the single-line contract"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "multiline credential performs no panel or network operation"

PUBLIC_SECRET_FILE="$TMP/public-secret.txt"
printf '%s' 'not-owner-only' > "$PUBLIC_SECRET_FILE"
chmod 644 "$PUBLIC_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$PUBLIC_SECRET_FILE"
assert_failure "group-readable credential file fails before panel contact"
assert_contains 'readable by other users' "group-readable credential names the permission problem"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "group-readable credential performs no panel or network operation"

run_provision "$MOCK_TARGET" --verify --mqtt-pass first-secret --mqtt-pass second-secret
assert_failure "duplicate literal credential sources are rejected"
assert_contains 'source was supplied more than once' "duplicate literal credential names the ambiguous source"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "duplicate literal credential performs no panel or network operation"

chmod 600 "$PUBLIC_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --mqtt-pass-file "$PUBLIC_SECRET_FILE" --mqtt-pass-file "$HA_TOKEN_FILE"
assert_failure "duplicate credential-file sources are rejected"
assert_contains 'source was supplied more than once' "duplicate credential files name the ambiguous source"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "duplicate credential files perform no panel or network operation"

SYMLINK_SECRET_FILE="$TMP/symlink-secret.txt"
ln -s "$HA_TOKEN_FILE" "$SYMLINK_SECRET_FILE"
run_provision "$MOCK_TARGET" --verify --ha-token-file "$SYMLINK_SECRET_FILE"
assert_failure "credential symlink fails before panel contact"
assert_contains 'must not be a symbolic link' "credential symlink names the safe-file requirement"
assert_not_contains '^adb |^curl ' "$MOCK_CALL_LOG" "credential symlink performs no panel or network operation"

run_provision "$MOCK_TARGET" --ha-token token-without-server
assert_failure "long-lived HA token without a server URL returns nonzero"
assert_contains '(--ha-token.*require.*--ha-url|require.*--ha-url)' "token without server URL gives a direct correction"

run_provision "$MOCK_TARGET" --builtin --ha-url https://ha.test
assert_failure "built-in renderer with a server URL but no credentials returns nonzero"
assert_contains 'printed guided-setup URL' "credentialless built-in setup directs the normal browser sign-in path"

# ── Closing guidance: the installer renders the panel's own setup journey ────────────────────────
# The rule these pin: the next step named at the end of a run must be the one the panel itself is
# waiting for. The previous contract printed the Home Assistant OAuth deep link on every run that
# had not completed OAuth — including a brand-new panel, where that link cannot work at all because
# the sign-in endpoint refuses the request until an ha_url exists.
MOCK_SETUP=identity run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "fresh install completes and hands over to guided setup"
assert_contains 'Next: confirm this panel.s name.*/setup' "a fresh panel is sent to guided setup, not to a sign-in it cannot complete"
assert_not_contains 'cfg-ha-oauth' "$LAST_OUTPUT" "a fresh panel is never offered the Home Assistant sign-in deep link"
assert_contains 'installed and verified' "an unfinished panel is not reported as fully provisioned"
assert_contains 'tap Set up' "guided setup names the on-panel route as well as the browser one"

MOCK_SETUP=ha_credentials run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel awaiting Home Assistant sign-in completes provisioning"
assert_contains 'Next: sign in to Home Assistant.*/setup' "the sign-in step is named only once the panel is actually ready for it"

# A live journey can carry a home_dashboard stage that postdates the original wording table. The
# generic fallback keeps the output correct, but a stage the panel actually blocks on should be named.
MOCK_SETUP=home_dashboard run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel awaiting a dashboard choice completes provisioning"
assert_contains 'Next: choose which Home Assistant dashboard to show.*/setup' "the dashboard-choice step is named specifically"

MOCK_SETUP=mqtt_auth_failed run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel with a rejecting broker completes provisioning"
assert_contains 'Next: get MQTT connected' "a rejecting broker is named as the next step"
assert_contains 'rejected these credentials' "the broker failure explains itself rather than printing a state token"

MOCK_SETUP=in_flight run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel mid-connect completes provisioning"
assert_contains 'Nothing to do yet' "work in progress is reported as progress, never as a fault to fix"
assert_not_contains 'Next: ' "$LAST_OUTPUT" "an in-flight stage does not issue an instruction"

MOCK_SETUP=repair run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a previously working panel completes provisioning"
assert_contains 'This panel needs attention' "a re-armed journey reads as repair, not as a first run"

MOCK_SETUP=unknown_stage run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an unrecognised journey stage completes provisioning"
assert_contains 'Next: finish guided setup.*/setup' "a stage this script does not know still yields correct generic guidance"
assert_not_contains 'opaque_token' "$LAST_OUTPUT" "an unrecognised stage never leaks a machine token into user-facing output"

MOCK_SETUP=complete run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a fully configured panel completes provisioning"
assert_contains 'provisioned and verified' "a finished panel is reported as provisioned"
assert_not_contains 'Next: ' "$LAST_OUTPUT" "a finished panel is given no next step"

# ── Panel storage headroom ──────────────────────────────────────────────────────────────────────
# ha-paneld.db is the canonical store and the pre-upgrade snapshot stages a full copy of it, so an
# upgrade needs room for two copies. Failing that up front beats failing part-way through a write to
# the store itself.
MOCK_DATA_AVAIL_KB=1024 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an upgrade onto a full data partition returns nonzero"
assert_contains 'too little free storage' "insufficient panel storage is named before anything is installed"
assert_contains 'room for two copies' "the storage requirement explains why twice the database is needed"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "insufficient storage stops before any APK install"

MOCK_DATA_AVAIL_KB=131072 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "exactly 128 MiB free is below the safe capacity floor"
assert_contains 'more than 131072KB \(128 MiB\) is required' "the capacity boundary is explicit"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "the exact capacity floor stops before APK install"

# Unknown capacity cannot prove that the database plus its staged copy fit, so it fails closed.
MOCK_DATA_CAPACITY=wrapped run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "an unreadable df blocks an upgrade safely"
assert_contains 'storage capacity could not be determined safely' "an unreadable df fails without guessing a number"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "unreadable capacity stops before APK install"

MOCK_DATA_AVAIL_KB=131073 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "capacity immediately above 128 MiB permits an upgrade"

run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an upgrade with adequate storage succeeds"
assert_contains 'panel storage: .*free on /data' "adequate storage is reported"

# ── Data-store snapshot ─────────────────────────────────────────────────────────────────────────
# The settings export is not a recovery point: configuration, the entity catalog, proximity and
# ambient history and the revision ring all live in ha-paneld.db. Until this existed, nothing in the
# toolchain held a copy of that file, so the uninstall-based recovery the script itself recommends
# destroyed it irrecoverably.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an upgrade with a root route succeeds"
assert_log_contains 'adb .*pull .*hapaneld-db-snapshot.*ha-paneld\.db ' "the upgrade snapshot copies the canonical data store"
assert_log_contains 'adb .*pull .*ha-paneld\.db-wal ' "the snapshot also copies the write-ahead log"
assert_log_contains 'adb .*pull .*ha-paneld\.db-shm ' "the snapshot also copies the shared-memory sidecar"
assert_contains 'data-store snapshot' "the snapshot reports where it wrote the database"
assert_log_contains 'rm -rf /data/local/tmp/\.hapaneld-db-snapshot' "the on-panel staging copy is removed again"
# The label is the whole safety story for this artefact: the panel's own backup deliberately omits a
# raw database because restoring one across versions or onto another panel is the known hazard. These
# files are same-panel, same-version, manual-restore-only, and must say so where someone finds them.
assert_log_contains 'adb .*pull .*break-glass\.db$' "the snapshot is named break-glass on disk, not as an ordinary backup"
assert_contains 'restored by hand only' "the snapshot states that nothing restores it automatically"
assert_contains 'Install → Backup .hpb is the supported restore path' "the snapshot points at the supported restore route instead"

# A cleanly checkpointed database has no sidecars. That is a complete backup, not a failed one.
MOCK_DB_SIDECARS=absent run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an upgrade succeeds when the database has no sidecar files"
assert_contains 'data-store snapshot' "a sidecar-free database still produces a snapshot"
assert_contains 'with 0 sidecar file\(s\)' "a sidecar-free database reports a complete main-file-only snapshot"

# Best-effort by design: a panel with no root route cannot do this, and must not fail because of it.
MOCK_ROOT=0 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an upgrade on a panel with no root route still succeeds"
assert_contains 'no root route, so only settings could be saved' "a sandboxed panel is told what was and was not saved"
assert_not_contains 'adb .*pull .*hapaneld-db-snapshot' "$MOCK_CALL_LOG" "a panel with no root route is never asked to stage its database"

MOCK_DB_SNAPSHOT=stage_fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a failed database staging does not fail the upgrade"
assert_contains 'could not be staged' "a failed staging says so without claiming a backup"

MOCK_DB_SNAPSHOT=pull_fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a failed database copy does not fail the upgrade"
assert_contains 'could not be copied' "a failed copy never reports a snapshot that does not exist"

MOCK_DB_SNAPSHOT=wal_pull_fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a partial sidecar copy does not fail the upgrade"
assert_contains 'could not be copied' "a missing staged WAL rejects the whole snapshot instead of claiming success"
assert_not_contains 'with [0-9]+ sidecar file' "$LAST_OUTPUT" "a partial SQLite file set is never reported as recoverable"

# ── --reset-config ──────────────────────────────────────────────────────────────────────────────
# A clean install must reach a genuine FIRST RUN, not a repair. Everything here exists to make the
# erase deliberate, recoverable, and impossible to trigger by accident or in bulk.
run_provision "$MOCK_TARGET" --verify --reset-config
assert_failure "--reset-config with --verify returns nonzero"
assert_contains '(read-only|never changes)' "a read-only run refuses to combine with an erase"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "a rejected reset never reaches the package manager"

run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config --restore "$RESTORE"
assert_failure "--reset-config with --restore returns nonzero"
assert_contains 'opposite intents' "erase-then-import names the contradiction"

HAPANELD_RESET_CONFIRM=no run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_failure "an unconfirmed reset returns nonzero"
assert_contains 'was not confirmed' "an unconfirmed reset says so"
assert_contains 'Nothing was erased' "an unconfirmed reset states that the panel is untouched"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "an unconfirmed reset never reaches the package manager"

# --force skips a version comparison; it must not stand in for authorising a wipe.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config --force
assert_failure "--force does not authorise an unconfirmed reset"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "--force never reaches the package manager on its own"

HAPANELD_RESET_CONFIRM=RESET MOCK_SETUP=identity run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_success "a confirmed reset completes"
assert_log_contains 'adb .*pm clear io.github.maxlyth.hapaneld' "a confirmed reset erases the app's stored state"
assert_contains 'configuration erased' "a confirmed reset reports what it did"
assert_contains 'Next: confirm this panel.s name' "a reset panel lands in guided setup, not in repair"

# The backup is the whole safety story: it must exist and be non-empty BEFORE anything is erased.
MOCK_EXPORT=fail HAPANELD_RESET_CONFIRM=RESET run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_failure "a reset without a usable backup returns nonzero"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "a reset without a usable backup never reaches the package manager"

HAPANELD_RESET_CONFIRM=RESET MOCK_PM_CLEAR=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --reset-config
assert_failure "a failed erase returns nonzero"
assert_contains 'could not erase the panel configuration' "a failed erase names what went wrong"

# Bulk erase is not offered: fleet workers run with stdin closed, so a single exported confirmation
# would otherwise wipe every panel at once.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-reset-output.txt"
bash "$UPDATE_FLEET" --reset-config -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet updates refuse a bulk configuration erase"
assert_contains 'not available for fleet updates' "the fleet refusal names the safe alternative"
assert_not_contains 'pm clear' "$MOCK_CALL_LOG" "a refused fleet erase never reaches any panel"

# Official-signer policy is enforced once before workers start. The default still accepts one consistent
# developer signer; --require-release-signer pins the official ha-paneld release certificate.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-wrong-signer-output.txt"
MOCK_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  bash "$UPDATE_FLEET" --require-release-signer --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet release policy rejects a foreign signer before workers start"
assert_contains 'does not use the required release signer' "fleet foreign-signer failure names the policy"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet foreign-signer failure starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-multiple-signers-output.txt"
MOCK_ADDITIONAL_RELEASE_CERT=0000000000000000000000000000000000000000000000000000000000000000 \
  bash "$UPDATE_FLEET" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet preflight rejects a multi-signed APK"
assert_contains 'must have exactly one signer' "fleet multi-signer failure names the invariant"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet multi-signer failure starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-wrong-package-output.txt"
MOCK_RELEASE_PACKAGE=example.foreign bash "$UPDATE_FLEET" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet preflight rejects a foreign package"
assert_contains 'fleet APK package mismatch' "fleet package failure names the mismatch"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet package failure starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-unverifiable-apk-output.txt"
MOCK_LOCAL_APK_VERIFY_FAIL=1 bash "$UPDATE_FLEET" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet preflight rejects an unverifiable local APK"
assert_contains 'fleet APK signature verification failed' "fleet unverifiable APK names the failure"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet unverifiable APK starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-missing-apk-output.txt"
bash "$UPDATE_FLEET" --apk "$TMP/does-not-exist.apk" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet preflight rejects a missing explicit APK"
assert_contains 'fleet APK is missing or empty' "fleet missing APK names the failure"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet missing APK starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-missing-signer-tool-output.txt"
PATH="$NO_SIGNER_FIXTURES:/usr/bin:/bin" ANDROID_HOME= ANDROID_SDK_ROOT= \
  bash "$UPDATE_FLEET" --require-release-signer --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet release policy fails closed without apksigner"
assert_contains 'apksigner is required for fleet deployment' "fleet missing-tool failure names apksigner"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet missing-tool failure starts no panel worker"

: > "$MOCK_CALL_LOG"
SIGNER_ONLY_FIXTURES="$TMP/signer-only-fixtures"
mkdir -p "$SIGNER_ONLY_FIXTURES"
ln -s "$FIXTURES/apksigner" "$SIGNER_ONLY_FIXTURES/apksigner"
LAST_OUTPUT="$TMP/fleet-missing-aapt-output.txt"
PATH="$SIGNER_ONLY_FIXTURES:/usr/bin:/bin" ANDROID_HOME= ANDROID_SDK_ROOT= \
  bash "$UPDATE_FLEET" --require-release-signer --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet release policy fails closed without aapt"
assert_contains 'aapt or aapt2 is required for fleet deployment' "fleet missing-tool failure names aapt"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet missing-aapt failure starts no panel worker"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-duplicate-apk-output.txt"
bash "$UPDATE_FLEET" --apk "$APK" --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_status 2 "fleet preflight rejects duplicate APK selectors"
assert_contains '--apk may be supplied only once' "fleet duplicate APK failure names the conflict"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "fleet duplicate APK failure starts no panel worker"

# Builds older than the setup endpoint keep working guidance, derived from config alone.
MOCK_SETUP=missing run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel without the setup endpoint completes provisioning"
assert_contains 'Next: configure this panel in your browser.*/configure' "a pre-wizard panel is still given a next step"
assert_not_contains 'cfg-ha-oauth' "$LAST_OUTPUT" "a pre-wizard panel with no server URL is not offered the sign-in deep link"

MOCK_ADB_STATE=unauthorized run_provision "$MOCK_TARGET" --verify
assert_failure "unauthorized adb returns nonzero"
assert_contains '(unauthorized|authorization dialog|accept)' "unauthorized adb gives on-panel recovery instructions"
unset MOCK_ADB_STATE

MOCK_ADB_STATE=offline run_provision "$MOCK_TARGET" --verify
assert_failure "offline adb returns nonzero"
assert_contains '(offline|ADB debugging|power-cycle)' "offline adb gives recovery instructions"
unset MOCK_ADB_STATE

MOCK_ADB_STATE=missing run_provision "$MOCK_TARGET" --verify
assert_failure "unreachable panel returns nonzero"
assert_contains '(cannot reach|network ADB|IP is right)' "unreachable panel gives network recovery instructions"
unset MOCK_ADB_STATE

# A typo must produce product language, not Bash's internal `unbound variable` diagnostic.
run_provision "$MOCK_TARGET" --mqtt
assert_failure "missing option value returns nonzero"
assert_contains '(--mqtt.*(value|argument)|missing.*(--mqtt|value)|requires?.*(value|argument))' "missing option value names the problem"
assert_not_contains 'unbound variable' "$LAST_OUTPUT" "missing option value hides Bash internals"

MOCK_GH_FAIL=1 run_provision "$MOCK_TARGET" --latest --no-tame
assert_failure "release resolver failure returns nonzero"
assert_contains 'could not fetch the latest release APK' "release resolver failure gives a product-level recovery message"
unset MOCK_GH_FAIL

# The unauthenticated fallback accepts only the exact HTTPS asset path implied by the release tag.
MOCK_GH_FAIL=1 MOCK_GITHUB_API=pretty run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner prerelease REST fallback accepts a matching GitHub asset"
assert_log_contains 'curl .*--proto =https --proto-redir =https .*https://api\.github\.com/repos/maxlyth/ha-paneld/releases\?per_page=100' "release metadata redirects remain HTTPS"
assert_log_contains 'curl .*--proto =https --proto-redir =https .*https://github\.com/maxlyth/ha-paneld/releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' "release APK download is constrained to the exact GitHub path"
rest_signer_line="$(grep -nE '^apksigner verify --print-certs ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
rest_install_line="$(grep -nE '^adb .* install( |$)' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$rest_signer_line" ] && [ -n "$rest_install_line" ] && [ "$rest_signer_line" -lt "$rest_install_line" ]; then
  pass "REST-downloaded release is signer-verified before install"
else
  fail_test "REST-downloaded release is signer-verified before install"
fi

PATH="$NO_GH_FIXTURES:/usr/bin:/bin" MOCK_GITHUB_API=pretty \
  run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner REST fallback works when gh is not installed"
assert_not_contains '^gh ' "$MOCK_CALL_LOG" "no-gh fallback does not invoke GitHub CLI"
assert_not_contains 'unbound variable' "$LAST_OUTPUT" "no-gh fallback never exposes an unset tag variable"

MOCK_GH_FAIL=1 MOCK_GITHUB_API=oversized run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_success "provisioner consumes an oversized prerelease response without SIGPIPE"
assert_log_contains 'releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' \
  "oversized provisioner response retains the first prerelease asset"

MOCK_GH_FAIL=1 MOCK_GITHUB_API=foreign run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_failure "provisioner rejects a release asset hosted outside the canonical GitHub path"
assert_contains 'could not fetch the latest release APK' "foreign release URL failure gives safe recovery guidance"
assert_not_contains 'https://downloads\.test/' "$MOCK_CALL_LOG" "foreign release URL is never downloaded"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "foreign release URL is rejected before APK install"

# Fleet prerelease selection must resolve and pin the newest release including release candidates.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-output.txt"
MOCK_GITHUB_API=pretty bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet prerelease update succeeds"
if grep -Fq 'https://api.github.com/repos/maxlyth/ha-paneld/releases?per_page=100' "$MOCK_CALL_LOG" && \
   grep -Fq 'https://github.com/maxlyth/ha-paneld/releases/download/v0.9.2-rc3/ha-paneld-v0.9.2-rc3-manual-setup-required.apk' "$MOCK_CALL_LOG"; then
  pass "fleet prerelease resolves an explicit release-candidate tag"
else
  fail_test "fleet prerelease resolves an explicit release-candidate tag"
fi
assert_log_contains 'curl .*--proto =https --proto-redir =https .*ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' "fleet download pins the exact release asset name"
assert_not_contains '^gh ' "$MOCK_CALL_LOG" "fleet release resolution has no unbounded GitHub CLI branch"
assert_contains 'verified.*v0\.9\.2-rc3' "fleet workers retain and verify the authenticated release tag"

# Fleet workers inherit provision.sh's final storage gate. A successfully installed panel whose
# shared health authority is critical must still fail the wave with the same recovery guidance.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-storage-critical-output.txt"
MOCK_STORAGE_HEALTH=critical MOCK_GITHUB_API=pretty \
  bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet update rejects a critically storage-constrained panel"
assert_contains 'storage health: critical.*pressure is critical' "fleet output retains the storage-pressure cause"
assert_contains 'Recover panel headroom or address WAL growth before writes fail.*re-run verification' "fleet failure retains the actionable storage recovery"
assert_contains 'fleet update: 0 OK, 1 failed' "fleet summary counts critical storage as a failed panel"

# The unauthenticated REST fallback receives GitHub's normal pretty multi-line JSON. It must skip a
# newer stable release and bind the candidate tag to that candidate's APK.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-rest-output.txt"
MOCK_GH_FAIL=1 MOCK_GITHUB_API=pretty bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet prerelease REST fallback accepts pretty GitHub JSON"
if grep -Fq 'https://github.com/maxlyth/ha-paneld/releases/download/v0.9.2-rc3/ha-paneld-v0.9.2-rc3-manual-setup-required.apk' "$MOCK_CALL_LOG" && \
   ! grep -Fq 'https://github.com/maxlyth/ha-paneld/releases/download/v0.9.1/ha-paneld-v0.9.1-manual-setup-required.apk' "$MOCK_CALL_LOG"; then
  pass "REST fallback selects the candidate APK rather than the newer stable channel"
else
  fail_test "REST fallback selects the candidate APK rather than the newer stable channel"
fi
assert_log_contains 'curl .*--proto =https --proto-redir =https .*https://github\.com/maxlyth/ha-paneld/releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' "fleet REST APK redirects remain HTTPS"
assert_contains 'verified.*v0\.9\.2-rc3' "fleet REST workers retain and verify the authenticated release tag"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-oversized-output.txt"
MOCK_GITHUB_API=oversized bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet updater consumes an oversized prerelease response without SIGPIPE"
assert_log_contains 'releases/download/v0\.9\.2-rc3/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' \
  "oversized fleet response retains the first prerelease asset"

# A legacy literal secret is unavoidable in this wrapper's original argv, but it must be normalized
# once rather than copied into every fleet worker, provisioner, curl or adb command.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-secret-output.txt"
FLEET_SECRET_SENTINEL='fleet-mqtt-secret-e6a901'
bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame \
  --mqtt-pass "$FLEET_SECRET_SENTINEL" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet normalizes a legacy literal credential"
assert_not_contains "$FLEET_SECRET_SENTINEL" "$MOCK_CALL_LOG" "fleet workers do not repeat the credential in descendant argv"
assert_not_contains "$FLEET_SECRET_SENTINEL" "$LAST_OUTPUT" "fleet output does not print the credential"
assert_log_contains 'mqtt_password@.*/mqtt-password' "fleet provisioner uses the normalized private credential file"

# Parallel fleet execution must aggregate a mixed outcome and replay both panel sections.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-mixed-output.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' MOCK_VERIFY_FAIL_HOST=panel-b.test \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame -- panel-a.test panel-b.test > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "parallel fleet reports nonzero for a mixed panel outcome"
assert_contains 'panel-a\.test:5555' "parallel fleet replays the successful panel section"
assert_contains 'panel-b\.test:5555' "parallel fleet replays the failed panel section"
assert_contains '1 OK, 1 failed' "parallel fleet aggregates mixed results"

# The wrapper consumes --jobs itself and does not launch a later batch while the selected worker
# limit is occupied. A blocked first install makes the scheduling boundary directly observable.
: > "$MOCK_CALL_LOG"
FLEET_JOBS_PID_FILE="$TMP/fleet-jobs-install.pid"
FLEET_JOBS_OUTPUT="$TMP/fleet-jobs-output.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' MOCK_APK_INSTALL=block \
  MOCK_APK_INSTALL_PID_FILE="$FLEET_JOBS_PID_FILE" \
  bash "$UPDATE_FLEET" --jobs 1 --apk "$APK" --allow-unsigned-helper --no-tame -- \
    panel-a.test panel-b.test > "$FLEET_JOBS_OUTPUT" 2>&1 &
fleet_jobs_owner_pid=$!
fleet_jobs_ready=0
for _ in {1..100}; do
  if [ -s "$FLEET_JOBS_PID_FILE" ]; then fleet_jobs_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$fleet_jobs_ready" -eq 1 ]; then
  pass "fleet jobs test reaches the occupied worker slot"
else
  LAST_OUTPUT="$FLEET_JOBS_OUTPUT"
  fail_test "fleet jobs test reaches the occupied worker slot"
fi
assert_not_contains 'adb -s panel-b\.test:5555' "$MOCK_CALL_LOG" "--jobs 1 keeps the second panel queued"
kill -TERM "$fleet_jobs_owner_pid" 2>/dev/null || true
wait "$fleet_jobs_owner_pid" 2>/dev/null || true

LAST_OUTPUT="$TMP/fleet-invalid-jobs-output.txt"
bash "$UPDATE_FLEET" --jobs 0 --apk "$APK" -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_status 2 "fleet rejects an unbounded zero-worker override"
assert_contains '--jobs must be a whole number from 1 to 32' "invalid fleet concurrency gives its accepted range"

# The fleet wrapper owns every provisioner it launches. TERM must be forwarded through the worker
# wrapper to the blocked package install, then all descendants must be reaped before shared temp/log
# state is removed.
: > "$MOCK_CALL_LOG"
FLEET_BLOCKED_PID_FILE="$TMP/fleet-blocked-install.pid"
FLEET_BLOCKED_OUTPUT="$TMP/fleet-blocked-output.txt"
MOCK_APK_INSTALL=block MOCK_APK_INSTALL_PID_FILE="$FLEET_BLOCKED_PID_FILE" \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --no-tame -- "$MOCK_TARGET" > "$FLEET_BLOCKED_OUTPUT" 2>&1 &
fleet_owner_pid=$!
fleet_blocked_ready=0
for _ in {1..100}; do
  if [ -s "$FLEET_BLOCKED_PID_FILE" ]; then fleet_blocked_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$fleet_blocked_ready" -eq 1 ]; then
  pass "blocked fleet worker exposes its package-install subprocess"
else
  LAST_OUTPUT="$FLEET_BLOCKED_OUTPUT"
  fail_test "blocked fleet worker exposes its package-install subprocess"
fi
kill -TERM "$fleet_owner_pid" 2>/dev/null || true
if wait "$fleet_owner_pid"; then fleet_signal_status=0; else fleet_signal_status=$?; fi
if [ "$fleet_signal_status" -eq 143 ]; then
  pass "TERM exits the fleet wrapper with signal status"
else
  LAST_OUTPUT="$FLEET_BLOCKED_OUTPUT"
  fail_test "TERM exits the fleet wrapper with signal status (got $fleet_signal_status)"
fi
fleet_blocked_pid="$(cat "$FLEET_BLOCKED_PID_FILE" 2>/dev/null || true)"
if [ -n "$fleet_blocked_pid" ] && ! kill -0 "$fleet_blocked_pid" 2>/dev/null; then
  pass "TERM reaps blocked fleet provisioning descendants"
else
  fail_test "TERM reaps blocked fleet provisioning descendants"
fi
/bin/sleep 0.2
if [ -n "$fleet_blocked_pid" ] && ! kill -0 "$fleet_blocked_pid" 2>/dev/null; then
  pass "fleet interruption leaves no orphan panel mutation"
else
  fail_test "fleet interruption leaves no orphan panel mutation"
fi

if [ "$PROVISION_TEST_SCOPE" = all ]; then
# A foreground adb inspection is not one of provision.sh's explicitly tracked subprocesses. Fleet
# ownership must still terminate it through the provisioner's dedicated process group.
: > "$MOCK_CALL_LOG"
FLEET_SHIZUKU_INSPECT_PID_FILE="$TMP/fleet-shizuku-inspect.pid"
FLEET_SHIZUKU_INSPECT_OUTPUT="$TMP/fleet-shizuku-inspect-output.txt"
MOCK_SHIZUKU_INSPECT=block MOCK_SHIZUKU_INSPECT_PID_FILE="$FLEET_SHIZUKU_INSPECT_PID_FILE" \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --shizuku --no-tame -- "$MOCK_TARGET" \
    > "$FLEET_SHIZUKU_INSPECT_OUTPUT" 2>&1 &
fleet_owner_pid=$!
fleet_blocked_ready=0
for _ in {1..100}; do
  if [ -s "$FLEET_SHIZUKU_INSPECT_PID_FILE" ]; then fleet_blocked_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$fleet_blocked_ready" -eq 1 ]; then
  pass "blocked foreground Shizuku inspection exposes its adb process"
else
  LAST_OUTPUT="$FLEET_SHIZUKU_INSPECT_OUTPUT"
  fail_test "blocked foreground Shizuku inspection exposes its adb process"
fi
kill -TERM "$fleet_owner_pid" 2>/dev/null || true
if wait "$fleet_owner_pid"; then fleet_signal_status=0; else fleet_signal_status=$?; fi
if [ "$fleet_signal_status" -eq 143 ]; then
  pass "TERM preserves fleet signal status while foreground adb is blocked"
else
  LAST_OUTPUT="$FLEET_SHIZUKU_INSPECT_OUTPUT"
  fail_test "TERM preserves fleet signal status while foreground adb is blocked (got $fleet_signal_status)"
fi
fleet_blocked_pid="$(cat "$FLEET_SHIZUKU_INSPECT_PID_FILE" 2>/dev/null || true)"
if [ -n "$fleet_blocked_pid" ] && ! kill -0 "$fleet_blocked_pid" 2>/dev/null; then
  pass "fleet process-group shutdown reaps untracked foreground adb"
else
  fail_test "fleet process-group shutdown reaps untracked foreground adb"
fi

# Deadline-wrapped commands intentionally run in a nested process group so their local timeout can
# kill every descendant. Fleet cancellation must compose with that ownership and signal the deadline
# supervisor, which in turn terminates and reaps the nested adb group before the fleet wrapper exits.
: > "$MOCK_CALL_LOG"
FLEET_SHIZUKU_INSTALL_PID_FILE="$TMP/fleet-shizuku-install.pid"
FLEET_SHIZUKU_INSTALL_OUTPUT="$TMP/fleet-shizuku-install-output.txt"
MOCK_SHIZUKU_INSTALL=block MOCK_SHIZUKU_INSTALL_PID_FILE="$FLEET_SHIZUKU_INSTALL_PID_FILE" \
SHIZUKU_INSTALL_TIMEOUT_SECONDS=180 ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS=0.05 \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --shizuku --no-tame -- "$MOCK_TARGET" \
    > "$FLEET_SHIZUKU_INSTALL_OUTPUT" 2>&1 &
fleet_owner_pid=$!
fleet_blocked_ready=0
for _ in {1..100}; do
  if [ -s "$FLEET_SHIZUKU_INSTALL_PID_FILE" ]; then fleet_blocked_ready=1; break; fi
  /bin/sleep 0.05
done
if [ "$fleet_blocked_ready" -eq 1 ]; then
  pass "blocked deadline-wrapped Shizuku install exposes its nested adb process"
else
  LAST_OUTPUT="$FLEET_SHIZUKU_INSTALL_OUTPUT"
  fail_test "blocked deadline-wrapped Shizuku install exposes its nested adb process"
fi
kill -TERM "$fleet_owner_pid" 2>/dev/null || true
if wait "$fleet_owner_pid"; then fleet_signal_status=0; else fleet_signal_status=$?; fi
if [ "$fleet_signal_status" -eq 143 ]; then
  pass "TERM preserves fleet signal status while a nested deadline command is blocked"
else
  LAST_OUTPUT="$FLEET_SHIZUKU_INSTALL_OUTPUT"
  fail_test "TERM preserves fleet signal status for a nested deadline command (got $fleet_signal_status)"
fi
fleet_blocked_pid="$(cat "$FLEET_SHIZUKU_INSTALL_PID_FILE" 2>/dev/null || true)"
if [ -n "$fleet_blocked_pid" ] && ! kill -0 "$fleet_blocked_pid" 2>/dev/null; then
  pass "fleet cancellation reaps the deadline wrapper's nested adb process group"
else
  fail_test "fleet cancellation reaps the deadline wrapper's nested adb process group"
fi

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-shizuku-failure-output.txt"
MOCK_SHIZUKU_START=fail bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --shizuku --no-tame -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet update fails when requested Shizuku service setup fails"
assert_contains '0 OK, 1 failed' "fleet summary does not count incomplete Shizuku setup as success"
assert_contains 'service did not start' "fleet output retains the Shizuku recovery reason"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-shizuku-timeout-output.txt"
MOCK_SHIZUKU_START=hang SHIZUKU_START_TIMEOUT_SECONDS=1 \
  bash "$UPDATE_FLEET" --apk "$APK" --allow-unsigned-helper --shizuku --no-tame -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet update fails when Shizuku service setup times out"
assert_contains '0 OK, 1 failed' "fleet summary does not count timed-out Shizuku setup as success"
assert_contains 'service start timed out after 1s' "fleet output retains the Shizuku timeout reason"
fi

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-duplicate-output.txt"
bash "$UPDATE_FLEET" --apk "$APK" -- panel.test panel.test:5555 > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_status 2 "normalized duplicate fleet targets are rejected"
assert_contains 'duplicate panel target' "duplicate fleet target gives a direct correction"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "duplicate fleet input fails before any install"

LAST_OUTPUT="$TMP/install-help.txt"
bash "$ROOT/scripts/install.sh" --help > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "one-line installer exposes help without requiring a panel"
assert_contains 'Usage:.*install\.sh' "one-line installer help shows the supported command"
assert_not_contains 'Disable those recommended vendor apps|TAME=' "$ROOT/scripts/install.sh" "one-line installer has no broad pre-profile taming prompt"

# A release-generated installer must bind the immutable tag to its exact asset name and source commit
# before it asks for a panel or contacts one. This mirrors the release workflow's three substitutions.
BAD_INSTALLER="$TMP/install-bad-release.sh"
sed -e 's/^RELEASE_TAG=""/RELEASE_TAG="v0.9.2-rc3"/' \
    -e 's/^RELEASE_APK_NAME=""/RELEASE_APK_NAME="ha-paneld-v0.9.1-manual-setup-required.apk"/' \
    -e 's/^PROVISION_COMMIT=""/PROVISION_COMMIT="0123456789abcdef0123456789abcdef01234567"/' \
    "$ROOT/scripts/install.sh" > "$BAD_INSTALLER"
LAST_OUTPUT="$TMP/install-bad-release-output.txt"
: > "$MOCK_CALL_LOG"
bash "$BAD_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer rejects a mismatched injected asset name"
assert_contains 'does not pair its tag with the expected APK asset' "mismatched release installer names the packaging error"
assert_not_contains '^curl |^adb ' "$MOCK_CALL_LOG" "mismatched release installer fails before network or panel access"

RELEASE_INSTALLER="$TMP/install-release.sh"
sed -e 's/^RELEASE_TAG=""/RELEASE_TAG="v0.9.2-rc3"/' \
    -e 's/^RELEASE_APK_NAME=""/RELEASE_APK_NAME="ha-paneld-v0.9.2-rc3-manual-setup-required.apk"/' \
    -e 's/^PROVISION_COMMIT=""/PROVISION_COMMIT="0123456789abcdef0123456789abcdef01234567"/' \
    "$ROOT/scripts/install.sh" > "$RELEASE_INSTALLER"
if bash -n "$RELEASE_INSTALLER" && \
   grep -Fq -- '--proto '\''=https'\'' --proto-redir '\''=https'\''' "$RELEASE_INSTALLER" && \
   grep -Fq 'command -v openssl' "$RELEASE_INSTALLER" && \
   grep -Fq 'It authenticates the release before anything is installed' "$RELEASE_INSTALLER" && \
   grep -Fq -- '--release-tag "$RELEASE_TAG"' "$RELEASE_INSTALLER" && \
   grep -Fq 'PROVISION_URL="$(provision_asset_url "$PROVISION_REF")"' "$RELEASE_INSTALLER" && \
   grep -Fq 'openssl dgst -sha256 -verify "$PROVISION_PUBLIC_KEY"' "$RELEASE_INSTALLER" && \
   grep -Fq 'PROVISION_COMMIT="0123456789abcdef0123456789abcdef01234567"' "$RELEASE_INSTALLER"; then
  pass "generated release installer preserves HTTPS, OpenSSL authentication, release verification, and immutable provisioner source"
else
  fail_test "generated release installer preserves HTTPS, OpenSSL authentication, release verification, and immutable provisioner source"
fi

# Downloaded executable code must be independently authenticated with the release key. These runs
# stop at the no-terminal prompt gate, after authentication but before any panel contact.
LAST_OUTPUT="$TMP/install-release-auth-output.txt"
: > "$MOCK_CALL_LOG"
bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer without a terminal stops safely after provisioner authentication"
assert_contains 'authenticated v0\.9\.2-rc3 provisioner' "release installer authenticates its provisioner before prompting"
assert_log_contains '^curl .*releases/download/v0\.9\.2-rc3/ha-paneld-provision-v0\.9\.2-rc3\.sh -o ' "release installer downloads the versioned provisioner asset"
assert_log_contains '^curl .*ha-paneld-provision-v0\.9\.2-rc3\.sh\.sha256 -o ' "release installer downloads the provisioner checksum"
assert_log_contains '^curl .*ha-paneld-provision-v0\.9\.2-rc3\.sh\.sha256\.sig -o ' "release installer downloads the provisioner checksum signature"
assert_log_contains '^openssl dgst -sha256 -verify .* -signature .*/provision\.sha256\.sig .*/provision\.sha256$' "release installer verifies the provisioner checksum signature"
assert_log_contains '^openssl dgst -sha256 -r .*/provision\.sh$' "release installer hashes the downloaded provisioner"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "provisioner authentication completes before panel contact"

LAST_OUTPUT="$TMP/install-release-invalid-signature-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_RELEASE_SIGNATURE_FAIL=1 bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer rejects an invalid provisioner checksum signature"
assert_contains 'provisioner checksum signature is invalid' "invalid provisioner signature names the authentication failure"
assert_contains 'Nothing was installed, started, or privileged' "invalid provisioner signature states the safe outcome"
assert_not_contains '^openssl dgst -sha256 -r ' "$MOCK_CALL_LOG" "invalid provisioner signature is rejected before hashing or trusting its checksum"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "invalid provisioner signature stops before panel contact"

LAST_OUTPUT="$TMP/install-release-missing-proof-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_PROOF_DOWNLOAD=checksum_fail bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer fails closed when the provisioner checksum asset is missing"
assert_contains 'Could not download the signed provisioner checksum' "missing provisioner proof names the incomplete release"
assert_not_contains '^openssl dgst -sha256 -verify ' "$MOCK_CALL_LOG" "missing provisioner proof is never verified as if it were complete"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "missing provisioner proof stops before panel contact"

LAST_OUTPUT="$TMP/install-release-malformed-proof-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_CHECKSUM=malformed bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer rejects a malformed signed provisioner record"
assert_contains 'signed provisioner checksum record.*is malformed' "malformed provisioner record names the metadata failure"
assert_not_contains '^openssl dgst -sha256 -r ' "$MOCK_CALL_LOG" "malformed provisioner record is rejected before hashing executable bytes"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "malformed provisioner record stops before panel contact"

LAST_OUTPUT="$TMP/install-release-mismatched-provisioner-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_CHECKSUM=mismatch bash "$RELEASE_INSTALLER" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "release installer rejects provisioner bytes that do not match the signed checksum"
assert_contains 'provisioner does not match its signed checksum' "provisioner checksum mismatch names the integrity failure"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "provisioner checksum mismatch stops before panel contact"

# The main-channel bootstrap must remain usable while latest stable is v0.9.2, which predates proof
# assets. Once it resolves v0.9.3 or newer, absence or failure of proof must never trigger downgrade.
LAST_OUTPUT="$TMP/install-legacy-channel-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_RELEASE_API=legacy bash "$ROOT/scripts/install.sh" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "pre-v0.9.3 channel installer reaches the prompt gate through its compatibility path"
assert_log_contains '^curl .*raw\.githubusercontent\.com/maxlyth/ha-paneld/v0\.9\.2/scripts/provision\.sh -o ' "pre-v0.9.3 channel install retains immutable-tag compatibility"
assert_not_contains 'ha-paneld-provision-v0\.9\.2\.sh\.sha256' "$MOCK_CALL_LOG" "legacy channel compatibility does not expect assets that were never published"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "legacy channel resolution still occurs before panel contact"

LAST_OUTPUT="$TMP/install-authenticated-channel-output.txt"
: > "$MOCK_CALL_LOG"
MOCK_INSTALLER_RELEASE_API=authenticated bash "$ROOT/scripts/install.sh" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "v0.9.3 channel installer reaches the prompt gate only after authentication"
assert_contains 'authenticated v0\.9\.3 provisioner' "v0.9.3 channel resolution requires the authenticated provisioner asset"
assert_log_contains '^curl .*releases/download/v0\.9\.3/ha-paneld-provision-v0\.9\.3\.sh\.sha256\.sig -o ' "v0.9.3 channel resolution fetches signed proof"
assert_not_contains 'raw\.githubusercontent\.com/.*/v0\.9\.3/scripts/provision\.sh' "$MOCK_CALL_LOG" "v0.9.3 channel resolution cannot downgrade to the legacy provisioner path"
assert_not_contains '^adb ' "$MOCK_CALL_LOG" "authenticated channel resolution completes before panel contact"

# Advanced installer mode is the checkout-free entry point for explicit provisioning. Use a
# downloaded fake provisioner here so the wrapper remains a black box while its exact hand-off can
# be inspected without contacting a panel.
ADVANCED_FIXTURES="$TMP/advanced-installer-fixtures"
mkdir -p "$ADVANCED_FIXTURES"
for fixture in "$FIXTURES"/*; do
  [ "$(basename "$fixture")" = curl ] || ln -s "$fixture" "$ADVANCED_FIXTURES/$(basename "$fixture")"
done
ADVANCED_PROVISION="$TMP/advanced-provision.sh"
cat > "$ADVANCED_PROVISION" <<'EOF'
#!/usr/bin/env bash
printf 'provision-argv' >> "${MOCK_CALL_LOG:?}"
printf ' <%s>' "$@" >> "$MOCK_CALL_LOG"
printf '\n' >> "$MOCK_CALL_LOG"
EOF
chmod +x "$ADVANCED_PROVISION"
cat > "$ADVANCED_FIXTURES/curl" <<'EOF'
#!/usr/bin/env bash
set -u
printf 'curl %s\n' "$*" >> "${MOCK_CALL_LOG:?}"
url=""
output=""
next_output=0
for arg in "$@"; do
  if [ "$next_output" = 1 ]; then output="$arg"; next_output=0; continue; fi
  case "$arg" in
    -o|--output) next_output=1 ;;
    http://*|https://*) url="$arg" ;;
  esac
done
case "$url" in
  https://api.github.com/repos/maxlyth/ha-paneld/releases/latest)
    printf '%s\n' '{"tag_name":"v0.9.3","assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.3/ha-paneld-v0.9.3-manual-setup-required.apk"}]}'
    ;;
  https://api.github.com/repos/maxlyth/ha-paneld/releases\?per_page=100)
    if [ "${MOCK_ADVANCED_GITHUB_API:-small}" = oversized ]; then
      printf '%s' '[{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/204","tag_name":"v0.9.4-rc1","draft":false,"prerelease":true,"assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.4-rc1/ha-paneld-v0.9.4-rc1-manual-setup-required.apk"}]},{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/203","tag_name":"v0.9.3","draft":false,"prerelease":false,"padding":"'
      awk 'BEGIN { for (i = 0; i < 2097152; i++) printf "x" }'
      printf '%s\n' '"}]'
    else
      printf '%s\n' '[{"url":"https://api.github.com/repos/maxlyth/ha-paneld/releases/204","tag_name":"v0.9.4-rc1","draft":false,"prerelease":true,"assets":[{"browser_download_url":"https://github.com/maxlyth/ha-paneld/releases/download/v0.9.4-rc1/ha-paneld-v0.9.4-rc1-manual-setup-required.apk"}]}]'
    fi
    ;;
  */ha-paneld-provision-v*.sh.sha256.sig)
    printf 'mock signature\n' > "$output"
    ;;
  */ha-paneld-provision-v*.sh.sha256)
    name="$(basename "$url" .sha256)"
    hash="$(/usr/bin/sha256sum "${ADVANCED_PROVISION_SOURCE:?}" | awk '{print $1}')"
    printf '%s  %s\n' "$hash" "$name" > "$output"
    ;;
  */ha-paneld-provision-v*.sh)
    cp "${ADVANCED_PROVISION_SOURCE:?}" "$output"
    ;;
  */ha-paneld-v*-manual-setup-required.apk)
    printf 'release apk\n' > "$output"
    ;;
  *)
    # Once the generated installer has authenticated and exec'd the real provisioner, defer its
    # panel-facing HTTP contract to the normal safe fixture rather than maintaining a second fake.
    exec "$PROVISION_TEST_CURL" "$@"
    ;;
esac
EOF
chmod +x "$ADVANCED_FIXTURES/curl"

run_advanced_installer() {
  LAST_OUTPUT="$TMP/advanced-installer-output.txt"
  : > "$MOCK_CALL_LOG"
  env -u 'BASH_FUNC_curl%%' \
    PATH="$ADVANCED_FIXTURES:/usr/bin:/bin" \
    ADVANCED_PROVISION_SOURCE="$ADVANCED_PROVISION" \
    MOCK_CALL_LOG="$MOCK_CALL_LOG" \
      bash "$RELEASE_INSTALLER" "$@" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}

# This is deliberately not the small argv-logging child above. It proves that a release-generated
# installer can authenticate, download and hand off to the actual provisioner while every device and
# HTTP effect remains inside this script's existing mocks.
run_generated_installer_with_real_provisioner() {
  LAST_OUTPUT="$TMP/generated-installer-real-provisioner-output.txt"
  : > "$MOCK_CALL_LOG"
  rm -f "$TMP/write-settings-granted" "$TMP/accessibility-services" "$TMP/accessibility-enabled"
  printf 'previous installed apk\n' > "$TMP/installed-apk"
  env -u 'BASH_FUNC_curl%%' \
    PATH="$ADVANCED_FIXTURES:/usr/bin:/bin" \
    ADVANCED_PROVISION_SOURCE="$PROVISION" \
    MOCK_CURL_DIRECT=1 \
    MOCK_CALL_LOG="$MOCK_CALL_LOG" \
      bash "$RELEASE_INSTALLER" "$@" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}

run_moving_advanced_installer() {
  LAST_OUTPUT="$TMP/moving-advanced-installer-output.txt"
  : > "$MOCK_CALL_LOG"
  env -u 'BASH_FUNC_curl%%' \
    PATH="$ADVANCED_FIXTURES:/usr/bin:/bin" \
    ADVANCED_PROVISION_SOURCE="$ADVANCED_PROVISION" \
    MOCK_ADVANCED_GITHUB_API="${MOCK_ADVANCED_GITHUB_API:-small}" \
    MOCK_CALL_LOG="$MOCK_CALL_LOG" \
      bash "$ROOT/scripts/install.sh" "$@" > "$LAST_OUTPUT" 2>&1
  LAST_STATUS=$?
}

if [ "$PROVISION_TEST_SCOPE" = all ]; then
run_advanced_installer --provision panel.test --id kitchen --shizuku
assert_success "checkout-free advanced provisioning succeeds without repository prompts"
assert_log_contains '^provision-argv <panel\.test:5555> <--apk> <.*/ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk> <--release-tag> <v0\.9\.2-rc3> <--id> <kitchen> <--shizuku>$' \
  "mutating advanced provisioning receives the exact paired release APK"
assert_log_contains 'ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk -o ' \
  "mutating advanced provisioning downloads the paired APK"
fi

run_generated_installer_with_real_provisioner --provision panel.test --verify
assert_success "generated installer composes with the real provisioner for read-only verification"
assert_contains 'root helper daemon: running' \
  "generated installer hands helper-aware verification to the real provisioner"
assert_not_contains 'ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "generated installer plus real provisioner does not download an APK for verification"

run_generated_installer_with_real_provisioner --provision panel.test --id kitchen
assert_success "generated installer composes with the real provisioner for a mocked install"
assert_log_contains '^adb -s panel\.test:5555 install ' \
  "generated installer hands the authenticated APK to the real provisioner"
assert_contains 'Detected panel: Test Panel' \
  "real provisioner preserves panel-aware installation through the generated hand-off"

ADVANCED_SECRET_SENTINEL='advanced-ha-token-secret-9b173e'
run_advanced_installer --provision panel.test --ha-url https://ha.test --ha-token "$ADVANCED_SECRET_SENTINEL"
assert_success "checkout-free installer normalizes a legacy literal credential"
assert_log_contains '^provision-argv .* <--ha-token-file> <.*/ha-token\.[^>]+>$' \
  "checkout-free provisioner child receives only a private token-file path"
assert_not_contains "$ADVANCED_SECRET_SENTINEL" "$MOCK_CALL_LOG" \
  "checkout-free installer does not copy the literal token into descendant argv"

ADVANCED_TOKEN_FILE="$TMP/advanced-token.txt"
printf '%s' 'advanced-file-token' > "$ADVANCED_TOKEN_FILE"; chmod 600 "$ADVANCED_TOKEN_FILE"
run_advanced_installer --provision panel.test --ha-token duplicate-literal --ha-token-file "$ADVANCED_TOKEN_FILE"
assert_status 2 "checkout-free installer rejects duplicate credential sources"
assert_contains 'credential source supplied more than once' "checkout-free duplicate names the ambiguous credential source"
assert_not_contains '^curl ' "$MOCK_CALL_LOG" "checkout-free duplicate stops before downloads or provisioner launch"

run_advanced_installer --provision panel.test:5556 --verify
assert_success "checkout-free verify succeeds without downloading an APK"
assert_log_contains '^provision-argv <panel\.test:5556> <--verify>$' \
  "checkout-free verify forwards only the read-only operation"
assert_not_contains 'ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "checkout-free verify does not download or install an APK"
assert_log_contains 'ha-paneld-provision-v0\.9\.2-rc3\.sh\.sha256\.sig -o ' \
  "checkout-free verify still authenticates its downloaded provisioner"

run_advanced_installer --provision panel.test --export panel-backup.json
assert_success "checkout-free export-only succeeds without downloading an APK"
assert_log_contains '^provision-argv <panel\.test:5555> <--export> <panel-backup\.json>$' \
  "checkout-free export-only forwards the requested backup"
assert_not_contains 'ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "checkout-free export-only does not download or install an APK"

for blocked in --apk --release-tag --latest --prerelease; do
  run_advanced_installer --provision panel.test "$blocked" value
  assert_status 2 "checkout-free provisioning rejects the $blocked source override"
  assert_not_contains '^curl ' "$MOCK_CALL_LOG" "rejected $blocked override stops before downloads"
done
run_advanced_installer --provision panel.test --unknown
assert_status 2 "checkout-free provisioning rejects unknown options"
assert_not_contains '^curl ' "$MOCK_CALL_LOG" "unknown advanced option stops before downloads"
run_advanced_installer --provision panel.test --verify --id kitchen
assert_status 2 "checkout-free verification rejects ignored mutating options"
assert_not_contains '^curl ' "$MOCK_CALL_LOG" "mixed read-only and mutating options stop before downloads"

# The user-facing command downloads the moving main installer rather than a release-generated copy.
# Exercise both channel resolvers through the complete advanced hand-off so their authenticated
# provisioner and APK selection cannot drift from the already-covered immutable-release path.
run_moving_advanced_installer --provision panel.test --id kitchen
assert_success "moving stable-channel advanced provisioning succeeds"
assert_log_contains '^curl .*api\.github\.com/repos/maxlyth/ha-paneld/releases/latest' \
  "moving stable-channel provisioning resolves the latest release"
assert_log_contains '^provision-argv <panel\.test:5555> <--apk> <.*/ha-paneld-v0\.9\.3-manual-setup-required\.apk> <--release-tag> <v0\.9\.3> <--id> <kitchen>$' \
  "moving stable-channel provisioning pairs the resolved APK and provisioner"

if [ "$PROVISION_TEST_SCOPE" = all ]; then
run_moving_advanced_installer --prerelease --provision panel.test --shizuku
assert_success "moving prerelease-channel advanced provisioning succeeds"
assert_log_contains '^curl .*api\.github\.com/repos/maxlyth/ha-paneld/releases\?per_page=100' \
  "moving prerelease provisioning resolves the release-candidate channel"
assert_log_contains '^provision-argv <panel\.test:5555> <--apk> <.*/ha-paneld-v0\.9\.4-rc1-manual-setup-required\.apk> <--release-tag> <v0\.9\.4-rc1> <--shizuku>$' \
  "moving prerelease provisioning pairs the resolved APK and provisioner"

MOCK_ADVANCED_GITHUB_API=oversized run_moving_advanced_installer --prerelease --provision panel.test --shizuku
assert_success "moving installer consumes an oversized prerelease response without SIGPIPE"
assert_log_contains '^provision-argv <panel\.test:5555> <--apk> <.*/ha-paneld-v0\.9\.4-rc1-manual-setup-required\.apk> <--release-tag> <v0\.9\.4-rc1> <--shizuku>$' \
  "oversized moving-installer response retains the first prerelease asset"
fi

run_moving_advanced_installer --provision panel.test --verify
assert_success "moving stable-channel verification succeeds without an APK"
assert_log_contains '^provision-argv <panel\.test:5555> <--verify>$' \
  "moving stable-channel verification forwards only the read-only operation"
assert_not_contains 'ha-paneld-v0\.9\.3-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "moving stable-channel verification does not download an APK"

run_moving_advanced_installer --prerelease --provision panel.test --export panel-backup.json
assert_success "moving prerelease-channel export succeeds without an APK"
assert_log_contains '^provision-argv <panel\.test:5555> <--export> <panel-backup\.json>$' \
  "moving prerelease-channel export forwards only the backup operation"
assert_not_contains 'ha-paneld-v0\.9\.4-rc1-manual-setup-required\.apk -o ' "$MOCK_CALL_LOG" \
  "moving prerelease-channel export does not download an APK"

LAST_OUTPUT="$TMP/provision-help.txt"
bash "$PROVISION" --help > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "provisioner exposes help without requiring a panel"
if [ "$PROVISION_TEST_SCOPE" = all ]; then
  assert_contains '^ *--shizuku +Install/start pinned Shizuku' "provisioner help advertises enhanced-access setup"
fi

RELEASE_WORKFLOW="$ROOT/.github/workflows/release.yml"
CI_WORKFLOW="$ROOT/.github/workflows/ci.yml"
SHIZUKU_WORKFLOW="$ROOT/.github/workflows/shizuku-emulator.yml"
LAST_OUTPUT="$TMP/release-workflow-contract.txt"
cp "$RELEASE_WORKFLOW" "$LAST_OUTPUT"
if grep -Fq 'PROVISION_TEST_SCOPE: core' "$CI_WORKFLOW" && \
   ! grep -Fq 'scripts/tests/provision_test.sh' "$RELEASE_WORKFLOW" && \
   grep -Fq 'PROVISION_TEST_SCOPE=all scripts/tests/provision_test.sh' "$SHIZUKU_WORKFLOW"; then
  pass "Shizuku provisioning contracts stay outside CI and release gates"
else
  fail_test "Shizuku provisioning contracts stay outside CI and release gates"
fi
if grep -Fq 'release-input/hapaneld-helper-armeabi-v7a' "$RELEASE_WORKFLOW" && \
   grep -Fq 'release-input/hapaneld-helper-arm64-v8a' "$RELEASE_WORKFLOW"; then
  pass "release workflow builds both supported helper ABIs"
else
  fail_test "release workflow builds both supported helper ABIs"
fi
if grep -Fq 'armv7a-linux-androideabi26-clang' "$RELEASE_WORKFLOW" && \
   grep -Fq 'aarch64-linux-android26-clang' "$RELEASE_WORKFLOW" && \
   ! grep -Fq 'android27-clang' "$RELEASE_WORKFLOW"; then
  pass "release workflow aligns both helper assets with the app API 26 floor"
else
  fail_test "release workflow aligns both helper assets with the app API 26 floor"
fi
if grep -Fq 'name: Privileged helper' "$CI_WORKFLOW" && \
   grep -Fq '"Privileged helper"' "$RELEASE_WORKFLOW" && \
   ! grep -Fq 'make -C helper clean test contract' "$RELEASE_WORKFLOW"; then
  pass "release tags require the exact-source privileged-helper gate before publication"
else
  fail_test "release tags require the exact-source privileged-helper gate before publication"
fi
if grep -Fq 'fetch-depth: 0' "$RELEASE_WORKFLOW" && \
   grep -Fq "'+refs/heads/main:refs/remotes/origin/main'" "$RELEASE_WORKFLOW" && \
   grep -Fq 'git merge-base --is-ancestor "$source_commit" refs/remotes/origin/main' "$RELEASE_WORKFLOW" && \
   grep -Fq 'if [ "$GITHUB_EVENT_NAME" = push ]; then' "$RELEASE_WORKFLOW"; then
  pass "release tags must resolve to commits already merged on origin main"
else
  fail_test "release tags must resolve to commits already merged on origin main"
fi
snapshot_line="$(grep -n 'old_bin_record=.*snapshot /system/bin/hapaneld-helper ' "$PROVISION" | head -1 | cut -d: -f1)"
marker_line="$(grep -n '} > "\$marker.new"' "$PROVISION" | head -1 | cut -d: -f1)"
pre_marker_sync_line="$(awk -v after="$marker_line" 'NR > after && /sync \|\| return 1/{print NR; exit}' "$PROVISION")"
marker_move_line="$(grep -n 'mv -f "\$marker.new" "\$marker"' "$PROVISION" | head -1 | cut -d: -f1)"
post_marker_sync_line="$(awk -v after="$marker_move_line" 'NR > after && /sync \|\| return 1/{print NR; exit}' "$PROVISION")"
retire_alt_line="$(grep -n '/data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh' "$PROVISION" | head -1 | cut -d: -f1)"
if [ -n "$snapshot_line" ] && [ -n "$marker_line" ] && [ -n "$retire_alt_line" ] && \
   [ -n "$pre_marker_sync_line" ] && [ -n "$marker_move_line" ] && [ -n "$post_marker_sync_line" ] && \
   [ "$snapshot_line" -lt "$marker_line" ] && [ "$marker_line" -lt "$pre_marker_sync_line" ] && \
   [ "$pre_marker_sync_line" -lt "$marker_move_line" ] && [ "$marker_move_line" -lt "$post_marker_sync_line" ] && \
   [ "$post_marker_sync_line" -lt "$retire_alt_line" ] && \
   grep -Fq 'root_owned "$recovery"' "$PROVISION" && \
   grep -Fq 'OLD_BIN_SHA256=$old_bin_sha' "$PROVISION" && \
   grep -Fq '[ "$(file_sha256 "$recovery")" = "$expected" ]' "$PROVISION"; then
  pass "system migration durably verifies hashed root-owned recovery before journaling and retirement"
else
  fail_test "system migration durably verifies hashed root-owned recovery before journaling and retirement"
fi
if grep -Fq 'echo JOURNAL_VERSION=1' "$PROVISION" && \
   grep -Fq 'echo JOURNAL_SCOPE=APK_HELPER' "$PROVISION" && \
   grep -Fq 'echo TRANSACTION_ID=@TRANSACTION_ID@' "$PROVISION" && \
   grep -Fq 'echo LEASE_BOOT_ID=$current_boot' "$PROVISION" && \
   grep -Fq 'echo LEASE_UNTIL_UPTIME=$lease_until' "$PROVISION" && \
   grep -Fq 'echo TARGET_HELPER_SHA256=@BIN_SHA256@' "$PROVISION" && \
   grep -Fq '[ ! -f /system/bin/.hapaneld-helper-manual-upgrade ]' "$PROVISION" && \
   grep -Fq 'incomplete standalone root-helper installation must be recovered first' "$PROVISION"; then
  pass "provisioning uses a versioned APK-coupled journal and rejects the separate standalone journal"
else
  fail_test "provisioning uses a versioned APK-coupled journal and rejects the separate standalone journal"
fi
if grep -Fq 'valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper"' "$PROVISION" && \
   grep -Fq 'ACTIVE_SYSTEM_TRANSACTION' "$PROVISION" && \
   grep -Fq 'ACTIVE_SYSTEMLESS_TRANSACTION' "$PROVISION" && \
   grep -Fq 'ACTIVE_HYBRID_TRANSACTION' "$PROVISION" && \
   grep -Fq 'renew_root_helper_lease "$install_kind"' "$PROVISION" && \
   grep -Fq 'start_root_helper_lease_guard' "$PROVISION"; then
  pass "transaction nonce and monotonic lease protect validation through APK install and matching commit"
else
  fail_test "transaction nonce and monotonic lease protect validation through APK install and matching commit"
fi

if grep -Fq '/system/bin/hapaneld-helper --request PING >/dev/null 2>&1 ||' "$PROVISION" && \
   grep -Fq '( /system/bin/hapaneld-helper >/dev/null 2>&1 & )' "$PROVISION"; then
  pass "first-time system helper install verifies init start before direct fallback"
else
  fail_test "first-time system helper install verifies init start before direct fallback"
fi

# `start` is successful even when Android init has not loaded the restored service. Every rollback
# route that can restore /system/bin/hapaneld-helper must therefore probe the helper and launch it
# directly if init did not create a process. The root transaction bodies are intentionally executed
# only on a panel, so this source-contract check keeps all three provisioner routes covered in CI.
assert_rollback_restart_probe() {
  local function_name="$1" body expected
  body="$(sed -n "/^${function_name}() {$/,/^}$/p" "$PROVISION")"
  expected=$'start hapaneld_helper 2>/dev/null\n    /system/bin/hapaneld-helper --request PING >/dev/null 2>&1 ||\n      ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )'
  if grep -Fq 'start hapaneld_helper 2>/dev/null || /system/bin/hapaneld-helper >/dev/null 2>&1 &' <<<"$body"; then
    fail_test "$function_name does not trust init start success during rollback"
  elif [[ "$body" == *"$expected"* ]] &&
       [[ "$body" == *$'pkill -x hapaneld-ledd 2>/dev/null\n  wait_for_helper_retirement || return 1'* ]]; then
    pass "$function_name probes the restored helper before direct rollback fallback"
  else
    fail_test "$function_name probes the restored helper before direct rollback fallback"
  fi
}
assert_rollback_restart_probe rollback_system
assert_rollback_restart_probe rollback_systemless
assert_rollback_restart_probe rollback_hybrid

commit_fn_line="$(grep -n '^commit_system() {' "$PROVISION" | head -1 | cut -d: -f1)"
commit_marker_line="$(awk -v after="$commit_fn_line" 'NR > after && /rm -f "\$marker" \|\| return 1/{print NR; exit}' "$PROVISION")"
commit_target_line="$(grep -n '\[ "$(classify_system)" = TARGET \] || return 1' "$PROVISION" | head -1 | cut -d: -f1)"
commit_sync_line="$(awk -v after="$commit_marker_line" 'NR > after && /sync \|\| return 1/{print NR; exit}' "$PROVISION")"
commit_recovery_line="$(awk -v after="$commit_sync_line" 'NR > after && index($0, "rm -f /system/bin/hapaneld-helper.hapaneld-recovery"){print NR; exit}' "$PROVISION")"
if [ -n "$commit_target_line" ] && [ -n "$commit_marker_line" ] && [ -n "$commit_sync_line" ] && [ -n "$commit_recovery_line" ] && \
   [ "$commit_target_line" -lt "$commit_marker_line" ] && [ "$commit_marker_line" -lt "$commit_sync_line" ] && \
   [ "$commit_sync_line" -lt "$commit_recovery_line" ] && \
   grep -Fq '[ "$(classify_systemless)" = TARGET ] || return 1' "$PROVISION" && \
   grep -Fq '[ "$(classify_hybrid)" = TARGET ] || return 1' "$PROVISION"; then
  pass "helper commit rechecks exact target state before durably removing recovery"
else
  fail_test "helper commit rechecks exact target state before durably removing recovery"
fi
if grep -Fq 'live_matches_recorded_or_target OLD_BIN /data/adb/hapaneld/hapaneld-helper @BIN_SHA256@ "$marker"' "$PROVISION" && \
   grep -Fq 'live_matches_recorded_or_target OLD_RC /vendor/etc/init/hapaneld-helper.rc @HYBRID_RC_SHA256@ "$marker"' "$PROVISION" && \
   grep -Fq 'echo TRANSITION' "$PROVISION" && \
   grep -Fq '[ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1' "$PROVISION" && \
   grep -Fq 'mv -f /data/adb/hapaneld/hapaneld-helper.new /data/adb/hapaneld/hapaneld-helper || return 1' "$PROVISION" && \
   grep -Fq 'mv -f /vendor/etc/init/hapaneld-helper.rc.new /vendor/etc/init/hapaneld-helper.rc || return 1' "$PROVISION"; then
  pass "hybrid recovery authenticates each atomic step of a partially completed two-file swap"
else
  fail_test "hybrid recovery authenticates each atomic step of a partially completed two-file swap"
fi
if grep -Fq 'hybrid_matches_recorded() {' "$PROVISION" && \
   grep -Fq 'elif hybrid_matches_recorded; then' "$PROVISION" && \
   grep -Fq 'hybrid_matches_recorded || return 1' "$PROVISION"; then
  pass "hybrid rollback finalizes against the journaled state even when target bytes are unchanged"
else
  fail_test "hybrid rollback finalizes against the journaled state even when target bytes are unchanged"
fi
# System and systemless rollback finalization must compare the journaled state directly because their
# classifiers deliberately resolve unchanged target bytes in favour of a successful commit.
if grep -Fq 'system_matches_recorded() {' "$PROVISION" && \
   grep -Fq 'elif system_matches_recorded; then' "$PROVISION" && \
   grep -Fq 'system_matches_recorded || return 1' "$PROVISION" && \
   ! grep -Fq '[ "$(classify_system)" = PRE_SWAP ] || return 1' "$PROVISION"; then
  pass "system rollback finalizes against the journaled state even when target bytes are unchanged"
else
  fail_test "system rollback finalizes against the journaled state even when target bytes are unchanged"
fi
if grep -Fq 'systemless_matches_recorded() {' "$PROVISION" && \
   grep -Fq 'elif systemless_matches_recorded; then' "$PROVISION" && \
   grep -Fq 'systemless_matches_recorded || return 1' "$PROVISION" && \
   ! grep -Fq '[ "$(classify_systemless)" = PRE_SWAP ] || return 1' "$PROVISION"; then
  pass "systemless rollback finalizes against the journaled state even when target bytes are unchanged"
else
  fail_test "systemless rollback finalizes against the journaled state even when target bytes are unchanged"
fi
if grep -Fq 'helper_build_id="$(helper/source-id.sh)"' "$RELEASE_WORKFLOW" && \
   grep -Fq -- '-DHAPANELD_BUILD_ID=' "$RELEASE_WORKFLOW" && \
   grep -Fq 'RELEASE_HELPER_BUILD_ID=' "$RELEASE_WORKFLOW" && \
   grep -Fq 'HELPER_BUILD_ID' "$RELEASE_WORKFLOW"; then
  pass "release helpers and provisioner share one deterministic full-source build identity"
else
  fail_test "release helpers and provisioner share one deterministic full-source build identity"
fi
if grep -Fq 'cmp release-input/hapaneld-helper-armeabi-v7a app/src/main/assets/hapaneld-helper-arm' "$RELEASE_WORKFLOW" && \
   grep -Fq 'cmp release-input/hapaneld-helper-arm64-v8a app/src/main/assets/hapaneld-helper-arm64' "$RELEASE_WORKFLOW"; then
  pass "release workflow proves standalone privileged assets are byte-identical to the APK bundle"
else
  fail_test "release workflow proves standalone privileged assets are byte-identical to the APK bundle"
fi
if grep -Fq 'hapaneld-helper-arm64-v8a \' "$RELEASE_WORKFLOW" && \
   grep -Fq 'hapaneld-helper-armeabi-v7a \' "$RELEASE_WORKFLOW" && \
   grep -Fq 'sha256sum "$helper_arm_name"' "$RELEASE_WORKFLOW" && \
   grep -Fq 'sha256sum "$helper_arm64_name"' "$RELEASE_WORKFLOW"; then
  pass "release workflow seals both helper inputs and publishes checksums"
else
  fail_test "release workflow seals both helper inputs and publishes checksums"
fi
if grep -Fq 'for helper_name in "$helper_arm_name" "$helper_arm64_name"' "$RELEASE_WORKFLOW" && \
   grep -Fq '"dist/$helper_name.sha256.sig"' "$RELEASE_WORKFLOW"; then
  pass "release workflow signs both helper checksum records"
else
  fail_test "release workflow signs both helper checksum records"
fi
if grep -Fq 'release_key_digest=$(openssl pkey -pubin -in "$public_key" -outform DER | sha256sum' "$RELEASE_WORKFLOW" && \
   grep -Fq "awk '/^-----BEGIN PUBLIC KEY-----$/{copy=1}" "$RELEASE_WORKFLOW" && \
   grep -Fq 'embedded_key_digest=$(openssl pkey -pubin -in "$embedded_key" -outform DER | sha256sum' "$RELEASE_WORKFLOW" && \
   grep -Fq 'if [ "$embedded_key_digest" != "$release_key_digest" ]; then' "$RELEASE_WORKFLOW"; then
  pass "release signing fails before publication when installer trust keys drift from the keystore"
else
  fail_test "release signing fails before publication when installer trust keys drift from the keystore"
fi

# A failed direct-start COMMAND must not skip the health wait: the launcher route may already have
# the agent coming up, and aborting there is how a starting panel gets called a failure.
MOCK_DIRECT_START=fail MOCK_HEALTH=slow MOCK_HEALTH_READY_AFTER=4 APP_HEALTH_TIMEOUT_SECONDS=20 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a failed direct start still waits out the health budget"

# Exactly one direct start. A second launch restarts the first-run migration being waited on.
# MOCK_STOPPED_STATE + an ineffective launcher forces the escalation. Without that the launcher
# answers during the probe and this passes with zero direct launches, proving nothing.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective APP_HEALTH_TIMEOUT_SECONDS=20 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "an ineffective launcher escalates to the direct route and still provisions"
direct_starts="$(grep -c 'shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity' "$MOCK_CALL_LOG" || true)"
if [ "$direct_starts" -eq 1 ]; then
  pass "the direct route is issued exactly once while the agent is starting"
else
  fail_test "the direct route is issued exactly once while the agent is starting (saw $direct_starts)"
fi

# A hanging launcher command must not consume the health budget with it.
MOCK_LAUNCHER_START=block APP_LAUNCH_COMMAND_TIMEOUT_SECONDS=2 APP_HEALTH_TIMEOUT_SECONDS=3 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a hanging launcher command is bounded and the run still completes"

# ── #74: a slow first start is not a failed start ───────────────────────────────────────────────
# A panel can need about two minutes to answer after an upgrade while it is still migrating. The
# launch route and the health budget must stay separate, and the shipped default must be long.
default_health_budget="$(sed -n 's/^APP_HEALTH_TIMEOUT_SECONDS="\${APP_HEALTH_TIMEOUT_SECONDS:-\([0-9]*\)}"/\1/p' "$PROVISION")"
if [ "${default_health_budget:-0}" -ge 120 ]; then
  pass "the shipped health budget tolerates a multi-minute first start (${default_health_budget}s)"
else
  fail_test "the shipped health budget tolerates a multi-minute first start (got '${default_health_budget}')"
fi

MOCK_HEALTH=slow MOCK_HEALTH_READY_AFTER=4 APP_HEALTH_TIMEOUT_SECONDS=20 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "a panel that answers only after a delay still provisions"
assert_not_contains 'provisioning is incomplete' "$LAST_OUTPUT" "a late-starting panel is not reported as incomplete"

MOCK_HEALTH=fail APP_HEALTH_TIMEOUT_SECONDS=2 run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "a genuinely dead agent still fails after the budget expires"
assert_contains 'still not answering .* after [0-9]+s' "the failure names how long it waited"


# ── #74: nothing may mutate a panel whose agent never answered ──────────────────────────────────
# A health timeout used to fall through to the configuration POST and the restore import, so the run
# wrote settings — or imported a whole bundle — into an agent that had not come up.
# A package that stays stopped fails health at the LAUNCH step specifically, leaving the earlier
# steps intact. MOCK_HEALTH=fail cannot be used here: it breaks health for the whole run, so the
# provisioner dies before the launch step and the gate under test is never reached.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective MOCK_DIRECT_START=fail \
  APP_HEALTH_TIMEOUT_SECONDS=2 APP_LAUNCH_PROBE_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --id post-timeout-panel --mqtt tcp://broker.test:1883
assert_failure "a panel whose agent never answered fails the run"
assert_contains 'refusing to write configuration' "the run says why it refused to write"
assert_not_contains 'X POST .*api/v1/config$' "$MOCK_CALL_LOG" "no configuration is written after a health timeout"

MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective MOCK_DIRECT_START=fail \
  APP_HEALTH_TIMEOUT_SECONDS=2 APP_LAUNCH_PROBE_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --restore "$RESTORE"
assert_failure "a restore into an unanswering panel fails the run"
assert_contains 'refusing to import a configuration bundle' "the run says why it refused to import"
assert_not_contains 'api/v1/config/import' "$MOCK_CALL_LOG" "no bundle is imported after a health timeout"

# Minting is a side effect on Home Assistant, not on the panel: a token minted for a panel that never
# came up cannot be delivered and is left behind as a dangling credential to find and revoke.
MOCK_STOPPED_STATE=1 MOCK_LAUNCHER_START=ineffective MOCK_DIRECT_START=fail \
  APP_HEALTH_TIMEOUT_SECONDS=2 APP_LAUNCH_PROBE_SECONDS=1 \
  run_provision "$MOCK_TARGET" --apk "$APK" --no-tame --ha-url https://ha.test --ha-user u --ha-pass p
assert_failure "a panel that never answered does not get a token minted for it"
assert_contains 'refusing to mint a Home Assistant token' "the run says which side effect it refused"
assert_not_contains 'auth/token' "$MOCK_CALL_LOG" "no token is minted against Home Assistant after a health timeout"

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
