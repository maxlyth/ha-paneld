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
# Requires: adb access + root on the panel, and a built binary (./helper/build.sh).
set -euo pipefail

TARGET="${1:?usage: install-daemon.sh <panel-ip:5555> [abi]}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

adb connect "$TARGET" >/dev/null || true
adb -s "$TARGET" root >/dev/null 2>&1 || true
sleep 1

ABI="${2:-$(adb -s "$TARGET" shell getprop ro.product.cpu.abi | tr -d '\r')}"
BIN="$HERE/dist/$ABI/hapaneld-helper"
[ -f "$BIN" ] || { echo "missing $BIN — run ./helper/build.sh first"; exit 1; }

# Stage the binary. Both install paths start from /data/local/tmp.
adb -s "$TARGET" push "$BIN" /data/local/tmp/hapaneld-helper >/dev/null

# ── Capability probe + install in ONE su session ─────────────────────────────────────────────────
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
out="$(adb -s "$TARGET" shell 'su 0 sh -c "
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
"' 2>&1)"
echo "$out" | sed 's/^/   /'

if echo "$out" | grep -q SYSTEM_RW; then
  # ── Vendor root / userdebug path ───────────────────────────────────────────────────────────────
  adb -s "$TARGET" push "$HERE/hapaneld-helper.rc" /data/local/tmp/hapaneld-helper.rc >/dev/null
  echo "==> /system is writable — installing to /system/bin ($ABI)"
  out2="$(adb -s "$TARGET" shell 'su 0 sh -c "
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
  "' 2>&1)" || true
  echo "$out2" | sed 's/^/   /'
  echo "$out2" | grep -q INSTALL_OK || { echo "   ✗ /system install failed"; exit 1; }

  echo "==> starting now (init registers the service on next boot)"
  # Prefer the init service (start hapaneld_helper) so the running copy is supervised; on a FIRST
  # install init hasn't parsed the new .rc yet and start fails (unknown service), so fall back to a
  # direct su-domain start for this session. The init service takes over after a reboot.
  adb -s "$TARGET" shell 'su 0 sh -c "
    start hapaneld_helper 2>/dev/null || ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
  "' || true

else
  # ── Systemless root path (Magisk / KernelSU / APatch / …) ─────────────────────────────────────
  echo "==> /system not rw-remountable — using service.d path ($ABI)"
  chmod_result="$(adb -s "$TARGET" shell 'su 0 sh -c "
    chmod 755 /data/local/tmp/hapaneld-helper
    pkill -x hapaneld-ledd 2>/dev/null; pkill -x hapaneld-helper 2>/dev/null
    sleep 1
    mkdir -p /data/adb/service.d
    cat > /data/adb/service.d/hapaneld-helper.sh << '"'"'SVCEOF'"'"'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/data/local/tmp/hapaneld-helper >/dev/null 2>&1 &
SVCEOF
    chmod 755 /data/adb/service.d/hapaneld-helper.sh
    echo INSTALL_OK
  "' 2>&1)" || true
  echo "$chmod_result" | sed 's/^/   /'
  echo "$chmod_result" | grep -q INSTALL_OK || { echo "   ✗ service.d install failed (is su present?)"; exit 1; }

  echo "==> starting now (service.d script runs on next boot)"
  adb -s "$TARGET" shell 'su 0 sh -c "
    pkill -x hapaneld-helper 2>/dev/null
    /data/local/tmp/hapaneld-helper >/dev/null 2>&1 &
  "' || true
fi

sleep 1
# Health check: the daemon listens on an abstract UNIX socket (not loopback TCP); confirm liveness
# by process. (ha-paneld itself PINGs the socket; only it/root/shell are accepted — see helper/README.md.)
adb -s "$TARGET" shell 'su 0 sh -c "pidof hapaneld-helper >/dev/null && echo \"   daemon running (pid $(pidof hapaneld-helper))\" || echo \"   (daemon not running)\""'

echo "==> done. Reboot the panel to confirm the daemon auto-starts."
