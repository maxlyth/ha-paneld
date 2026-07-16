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

# `adb connect` exits 0 even when it fails, and a device can sit "unauthorized" (RSA dialog waiting on
# the panel screen) or "offline" (stale session). Verify the state so failures surface here with
# recovery steps, not as a raw abort at the first adb push. $1=quiet re-checks after an adbd restart.
adb_preflight() {
  [ "${1:-}" = quiet ] || echo "==> connecting to $TARGET"
  # `adb connect` itself can block for MINUTES on a dead IP (TCP retry) — run it in the background
  # and bound the wait; the poll below reads the device state the adb server ends up with.
  adb connect "$TARGET" >/dev/null 2>&1 &
  local cpid=$!
  local state="" i=0
  while [ "$i" -lt 12 ]; do
    i=$((i + 1))
    state="$(adb devices 2>/dev/null | awk -v t="$TARGET" '$1==t {print $2}')"
    if [ "$state" = "device" ]; then kill "$cpid" 2>/dev/null || true; return 0; fi
    if [ "$state" = "offline" ] && [ "$i" = 4 ]; then
      adb disconnect "$TARGET" >/dev/null 2>&1 || true
      ( adb connect "$TARGET" >/dev/null 2>&1 & )
    fi
    sleep 1
  done
  kill "$cpid" 2>/dev/null || true
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
probe_su() {
  if [ -n "$SU_FORM" ]; then [ "$SU_FORM" != none ]; return; fi
  local u key pre
  u="$(adb -s "$TARGET" shell id 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in uid=0*) SU_FORM=shell; return 0 ;; esac
  for key in su0 suroot; do
    case "$key" in su0) pre="su 0" ;; suroot) pre="su root" ;; esac
    u="$(adb -s "$TARGET" shell "$pre \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) SU_FORM="${key}join"; return 0 ;; esac
    u="$(adb -s "$TARGET" shell "$pre sh -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) SU_FORM="${key}shc"; return 0 ;; esac
  done
  u="$(adb -s "$TARGET" shell "su -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in *uid=0*) SU_FORM=suc; return 0 ;; esac
  SU_FORM=none; return 1
}

# Run a shell block as root via the probed form. The block reaches the DEVICE shell inside double
# quotes (as before this refactor) — keep blocks free of quote characters and $-expansions; files
# with such content are pushed from the host instead (see the service.d script below).
run_root() {
  case "$SU_FORM" in
    shell)      adb -s "$TARGET" shell "$1" ;;
    su0join)    adb -s "$TARGET" shell "su 0 \"$1\"" ;;
    su0shc)     adb -s "$TARGET" shell "su 0 sh -c \"$1\"" ;;
    surootjoin) adb -s "$TARGET" shell "su root \"$1\"" ;;
    surootshc)  adb -s "$TARGET" shell "su root sh -c \"$1\"" ;;
    suc)        adb -s "$TARGET" shell "su -c \"$1\"" ;;
  esac
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

if ! run_root '[ ! -f /system/bin/.hapaneld-helper-upgrade ] && [ ! -f /data/adb/hapaneld/.helper-upgrade.marker ]' \
    >/dev/null 2>&1; then
  fail "an incomplete APK-coupled helper upgrade must be recovered by the provisioner first" \
    "Re-run the same scripts/provision.sh or scripts/update-fleet.sh command that started the upgrade." \
    "This standalone installer uses a separate journal and did not change helper files."
fi

ABI="${2:-$(adb -s "$TARGET" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')}"
[ -n "$ABI" ] || fail "could not read the panel's ABI (getprop returned nothing)" \
  "Pass it explicitly: ./helper/install-daemon.sh $TARGET arm64-v8a   (or armeabi-v7a)"
BIN="$HERE/dist/$ABI/hapaneld-helper"
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

helper_daemon_reply() {
  local command="$1" port="" reply=""
  port="$(adb -s "$TARGET" forward tcp:0 localabstract:hapaneld-helper 2>/dev/null | tr -d '\r' || true)"
  case "$port" in ''|*[!0-9]*) return 1 ;; esac
  if exec 9<>"/dev/tcp/127.0.0.1/$port" 2>/dev/null; then
    printf '%s\n' "$command" >&9
    IFS= read -r -t 3 reply <&9 || reply=""
    exec 9>&-
  fi
  adb -s "$TARGET" forward --remove "tcp:$port" >/dev/null 2>&1 || true
  [ -n "$reply" ] || return 1
  printf '%s\n' "$reply"
}

wait_for_helper_reply() {
  local command="$1" expected="$2" reply="" attempt=0
  while [ "$attempt" -lt 10 ]; do
    attempt=$((attempt + 1))
    reply="$(helper_daemon_reply "$command" 2>/dev/null || true)"
    [ "$reply" = "$expected" ] && return 0
    sleep 1
  done
  return 1
}

rollback_root_helper() {
  local install_kind="$1" restored
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
        grep -q ^JOURNAL_VERSION=1$ /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ /system/bin/.hapaneld-helper-manual-upgrade || exit 1
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
        stop hapaneld_helper 2>/dev/null
        stop hapaneld_ledd 2>/dev/null
        pkill -x hapaneld-helper 2>/dev/null
        pkill -x hapaneld-ledd 2>/dev/null
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
        rm -f /system/bin/.hapaneld-helper-manual-upgrade || exit 1
        sync || exit 1
        if [ -f /system/bin/hapaneld-helper.hapaneld-manual-recovery ]; then
          start hapaneld_helper 2>/dev/null || ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
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
        rm -f /system/bin/hapaneld-helper.hapaneld-manual-recovery /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery
        rm -f /system/bin/hapaneld-ledd.hapaneld-manual-recovery /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery
        rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
        sync || exit 1
      ' 2>&1)" || true
      ;;
    systemless)
      restored="$(run_root_locked '
        [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ] || { echo ROLLBACK_UNNEEDED; exit 0; }
        grep -q ^OLD_BIN=0$ /data/adb/hapaneld/.helper-manual-upgrade.marker || [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ] || exit 1
        grep -q ^OLD_SERVICE=0$ /data/adb/hapaneld/.helper-manual-upgrade.marker || [ -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery ] || exit 1
        grep -q ^JOURNAL_VERSION=1$ /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        grep -q ^JOURNAL_SCOPE=HELPER_ONLY$ /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
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
        stop hapaneld_helper 2>/dev/null
        pkill -x hapaneld-helper 2>/dev/null
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
        rm -f /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1
        sync || exit 1
        if [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery ]; then
          /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
          echo ROLLBACK_RESTARTED
        elif [ -x /system/bin/hapaneld-helper ]; then
          start hapaneld_helper 2>/dev/null || ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
          echo ROLLBACK_RESTARTED
        elif [ -x /system/bin/hapaneld-ledd ]; then
          start hapaneld_ledd 2>/dev/null || ( /system/bin/hapaneld-ledd >/dev/null 2>&1 & )
          echo ROLLBACK_LEGACY
        else
          echo ROLLBACK_EMPTY
        fi
        rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
        sync || exit 1
      ' 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  if printf '%s\n' "$restored" | grep -qx ROLLBACK_RESTARTED; then
    wait_for_helper_reply PING OK
  else
    printf '%s\n' "$restored" | grep -Eqx 'ROLLBACK_(EMPTY|LEGACY|UNNEEDED)'
  fi
}

commit_root_helper_upgrade() {
  local committed
  case "$1" in
    system)
      committed="$(run_root_locked '
        mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
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

manual_journal_state="$(run_root_locked '
  if [ -f /system/bin/.hapaneld-helper-upgrade ] || [ -f /data/adb/hapaneld/.helper-upgrade.marker ]; then
    echo FOREIGN_PROVISION_TRANSACTION
  elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ] && [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
    echo MULTIPLE_STALE_TRANSACTIONS
  elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ]; then
    echo STALE_SYSTEM_TRANSACTION
  elif [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
    echo STALE_SYSTEMLESS_TRANSACTION
  else
    echo NO_STALE_TRANSACTION
  fi
' 2>&1)" || true
case "$manual_journal_state" in
  STALE_SYSTEM_TRANSACTION)
    rollback_root_helper system || fail "the retained /system helper-only journal could not be recovered safely" \
      "No new helper files were staged. Inspect the authenticated recovery snapshots before retrying." ;;
  STALE_SYSTEMLESS_TRANSACTION)
    rollback_root_helper systemless || fail "the retained systemless helper-only journal could not be recovered safely" \
      "No new helper files were staged. Inspect the authenticated recovery snapshots before retrying." ;;
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
  *) fail "could not determine the root-helper recovery state" \
    "No helper files were staged. Restore adb/root access and re-run." ;;
esac

# Stage the binary only for this installation transaction. The systemless path must never execute this
# adb/shell-owned copy as root; it atomically installs a root-owned copy under /data/adb first.
adb -s "$TARGET" push "$BIN" /data/local/tmp/hapaneld-helper >/dev/null

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
  adb -s "$TARGET" push "$HERE/hapaneld-helper.rc" /data/local/tmp/hapaneld-helper.rc >/dev/null
  echo "==> /system is writable — installing to /system/bin ($ABI)"
  out2="$(run_root_locked '
    mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
    if [ -f /system/bin/.hapaneld-helper-upgrade ] || [ -f /data/adb/hapaneld/.helper-upgrade.marker ]; then
      echo FOREIGN_PROVISION_TRANSACTION; exit 75
    elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ] && [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
      echo MULTIPLE_STALE_TRANSACTIONS; exit 75
    elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ]; then
      echo STALE_SYSTEM_TRANSACTION; exit 75
    elif [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
      echo STALE_SYSTEMLESS_TRANSACTION; exit 75
    fi
    cp /data/local/tmp/hapaneld-helper /system/bin/hapaneld-helper.new || { echo CP_FAIL; exit 1; }
    ( sha256sum /system/bin/hapaneld-helper.new 2>/dev/null || toybox sha256sum /system/bin/hapaneld-helper.new 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || { echo HASH_FAIL; exit 1; }
    chmod 755 /system/bin/hapaneld-helper.new
    chcon u:object_r:system_file:s0 /system/bin/hapaneld-helper.new 2>/dev/null
    cp /data/local/tmp/hapaneld-helper.rc /system/etc/init/hapaneld-helper.rc.new || { echo RC_FAIL; exit 1; }
    ( sha256sum /system/etc/init/hapaneld-helper.rc.new 2>/dev/null || toybox sha256sum /system/etc/init/hapaneld-helper.rc.new 2>/dev/null ) | grep -q ^'"$RC_SHA256"' || { echo RC_HASH_FAIL; exit 1; }
    chmod 644 /system/etc/init/hapaneld-helper.rc.new
    chcon u:object_r:system_file:s0 /system/etc/init/hapaneld-helper.rc.new 2>/dev/null

    rm -f /system/bin/hapaneld-helper.hapaneld-manual-recovery
    rm -f /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery
    rm -f /system/bin/hapaneld-ledd.hapaneld-manual-recovery
    rm -f /system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery
    rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery
    rm -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
    echo JOURNAL_VERSION=1 > /system/bin/.hapaneld-helper-manual-upgrade.new
    echo JOURNAL_SCOPE=HELPER_ONLY >> /system/bin/.hapaneld-helper-manual-upgrade.new
    echo TARGET_BUILD_ID='"$BUILD_ID"' >> /system/bin/.hapaneld-helper-manual-upgrade.new
    echo TARGET_HELPER_SHA256='"$BIN_SHA256"' >> /system/bin/.hapaneld-helper-manual-upgrade.new
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
    rm -f /data/local/tmp/hapaneld-helper /data/local/tmp/hapaneld-helper.rc /data/local/tmp/hapaneld-helper.svc
    echo INSTALL_OK
  ' 2>&1)" || true
  echo "$out2" | sed 's/^/   /'
  case "$out2" in
    *STALE_SYSTEM_TRANSACTION*|*STALE_SYSTEMLESS_TRANSACTION*|*MULTIPLE_STALE_TRANSACTIONS*|*FOREIGN_PROVISION_TRANSACTION*|*TRANSACTION_BUSY*)
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
    start hapaneld_helper 2>/dev/null || ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
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
  adb -s "$TARGET" push "$SVC" /data/local/tmp/hapaneld-helper.svc >/dev/null
  rm -f "$SVC"
  out2="$(run_root_locked '
    mkdir -p /data/adb/service.d /data/adb/hapaneld
    chown 0:0 /data/adb/hapaneld
    chmod 700 /data/adb/hapaneld
    if [ -f /system/bin/.hapaneld-helper-upgrade ] || [ -f /data/adb/hapaneld/.helper-upgrade.marker ]; then
      echo FOREIGN_PROVISION_TRANSACTION; exit 75
    elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ] && [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
      echo MULTIPLE_STALE_TRANSACTIONS; exit 75
    elif [ -f /system/bin/.hapaneld-helper-manual-upgrade ]; then
      echo STALE_SYSTEM_TRANSACTION; exit 75
    elif [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
      echo STALE_SYSTEMLESS_TRANSACTION; exit 75
    fi
    cp /data/local/tmp/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.new || { echo CP_FAIL; exit 1; }
    ( sha256sum /data/adb/hapaneld/hapaneld-helper.new 2>/dev/null || toybox sha256sum /data/adb/hapaneld/hapaneld-helper.new 2>/dev/null ) | grep -q ^'"$BIN_SHA256"' || { echo HASH_FAIL; exit 1; }
    chown 0:0 /data/adb/hapaneld/hapaneld-helper.new
    chmod 755 /data/adb/hapaneld/hapaneld-helper.new
    cp /data/local/tmp/hapaneld-helper.svc /data/adb/service.d/hapaneld-helper.sh.new || { echo SVC_FAIL; exit 1; }
    ( sha256sum /data/adb/service.d/hapaneld-helper.sh.new 2>/dev/null || toybox sha256sum /data/adb/service.d/hapaneld-helper.sh.new 2>/dev/null ) | grep -q ^'"$SVC_SHA256"' || { echo SVC_HASH_FAIL; exit 1; }
    chown 0:0 /data/adb/service.d/hapaneld-helper.sh.new
    chmod 755 /data/adb/service.d/hapaneld-helper.sh.new

    rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery
    rm -f /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery
    echo JOURNAL_VERSION=1 > /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo JOURNAL_SCOPE=HELPER_ONLY >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo TARGET_BUILD_ID='"$BUILD_ID"' >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
    echo TARGET_HELPER_SHA256='"$BIN_SHA256"' >> /data/adb/hapaneld/.helper-manual-upgrade.marker.new
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
    rm -f /data/local/tmp/hapaneld-helper /data/local/tmp/hapaneld-helper.rc /data/local/tmp/hapaneld-helper.svc
    echo INSTALL_OK
  ' 2>&1)" || true
  echo "$out2" | sed 's/^/   /'
  case "$out2" in
    *STALE_SYSTEM_TRANSACTION*|*STALE_SYSTEMLESS_TRANSACTION*|*MULTIPLE_STALE_TRANSACTIONS*|*FOREIGN_PROVISION_TRANSACTION*|*TRANSACTION_BUSY*)
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
  run_root 'rm -f /data/local/tmp/hapaneld-helper /data/local/tmp/hapaneld-helper.rc /data/local/tmp/hapaneld-helper.svc' >/dev/null 2>&1 || true
  fail "the panel has read-only /system and no verified systemless boot-service runner" \
    "The existing helper was left running and no files were replaced." \
    "Install a supported Magisk, KernelSU, or APatch service.d environment, or use firmware with a writable /system init path, then re-run."
fi

if ! wait_for_helper_reply COMPANIONCAPS "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL"; then
  if rollback_root_helper "$INSTALL_KIND"; then
    fail "new helper failed its exact capability check; the prior helper was restored" \
      "Re-run after checking helper logs and available storage."
  fi
  fail "new helper failed its exact capability check and rollback could not be verified" \
    "Restore the helper manually before relying on privileged operations."
fi
if ! wait_for_helper_reply BUILDID "BUILDID $BUILD_ID"; then
  if rollback_root_helper "$INSTALL_KIND"; then
    fail "new helper failed its exact build-identity check; the prior helper was restored" \
      "Rebuild with ./helper/build.sh and retry."
  fi
  fail "new helper failed its exact build-identity check and rollback could not be verified" \
    "Restore the helper manually before relying on privileged operations."
fi
if ! commit_root_helper_upgrade "$INSTALL_KIND" && \
   ! commit_root_helper_upgrade "$INSTALL_KIND"; then
  fail "new helper passed its checks, but the durable commit point could not be confirmed" \
    "Do not reboot yet. Check panel storage and permissions, then re-run this installer; it will reconcile any retained journal."
fi
echo "   helper running with Companion-data protocol 1"

echo "==> done. Reboot the panel when convenient to confirm the daemon auto-starts."
