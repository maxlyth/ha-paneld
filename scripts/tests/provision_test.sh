#!/usr/bin/env bash
# Black-box regression tests for the novice-facing provisioning contract.
# All adb and HTTP interactions are faked; this script never contacts a panel or the network.
set -u

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
MOCK_HELPER_DIST="$TMP/helper-dist"
mkdir -p "$MOCK_HELPER_DIST/armeabi-v7a" "$MOCK_HELPER_DIST/arm64-v8a"
printf 'mock arm helper\n' > "$MOCK_HELPER_DIST/armeabi-v7a/hapaneld-helper"
printf 'mock arm64 helper\n' > "$MOCK_HELPER_DIST/arm64-v8a/hapaneld-helper"
export HAPANELD_HELPER_DIST_DIR="$MOCK_HELPER_DIST"

passes=0
failures=0
LAST_OUTPUT=""
LAST_STATUS=0

run_provision() {
  : > "$MOCK_CALL_LOG"
  rm -f "$TMP/diag-attempts"
  rm -f "$TMP/plan-attempts"
  rm -f "$TMP/stale-helper-transaction" "$TMP/active-helper-transaction"
  if [ "${MOCK_STALE_TRANSACTION:-0}" = 1 ]; then : > "$TMP/stale-helper-transaction"; fi
  if [ -n "${MOCK_INSTALLED_APK_SOURCE:-}" ]; then
    cp "$MOCK_INSTALLED_APK_SOURCE" "$TMP/installed-apk"
  else
    printf 'previous installed apk\n' > "$TMP/installed-apk"
  fi
  LAST_OUTPUT="$TMP/output.txt"
  MOCK_HEALTH="${MOCK_HEALTH:-ok}" \
  MOCK_VERIFY="${MOCK_VERIFY:-ok}" \
  MOCK_EXPORT="${MOCK_EXPORT:-ok}" \
  MOCK_CONFIG="${MOCK_CONFIG:-ok}" \
  MOCK_RESTORE="${MOCK_RESTORE:-ok}" \
  MOCK_ADB_STATE="${MOCK_ADB_STATE:-device}" \
  MOCK_HA_LOGIN="${MOCK_HA_LOGIN:-ok}" \
  MOCK_HA_TOKEN="${MOCK_HA_TOKEN:-ok}" \
  MOCK_PLAN="${MOCK_PLAN:-ok}" \
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
  MOCK_SYSTEMLESS_RUNNER="${MOCK_SYSTEMLESS_RUNNER:-1}" \
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
  MOCK_HELPER_BUILD_ID="${MOCK_HELPER_BUILD_ID}" \
  MOCK_HELPER_BUILD_ID_MATCH="${MOCK_HELPER_BUILD_ID_MATCH:-ok}" \
  MOCK_RELEASE_PROVISION_BUILD_ID="${MOCK_RELEASE_PROVISION_BUILD_ID:-$MOCK_HELPER_BUILD_ID}" \
  MOCK_APK_INSTALL="${MOCK_APK_INSTALL:-ok}" \
  MOCK_APK_QUERY="${MOCK_APK_QUERY:-ok}" \
  MOCK_MANUAL_STALE="${MOCK_MANUAL_STALE:-0}" \
  MOCK_STALE_TRANSACTION_KIND="${MOCK_STALE_TRANSACTION_KIND:-system}" \
  MOCK_ACTIVE_TRANSACTION="${MOCK_ACTIVE_TRANSACTION:-0}" \
  MOCK_STALE_LIVE_STATE="${MOCK_STALE_LIVE_STATE:-TARGET}" \
  MOCK_STALE_APK_SHA256="${MOCK_STALE_APK_SHA256:-$(/usr/bin/sha256sum "$TMP/installed-apk" | awk '{print $1}')}" \
  MOCK_STALE_BUILD_ID="${MOCK_STALE_BUILD_ID:-$MOCK_HELPER_BUILD_ID}" \
  MOCK_SU_DIALECT="${MOCK_SU_DIALECT:-join}" \
  MOCK_SHIZUKU_START="${MOCK_SHIZUKU_START:-ok}" \
  MOCK_SHIZUKU_START_SCRIPT="${MOCK_SHIZUKU_START_SCRIPT:-ok}" \
  MOCK_SHIZUKU_INSTALL="${MOCK_SHIZUKU_INSTALL:-ok}" \
  MOCK_SHIZUKU_INSTALL_PID_FILE="${MOCK_SHIZUKU_INSTALL_PID_FILE:-}" \
  SHIZUKU_INSTALL_TIMEOUT_SECONDS="${SHIZUKU_INSTALL_TIMEOUT_SECONDS:-180}" \
  ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS="${ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS:-60}" \
  MOCK_OPENSSL_MISSING="${MOCK_OPENSSL_MISSING:-0}" \
  MOCK_OPENSSL_DIGEST_FAIL="${MOCK_OPENSSL_DIGEST_FAIL:-0}" \
  HAPANELD_HELPER_DIST_DIR="${HAPANELD_HELPER_DIST_DIR:-$MOCK_HELPER_DIST}" \
  MOCK_STATE_DIR="$TMP" \
  PROVISIONING_PLAN_TIMEOUT_SECONDS="${PROVISIONING_PLAN_TIMEOUT_SECONDS:-2}" \
    bash "$PROVISION" "$@" > "$LAST_OUTPUT" 2>&1
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

APK="$TMP/ha-paneld.apk"
printf 'test apk\n' > "$APK"
RELEASE_APK="$TMP/ha-paneld-v0.9.2-rc3-manual-setup-required.apk"
printf 'test release apk\n' > "$RELEASE_APK"
HELPER_RELEASE_APK="$TMP/ha-paneld-v0.9.4-rc1-manual-setup-required.apk"
printf 'test helper release apk\n' > "$HELPER_RELEASE_APK"
NO_SIGNER_FIXTURES="$TMP/fixtures-without-apksigner"
mkdir -p "$NO_SIGNER_FIXTURES"
for fixture in "$FIXTURES"/*; do
  [ "$(basename "$fixture")" = apksigner ] || ln -s "$fixture" "$NO_SIGNER_FIXTURES/$(basename "$fixture")"
done

# Export is a recovery operation. It must be possible before resolving or installing an APK.
EXPORT="$TMP/panel-backup.json"
run_provision "$MOCK_TARGET" --export "$EXPORT"
assert_success "export-only succeeds"
if [ -s "$EXPORT" ]; then pass "export-only writes a non-empty bundle"; else fail_test "export-only writes a non-empty bundle"; fi
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "export-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "export-only performs no panel mutation"

FAILED_EXPORT="$TMP/failed-backup.json"
MOCK_EXPORT=fail run_provision "$MOCK_TARGET" --export "$FAILED_EXPORT" --apk "$APK"
assert_failure "failed pre-install backup returns nonzero"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "failed pre-install backup stops before APK install"
if [ ! -e "$FAILED_EXPORT" ]; then pass "failed backup leaves no misleading output file"; else fail_test "failed backup leaves no misleading output file"; fi

# Verification is explicitly read-only and must not even attempt installation.
run_provision "$MOCK_TARGET" --verify
assert_success "verify-only succeeds for a healthy panel"
assert_contains 'Detected panel: Test Panel' "verify-only displays the app-owned hardware profile guidance"
assert_log_contains '^curl .* /api/v1/provisioning/plan\.txt$|^curl .*http://panel\.test:8888/api/v1/provisioning/plan\.txt$' "verify-only reads the provisioning plan"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "verify-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "verify-only performs no panel mutation"

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

# A normal local install must work with only portable shell facilities. The fixture PATH deliberately
# supplies failing seq and GNU sort -V implementations; invoking either makes this test fail.
run_provision "$MOCK_TARGET" --apk "$APK"
assert_success "successful install completes without seq or GNU sort -V"
if grep -Eq '^adb .* install( |$)' "$MOCK_CALL_LOG"; then pass "successful install invokes adb install"
else fail_test "successful install invokes adb install"; fi
assert_contains 'provisioned' "successful install reports completion"
assert_contains 'Detected panel: Test Panel' "successful install identifies the resolved panel profile"
assert_not_contains '/api/v1/tame' "$MOCK_CALL_LOG" "ordinary install never auto-applies profile recommendations"
start_line="$(grep -nE '^adb .* shell am start -n ' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
plan_line="$(grep -nE '^curl .*api/v1/provisioning/plan\.txt' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
if [ -n "$start_line" ] && [ -n "$plan_line" ] && [ "$start_line" -lt "$plan_line" ]; then
  pass "profile guidance is read only after the newly installed app is launched"
else
  fail_test "profile guidance is read only after the newly installed app is launched"
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
assert_contains 'Root daemon: required' "missing plan retains the legacy helper guidance"
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
assert_not_contains 'Root daemon: required|system WebView is very old' "$LAST_OUTPUT" \
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
assert_log_contains '^adb .* push .*/arm64-v8a/hapaneld-helper /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "local rooted provisioning stages the matching helper under its transaction nonce"
assert_log_contains '^adb .* push .* /data/local/tmp/hapaneld-helper-[0-9a-f]{32}\.txn$' "local rooted provisioning stages its authenticated transaction under the same nonce"
helper_push_line="$(grep -nE '^adb .* push .*/arm64-v8a/hapaneld-helper /data/local/tmp/hapaneld-helper-[0-9a-f]{32}$' "$MOCK_CALL_LOG" | head -1 | cut -d: -f1)"
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

EMPTY_HELPER_DIST="$TMP/empty-helper-dist"
mkdir -p "$EMPTY_HELPER_DIST"
HAPANELD_HELPER_DIST_DIR="$EMPTY_HELPER_DIST" run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "rooted local provisioning fails before APK replacement when its helper was not built"
assert_contains 'local arm64-v8a root helper has not been built' "missing local helper gives the exact build recovery"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "missing local helper leaves the existing APK untouched"

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

MOCK_APK_INSTALL=ambiguous_commit \
  run_provision "$MOCK_TARGET" --apk "$HELPER_RELEASE_APK" --release-tag v0.9.4-rc1 --no-tame
assert_success "transport loss after package-manager commit is reconciled from exact installed APK bytes"
assert_contains 'exact target APK bytes are installed; completing the helper transaction' "ambiguous install success explains the exact-byte reconciliation"
assert_log_contains '^adb .* shell pm path io\.github\.maxlyth\.hapaneld$' "ambiguous install outcome queries the installed package path"
assert_log_contains '^adb .* pull /data/app/io\.github\.maxlyth\.hapaneld/base\.apk ' "ambiguous install outcome authenticates the installed base APK"
assert_log_contains 'helper-transaction-[0-9a-f]+.*commit-system' "confirmed package-manager commit also commits helper recovery"
assert_not_contains 'helper-transaction-[0-9a-f]+.*rollback-system' "$MOCK_CALL_LOG" "confirmed package-manager commit never rolls the matching helper back"

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
assert_failure "simultaneous system and systemless journals fail closed"
assert_contains 'both root-helper recovery journals are present' "dual-journal failure explains the ambiguity"
assert_not_contains 'hapaneld-helper\.txn (rollback|commit)-(system|systemless)' "$MOCK_CALL_LOG" "dual-journal ambiguity preserves both recovery records"
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
assert_not_contains '^adb .* shell (am start|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "missing OpenSSL stops before launch or grants"

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
assert_not_contains '^adb .* shell (am start|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "foreign signer is rejected before launch or grants"

MOCK_RELEASE_PACKAGE=example.foreign \
  run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag v0.9.2-rc3 --no-tame
assert_failure "release APK with a foreign package name fails closed"
assert_contains 'release APK package mismatch' "foreign package failure names the trust violation"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "foreign package is rejected before APK install"
assert_not_contains '^adb .* shell (am start|settings put|appops set|pm grant)' "$MOCK_CALL_LOG" "foreign package is rejected before launch or grants"

run_provision "$MOCK_TARGET" --apk "$RELEASE_APK" --release-tag '../../main' --no-tame
assert_status 2 "invalid internal release tag is rejected as a usage error"
assert_contains 'invalid release tag' "invalid internal release tag gives a direct correction"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "invalid release tag is rejected before APK install"

# A panel without the manager must receive the exact pinned APK, verify it before installation, and
# start the official service script. The fake checksum tool makes this deterministic without network
# access while the call log proves the security-sensitive ordering.
run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "missing Shizuku manager is bootstrapped"
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
assert_log_contains '^adb .* shell monkey -p moe\.shizuku\.privileged\.api 1$' "Shizuku bootstrap launches the manager to materialise start.sh"
assert_log_contains '^adb .* shell test -f .*/moe\.shizuku\.privileged\.api/start\.sh$' "Shizuku bootstrap waits for start.sh"
assert_log_contains '^adb .* shell sh .*/moe\.shizuku\.privileged\.api/start\.sh$' "Shizuku bootstrap rearms the service through start.sh"
assert_log_contains '^adb .* shell pm grant moe\.shizuku\.privileged\.api android\.permission\.WRITE_SECURE_SETTINGS$' "Shizuku bootstrap enables supported restart setup"

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
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "Shizuku start failure still launches the core agent"

# A stuck device-side script must be terminated at a host deadline. It has the same recoverable
# semantics as any other service-start failure: install and relaunch the core agent, then return
# nonzero to both an individual caller and an enclosing fleet run.
SHIZUKU_HANG_PID_FILE="$TMP/shizuku-hang.pid"
MOCK_SHIZUKU_START=hang MOCK_SHIZUKU_HANG_PID_FILE="$SHIZUKU_HANG_PID_FILE" \
  SHIZUKU_START_TIMEOUT_SECONDS=1 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_failure "stuck Shizuku service start returns nonzero at its host deadline"
assert_contains 'service start timed out after 1s' "Shizuku timeout reports the bounded failed step"
assert_log_contains '^adb .* install -r -g .*ha-paneld\.apk$' "Shizuku timeout still installs the core agent"
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "Shizuku timeout still launches the core agent"
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
assert_log_contains '^adb .* shell am start -n io\.github\.maxlyth\.hapaneld/\.MainActivity$' "portable fallback timeout still launches the core agent"

# Re-running --shizuku against the trusted curated manager (or a trusted newer manager) must not try
# to downgrade it. The manager stays locally approved; provisioning only restarts its service.
MOCK_SHIZUKU_VERSION_CODE=1086 MOCK_SHIZUKU_TRUSTED=1 run_provision "$MOCK_TARGET" --apk "$APK" --shizuku --no-tame
assert_success "trusted current Shizuku provisioning is idempotent"
assert_not_contains 'install -r .*shizuku\.apk' "$MOCK_CALL_LOG" "current Shizuku manager is not reinstalled"
assert_log_contains '^adb .* shell sh .*/moe\.shizuku\.privileged\.api/start\.sh$' "current trusted Shizuku is rearmed through start.sh"
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

# A launched app that never answers is not provisioned, even if adb install itself succeeded.
MOCK_HEALTH=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "launch timeout returns nonzero"
assert_contains '(did not start|not answering|launch|health)' "launch timeout explains what failed"
unset MOCK_HEALTH

# Some panels answer /health before the heavier diagnostics endpoint finishes root/capability probes.
MOCK_VERIFY=transient run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "healthy panel survives one transient slow diagnostics response"
assert_contains 'diagnostics are still starting; retrying once' "slow diagnostics retry is explained"
unset MOCK_VERIFY

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
MOCK_RESTORE=fail run_provision "$MOCK_TARGET" --apk "$APK" --restore "$RESTORE" --no-tame
assert_failure "restore failure returns nonzero"
assert_contains '(import failed|provisioning incomplete)' "restore failure explains what remains incomplete"
unset MOCK_RESTORE

for mode in flow_fail rejected token_missing; do
  MOCK_HA_LOGIN="$mode" run_provision "$MOCK_TARGET" --apk "$APK" --builtin --ha-url https://ha.test --ha-user owner --ha-pass password --no-tame
  assert_failure "HA login $mode returns nonzero"
  assert_contains '(login|token).*failed|login rejected|no usable|provisioning incomplete' "HA login $mode explains the authentication failure"
  if grep -E '/api/v1/config.*dashboard_package=builtin|dashboard_package=builtin.*/api/v1/config' "$MOCK_CALL_LOG" >/dev/null; then
    fail_test "HA login $mode does not activate the built-in renderer"
  else
    pass "HA login $mode does not activate the built-in renderer"
  fi
done
unset MOCK_HA_LOGIN

MOCK_HA_TOKEN=invalid run_provision "$MOCK_TARGET" --apk "$APK" --builtin --ha-url https://ha.test --ha-token definitely-invalid --no-tame
assert_failure "invalid long-lived HA token returns nonzero"
assert_contains '(rejected the token|long-lived access token|provisioning incomplete)' "invalid long-lived HA token explains authentication recovery"
if grep -E '/api/v1/config.*dashboard_package=builtin|dashboard_package=builtin.*/api/v1/config' "$MOCK_CALL_LOG" >/dev/null; then
  fail_test "invalid long-lived HA token does not activate the built-in renderer"
else
  pass "invalid long-lived HA token does not activate the built-in renderer"
fi
unset MOCK_HA_TOKEN

run_provision "$MOCK_TARGET" --ha-token token-without-server
assert_failure "long-lived HA token without a server URL returns nonzero"
assert_contains '(--ha-token.*require.*--ha-url|require.*--ha-url)' "token without server URL gives a direct correction"

run_provision "$MOCK_TARGET" --builtin --ha-url https://ha.test
assert_failure "built-in renderer with a server URL but no credentials returns nonzero"
assert_contains '(also needs --ha-token|borrowing.*Companion)' "credentialless built-in setup explains the intentional borrow path"

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

MOCK_GH_FAIL=1 MOCK_GITHUB_API=foreign run_provision "$MOCK_TARGET" --prerelease --no-tame
assert_failure "provisioner rejects a release asset hosted outside the canonical GitHub path"
assert_contains 'could not fetch the latest release APK' "foreign release URL failure gives safe recovery guidance"
assert_not_contains 'https://downloads\.test/' "$MOCK_CALL_LOG" "foreign release URL is never downloaded"
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "foreign release URL is rejected before APK install"

# Fleet prerelease selection must resolve and pin the newest release including release candidates.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-output.txt"
bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet prerelease update succeeds"
if grep -Fq 'gh release list' "$MOCK_CALL_LOG" && grep -Fq 'gh release download v0.9.2-rc3' "$MOCK_CALL_LOG"; then
  pass "fleet prerelease resolves an explicit release-candidate tag"
else
  fail_test "fleet prerelease resolves an explicit release-candidate tag"
fi
assert_log_contains 'gh release download v0\.9\.2-rc3 .*--pattern ha-paneld-v0\.9\.2-rc3-manual-setup-required\.apk' "fleet gh download pins the exact release asset name"
assert_contains 'verified.*v0\.9\.2-rc3' "fleet workers retain and verify the authenticated release tag"

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

# Parallel fleet execution must aggregate a mixed outcome and replay both panel sections.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-mixed-output.txt"
MOCK_TARGETS='panel-a.test:5555 panel-b.test:5555' MOCK_VERIFY_FAIL_HOST=panel-b.test \
  bash "$UPDATE_FLEET" --apk "$APK" --no-tame -- panel-a.test panel-b.test > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "parallel fleet reports nonzero for a mixed panel outcome"
assert_contains 'panel-a\.test:5555' "parallel fleet replays the successful panel section"
assert_contains 'panel-b\.test:5555' "parallel fleet replays the failed panel section"
assert_contains '1 OK, 1 failed' "parallel fleet aggregates mixed results"

# The fleet wrapper owns every provisioner it launches. TERM must be forwarded through the worker
# wrapper to the blocked package install, then all descendants must be reaped before shared temp/log
# state is removed.
: > "$MOCK_CALL_LOG"
FLEET_BLOCKED_PID_FILE="$TMP/fleet-blocked-install.pid"
FLEET_BLOCKED_OUTPUT="$TMP/fleet-blocked-output.txt"
MOCK_APK_INSTALL=block MOCK_APK_INSTALL_PID_FILE="$FLEET_BLOCKED_PID_FILE" \
  bash "$UPDATE_FLEET" --apk "$APK" --no-tame -- "$MOCK_TARGET" > "$FLEET_BLOCKED_OUTPUT" 2>&1 &
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

# A foreground adb inspection is not one of provision.sh's explicitly tracked subprocesses. Fleet
# ownership must still terminate it through the provisioner's dedicated process group.
: > "$MOCK_CALL_LOG"
FLEET_SHIZUKU_INSPECT_PID_FILE="$TMP/fleet-shizuku-inspect.pid"
FLEET_SHIZUKU_INSPECT_OUTPUT="$TMP/fleet-shizuku-inspect-output.txt"
MOCK_SHIZUKU_INSPECT=block MOCK_SHIZUKU_INSPECT_PID_FILE="$FLEET_SHIZUKU_INSPECT_PID_FILE" \
  bash "$UPDATE_FLEET" --apk "$APK" --shizuku --no-tame -- "$MOCK_TARGET" \
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
  bash "$UPDATE_FLEET" --apk "$APK" --shizuku --no-tame -- "$MOCK_TARGET" \
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
MOCK_SHIZUKU_START=fail bash "$UPDATE_FLEET" --apk "$APK" --shizuku --no-tame -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet update fails when requested Shizuku service setup fails"
assert_contains '0 OK, 1 failed' "fleet summary does not count incomplete Shizuku setup as success"
assert_contains 'service did not start' "fleet output retains the Shizuku recovery reason"

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-shizuku-timeout-output.txt"
MOCK_SHIZUKU_START=hang SHIZUKU_START_TIMEOUT_SECONDS=1 \
  bash "$UPDATE_FLEET" --apk "$APK" --shizuku --no-tame -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet update fails when Shizuku service setup times out"
assert_contains '0 OK, 1 failed' "fleet summary does not count timed-out Shizuku setup as success"
assert_contains 'service start timed out after 1s' "fleet output retains the Shizuku timeout reason"

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

LAST_OUTPUT="$TMP/provision-help.txt"
bash "$PROVISION" --help > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "provisioner exposes help without requiring a panel"
assert_contains '^ *--shizuku +Install/start pinned Shizuku' "provisioner help advertises enhanced-access setup"

RELEASE_WORKFLOW="$ROOT/.github/workflows/release.yml"
LAST_OUTPUT="$TMP/release-workflow-contract.txt"
cp "$RELEASE_WORKFLOW" "$LAST_OUTPUT"
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
if grep -Fq 'make -C helper clean test contract' "$RELEASE_WORKFLOW"; then
  pass "release tags rerun privileged helper boundary and app-contract gates before asset build"
else
  fail_test "release tags rerun privileged helper boundary and app-contract gates before asset build"
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
   grep -Fq 'renew_root_helper_lease "$install_kind"' "$PROVISION" && \
   grep -Fq 'start_root_helper_lease_guard' "$PROVISION"; then
  pass "transaction nonce and monotonic lease protect validation through APK install and matching commit"
else
  fail_test "transaction nonce and monotonic lease protect validation through APK install and matching commit"
fi
commit_marker_line="$(grep -n 'rm -f "\$marker" || return 1' "$PROVISION" | tail -2 | head -1 | cut -d: -f1)"
commit_target_line="$(grep -n '\[ "$(classify_system)" = TARGET \] || return 1' "$PROVISION" | head -1 | cut -d: -f1)"
commit_sync_line="$(awk -v after="$commit_marker_line" 'NR > after && /sync \|\| return 1/{print NR; exit}' "$PROVISION")"
commit_recovery_line="$(awk -v after="$commit_sync_line" 'NR > after && index($0, "rm -f /system/bin/hapaneld-helper.hapaneld-recovery"){print NR; exit}' "$PROVISION")"
if [ -n "$commit_target_line" ] && [ -n "$commit_marker_line" ] && [ -n "$commit_sync_line" ] && [ -n "$commit_recovery_line" ] && \
   [ "$commit_target_line" -lt "$commit_marker_line" ] && [ "$commit_marker_line" -lt "$commit_sync_line" ] && \
   [ "$commit_sync_line" -lt "$commit_recovery_line" ] && \
   grep -Fq '[ "$(classify_systemless)" = TARGET ] || return 1' "$PROVISION"; then
  pass "helper commit rechecks exact target state before durably removing recovery"
else
  fail_test "helper commit rechecks exact target state before durably removing recovery"
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

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
