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

passes=0
failures=0
LAST_OUTPUT=""
LAST_STATUS=0

run_provision() {
  : > "$MOCK_CALL_LOG"
  LAST_OUTPUT="$TMP/output.txt"
  MOCK_HEALTH="${MOCK_HEALTH:-ok}" \
  MOCK_VERIFY="${MOCK_VERIFY:-ok}" \
  MOCK_EXPORT="${MOCK_EXPORT:-ok}" \
  MOCK_CONFIG="${MOCK_CONFIG:-ok}" \
  MOCK_RESTORE="${MOCK_RESTORE:-ok}" \
  MOCK_ADB_STATE="${MOCK_ADB_STATE:-device}" \
  MOCK_HA_LOGIN="${MOCK_HA_LOGIN:-ok}" \
  MOCK_HA_TOKEN="${MOCK_HA_TOKEN:-ok}" \
  MOCK_GH_FAIL="${MOCK_GH_FAIL:-0}" \
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

APK="$TMP/ha-paneld.apk"
printf 'test apk\n' > "$APK"

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
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "verify-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "verify-only performs no panel mutation"

# A normal local install must work with only portable shell facilities. The fixture PATH deliberately
# supplies failing seq and GNU sort -V implementations; invoking either makes this test fail.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "successful install completes without seq or GNU sort -V"
if grep -Eq '^adb .* install( |$)' "$MOCK_CALL_LOG"; then pass "successful install invokes adb install"
else fail_test "successful install invokes adb install"; fi
assert_contains 'provisioned' "successful install reports completion"

# A launched app that never answers is not provisioned, even if adb install itself succeeded.
MOCK_HEALTH=fail run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_failure "launch timeout returns nonzero"
assert_contains '(did not start|not answering|launch|health)' "launch timeout explains what failed"
unset MOCK_HEALTH

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

# The unauthenticated REST fallback receives GitHub's normal pretty multi-line JSON. It must skip a
# newer stable release and bind the candidate tag to that candidate's APK.
: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-rest-output.txt"
MOCK_GH_FAIL=1 MOCK_GITHUB_API=pretty bash "$UPDATE_FLEET" --prerelease -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "fleet prerelease REST fallback accepts pretty GitHub JSON"
if grep -Fq 'https://downloads.test/ha-paneld-v0.9.2-rc3.apk' "$MOCK_CALL_LOG" && \
   ! grep -Fq 'https://downloads.test/ha-paneld-v0.9.1.apk' "$MOCK_CALL_LOG"; then
  pass "REST fallback selects the candidate APK rather than the newer stable channel"
else
  fail_test "REST fallback selects the candidate APK rather than the newer stable channel"
fi

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

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
