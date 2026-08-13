#!/usr/bin/env bash
#
# Install the hapaneld-helper root helper as a boot-persistent service on a panel.
# Run it on every rooted supported panel. Sandbox-walled panels need the daemon for privileged
# controls; direct-su panels also need the current descriptor-confined Companion data protocol.
#
# Two install paths, chosen by a capability probe on the device (not by root-tool identity):
#
#   Vendor root / userdebug (default): /system is rw-remountable. Places the binary in /system/bin
#   and the init .rc in /system/etc/init. Boot-persistent via the Android init service. An
#   OTA/factory-reset would remove it.
#
#   Systemless root (Magisk, KernelSU, APatch, …): /system is NOT rw-remountable. A root-owned binary
#   is installed under /data/adb/hapaneld and a service.d script is written to
#   /data/adb/service.d/hapaneld-helper.sh, which waits for sys.boot_completed then launches the
#   daemon in background. This path is selected only after detecting a supported systemless runner.
#   Survives reboots; removed by a factory-reset (same as the vendor path).
#
#   ./helper/install-daemon.sh <panel-ip:5555> [abi]
#
# Requires: adb access + root on the panel, and a built binary (./helper/build.sh). Root is probed —
# vendor su forms vary (`su 0`, `su -c`, `su root`) and userdebug adbd may be root with no su at all.
set -euo pipefail

TARGET="${1:?usage: install-daemon.sh <panel-ip:5555> [abi]}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  echo "✗ $1" >&2; shift
  local l; for l in "$@"; do echo "   $l" >&2; done
  exit 1
}

# Render what the panel actually answered so an unrecognised state is reportable instead of anonymous.
# A refusal that cannot say what it saw sends the operator to repair the wrong thing: the SMT1019
# report (#106) had working adb and working root, and was told to restore both.
#
# Non-printing characters are REPORTED, not silently removed. Deleting them would render an answer
# that differs from a valid one only by an invisible byte as though it were the valid one — hiding the
# single most useful clue in the line whose whole purpose is to carry clues. They are still not
# printed raw: this is text from an untrusted device shell, so the line is sanitised and truncated and
# their presence is stated instead. Line breaks and tabs are ordinary answer shape and are not flagged.
describe_observed_state() {
  local observed="$1" flattened rendered
  flattened="$(printf '%s' "$observed" | LC_ALL=C tr '\n\t' '  ')"
  rendered="$(printf '%s' "$flattened" | LC_ALL=C tr -d '\000-\037\177')"
  if [ -z "$rendered" ]; then
    if [ -n "$flattened" ]; then
      printf 'the panel answered only non-printing characters'
    else
      printf 'the panel answered nothing'
    fi
    return
  fi
  [ "${#rendered}" -le 200 ] || rendered="${rendered:0:200}…"
  if [ "$flattened" = "$rendered" ]; then
    printf 'the panel answered: %s' "$rendered"
  else
    printf 'the panel answered: %s (plus non-printing characters, so this is how it was sent, not what it said)' "$rendered"
  fi
}

# Run one host command behind a hard deadline. The command owns a local process group so timeout and
# caller cancellation terminate and reap shell wrappers as well as their non-detached descendants.
# Status 124 is reserved for the deadline; ordinary command failures retain their original status.
run_with_deadline() {
  local seconds="$1" command_pid status deadline
  shift

  (
    command_pid=""
    terminate_deadline_command_group() {
      local signal_status="$1" kill_deadline
      trap - INT TERM
      if [ -n "$command_pid" ]; then
        kill -TERM -- "-$command_pid" 2>/dev/null || true
        kill_deadline=$((SECONDS + 2))
        while kill -0 -- "-$command_pid" 2>/dev/null && [ "$SECONDS" -lt "$kill_deadline" ]; do
          sleep 0.1
        done
        kill -KILL -- "-$command_pid" 2>/dev/null || true
        wait "$command_pid" 2>/dev/null || true
        command_pid=""
      fi
      return "$signal_status"
    }
    handle_deadline_signal() {
      local signal_status="$1"
      terminate_deadline_command_group "$signal_status" || true
      exit "$signal_status"
    }
    trap 'handle_deadline_signal 130' INT
    trap 'handle_deadline_signal 143' TERM
    set -m
    "$@" &
    command_pid=$!
    # The asynchronous command keeps the process group assigned above after monitor mode is disabled;
    # disabling it immediately prevents Bash job-completion notices from contaminating captured output.
    set +m
    deadline=$((SECONDS + seconds))
    while kill -0 -- "-$command_pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
      sleep 0.1
    done
    if kill -0 -- "-$command_pid" 2>/dev/null; then
      terminate_deadline_command_group 124 || true
      return 124
    fi
    wait "$command_pid"
    status=$?
    command_pid=""
    return "$status"
  )
}

ADB_COMMAND="$(command -v adb 2>/dev/null || true)"
[ -n "$ADB_COMMAND" ] || fail "adb (Android Platform Tools) was not found" \
  "Install adb, then re-run the identical command; no helper files were changed."
ADB_COMMAND_TIMEOUT_SECONDS="${ADB_COMMAND_TIMEOUT_SECONDS:-120}"
ADB_PREFLIGHT_TIMEOUT_SECONDS="${ADB_PREFLIGHT_TIMEOUT_SECONDS:-20}"
PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}"
for timeout_name in ADB_COMMAND_TIMEOUT_SECONDS ADB_PREFLIGHT_TIMEOUT_SECONDS \
    PRIVILEGE_INSPECTION_TIMEOUT_SECONDS; do
  timeout_value="${!timeout_name}"
  case "$timeout_value" in
    ''|*[!0-9]*|0) fail "$timeout_name must be a positive whole number of seconds" ;;
  esac
done

# Every ordinary adb operation crosses this one boundary. Compound operations that need one total
# budget use the absolute executable inside their own run_with_deadline call instead of nesting owners.
adb() {
  run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" "$ADB_COMMAND" "$@"
}

# `adb connect` exits 0 even when it fails, and a device can sit "unauthorized" (RSA dialog waiting on
# the panel screen) or "offline" (stale session). Verify the state so failures surface here with
# recovery steps, not as a raw abort at the first adb push. $1=quiet re-checks after an adbd restart.
adb_preflight_raw() {
  local state="" i=0
  "$ADB_COMMAND" connect "$TARGET" >/dev/null 2>&1 || true
  while [ "$i" -lt 12 ]; do
    i=$((i + 1))
    state="$("$ADB_COMMAND" devices 2>/dev/null | awk -v t="$TARGET" '$1==t {print $2}')"
    if [ "$state" = "device" ]; then printf '%s\n' "$state"; return 0; fi
    if [ "$state" = "offline" ] && [ "$i" = 4 ]; then
      "$ADB_COMMAND" disconnect "$TARGET" >/dev/null 2>&1 || true
      "$ADB_COMMAND" connect "$TARGET" >/dev/null 2>&1 || true
    fi
    sleep 1
  done
  printf '%s\n' "${state:-missing}"
  return 1
}

adb_preflight() {
  [ "${1:-}" = quiet ] || echo "==> connecting to $TARGET"
  local state="" status
  if state="$(run_with_deadline "$ADB_PREFLIGHT_TIMEOUT_SECONDS" adb_preflight_raw)"; then
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    fail "adb connection check timed out after ${ADB_PREFLIGHT_TIMEOUT_SECONDS}s" \
      "Restore adb responsiveness and re-run; no helper files were changed."
  fi
  case "$state" in
    unauthorized) fail "panel refused adb: unauthorized" \
      "Accept the ADB authorization dialog shown ON THE PANEL'S SCREEN (tick 'always allow'), then re-run." ;;
    offline) fail "panel is stuck 'offline' on adb" \
      "Toggle 'ADB debugging' off/on in the panel's Developer options (or power-cycle the panel), then re-run." ;;
    *) fail "cannot reach $TARGET over adb" \
      "Check: the IP, that network ADB is enabled (Developer options), the port ($TARGET), and that this machine is on the panel's network/VLAN." \
      "Some panels only expose adb on USB until 'adb tcpip 5555' is run once — see docs/provisioning.md ('Bootstrapping adb')." ;;
  esac
}

# Root-path probe — vendor root varies TWICE over: the prefix (`su 0`, `su root`, `su -c`) AND the
# dialect. Join-style su (SuperSU/toolbox — the NSPanel Pro) re-joins argv and runs it through its
# own `sh -c`, so a block must be passed as ONE quoted word (`su 0 "BLOCK"`) — adding `sh -c`
# double-wraps and silently STRIPS the quoting. Execvp-style su (AOSP) execs argv directly, so a
# block DOES need the `sh -c` wrapper. `"id; id"` only succeeds through a shell, so probing with it
# identifies the wrapping that preserves a multi-command block. A root adbd (userdebug after
# `adb root`) needs no su at all — probed first. A su that prompts on-screen (Magisk) can take ~10s
# to auto-deny a form; the probe tolerates that.
SU_FORM=""
SU_PROBE_TIMED_OUT=0
probe_su_uncached() {
  local u key pre
  u="$("$ADB_COMMAND" -s "$TARGET" shell id 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in uid=0*) printf 'shell\n'; return 0 ;; esac
  for key in su0 suroot; do
    case "$key" in su0) pre="su 0" ;; suroot) pre="su root" ;; esac
    u="$("$ADB_COMMAND" -s "$TARGET" shell "$pre \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) printf '%sjoin\n' "$key"; return 0 ;; esac
    u="$("$ADB_COMMAND" -s "$TARGET" shell "$pre sh -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) printf '%sshc\n' "$key"; return 0 ;; esac
  done
  u="$("$ADB_COMMAND" -s "$TARGET" shell "su -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in *uid=0*) printf 'suc\n'; return 0 ;; esac
  printf 'none\n'
  return 1
}

probe_su() {
  if [ -n "$SU_FORM" ]; then [ "$SU_FORM" != none ]; return; fi
  local result="" status
  SU_PROBE_TIMED_OUT=0
  if result="$(run_with_deadline "$PRIVILEGE_INSPECTION_TIMEOUT_SECONDS" probe_su_uncached)"; then
    SU_FORM="$result"
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    SU_PROBE_TIMED_OUT=1
    SU_FORM=""
  else
    SU_FORM="${result:-none}"
  fi
  return 1
}

# Quote a shell block for the device shell's outer double quotes. Join-style su needs the complete
# block as one argv word, while the unprivileged device shell must not expand dollars, backticks,
# substitutions, or embedded quotes before su receives it.
quote_root_command() {
  local command="$1"
  command="${command//\\/\\\\}"
  command="${command//\"/\\\"}"
  command="${command//\$/\\\$}"
  command="${command//\`/\\\`}"
  printf '%s\n' "$command"
}

run_root() {
  local command="$1" quoted
  case "$SU_FORM" in
    shell|su0join|su0shc|surootjoin|surootshc|suc) ;;
    # A form this dispatch does not know is a failure, never a silent success with empty output —
    # callers read run_root's status as "the panel was asked".
    *) return 1 ;;
  esac
  quoted="$(quote_root_command "$command")"
  # Every classifier below reads device state as an exact token, so the transport must not be able to
  # alter one. An adbd without the `shell_v2` feature serves `shell:` through a PTY unconditionally,
  # which turns each device newline into CRLF; the panel then answers NO_STALE_TRANSACTION\r and every
  # exact comparison in this script misses. Normalise once, here, at the single seam they all read
  # through, rather than at each call site where the next added site would forget it. Only the carriage
  # return is removed: unexpected tokens, extra lines and empty output stay unexpected, so an unknown
  # state still refuses.
  case "$SU_FORM" in
    shell)      adb -s "$TARGET" shell "$command" ;;
    su0join)    adb -s "$TARGET" shell "su 0 \"$quoted\"" ;;
    su0shc)     adb -s "$TARGET" shell "su 0 sh -c \"$quoted\"" ;;
    surootjoin) adb -s "$TARGET" shell "su root \"$quoted\"" ;;
    surootshc)  adb -s "$TARGET" shell "su root sh -c \"$quoted\"" ;;
    suc)        adb -s "$TARGET" shell "su -c \"$quoted\"" ;;
  esac | tr -d '\r'
}

run_root_locked() {
  local block="$1"
  run_root '
    lock=/dev/.hapaneld-helper-transaction.lock
    if ! mkdir "$lock" 2>/dev/null; then
      holder=$(cat "$lock/pid" 2>/dev/null || true)
      case "$holder" in
        ''|*[!0-9]*) echo TRANSACTION_BUSY; exit 75 ;;
        *) [ ! -d "/proc/$holder" ] || { echo TRANSACTION_BUSY; exit 75; } ;;
      esac
      rm -rf "$lock" 2>/dev/null || { echo TRANSACTION_BUSY; exit 75; }
      mkdir "$lock" 2>/dev/null || { echo TRANSACTION_BUSY; exit 75; }
    fi
    echo $$ > "$lock/pid" || { rm -rf "$lock"; echo TRANSACTION_BUSY; exit 75; }
    cleanup_helper_lock() { rm -rf /dev/.hapaneld-helper-transaction.lock; }
    trap cleanup_helper_lock 0 1 2 3 15
  '"$block"
}

adb_preflight
# Try for a root adbd (userdebug builds) — harmless where unsupported. adbd restarts on success and
# can drop the TCP session, so quietly re-verify the connection either way.
adb -s "$TARGET" root >/dev/null 2>&1 || true
sleep 1
adb_preflight quiet

if ! probe_su; then
  [ "$SU_PROBE_TIMED_OUT" = 0 ] || fail "root-access inspection timed out after ${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS}s" \
    "adb or a privilege prompt did not respond. Nothing was installed; restore adb responsiveness, dismiss any on-panel root prompt, then re-run."
  fail "no working root path on this panel (tried: adbd-root, 'su 0', 'su -c', 'su root')" \
    "The helper daemon requires root — it IS the privileged control path on sandbox-walled panels." \
    "Rooted panel with a different su syntax? Run 'adb shell', find the invocation that gives uid=0, and open an issue: https://github.com/maxlyth/ha-paneld/issues" \
    "No root at all? The daemon cannot be installed; ha-paneld still runs with reduced control (see helper/README.md)."
fi
case "$SU_FORM" in
  shell)      echo "==> root path: adbd runs as root (no su needed)" ;;
  su0join)    echo "==> root path: su 0 \"<cmd>\" (join-style su)" ;;
  su0shc)     echo "==> root path: su 0 sh -c \"<cmd>\"" ;;
  surootjoin) echo "==> root path: su root \"<cmd>\" (join-style su)" ;;
  surootshc)  echo "==> root path: su root sh -c \"<cmd>\"" ;;
  suc)        echo "==> root path: su -c \"<cmd>\"" ;;
esac

if ! run_root '[ ! -f /system/bin/.hapaneld-helper-upgrade ] && [ ! -f /data/adb/hapaneld/.helper-upgrade.marker ] && [ ! -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ]' \
    >/dev/null 2>&1; then
  fail "an incomplete APK-coupled helper upgrade must be recovered by the provisioner first" \
    "Re-run the same scripts/provision.sh or scripts/update-fleet.sh command that started the upgrade." \
    "This standalone installer uses a separate journal and did not change helper files."
fi

ABI="${2:-$(adb -s "$TARGET" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')}"
[ -n "$ABI" ] || fail "could not read the panel's ABI (getprop returned nothing)" \
  "Pass it explicitly: ./helper/install-daemon.sh $TARGET arm64-v8a   (or armeabi-v7a)"
BIN_ROOT="${HAPANELD_HELPER_DIST_DIR:-$HERE/dist}"
BIN="$BIN_ROOT/$ABI/hapaneld-helper"
[ -f "$BIN" ] || fail "missing $BIN" "Build it first: ./helper/build.sh   (builds every ABI into helper/dist/)"
host_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else fail "cannot authenticate helper staging" "Install sha256sum (or shasum), then re-run."
  fi
}
BIN_SHA256="$(host_sha256 "$BIN")"
RC_SHA256="$(host_sha256 "$HERE/hapaneld-helper.rc")"
BUILD_ID="$("$HERE/source-id.sh")"
TRANSACTION_ID="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
printf '%s\n' "$TRANSACTION_ID" | grep -Eq '^[0-9a-f]{32}$' || fail "could not create a root-helper transaction nonce"
PROBE_STAGING_PATH="/data/local/tmp/hapaneld-helper.probe-$TRANSACTION_ID"
RC_STAGING_PATH="$PROBE_STAGING_PATH.rc"
SVC_STAGING_PATH="$PROBE_STAGING_PATH.svc"
MANUAL_LEASE_SECONDS="${HAPANELD_MANUAL_LEASE_SECONDS:-600}"
MANUAL_LEASE_RENEW_SECONDS="${HAPANELD_MANUAL_LEASE_RENEW_SECONDS:-60}"
case "$MANUAL_LEASE_SECONDS" in ''|*[!0-9]*|0) fail "invalid standalone transaction lease duration" ;; esac
case "$MANUAL_LEASE_RENEW_SECONDS" in ''|*[!0-9]*|0) fail "invalid standalone transaction lease renewal interval" ;; esac
[ "$MANUAL_LEASE_RENEW_SECONDS" -lt "$MANUAL_LEASE_SECONDS" ] || fail "standalone transaction lease renewal must be shorter than its duration"
MANUAL_LEASE_GUARD_PID=""
MANUAL_LEASE_GUARD_FAILURE=""
cleanup_probe_staging() {
  adb -s "$TARGET" shell rm -f "$PROBE_STAGING_PATH" "$RC_STAGING_PATH" "$SVC_STAGING_PATH" >/dev/null 2>&1 || true
}
stop_manual_lease_guard() {
  if [ -n "${MANUAL_LEASE_GUARD_PID:-}" ]; then
    kill "$MANUAL_LEASE_GUARD_PID" >/dev/null 2>&1 || true
    wait "$MANUAL_LEASE_GUARD_PID" >/dev/null 2>&1 || true
    MANUAL_LEASE_GUARD_PID=""
  fi
}
cleanup_installer_resources() {
  stop_manual_lease_guard
  [ -z "${MANUAL_LEASE_GUARD_FAILURE:-}" ] || rm -f "$MANUAL_LEASE_GUARD_FAILURE"
  MANUAL_LEASE_GUARD_FAILURE=""
  cleanup_probe_staging
}
trap cleanup_installer_resources EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

helper_daemon_reply() {
  local command="$1" install_kind="$2" helper_path="${3:-}"
  case "$command" in PING|COMPANIONCAPS|BUILDID) ;; *) return 1 ;; esac
  if [ -z "$helper_path" ]; then
    case "$install_kind" in
      system) helper_path=/system/bin/hapaneld-helper ;;
      systemless) helper_path=/data/adb/hapaneld/hapaneld-helper ;;
      *) return 1 ;;
    esac
  fi
  case "$helper_path" in
    /system/bin/hapaneld-helper|/data/adb/hapaneld/hapaneld-helper|/data/adb/hapaneld/.helper-manual-probe-[0-9a-f]*) ;;
    *) return 1 ;;
  esac
  run_root 'exec '"$helper_path"' --request '"$command"
}

wait_for_helper_reply() {
  local command="$1" expected="$2" install_kind="$3" helper_path="${4:-}" reply="" attempt=0
  while [ "$attempt" -lt 10 ]; do
    attempt=$((attempt + 1))
    reply="$(helper_daemon_reply "$command" "$install_kind" "$helper_path" 2>/dev/null || true)"
    [ "$reply" = "$expected" ] && return 0
    sleep 1
  done
  return 1
}

renew_manual_lease() {
  local install_kind="$1" marker target_service renewed
  case "$install_kind" in
    system)
      marker=/system/bin/.hapaneld-helper-manual-upgrade
      target_service="$RC_SHA256"
      ;;
    systemless)
      marker=/data/adb/hapaneld/.helper-manual-upgrade.marker
      target_service="${SVC_SHA256:-}"
      ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$target_service" | grep -Eq '^[0-9a-f]{64}$' || return 1
  renewed="$(run_root '
    marker='"$marker"'
    [ -f "$marker" ] || { echo LEASE_PENDING; exit 0; }
    grep -q ^JOURNAL_VERSION=2$ "$marker" || exit 1
    grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ "$marker" || exit 1
    grep -qx TRANSACTION_ID='"$TRANSACTION_ID"' "$marker" || exit 1
    grep -qx TARGET_BUILD_ID='"$BUILD_ID"' "$marker" || exit 1
    grep -qx TARGET_HELPER_SHA256='"$BIN_SHA256"' "$marker" || exit 1
    grep -qx TARGET_SERVICE_SHA256='"$target_service"' "$marker" || exit 1
    current_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 1
    current_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null) || exit 1
    echo "$current_uptime" | grep -Eq ^[0-9]+$ || exit 1
    lease_until=$((current_uptime + '"$MANUAL_LEASE_SECONDS"'))
    sed "/^LEASE_BOOT_ID=/d; /^LEASE_UNTIL_UPTIME=/d" "$marker" > "$marker.lease-new" || exit 1
    echo LEASE_BOOT_ID="$current_boot" >> "$marker.lease-new"
    echo LEASE_UNTIL_UPTIME="$lease_until" >> "$marker.lease-new"
    chown 0:0 "$marker.lease-new" || exit 1
    chmod 600 "$marker.lease-new" || exit 1
    sync || exit 1
    mv -f "$marker.lease-new" "$marker" || exit 1
    sync || exit 1
    echo LEASE_OK
  ' 2>&1)" || true
  [ "$renewed" = LEASE_OK ] || [ "$renewed" = LEASE_PENDING ]
}

start_manual_lease_guard() {
  local install_kind="$1" owner_pid="$$"
  MANUAL_LEASE_GUARD_FAILURE="$(mktemp)"
  (
    sleep_pid=""
    cleanup_manual_lease_sleep() {
      [ -z "$sleep_pid" ] || kill "$sleep_pid" >/dev/null 2>&1 || true
      [ -z "$sleep_pid" ] || wait "$sleep_pid" >/dev/null 2>&1 || true
      sleep_pid=""
    }
    trap 'cleanup_manual_lease_sleep; exit 0' INT TERM
    trap cleanup_manual_lease_sleep EXIT
    while :; do
      sleep "$MANUAL_LEASE_RENEW_SECONDS" &
      sleep_pid=$!
      wait "$sleep_pid" || exit 0
      sleep_pid=""
      kill -0 "$owner_pid" >/dev/null 2>&1 || exit 0
      renew_manual_lease "$install_kind" || {
        printf 'failed\n' > "$MANUAL_LEASE_GUARD_FAILURE"
        exit 1
      }
    done
  ) &
  MANUAL_LEASE_GUARD_PID=$!
}

manual_lease_guard_succeeded() {
  [ -z "${MANUAL_LEASE_GUARD_FAILURE:-}" ] || [ ! -s "$MANUAL_LEASE_GUARD_FAILURE" ]
}

rollback_root_helper() {
  local install_kind="$1" transaction_id="${2:-$TRANSACTION_ID}" target_build="${3:-$BUILD_ID}"
  local target_helper="${4:-$BIN_SHA256}" target_service="${5:-}" restored probe_path
  if [ -z "$target_service" ]; then
    case "$install_kind" in
      system) target_service="$RC_SHA256" ;;
      systemless) target_service="${SVC_SHA256:-}" ;;
    esac
  fi
  if ! printf '%s\n' "$target_service" | grep -Eq '^[0-9a-f]{64}$'; then
    [ "$transaction_id" = legacy ] && [ "$target_service" = - ] || return 1
  fi
  stop_manual_lease_guard
  # The probe client belongs to this invocation, not to the recovered journal. This keeps legacy V1
  # recovery compatible while the journal's own transaction identity still gates rollback content.
  probe_path="/data/adb/hapaneld/.helper-manual-probe-$TRANSACTION_ID"
  case "$install_kind" in
    system)
      restored="$(run_root_locked '
        mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
        [ -f /system/bin/.hapaneld-helper-manual-upgrade ] || { echo ROLLBACK_UNNEEDED; exit 0; }
        grep -q ^OLD_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /system/bin/hapaneld-helper.hapaneld-manual-recovery ] || exit 1
        grep -q ^OLD_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery ] || exit 1
        grep -q ^LEGACY_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /system/bin/hapaneld-ledd.hapaneld-manual-recovery ] || exit 1
        grep -q ^LEGACY_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery ] || exit 1
        grep -q ^ALT_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ] || exit 1
        grep -q ^ALT_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade || [ -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery ] || exit 1
        if grep -q ^JOURNAL_VERSION=2$ /system/bin/.hapaneld-helper-manual-upgrade; then
          grep -qx TRANSACTION_ID='"$transaction_id"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
          grep -qx TARGET_BUILD_ID='"$target_build"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
          grep -qx TARGET_HELPER_SHA256='"$target_helper"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
          grep -qx TARGET_SERVICE_SHA256='"$target_service"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        else
          [ '"$transaction_id"' = legacy ] || exit 1
          grep -q ^JOURNAL_VERSION=1$ /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        fi
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        marker=/system/bin/.hapaneld-helper-manual-upgrade
        live_recorded() {
          name=$1; live=$2
          if grep -q ^"$name"=1$ "$marker"; then
            expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
            actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$expected" ]
          else
            [ ! -e "$live" ]
          fi
        }
        hash_is() {
          expected=$1; live=$2
          actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
          [ "${actual%% *}" = "$expected" ]
        }
        wait_for_helper_retirement() {
          attempt=0
          while [ "$attempt" -lt 10 ]; do
            if ! pidof hapaneld-helper >/dev/null 2>&1 && ! pidof hapaneld-ledd >/dev/null 2>&1; then
              return 0
            fi
            attempt=$((attempt + 1))
            sleep 1
          done
          return 1
        }
        if live_recorded OLD_BIN /system/bin/hapaneld-helper &&
           live_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc &&
           live_recorded LEGACY_BIN /system/bin/hapaneld-ledd &&
           live_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc &&
           live_recorded ALT_BIN /data/adb/hapaneld/hapaneld-helper &&
           live_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh; then
          live_state=PRE_SWAP
        elif hash_is '"$target_helper"' /system/bin/hapaneld-helper &&
             hash_is '"$target_service"' /system/etc/init/hapaneld-helper.rc &&
             [ ! -e /system/bin/hapaneld-ledd ] && [ ! -e /system/etc/init/hapaneld-ledd.rc ] &&
             [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -e /data/adb/service.d/hapaneld-helper.sh ]; then
          live_state=TARGET
        else
          echo ROLLBACK_UNKNOWN
          exit 0
        fi
        if grep -q ^OLD_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^OLD_BIN_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /system/bin/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/bin/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^OLD_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^OLD_SERVICE_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^LEGACY_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^LEGACY_BIN_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /system/bin/hapaneld-ledd.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/bin/hapaneld-ledd.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^LEGACY_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^LEGACY_SERVICE_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^ALT_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^ALT_BIN_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^ALT_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          expected=$(sed -n s/^ALT_SERVICE_SHA256=//p /system/bin/.hapaneld-helper-manual-upgrade)
          actual=$(sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        mkdir -p /data/adb/hapaneld || exit 1
        chown 0:0 /data/adb/hapaneld || exit 1
        chmod 700 /data/adb/hapaneld || exit 1
        rm -f '"$probe_path"'
        ( sha256sum '"$PROBE_STAGING_PATH"' 2>/dev/null || toybox sha256sum '"$PROBE_STAGING_PATH"' 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || exit 1
        cp '"$PROBE_STAGING_PATH"' '"$probe_path"' || exit 1
        chown 0:0 '"$probe_path"'; chmod 700 '"$probe_path"'
        ( sha256sum '"$probe_path"' 2>/dev/null || toybox sha256sum '"$probe_path"' 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || exit 1
        stop hapaneld_helper 2>/dev/null
        stop hapaneld_ledd 2>/dev/null
        pkill -x hapaneld-helper 2>/dev/null
        pkill -x hapaneld-ledd 2>/dev/null
        wait_for_helper_retirement || exit 1
        if grep -q ^OLD_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          rm -f /system/bin/hapaneld-helper
          cp -p /system/bin/hapaneld-helper.hapaneld-manual-recovery /system/bin/hapaneld-helper || exit 1
          chown 0:0 /system/bin/hapaneld-helper; chmod 755 /system/bin/hapaneld-helper
        else
          rm -f /system/bin/hapaneld-helper
        fi
        if grep -q ^OLD_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          rm -f /system/etc/init/hapaneld-helper.rc
          cp -p /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery /system/etc/init/hapaneld-helper.rc || exit 1
          chown 0:0 /system/etc/init/hapaneld-helper.rc; chmod 644 /system/etc/init/hapaneld-helper.rc
        else
          rm -f /system/etc/init/hapaneld-helper.rc
        fi
        if grep -q ^LEGACY_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          cp -p /system/bin/hapaneld-ledd.hapaneld-manual-recovery /system/bin/hapaneld-ledd || exit 1
          chown 0:0 /system/bin/hapaneld-ledd; chmod 755 /system/bin/hapaneld-ledd
        else
          rm -f /system/bin/hapaneld-ledd
        fi
        if grep -q ^LEGACY_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          cp -p /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery /system/etc/init/hapaneld-ledd.rc || exit 1
          chown 0:0 /system/etc/init/hapaneld-ledd.rc; chmod 644 /system/etc/init/hapaneld-ledd.rc
        else
          rm -f /system/etc/init/hapaneld-ledd.rc
        fi
        if grep -q ^ALT_BIN=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          cp -p /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery /data/adb/hapaneld/hapaneld-helper || exit 1
          chown 0:0 /data/adb/hapaneld/hapaneld-helper; chmod 755 /data/adb/hapaneld/hapaneld-helper
        else
          rm -f /data/adb/hapaneld/hapaneld-helper
        fi
        if grep -q ^ALT_SERVICE=1$ /system/bin/.hapaneld-helper-manual-upgrade; then
          cp -p /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery /data/adb/service.d/hapaneld-helper.sh || exit 1
          chown 0:0 /data/adb/service.d/hapaneld-helper.sh; chmod 755 /data/adb/service.d/hapaneld-helper.sh
        else
          rm -f /data/adb/service.d/hapaneld-helper.sh
        fi
        sync || exit 1
        if [ -f /system/bin/hapaneld-helper.hapaneld-manual-recovery ]; then
          start hapaneld_helper 2>/dev/null
          /system/bin/hapaneld-helper --request PING >/dev/null 2>&1 ||
            ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
          echo ROLLBACK_RESTARTED
        elif [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ]; then
          /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
          echo ROLLBACK_RESTARTED
        elif [ -f /system/bin/hapaneld-ledd.hapaneld-manual-recovery ]; then
          start hapaneld_ledd 2>/dev/null || ( /system/bin/hapaneld-ledd >/dev/null 2>&1 & )
          echo ROLLBACK_LEGACY
        else
          echo ROLLBACK_EMPTY
        fi
      ' 2>&1)" || true
      ;;
    systemless)
      restored="$(run_root_locked '
        [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ] || { echo ROLLBACK_UNNEEDED; exit 0; }
        grep -q ^OLD_BIN=0$ /data/adb/hapaneld/.helper-manual-upgrade.marker || [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ] || exit 1
        grep -q ^OLD_SERVICE=0$ /data/adb/hapaneld/.helper-manual-upgrade.marker || [ -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery ] || exit 1
        if grep -q ^JOURNAL_VERSION=2$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          grep -qx TRANSACTION_ID='"$transaction_id"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
          grep -qx TARGET_BUILD_ID='"$target_build"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
          grep -qx TARGET_HELPER_SHA256='"$target_helper"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
          grep -qx TARGET_SERVICE_SHA256='"$target_service"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        else
          [ '"$transaction_id"' = legacy ] || exit 1
          grep -q ^JOURNAL_VERSION=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        fi
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        marker=/data/adb/hapaneld/.helper-manual-upgrade.marker
        live_recorded() {
          name=$1; live=$2
          if grep -q ^"$name"=1$ "$marker"; then
            expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
            actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$expected" ]
          else
            [ ! -e "$live" ]
          fi
        }
        wait_for_helper_retirement() {
          attempt=0
          while [ "$attempt" -lt 10 ]; do
            if ! pidof hapaneld-helper >/dev/null 2>&1 && ! pidof hapaneld-ledd >/dev/null 2>&1; then
              return 0
            fi
            attempt=$((attempt + 1))
            sleep 1
          done
          return 1
        }
        hash_is() {
          expected=$1; live=$2
          actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
          [ "${actual%% *}" = "$expected" ]
        }
        if live_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper &&
           live_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh; then
          live_state=PRE_SWAP
        elif hash_is '"$target_helper"' /data/adb/hapaneld/hapaneld-helper &&
             hash_is '"$target_service"' /data/adb/service.d/hapaneld-helper.sh; then
          live_state=TARGET
        else
          echo ROLLBACK_UNKNOWN
          exit 0
        fi
        if grep -q ^OLD_BIN=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          expected=$(sed -n s/^OLD_BIN_SHA256=//p /data/adb/hapaneld/.helper-manual-upgrade.marker)
          actual=$(sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        if grep -q ^OLD_SERVICE=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          expected=$(sed -n s/^OLD_SERVICE_SHA256=//p /data/adb/hapaneld/.helper-manual-upgrade.marker)
          actual=$(sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null) || exit 1
          [ "${actual%% *}" = "$expected" ] || exit 1
        fi
        mkdir -p /data/adb/hapaneld || exit 1
        chown 0:0 /data/adb/hapaneld || exit 1
        chmod 700 /data/adb/hapaneld || exit 1
        rm -f '"$probe_path"'
        ( sha256sum '"$PROBE_STAGING_PATH"' 2>/dev/null || toybox sha256sum '"$PROBE_STAGING_PATH"' 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || exit 1
        cp '"$PROBE_STAGING_PATH"' '"$probe_path"' || exit 1
        chown 0:0 '"$probe_path"'; chmod 700 '"$probe_path"'
        ( sha256sum '"$probe_path"' 2>/dev/null || toybox sha256sum '"$probe_path"' 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || exit 1
        stop hapaneld_helper 2>/dev/null
        pkill -x hapaneld-helper 2>/dev/null
        pkill -x hapaneld-ledd 2>/dev/null
        wait_for_helper_retirement || exit 1
        if grep -q ^OLD_BIN=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          rm -f /data/adb/hapaneld/hapaneld-helper
          cp -p /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery /data/adb/hapaneld/hapaneld-helper || exit 1
          chown 0:0 /data/adb/hapaneld/hapaneld-helper; chmod 755 /data/adb/hapaneld/hapaneld-helper
        else
          rm -f /data/adb/hapaneld/hapaneld-helper
        fi
        if grep -q ^OLD_SERVICE=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker; then
          rm -f /data/adb/service.d/hapaneld-helper.sh
          cp -p /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery /data/adb/service.d/hapaneld-helper.sh || exit 1
          chown 0:0 /data/adb/service.d/hapaneld-helper.sh; chmod 755 /data/adb/service.d/hapaneld-helper.sh
        else
          rm -f /data/adb/service.d/hapaneld-helper.sh
        fi
        sync || exit 1
        if [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ]; then
          /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
          echo ROLLBACK_RESTARTED
        elif [ -x /system/bin/hapaneld-helper ]; then
          start hapaneld_helper 2>/dev/null
          /system/bin/hapaneld-helper --request PING >/dev/null 2>&1 ||
            ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
          echo ROLLBACK_RESTARTED
        elif [ -x /system/bin/hapaneld-ledd ]; then
          start hapaneld_ledd 2>/dev/null || ( /system/bin/hapaneld-ledd >/dev/null 2>&1 & )
          echo ROLLBACK_LEGACY
        else
          echo ROLLBACK_EMPTY
        fi
      ' 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  if printf '%s\n' "$restored" | grep -qx ROLLBACK_RESTARTED; then
    if wait_for_helper_reply PING OK "$install_kind" "$probe_path"; then
      run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
      finalize_root_helper_rollback "$install_kind" "$transaction_id" "$target_build" "$target_helper" "$target_service"
      return $?
    fi
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    return 1
  else
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    if printf '%s\n' "$restored" | grep -Eqx 'ROLLBACK_(EMPTY|LEGACY)'; then
      finalize_root_helper_rollback "$install_kind" "$transaction_id" "$target_build" "$target_helper" "$target_service"
    else
      printf '%s\n' "$restored" | grep -qx ROLLBACK_UNNEEDED
    fi
  fi
}

finalize_root_helper_rollback() {
  local install_kind="$1" transaction_id="$2" target_build="$3" target_helper="$4" target_service="$5" finalized
  case "$install_kind" in
    system)
      finalized="$(run_root_locked '
        marker=/system/bin/.hapaneld-helper-manual-upgrade
        mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
        [ -f "$marker" ] || exit 1
        if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
          grep -qx TRANSACTION_ID='"$transaction_id"' "$marker" || exit 1
          grep -qx TARGET_BUILD_ID='"$target_build"' "$marker" || exit 1
          grep -qx TARGET_HELPER_SHA256='"$target_helper"' "$marker" || exit 1
          grep -qx TARGET_SERVICE_SHA256='"$target_service"' "$marker" || exit 1
        else
          [ '"$transaction_id"' = legacy ] || exit 1
          grep -q ^JOURNAL_VERSION=1$ "$marker" || exit 1
        fi
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ "$marker" || exit 1
        live_recorded() {
          name=$1; live=$2
          if grep -q ^"$name"=1$ "$marker"; then
            expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
            actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$expected" ]
          else
            [ ! -e "$live" ]
          fi
        }
        live_recorded OLD_BIN /system/bin/hapaneld-helper &&
          live_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc &&
          live_recorded LEGACY_BIN /system/bin/hapaneld-ledd &&
          live_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc &&
          live_recorded ALT_BIN /data/adb/hapaneld/hapaneld-helper &&
          live_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh || exit 1
        rm -f "$marker" || exit 1
        sync || exit 1
        rm -f /system/bin/hapaneld-helper.hapaneld-manual-recovery \
          /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery \
          /system/bin/hapaneld-ledd.hapaneld-manual-recovery \
          /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery \
          /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery \
          /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || true
        sync 2>/dev/null || true
        echo ROLLBACK_FINALIZED
      ' 2>&1)" || true
      ;;
    systemless)
      finalized="$(run_root_locked '
        marker=/data/adb/hapaneld/.helper-manual-upgrade.marker
        [ -f "$marker" ] || exit 1
        if grep -q ^JOURNAL_VERSION=2$ "$marker"; then
          grep -qx TRANSACTION_ID='"$transaction_id"' "$marker" || exit 1
          grep -qx TARGET_BUILD_ID='"$target_build"' "$marker" || exit 1
          grep -qx TARGET_HELPER_SHA256='"$target_helper"' "$marker" || exit 1
          grep -qx TARGET_SERVICE_SHA256='"$target_service"' "$marker" || exit 1
        else
          [ '"$transaction_id"' = legacy ] || exit 1
          grep -q ^JOURNAL_VERSION=1$ "$marker" || exit 1
        fi
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ "$marker" || exit 1
        live_recorded() {
          name=$1; live=$2
          if grep -q ^"$name"=1$ "$marker"; then
            expected=$(sed -n s/^"$name"_SHA256=//p "$marker")
            actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$expected" ]
          else
            [ ! -e "$live" ]
          fi
        }
        live_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper &&
          live_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh || exit 1
        rm -f "$marker" || exit 1
        sync || exit 1
        rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery \
          /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || true
        sync 2>/dev/null || true
        echo ROLLBACK_FINALIZED
      ' 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$finalized" | grep -qx ROLLBACK_FINALIZED
}

commit_root_helper_upgrade() {
  local committed
  case "$1" in
    system)
      committed="$(run_root_locked '
        mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
        grep -q ^JOURNAL_VERSION=2$ /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        grep -qx TRANSACTION_ID='"$TRANSACTION_ID"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        grep -qx TARGET_BUILD_ID='"$BUILD_ID"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        grep -qx TARGET_HELPER_SHA256='"$BIN_SHA256"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        grep -qx TARGET_SERVICE_SHA256='"$RC_SHA256"' /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        hash_is() {
          expected=$1; live=$2
          actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
          [ "${actual%% *}" = "$expected" ]
        }
        hash_is '"$BIN_SHA256"' /system/bin/hapaneld-helper || exit 1
        hash_is '"$RC_SHA256"' /system/etc/init/hapaneld-helper.rc || exit 1
        [ ! -e /system/bin/hapaneld-ledd ] && [ ! -e /system/etc/init/hapaneld-ledd.rc ] || exit 1
        [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -e /data/adb/service.d/hapaneld-helper.sh ] || exit 1
        rm -f /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        sync || exit 1
        rm -f /system/bin/hapaneld-helper.hapaneld-manual-recovery \
          /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery \
          /system/bin/hapaneld-ledd.hapaneld-manual-recovery \
          /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery \
          /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery \
          /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery \
          /system/bin/hapaneld-ledd /system/etc/init/hapaneld-ledd.rc 2>/dev/null || true
        sync 2>/dev/null || true
        echo COMMIT_OK
      ' 2>&1)" || true
      ;;
    systemless)
      committed="$(run_root_locked '
        grep -q ^JOURNAL_VERSION=2$ /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        grep -qx TRANSACTION_ID='"$TRANSACTION_ID"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        grep -qx TARGET_BUILD_ID='"$BUILD_ID"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        grep -qx TARGET_HELPER_SHA256='"$BIN_SHA256"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        grep -qx TARGET_SERVICE_SHA256='"$SVC_SHA256"' /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        hash_is() {
          expected=$1; live=$2
          actual=$(sha256sum "$live" 2>/dev/null || toybox sha256sum "$live" 2>/dev/null) || return 1
          [ "${actual%% *}" = "$expected" ]
        }
        hash_is '"$BIN_SHA256"' /data/adb/hapaneld/hapaneld-helper || exit 1
        hash_is '"$SVC_SHA256"' /data/adb/service.d/hapaneld-helper.sh || exit 1
        rm -f /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        sync || exit 1
        rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery \
          /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || true
        sync 2>/dev/null || true
        echo COMMIT_OK
      ' 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$committed" | grep -qx COMMIT_OK
}

# Stage the current client-capable helper before stale-journal recovery. Root copies it only after
# authenticating BIN_SHA256; the shell-owned staging path is never executed directly.
adb -s "$TARGET" push "$BIN" "$PROBE_STAGING_PATH" >/dev/null

manual_journal_state="$(run_root_locked '
  inspect_manual_journal() {
    kind=$1; marker=$2
    owner=$(stat -c %u:%g "$marker" 2>/dev/null || toybox stat -c %u:%g "$marker" 2>/dev/null) || { echo INVALID_MANUAL_TRANSACTION; return; }
    [ "$owner" = 0:0 ] || { echo INVALID_MANUAL_TRANSACTION; return; }
    if grep -q ^JOURNAL_VERSION=1$ "$marker"; then
      target_build=$(sed -n s/^TARGET_BUILD_ID=//p "$marker")
      target_helper=$(sed -n s/^TARGET_HELPER_SHA256=//p "$marker")
      printf %s "$target_build" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
      printf %s "$target_helper" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
      echo STALE_${kind}_TRANSACTION legacy "$target_build" "$target_helper" -
      return
    fi
    grep -q ^JOURNAL_VERSION=2$ "$marker" || { echo INVALID_MANUAL_TRANSACTION; return; }
    transaction_id=$(sed -n s/^TRANSACTION_ID=//p "$marker")
    target_build=$(sed -n s/^TARGET_BUILD_ID=//p "$marker")
    target_helper=$(sed -n s/^TARGET_HELPER_SHA256=//p "$marker")
    target_service=$(sed -n s/^TARGET_SERVICE_SHA256=//p "$marker")
    lease_boot=$(sed -n s/^LEASE_BOOT_ID=//p "$marker")
    lease_until=$(sed -n s/^LEASE_UNTIL_UPTIME=//p "$marker")
    printf %s "$transaction_id" | grep -Eq ^[0-9a-f]{32}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_build" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_helper" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$target_service" | grep -Eq ^[0-9a-f]{64}$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    printf %s "$lease_until" | grep -Eq ^[0-9]+$ || { echo INVALID_MANUAL_TRANSACTION; return; }
    current_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
    current_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null || true)
    if [ -n "$current_boot" ] && [ "$lease_boot" = "$current_boot" ] &&
       printf %s "$current_uptime" | grep -Eq ^[0-9]+$ && [ "$current_uptime" -lt "$lease_until" ]; then
      echo ACTIVE_${kind}_TRANSACTION
    else
      echo STALE_${kind}_TRANSACTION "$transaction_id" "$target_build" "$target_helper" "$target_service"
    fi
  }
  if [ -f /system/bin/.hapaneld-helper-upgrade ] || [ -f /data/adb/hapaneld/.helper-upgrade.marker ] || [ -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ]; then
    echo FOREIGN_PROVISION_TRANSACTION
  elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ] && [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
    echo MULTIPLE_STALE_TRANSACTIONS
  elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ]; then
    inspect_manual_journal SYSTEM /system/bin/.hapaneld-helper-manual-upgrade
  elif [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
    inspect_manual_journal SYSTEMLESS /data/adb/hapaneld/.helper-manual-upgrade.marker
  else
    echo NO_STALE_TRANSACTION
  fi
' 2>&1)" || true
case "$manual_journal_state" in
  STALE_SYSTEM_TRANSACTION\ *)
    read -r _ recovery_id recovery_build recovery_helper recovery_service <<<"$manual_journal_state"
    rollback_root_helper system "$recovery_id" "$recovery_build" "$recovery_helper" "$recovery_service" || fail "the retained /system helper-only journal could not be recovered safely" \
      "No live helper files were changed. Inspect the authenticated recovery snapshots before retrying." ;;
  STALE_SYSTEMLESS_TRANSACTION\ *)
    read -r _ recovery_id recovery_build recovery_helper recovery_service <<<"$manual_journal_state"
    rollback_root_helper systemless "$recovery_id" "$recovery_build" "$recovery_helper" "$recovery_service" || fail "the retained systemless helper-only journal could not be recovered safely" \
      "No live helper files were changed. Inspect the authenticated recovery snapshots before retrying." ;;
  ACTIVE_SYSTEM_TRANSACTION|ACTIVE_SYSTEMLESS_TRANSACTION)
    fail "another standalone root-helper installer owns an active transaction lease" \
      "Wait for it to finish or for its boot-scoped uptime lease to expire, then re-run." ;;
  MULTIPLE_STALE_TRANSACTIONS)
    fail "both standalone root-helper recovery journals are present" \
      "No rollback was attempted because the authoritative prior install location is ambiguous." ;;
  FOREIGN_PROVISION_TRANSACTION)
    fail "an incomplete APK-coupled helper upgrade must be recovered by the provisioner first" \
      "Re-run the same scripts/provision.sh or scripts/update-fleet.sh command that started the upgrade." \
      "This standalone installer did not change helper files." ;;
  TRANSACTION_BUSY)
    fail "another root-helper transaction is active on the panel" \
      "Wait for the other installer or provisioner to finish, then re-run." ;;
  NO_STALE_TRANSACTION) ;;
  INVALID_MANUAL_TRANSACTION) fail "the retained standalone root-helper journal is invalid or not root-owned" \
    "No rollback was attempted. Inspect the journal and recovery snapshots before retrying." ;;
  *) fail "could not determine the root-helper recovery state" \
    "No live helper files were changed." \
    "$(describe_observed_state "$manual_journal_state")" \
    "Re-run, and if it repeats include that line and 'adb features $TARGET' in a report:" \
    "https://github.com/maxlyth/ha-paneld/issues" ;;
esac

# Select a verified persistence runner before stopping the old daemon. A read-only /system alone does
# not prove that any service.d implementation will execute at boot.
out="$(run_root '
  mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
  if touch /system/.rw_probe 2>/dev/null && rm /system/.rw_probe 2>/dev/null; then
    echo SYSTEM_RW
  else
    echo SYSTEM_RO
    if command -v magisk >/dev/null 2>&1 || [ -x /data/adb/magisk/busybox ] || [ -x /data/adb/ksu/bin/busybox ] || [ -x /data/adb/ap/bin/busybox ]; then
      echo SYSTEMLESS_RUNNER
    else
      echo NO_SYSTEMLESS_RUNNER
    fi
  fi
' 2>&1)" || true
echo "$out" | sed 's/^/   /'

INSTALL_KIND=""
if printf '%s\n' "$out" | grep -qx SYSTEM_RW; then
  INSTALL_KIND=system
  # ── Vendor root / userdebug path ───────────────────────────────────────────────────────────────
  adb -s "$TARGET" push "$HERE/hapaneld-helper.rc" "$RC_STAGING_PATH" >/dev/null
  echo "==> /system is writable — installing to /system/bin ($ABI)"
  start_manual_lease_guard "$INSTALL_KIND"
  out2="$(run_root_locked '
    mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
    mkdir -p /data/adb/hapaneld || { echo PROBE_DIR_FAIL; exit 1; }
    chown 0:0 /data/adb/hapaneld || { echo PROBE_DIR_FAIL; exit 1; }
    chmod 700 /data/adb/hapaneld || { echo PROBE_DIR_FAIL; exit 1; }
    if [ -f /system/bin/.hapaneld-helper-upgrade ] || [ -f /data/adb/hapaneld/.helper-upgrade.marker ] || [ -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ]; then
      echo FOREIGN_PROVISION_TRANSACTION; exit 75
    elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ] && [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
      echo MULTIPLE_STALE_TRANSACTIONS; exit 75
    elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ]; then
      echo ACTIVE_SYSTEM_TRANSACTION; exit 75
    elif [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
      echo ACTIVE_SYSTEMLESS_TRANSACTION; exit 75
    fi
    cp '"$PROBE_STAGING_PATH"' /system/bin/hapaneld-helper.new || { echo CP_FAIL; exit 1; }
    ( sha256sum /system/bin/hapaneld-helper.new 2>/dev/null || toybox sha256sum /system/bin/hapaneld-helper.new 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || { echo HASH_FAIL; exit 1; }
    chmod 755 /system/bin/hapaneld-helper.new
    chcon u:object_r:system_file:s0 /system/bin/hapaneld-helper.new 2>/dev/null
    cp '"$RC_STAGING_PATH"' /system/etc/init/hapaneld-helper.rc.new || { echo RC_FAIL; exit 1; }
    ( sha256sum /system/etc/init/hapaneld-helper.rc.new 2>/dev/null || toybox sha256sum /system/etc/init/hapaneld-helper.rc.new 2>/dev/null ) | grep -q ^'"$RC_SHA256"' || { echo RC_HASH_FAIL; exit 1; }
    chmod 644 /system/etc/init/hapaneld-helper.rc.new
    chcon u:object_r:system_file:s0 /system/etc/init/hapaneld-helper.rc.new 2>/dev/null

    rm -f /system/bin/hapaneld-helper.hapaneld-manual-recovery
    rm -f /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery
    rm -f /system/bin/hapaneld-ledd.hapaneld-manual-recovery
    rm -f /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery
    rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery
    rm -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
    lease_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 1
    lease_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null) || exit 1
    echo "$lease_uptime" | grep -Eq ^[0-9]+$ || exit 1
    lease_until=$((lease_uptime + '"$MANUAL_LEASE_SECONDS"'))
    echo JOURNAL_VERSION=2 > /system/bin/.hapaneld-helper-manual-upgrade.new
    echo JOURNAL_SCOPE=HELPER_ONLY >> /system/bin/.hapaneld-helper-manual-upgrade.new
    echo TRANSACTION_ID='"$TRANSACTION_ID"' >> /system/bin/.hapaneld-helper-manual-upgrade.new
    echo TARGET_BUILD_ID='"$BUILD_ID"' >> /system/bin/.hapaneld-helper-manual-upgrade.new
    echo TARGET_HELPER_SHA256='"$BIN_SHA256"' >> /system/bin/.hapaneld-helper-manual-upgrade.new
    echo TARGET_SERVICE_SHA256='"$RC_SHA256"' >> /system/bin/.hapaneld-helper-manual-upgrade.new
    echo LEASE_BOOT_ID="$lease_boot" >> /system/bin/.hapaneld-helper-manual-upgrade.new
    echo LEASE_UNTIL_UPTIME="$lease_until" >> /system/bin/.hapaneld-helper-manual-upgrade.new
    if [ -f /system/bin/hapaneld-helper ]; then
      cp -p /system/bin/hapaneld-helper /system/bin/hapaneld-helper.hapaneld-manual-recovery || exit 1
      chown 0:0 /system/bin/hapaneld-helper.hapaneld-manual-recovery
      chmod 755 /system/bin/hapaneld-helper.hapaneld-manual-recovery
      cmp -s /system/bin/hapaneld-helper /system/bin/hapaneld-helper.hapaneld-manual-recovery || exit 1
      echo OLD_BIN=1 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      ( sha256sum /system/bin/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/bin/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null ) | cut -d\  -f1 | sed s/^/OLD_BIN_SHA256=/ >> /system/bin/.hapaneld-helper-manual-upgrade.new
    else
      echo OLD_BIN=0 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      echo OLD_BIN_SHA256=- >> /system/bin/.hapaneld-helper-manual-upgrade.new
    fi
    if [ -f /system/etc/init/hapaneld-helper.rc ]; then
      cp -p /system/etc/init/hapaneld-helper.rc /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery || exit 1
      chown 0:0 /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery
      chmod 644 /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery
      cmp -s /system/etc/init/hapaneld-helper.rc /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery || exit 1
      echo OLD_SERVICE=1 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      ( sha256sum /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery 2>/dev/null ) | cut -d\  -f1 | sed s/^/OLD_SERVICE_SHA256=/ >> /system/bin/.hapaneld-helper-manual-upgrade.new
    else
      echo OLD_SERVICE=0 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      echo OLD_SERVICE_SHA256=- >> /system/bin/.hapaneld-helper-manual-upgrade.new
    fi
    if [ -f /system/bin/hapaneld-ledd ]; then
      cp -p /system/bin/hapaneld-ledd /system/bin/hapaneld-ledd.hapaneld-manual-recovery || exit 1
      chown 0:0 /system/bin/hapaneld-ledd.hapaneld-manual-recovery
      chmod 755 /system/bin/hapaneld-ledd.hapaneld-manual-recovery
      cmp -s /system/bin/hapaneld-ledd /system/bin/hapaneld-ledd.hapaneld-manual-recovery || exit 1
      echo LEGACY_BIN=1 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      ( sha256sum /system/bin/hapaneld-ledd.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/bin/hapaneld-ledd.hapaneld-manual-recovery 2>/dev/null ) | cut -d\  -f1 | sed s/^/LEGACY_BIN_SHA256=/ >> /system/bin/.hapaneld-helper-manual-upgrade.new
    else
      echo LEGACY_BIN=0 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      echo LEGACY_BIN_SHA256=- >> /system/bin/.hapaneld-helper-manual-upgrade.new
    fi
    if [ -f /system/etc/init/hapaneld-ledd.rc ]; then
      cp -p /system/etc/init/hapaneld-ledd.rc /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery || exit 1
      chown 0:0 /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery
      chmod 644 /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery
      cmp -s /system/etc/init/hapaneld-ledd.rc /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery || exit 1
      echo LEGACY_SERVICE=1 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      ( sha256sum /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery 2>/dev/null ) | cut -d\  -f1 | sed s/^/LEGACY_SERVICE_SHA256=/ >> /system/bin/.hapaneld-helper-manual-upgrade.new
    else
      echo LEGACY_SERVICE=0 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      echo LEGACY_SERVICE_SHA256=- >> /system/bin/.hapaneld-helper-manual-upgrade.new
    fi
    if [ -f /data/adb/hapaneld/hapaneld-helper ]; then
      cp -p /data/adb/hapaneld/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery || exit 1
      chown 0:0 /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery
      chmod 755 /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery
      cmp -s /data/adb/hapaneld/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery || exit 1
      echo ALT_BIN=1 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      ( sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null ) | cut -d\  -f1 | sed s/^/ALT_BIN_SHA256=/ >> /system/bin/.hapaneld-helper-manual-upgrade.new
    else
      echo ALT_BIN=0 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      echo ALT_BIN_SHA256=- >> /system/bin/.hapaneld-helper-manual-upgrade.new
    fi
    if [ -f /data/adb/service.d/hapaneld-helper.sh ]; then
      cp -p /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery || exit 1
      chown 0:0 /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
      chmod 755 /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
      cmp -s /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery || exit 1
      echo ALT_SERVICE=1 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      ( sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null ) | cut -d\  -f1 | sed s/^/ALT_SERVICE_SHA256=/ >> /system/bin/.hapaneld-helper-manual-upgrade.new
    else
      echo ALT_SERVICE=0 >> /system/bin/.hapaneld-helper-manual-upgrade.new
      echo ALT_SERVICE_SHA256=- >> /system/bin/.hapaneld-helper-manual-upgrade.new
    fi
    grep -q ^OLD_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade.new || grep -Eq ^OLD_BIN_SHA256=[0-9a-f]{64}$ /system/bin/.hapaneld-helper-manual-upgrade.new || exit 1
    grep -q ^OLD_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade.new || grep -Eq ^OLD_SERVICE_SHA256=[0-9a-f]{64}$ /system/bin/.hapaneld-helper-manual-upgrade.new || exit 1
    grep -q ^LEGACY_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade.new || grep -Eq ^LEGACY_BIN_SHA256=[0-9a-f]{64}$ /system/bin/.hapaneld-helper-manual-upgrade.new || exit 1
    grep -q ^LEGACY_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade.new || grep -Eq ^LEGACY_SERVICE_SHA256=[0-9a-f]{64}$ /system/bin/.hapaneld-helper-manual-upgrade.new || exit 1
    grep -q ^ALT_BIN=0$ /system/bin/.hapaneld-helper-manual-upgrade.new || grep -Eq ^ALT_BIN_SHA256=[0-9a-f]{64}$ /system/bin/.hapaneld-helper-manual-upgrade.new || exit 1
    grep -q ^ALT_SERVICE=0$ /system/bin/.hapaneld-helper-manual-upgrade.new || grep -Eq ^ALT_SERVICE_SHA256=[0-9a-f]{64}$ /system/bin/.hapaneld-helper-manual-upgrade.new || exit 1
    chown 0:0 /system/bin/.hapaneld-helper-manual-upgrade.new
    chmod 600 /system/bin/.hapaneld-helper-manual-upgrade.new
    sync || exit 1
    mv -f /system/bin/.hapaneld-helper-manual-upgrade.new /system/bin/.hapaneld-helper-manual-upgrade || exit 1
    sync || exit 1
    stop hapaneld_helper 2>/dev/null
    stop hapaneld_ledd 2>/dev/null
    pkill -x hapaneld-helper 2>/dev/null
    pkill -x hapaneld-ledd 2>/dev/null
    rm -f /system/bin/hapaneld-ledd /system/etc/init/hapaneld-ledd.rc
    rm -f /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh
    mv -f /system/bin/hapaneld-helper.new /system/bin/hapaneld-helper || { echo MV_FAIL; exit 1; }
    mv -f /system/etc/init/hapaneld-helper.rc.new /system/etc/init/hapaneld-helper.rc || { echo RC_MV_FAIL; exit 1; }
    sync || exit 1
    rm -f '"$PROBE_STAGING_PATH $RC_STAGING_PATH $SVC_STAGING_PATH"'
    echo INSTALL_OK
  ' 2>&1)" || true
  echo "$out2" | sed 's/^/   /'
  case "$out2" in
    *ACTIVE_SYSTEM_TRANSACTION*|*ACTIVE_SYSTEMLESS_TRANSACTION*|*STALE_SYSTEM_TRANSACTION*|*STALE_SYSTEMLESS_TRANSACTION*|*MULTIPLE_STALE_TRANSACTIONS*|*FOREIGN_PROVISION_TRANSACTION*|*TRANSACTION_BUSY*)
      fail "root-helper journal state changed while the standalone installer was running" \
        "No live helper files were replaced by this attempt. Re-run to recover the retained journal." ;;
  esac
  if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
    if rollback_root_helper "$INSTALL_KIND"; then
      fail "/system install failed; the prior helper was restored" \
        "Re-run after checking writable-system capacity and permissions."
    fi
    fail "/system install failed and rollback could not be verified" \
      "Restore the helper manually before relying on privileged operations."
  fi
  run_root '
    stop hapaneld_ledd 2>/dev/null; stop hapaneld_helper 2>/dev/null
    pkill -x hapaneld-ledd 2>/dev/null; pkill -x hapaneld-helper 2>/dev/null
    # A first-time init .rc is only loaded on the next boot on some vendor firmware, while `start`
    # still exits successfully. Verify the socket and run the authenticated binary directly when
    # init did not actually create the service process.
    start hapaneld_helper 2>/dev/null
    /system/bin/hapaneld-helper --request PING >/dev/null 2>&1 ||
      ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
  ' >/dev/null 2>&1 || true

elif printf '%s\n' "$out" | grep -qx SYSTEMLESS_RUNNER; then
  INSTALL_KIND=systemless
  # ── Systemless root path (Magisk / KernelSU / APatch / …) ─────────────────────────────────────
  echo "==> /system not rw-remountable — verified service.d runner found ($ABI)"
  # Build the boot script HOST-side and push it, like the .rc on the vendor path. Generating it via a
  # heredoc inside the nested device shells exposed it to the adb shell's expansion (the boot-time
  # $(getprop) got evaluated once at install time); a pushed file arrives verbatim.
  SVC="$(mktemp)"
  cat > "$SVC" << 'SVCEOF'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
SVCEOF
  SVC_SHA256="$(host_sha256 "$SVC")"
  adb -s "$TARGET" push "$SVC" "$SVC_STAGING_PATH" >/dev/null
  rm -f "$SVC"
  start_manual_lease_guard "$INSTALL_KIND"
  out2="$(run_root_locked '
    mkdir -p /data/adb/service.d /data/adb/hapaneld
    chown 0:0 /data/adb/hapaneld
    chmod 700 /data/adb/hapaneld
    if [ -f /system/bin/.hapaneld-helper-upgrade ] || [ -f /data/adb/hapaneld/.helper-upgrade.marker ] || [ -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ]; then
      echo FOREIGN_PROVISION_TRANSACTION; exit 75
    elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ] && [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
      echo MULTIPLE_STALE_TRANSACTIONS; exit 75
    elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ]; then
      echo ACTIVE_SYSTEM_TRANSACTION; exit 75
    elif [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
      echo ACTIVE_SYSTEMLESS_TRANSACTION; exit 75
    fi
    cp '"$PROBE_STAGING_PATH"' /data/adb/hapaneld/hapaneld-helper.new || { echo CP_FAIL; exit 1; }
    ( sha256sum /data/adb/hapaneld/hapaneld-helper.new 2>/dev/null || toybox sha256sum /data/adb/hapaneld/hapaneld-helper.new 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || { echo HASH_FAIL; exit 1; }
    chown 0:0 /data/adb/hapaneld/hapaneld-helper.new
    chmod 755 /data/adb/hapaneld/hapaneld-helper.new
    cp '"$SVC_STAGING_PATH"' /data/adb/service.d/hapaneld-helper.sh.new || { echo SVC_FAIL; exit 1; }
    ( sha256sum /data/adb/service.d/hapaneld-helper.sh.new 2>/dev/null || toybox sha256sum /data/adb/service.d/hapaneld-helper.sh.new 2>/dev/null ) | grep -q ^'"$SVC_SHA256"' || { echo SVC_HASH_FAIL; exit 1; }
    chown 0:0 /data/adb/service.d/hapaneld-helper.sh.new
    chmod 755 /data/adb/service.d/hapaneld-helper.sh.new

    rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery
    rm -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
    lease_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 1
    lease_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null) || exit 1
    echo "$lease_uptime" | grep -Eq ^[0-9]+$ || exit 1
    lease_until=$((lease_uptime + '"$MANUAL_LEASE_SECONDS"'))
    echo JOURNAL_VERSION=2 > /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo JOURNAL_SCOPE=HELPER_ONLY >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo TRANSACTION_ID='"$TRANSACTION_ID"' >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo TARGET_BUILD_ID='"$BUILD_ID"' >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo TARGET_HELPER_SHA256='"$BIN_SHA256"' >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo TARGET_SERVICE_SHA256='"$SVC_SHA256"' >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo LEASE_BOOT_ID="$lease_boot" >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo LEASE_UNTIL_UPTIME="$lease_until" >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    if [ -f /data/adb/hapaneld/hapaneld-helper ]; then
      cp -p /data/adb/hapaneld/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery || exit 1
      chown 0:0 /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery
      chmod 755 /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery
      cmp -s /data/adb/hapaneld/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery || exit 1
      echo OLD_BIN=1 >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
      ( sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery 2>/dev/null ) | cut -d\  -f1 | sed s/^/OLD_BIN_SHA256=/ >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    else
      echo OLD_BIN=0 >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
      echo OLD_BIN_SHA256=- >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    fi
    if [ -f /data/adb/service.d/hapaneld-helper.sh ]; then
      cp -p /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery || exit 1
      chown 0:0 /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
      chmod 755 /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
      cmp -s /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery || exit 1
      echo OLD_SERVICE=1 >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
      ( sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null || toybox sha256sum /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery 2>/dev/null ) | cut -d\  -f1 | sed s/^/OLD_SERVICE_SHA256=/ >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    else
      echo OLD_SERVICE=0 >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
      echo OLD_SERVICE_SHA256=- >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    fi
    grep -q ^OLD_BIN=0$ /data/adb/hapaneld/.helper-manual-upgrade.marker.new || grep -Eq ^OLD_BIN_SHA256=[0-9a-f]{64}$ /data/adb/hapaneld/.helper-manual-upgrade.marker.new || exit 1
    grep -q ^OLD_SERVICE=0$ /data/adb/hapaneld/.helper-manual-upgrade.marker.new || grep -Eq ^OLD_SERVICE_SHA256=[0-9a-f]{64}$ /data/adb/hapaneld/.helper-manual-upgrade.marker.new || exit 1
    chown 0:0 /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    chmod 600 /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    sync || exit 1
    mv -f /data/adb/hapaneld/.helper-manual-upgrade.marker.new /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
    sync || exit 1
    stop hapaneld_helper 2>/dev/null
    stop hapaneld_ledd 2>/dev/null
    pkill -x hapaneld-helper 2>/dev/null
    pkill -x hapaneld-ledd 2>/dev/null
    mv -f /data/adb/hapaneld/hapaneld-helper.new /data/adb/hapaneld/hapaneld-helper || { echo MV_FAIL; exit 1; }
    mv -f /data/adb/service.d/hapaneld-helper.sh.new /data/adb/service.d/hapaneld-helper.sh || { echo SVC_MV_FAIL; exit 1; }
    sync || exit 1
    rm -f '"$PROBE_STAGING_PATH $RC_STAGING_PATH $SVC_STAGING_PATH"'
    echo INSTALL_OK
  ' 2>&1)" || true
  echo "$out2" | sed 's/^/   /'
  case "$out2" in
    *ACTIVE_SYSTEM_TRANSACTION*|*ACTIVE_SYSTEMLESS_TRANSACTION*|*STALE_SYSTEM_TRANSACTION*|*STALE_SYSTEMLESS_TRANSACTION*|*MULTIPLE_STALE_TRANSACTIONS*|*FOREIGN_PROVISION_TRANSACTION*|*TRANSACTION_BUSY*)
      fail "root-helper journal state changed while the standalone installer was running" \
        "No live helper files were replaced by this attempt. Re-run to recover the retained journal." ;;
  esac
  if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
    if rollback_root_helper "$INSTALL_KIND"; then
      fail "systemless install failed; the prior helper was restored" \
        "Re-run after checking /data capacity and service.d permissions."
    fi
    fail "systemless install failed and rollback could not be verified" \
      "Restore the helper manually before relying on privileged operations."
  fi
  run_root '
    stop hapaneld_helper 2>/dev/null
    pkill -x hapaneld-helper 2>/dev/null
    /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
  ' >/dev/null 2>&1 || true
else
  run_root 'rm -f '"$PROBE_STAGING_PATH $RC_STAGING_PATH $SVC_STAGING_PATH" >/dev/null 2>&1 || true
  # Both branches refuse, but they must not claim the same cause. A panel that genuinely answered
  # "read-only /system, no runner" needs a different environment; a panel whose answer we could not
  # read at all has not told us anything about its /system, and saying it did would send the operator
  # to install a root manager it may not need.
  if printf '%s\n' "$out" | grep -qx SYSTEM_RO && printf '%s\n' "$out" | grep -qx NO_SYSTEMLESS_RUNNER; then
    fail "the panel has read-only /system and no verified systemless boot-service runner" \
      "The existing helper was left running and no files were replaced." \
      "Install a supported Magisk, KernelSU, or APatch service.d environment, or use firmware with a writable /system init path, then re-run."
  fi
  fail "could not determine where a boot-persistent helper can be installed" \
    "The existing helper was left running and no files were replaced." \
    "$(describe_observed_state "$out")" \
    "Re-run, and if it repeats include that line and 'adb features $TARGET' in a report:" \
    "https://github.com/maxlyth/ha-paneld/issues"
fi

if ! wait_for_helper_reply COMPANIONCAPS "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL" "$INSTALL_KIND"; then
  if rollback_root_helper "$INSTALL_KIND"; then
    fail "new helper failed its exact capability check; the prior helper was restored" \
      "Re-run after checking helper logs and available storage."
  fi
  fail "new helper failed its exact capability check and rollback could not be verified" \
    "Restore the helper manually before relying on privileged operations."
fi
if ! wait_for_helper_reply BUILDID "BUILDID $BUILD_ID" "$INSTALL_KIND"; then
  if rollback_root_helper "$INSTALL_KIND"; then
    fail "new helper failed its exact build-identity check; the prior helper was restored" \
      "Rebuild with ./helper/build.sh and retry."
  fi
  fail "new helper failed its exact build-identity check and rollback could not be verified" \
    "Restore the helper manually before relying on privileged operations."
fi
stop_manual_lease_guard
if ! manual_lease_guard_succeeded; then
  if rollback_root_helper "$INSTALL_KIND"; then
    fail "the standalone root-helper transaction lease could not be renewed; the prior helper was restored" \
      "Re-run after checking adb/root stability and competing installer activity."
  fi
  fail "the standalone root-helper transaction lease failed and rollback could not be verified" \
    "Do not reboot yet. Recover the retained helper journal before relying on privileged operations."
fi
if ! commit_root_helper_upgrade "$INSTALL_KIND" && \
   ! commit_root_helper_upgrade "$INSTALL_KIND"; then
  fail "new helper passed its checks, but the durable commit point could not be confirmed" \
    "Do not reboot yet. Check panel storage and permissions, then re-run this installer; it will reconcile any retained journal."
fi
echo "   helper running with Companion-data protocol 1"

echo "==> done. Reboot the panel when convenient to confirm the daemon auto-starts."
