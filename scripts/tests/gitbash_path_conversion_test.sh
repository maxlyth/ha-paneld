#!/usr/bin/env bash
# Git Bash (MSYS) argument-conversion contract for every adb boundary this project owns.
#
# WHY THIS EXISTS. A reporter on Git for Windows could not provision a panel at all (#24). The MSYS
# runtime rewrites any argument beginning with `/` into a Windows path before it exec's a native
# program, so `adb push <local> /data/local/tmp/hapaneld-helper` reached adb.exe as a push to
# `D:/Program Files/Git/data/local/tmp/hapaneld-helper`. Nothing was staged on the panel, and neither
# adb nor the provisioner had any way to notice: from their side the command succeeded.
#
# WHAT IS ASSERTED. Two directions, which is the whole difficulty — a fix that only stopped the
# rewrite would break the other one:
#   * DEVICE paths must arrive at adb byte-for-byte as written. `/data/local/tmp/...` is a path in
#     the panel's filesystem and means nothing on the host.
#   * HOST paths must still be rewritten. Under Git Bash the push sources, pull destinations and the
#     APK live at `/tmp/...`, and adb.exe cannot open that; the runtime translating it is the only
#     reason those operands work at all today.
#
# HOW IT IS ASSERTED ON LINUX. The rewrite happens in the caller's exec, before adb is entered, so no
# mock can observe it by being called. `fixtures/msys-adb` models the rule instead: it converts its
# own argv the way the runtime would, records the argv adb.exe would have received, and delegates.
# Its emulated installation root is a real directory here, holding the host filesystem roots and
# deliberately no Android ones — so a converted host path still resolves to its file while a
# converted device path stops resolving, which is exactly the reporter's failure.
#
# PROOF THAT THESE ASSERTIONS CAN FAIL. Every positive case is paired with a negative control that
# re-runs the identical flow with `MOCK_MSYS_IGNORE_EXCL=1`, making the emulator convert
# unconditionally — the unguarded world. The controls assert the mangled form appears and the literal
# device path does not. Without them a broken emulator would report green forever.
#
# SCOPE. `helper/install-daemon.sh` is driven end to end here because it reaches a push of a host
# path and a device path in one command cheaply. The provisioner's own boundaries — helper staging,
# the capture-script push, the database pull, exec-out and every shell command — are covered by the
# Git Bash section of scripts/tests/provision_test.sh, which already owns a full provisioning flow.
# The static section below covers both scripts.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURES="$ROOT/scripts/tests/fixtures"
PROVISION="$ROOT/scripts/provision.sh"
INSTALLER="$ROOT/helper/install-daemon.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
check() {
  local what="$1"; shift
  if "$@"; then
    printf '  ok   %s\n' "$what"
  else
    printf '  FAIL %s\n' "$what"
    failures=$((failures + 1))
  fi
}
refute() {
  local what="$1"; shift
  if "$@"; then
    printf '  FAIL %s\n' "$what"
    failures=$((failures + 1))
  else
    printf '  ok   %s\n' "$what"
  fi
}

echo "== Git Bash adb path conversion =="

# ---------------------------------------------------------------------------
# 1. Static contract: no adb execution may bypass the guard.
#
# The runtime assertions below can only see the boundaries a given flow happens to reach. This
# section is what makes "every push, pull, shell, install and database-script boundary" an invariant
# rather than a one-time audit: a new call site written the old way fails here even if no test
# exercises it.
# ---------------------------------------------------------------------------

# Every line that executes the resolved adb binary must either be the guard itself or carry the same
# environment prefix, which may sit on the preceding continued line. The presence test is not an
# execution and is named explicitly.
unguarded_adb_sites() {
  local file="$1" number text previous
  mapfile -t source_lines < "$file"
  while IFS= read -r line; do
    number="${line%%:*}"
    text="${line#*:}"
    previous="${source_lines[number - 2]:-}"
    case "$text" in
      *'[ -n "$ADB_COMMAND" ]'*) continue ;;
      *'MSYS2_ARG_CONV_EXCL="$ADB_MSYS_ARG_CONV_EXCL" "$ADB_COMMAND"'*) continue ;;
    esac
    case "$previous" in
      *'MSYS2_ARG_CONV_EXCL="$ADB_MSYS_ARG_CONV_EXCL"'*'\') continue ;;
    esac
    printf '%s\n' "$line"
    return 0
  done < <(grep -n '"\$ADB_COMMAND"' "$file")
  return 1
}

# The comments explain the policy by naming the mechanisms it rejects, so only executable lines are
# read; grepping the whole file would match its own rationale.
disables_conversion_wholesale() {
  grep -v '^[[:space:]]*#' "$1" | grep -Eq "MSYS_NO_PATHCONV|MSYS2_ARG_CONV_EXCL='?\*"
}

for script in "$PROVISION" "$INSTALLER"; do
  name="${script#$ROOT/}"
  refute "$name: no adb execution bypasses the conversion guard" unguarded_adb_sites "$script"
  check "$name: the deadline wrapper routes through adb_exec" \
    grep -Fq 'run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" adb_exec "$@"' "$script"
  check "$name: adb_exec sets the exclusion list" \
    grep -Fq 'MSYS2_ARG_CONV_EXCL="$ADB_MSYS_ARG_CONV_EXCL" "$ADB_COMMAND" "$@"' "$script"
  # A blanket exclusion would also stop the runtime translating the host paths this project hands to
  # push, pull and install, trading one broken direction for the other.
  refute "$name: conversion is not disabled wholesale" disables_conversion_wholesale "$script"
done

# The two lists are duplicated on purpose — neither script may source the other — so their agreement
# has to be enforced rather than assumed.
provision_excl="$(grep -m1 '^ADB_MSYS_ARG_CONV_EXCL=' "$PROVISION")"
installer_excl="$(grep -m1 '^ADB_MSYS_ARG_CONV_EXCL=' "$INSTALLER")"
check "the provisioner and the standalone installer share one exclusion list" \
  test -n "$provision_excl" -a "$provision_excl" = "$installer_excl"

# Every Android filesystem root either script can name in an adb argument must be in the list. The
# roots are taken from the scripts themselves so a newly referenced one cannot be forgotten.
EXCL_VALUE="${provision_excl#*=}"
EXCL_VALUE="${EXCL_VALUE#\'}"
EXCL_VALUE="${EXCL_VALUE%\'}"
missing_android_roots() {
  local root
  for root in data system vendor sdcard storage proc sys dev mnt cache product odm apex oem acct sbin config; do
    case ";$EXCL_VALUE;" in
      *";/$root;"*) ;;
      *) printf '/%s\n' "$root"; return 0 ;;
    esac
  done
  return 1
}
refute "the exclusion list covers every Android filesystem root" missing_android_roots

# A suite nobody runs proves nothing, and this one guards a platform the project has no runner for.
check "the suite runs in the host-contracts CI job" \
  grep -Fq 'scripts/tests/gitbash_path_conversion_test.sh' "$ROOT/.github/workflows/ci.yml"

# ---------------------------------------------------------------------------
# 2. The emulated Git Bash installation root.
#
# It carries a symlink to each real top-level directory of the host root EXCEPT the Android ones, so
# a converted host path resolves and a converted device path does not. That asymmetry is what lets a
# Linux runner tell the two directions apart.
# ---------------------------------------------------------------------------
MSYS_ROOT="$TMP/msys-root"
mkdir -p "$MSYS_ROOT"
for entry in /*; do
  base="${entry#/}"
  case ";$EXCL_VALUE;" in
    *";/$base;"*) continue ;;
  esac
  ln -s "$entry" "$MSYS_ROOT/$base" 2>/dev/null || true
done

MSYS_BIN="$TMP/msys-bin"
mkdir -p "$MSYS_BIN"
cp "$FIXTURES/msys-adb" "$MSYS_BIN/adb"
chmod 755 "$MSYS_BIN/adb"

# ---------------------------------------------------------------------------
# 3. The standalone helper installer, end to end, under the emulator.
#
# One `adb push` carries both directions at once: a host source under the harness temp directory and
# a device destination under /data/local/tmp.
# ---------------------------------------------------------------------------
BUILD_ID=facade00facade00facade00facade00facade00facade00facade00facade00
mkdir -p "$TMP/dist/arm64-v8a"
printf 'mock helper\nBUILDID %s\n' "$BUILD_ID" > "$TMP/dist/arm64-v8a/hapaneld-helper"
HOST_HELPER="$TMP/dist/arm64-v8a/hapaneld-helper"

ARGV_LOG="$TMP/argv.log"
CALL_LOG="$TMP/calls.log"

run_installer() {
  local mode="$1" path="$FIXTURES:/usr/bin:/bin"
  : > "$ARGV_LOG"
  : > "$CALL_LOG"
  rm -rf "$TMP/state"; mkdir -p "$TMP/state"
  [ "$mode" = plain ] || path="$MSYS_BIN:$path"
  MOCK_TARGET=panel.test:5555 \
  MOCK_CALL_LOG="$CALL_LOG" \
  MOCK_STATE_DIR="$TMP/state" \
  MOCK_ABI=arm64-v8a \
  MOCK_ROOT=1 \
  MOCK_ADB_ROOT=1 \
  MOCK_SYSTEM_WRITABLE=1 \
  MOCK_HELPER_BUILD_ID="$BUILD_ID" \
  HAPANELD_HELPER_DIST_DIR="$TMP/dist" \
  MOCK_MSYS_ROOT="$MSYS_ROOT" \
  MOCK_MSYS_ARGV_LOG="$ARGV_LOG" \
  MOCK_MSYS_DELEGATE="$FIXTURES/adb" \
  MOCK_MSYS_IGNORE_EXCL="${MOCK_MSYS_IGNORE_EXCL:-0}" \
  PATH="$path" \
    bash "$INSTALLER" panel.test:5555 > "$TMP/out.txt" 2>&1
  INSTALLER_STATUS=$?
}

# The device path the installer stages to is transaction-scoped, so it is read back out of the run
# rather than guessed; a guessed literal would silently stop matching and the check would pass while
# asserting nothing.
staged_device_path() {
  sed -n 's/^argv=\(\/data\/local\/tmp\/hapaneld-helper\.probe-[0-9a-f]*\)$/\1/p' "$ARGV_LOG" | head -1
}

# Nothing that the emulator converted may be a device path. The emulator decides that while the call
# is happening, because a genuine host path can be deleted the moment its command returns and would
# then read as a device path afterwards. This is the general form of the contract: it covers every
# boundary the run reaches without naming any of them.
converted_argument_that_is_not_a_host_path() {
  grep -q '^rewritten-non-host=' "$ARGV_LOG"
}

echo "-- standalone installer under Git Bash --"
run_installer msys
check "the run completes under the emulated MSYS runtime" test "$INSTALLER_STATUS" -eq 0
check "the device staging path reaches adb literally" test -n "$(staged_device_path)"
refute "no device path is rewritten into the Windows tree" \
  grep -q "^argv=$MSYS_ROOT/data" "$ARGV_LOG"
check "the host push source is still translated for adb.exe" \
  grep -Fqx "argv=$MSYS_ROOT$HOST_HELPER" "$ARGV_LOG"
refute "every rewritten argument is a real host path" converted_argument_that_is_not_a_host_path
# The serial carries a colon and the target is not a path; the runtime must leave it alone, and so
# must the exclusion list.
check "the panel serial is passed through unchanged" grep -Fqx 'argv=panel.test:5555' "$ARGV_LOG"

echo "-- negative control: the same flow with the guard ignored --"
MSYS_STAGED="$(staged_device_path)"
MOCK_MSYS_IGNORE_EXCL=1 run_installer msys
check "the unguarded run rewrites the device staging path" \
  grep -q "^argv=$MSYS_ROOT/data/local/tmp/hapaneld-helper\.probe-" "$ARGV_LOG"
refute "the unguarded run never sends a literal device path" \
  grep -q '^argv=/data/local/tmp/hapaneld-helper\.probe-' "$ARGV_LOG"
check "the unguarded run trips the rewritten-argument contract" \
  converted_argument_that_is_not_a_host_path
check "the guarded run and the control differ only in the guard" test -n "$MSYS_STAGED"

echo "-- non-regression: Linux and macOS are unaffected --"
run_installer plain
check "the run completes with no MSYS emulation in the path" test "$INSTALLER_STATUS" -eq 0
check "the device staging path is unchanged off Windows" \
  grep -Eq 'push .* /data/local/tmp/hapaneld-helper\.probe-[0-9a-f]+$' "$CALL_LOG"
check "the host push source is unchanged off Windows" \
  grep -Fq "push $HOST_HELPER " "$CALL_LOG"
refute "no emulator argv is recorded when the emulator is absent" test -s "$ARGV_LOG"

if [ "$failures" -eq 0 ]; then
  echo "all Git Bash path-conversion checks passed"
else
  echo "$failures check(s) failed" >&2
fi
exit $(( failures > 0 ))
