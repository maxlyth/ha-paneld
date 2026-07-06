#!/usr/bin/env bash
#
# Install the hapaneld-helper root helper as a boot-persistent service on a panel.
# Run it on EVERY sandbox-walled panel (a DeviceProfile with appCanSu=false) — there the daemon is the
# privileged control path (screen-off, density, CPU governor, screenshot, perf, buttons, LED), not just
# on LED panels.
#
# Two install paths, chosen by a capability probe on the device (not by root-tool identity):
#
#   Vendor root / userdebug (default): /system is rw-remountable. Places the binary in /system/bin
#   and the init .rc in /system/etc/init. Boot-persistent via the Android init service. An
#   OTA/factory-reset would remove it.
#
#   Systemless root (Magisk, KernelSU, APatch, …): /system is NOT rw-remountable. The binary stays
#   in /data/local/tmp/hapaneld-helper and a service.d script is written to
#   /data/adb/service.d/hapaneld-helper.sh, which waits for sys.boot_completed then launches the
#   daemon in background. The service.d convention is honoured by Magisk, KernelSU, and APatch.
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
  local state="" i
  for i in $(seq 1 12); do
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

ABI="${2:-$(adb -s "$TARGET" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')}"
[ -n "$ABI" ] || fail "could not read the panel's ABI (getprop returned nothing)" \
  "Pass it explicitly: ./helper/install-daemon.sh $TARGET arm64-v8a   (or armeabi-v7a)"
BIN="$HERE/dist/$ABI/hapaneld-helper"
[ -f "$BIN" ] || fail "missing $BIN" "Build it first: ./helper/build.sh   (builds every ABI into helper/dist/)"

# Stage the binary. Both install paths start from /data/local/tmp.
adb -s "$TARGET" push "$BIN" /data/local/tmp/hapaneld-helper >/dev/null

# ── Capability probe + install in ONE root session ───────────────────────────────────────────────
# All /system operations (mount, write) must share ONE su session. Magisk and some KernelSU builds
# give each `su` invocation its own mount namespace, so a remount done in a separate su call would
# NOT be visible here (splitting them was a real bug: the writes hit a read-only /system).
#
# We probe writability by attempting the remount then a test write — if that fails we take the
# systemless path regardless of which root tool is installed.
#
# Migration: remove any legacy hapaneld-ledd binary + .rc (no-op on clean panels).
# Atomic replace: cp -> .new then mv -f — cp directly onto a running binary fails "text file busy";
# rename() swaps the directory entry without touching the busy inode.
# stop takes the SERVICE name (hapaneld_helper, underscore), not the binary name.
out="$(run_root '
  # Stop any running instance first (safe before the writability probe).
  stop hapaneld_ledd 2>/dev/null; stop hapaneld_helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null; pkill -x hapaneld-helper 2>/dev/null
  sleep 1

  # Capability probe: try to remount /system rw and write a test file.
  mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
  if touch /system/.rw_probe 2>/dev/null && rm /system/.rw_probe 2>/dev/null; then
    echo SYSTEM_RW
  else
    echo SYSTEM_RO
  fi
' 2>&1)"
echo "$out" | sed 's/^/   /'

if echo "$out" | grep -q SYSTEM_RW; then
  # ── Vendor root / userdebug path ───────────────────────────────────────────────────────────────
  adb -s "$TARGET" push "$HERE/hapaneld-helper.rc" /data/local/tmp/hapaneld-helper.rc >/dev/null
  echo "==> /system is writable — installing to /system/bin ($ABI)"
  out2="$(run_root '
    mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
    rm -f /system/etc/init/hapaneld-ledd.rc /system/bin/hapaneld-ledd
    cp /data/local/tmp/hapaneld-helper /system/bin/hapaneld-helper.new || { echo CP_FAIL; exit 1; }
    chmod 755 /system/bin/hapaneld-helper.new
    chcon u:object_r:system_file:s0 /system/bin/hapaneld-helper.new 2>/dev/null
    mv -f /system/bin/hapaneld-helper.new /system/bin/hapaneld-helper || { echo MV_FAIL; exit 1; }
    cp /data/local/tmp/hapaneld-helper.rc /system/etc/init/hapaneld-helper.rc || { echo RC_FAIL; exit 1; }
    chmod 644 /system/etc/init/hapaneld-helper.rc
    chcon u:object_r:system_file:s0 /system/etc/init/hapaneld-helper.rc 2>/dev/null
    echo INSTALL_OK
  ' 2>&1)" || true
  echo "$out2" | sed 's/^/   /'
  echo "$out2" | grep -q INSTALL_OK || fail "/system install failed (output above)" \
    "If a step above shows 'Read-only file system', the rw-probe passed but the write path is still protected (verity/overlay) — re-run; if it persists the panel needs the systemless path: remove any partial files and retry after 'adb reboot' with the panel attended." \
    "Re-running is safe — every step is idempotent."

  echo "==> starting now (init registers the service on next boot)"
  # Prefer the init service (start hapaneld_helper) so the running copy is supervised; on a FIRST
  # install init hasn't parsed the new .rc yet and start fails (unknown service), so fall back to a
  # direct root-domain start for this session. The init service takes over after a reboot.
  run_root '
    start hapaneld_helper 2>/dev/null || ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
  ' || true

else
  # ── Systemless root path (Magisk / KernelSU / APatch / …) ─────────────────────────────────────
  echo "==> /system not rw-remountable — using service.d path ($ABI)"
  # Build the boot script HOST-side and push it, like the .rc on the vendor path. Generating it via a
  # heredoc inside the nested device shells exposed it to the adb shell's expansion (the boot-time
  # $(getprop) got evaluated once at install time); a pushed file arrives verbatim.
  SVC="$(mktemp)"
  cat > "$SVC" << 'SVCEOF'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/data/local/tmp/hapaneld-helper >/dev/null 2>&1 &
SVCEOF
  adb -s "$TARGET" push "$SVC" /data/local/tmp/hapaneld-helper.svc >/dev/null
  rm -f "$SVC"
  out2="$(run_root '
    chmod 755 /data/local/tmp/hapaneld-helper
    pkill -x hapaneld-ledd 2>/dev/null; pkill -x hapaneld-helper 2>/dev/null
    sleep 1
    mkdir -p /data/adb/service.d
    cp /data/local/tmp/hapaneld-helper.svc /data/adb/service.d/hapaneld-helper.sh || { echo CP_FAIL; exit 1; }
    chmod 755 /data/adb/service.d/hapaneld-helper.sh
    rm -f /data/local/tmp/hapaneld-helper.svc
    echo INSTALL_OK
  ' 2>&1)" || true
  echo "$out2" | sed 's/^/   /'
  echo "$out2" | grep -q INSTALL_OK || fail "service.d install failed (output above)" \
    "The root session worked but writing /data/adb/service.d failed — some root tools mount it late or confine it. Check the path exists ('adb shell', then the probed su form, 'ls /data/adb') and re-run." \
    "Re-running is safe — the script is idempotent."

  echo "==> starting now (service.d script runs on next boot)"
  run_root '
    pkill -x hapaneld-helper 2>/dev/null
    /data/local/tmp/hapaneld-helper >/dev/null 2>&1 &
  ' || true
fi

sleep 1
# Health check IS the success gate: files in place but no live process = not installed. The daemon
# listens on an abstract UNIX socket (not loopback TCP), so liveness is confirmed by process.
# (ha-paneld itself PINGs the socket; only it/root/shell are accepted — see helper/README.md.)
PID="$(run_root 'pidof hapaneld-helper' 2>/dev/null | tr -d '\r ' || true)"
[ -n "$PID" ] || fail "daemon installed but not running" \
  "Re-run this script; if it persists, reboot the panel (the boot service starts it) and check with: adb -s $TARGET shell pidof hapaneld-helper" \
  "Still nothing after a reboot? Grab the last logs: adb -s $TARGET logcat -d -s hapaneld-helper — and open an issue."
echo "   daemon running (pid $PID)"

echo "==> done. Reboot the panel when convenient to confirm the daemon auto-starts."
