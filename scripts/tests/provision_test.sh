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
  rm -f "$TMP/diag-attempts"
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
  MOCK_GITHUB_API="${MOCK_GITHUB_API:-fail}" \
  MOCK_RELEASE_CERT="${MOCK_RELEASE_CERT:-ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339}" \
  MOCK_RELEASE_PACKAGE="${MOCK_RELEASE_PACKAGE:-io.github.maxlyth.hapaneld}" \
  MOCK_RELEASE_VERIFY_FAIL="${MOCK_RELEASE_VERIFY_FAIL:-0}" \
  MOCK_RELEASE_PROOF_DOWNLOAD="${MOCK_RELEASE_PROOF_DOWNLOAD:-ok}" \
  MOCK_RELEASE_CHECKSUM="${MOCK_RELEASE_CHECKSUM:-ok}" \
  MOCK_RELEASE_SIGNATURE_FAIL="${MOCK_RELEASE_SIGNATURE_FAIL:-0}" \
  MOCK_SHIZUKU_START="${MOCK_SHIZUKU_START:-ok}" \
  MOCK_SHIZUKU_START_SCRIPT="${MOCK_SHIZUKU_START_SCRIPT:-ok}" \
  MOCK_OPENSSL_MISSING="${MOCK_OPENSSL_MISSING:-0}" \
  MOCK_OPENSSL_DIGEST_FAIL="${MOCK_OPENSSL_DIGEST_FAIL:-0}" \
  MOCK_STATE_DIR="$TMP" \
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
assert_not_contains '^adb .* install( |$)' "$MOCK_CALL_LOG" "verify-only never installs an APK"
assert_not_contains '^adb .* (install|shell (settings put|appops set|pm grant|am start))|^curl .* (-X POST|--data|--data-urlencode)' "$MOCK_CALL_LOG" "verify-only performs no panel mutation"

# A normal local install must work with only portable shell facilities. The fixture PATH deliberately
# supplies failing seq and GNU sort -V implementations; invoking either makes this test fail.
run_provision "$MOCK_TARGET" --apk "$APK" --no-tame
assert_success "successful install completes without seq or GNU sort -V"
if grep -Eq '^adb .* install( |$)' "$MOCK_CALL_LOG"; then pass "successful install invokes adb install"
else fail_test "successful install invokes adb install"; fi
assert_contains 'provisioned' "successful install reports completion"

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

: > "$MOCK_CALL_LOG"
LAST_OUTPUT="$TMP/fleet-shizuku-failure-output.txt"
MOCK_SHIZUKU_START=fail bash "$UPDATE_FLEET" --apk "$APK" --shizuku --no-tame -- "$MOCK_TARGET" > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_failure "fleet update fails when requested Shizuku service setup fails"
assert_contains '0 OK, 1 failed' "fleet summary does not count incomplete Shizuku setup as success"
assert_contains 'service did not start' "fleet output retains the Shizuku recovery reason"

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
   grep -Fq 'raw.githubusercontent.com/$REPO/$PROVISION_REF/scripts/provision.sh' "$RELEASE_INSTALLER" && \
   grep -Fq 'PROVISION_COMMIT="0123456789abcdef0123456789abcdef01234567"' "$RELEASE_INSTALLER"; then
  pass "generated release installer preserves HTTPS, OpenSSL authentication, release verification, and immutable provisioner source"
else
  fail_test "generated release installer preserves HTTPS, OpenSSL authentication, release verification, and immutable provisioner source"
fi

LAST_OUTPUT="$TMP/provision-help.txt"
bash "$PROVISION" --help > "$LAST_OUTPUT" 2>&1
LAST_STATUS=$?
assert_success "provisioner exposes help without requiring a panel"
assert_contains '^ *--shizuku +Install/start pinned Shizuku' "provisioner help advertises enhanced-access setup"

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
