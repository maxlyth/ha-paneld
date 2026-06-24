#!/usr/bin/env bash
#
# Install the hapaneld-helper root helper as a boot-persistent init service on a userdebug panel.
# Run it on EVERY sandbox-walled panel (a DeviceProfile with appCanSu=false) — there the daemon is the
# privileged control path (screen-off, density, CPU governor, screenshot, perf, buttons, LED), not just
# on LED panels. Places the binary in /system/bin and the init .rc in /system/etc/init (both persist
# across reboot; the service runs in the `su` domain so it can write the root-only nodes).
#
#   ./helper/install-daemon.sh <panel-ip:5555> [abi]
#
# Requires: adb access + root (adb root) on the panel, and a built binary (./helper/build.sh).
# Modifies /system — appropriate for a controlled fleet; an OTA/factory-reset would remove it.
set -euo pipefail

TARGET="${1:?usage: install-daemon.sh <panel-ip:5555> [abi]}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

adb connect "$TARGET" >/dev/null || true
adb -s "$TARGET" root >/dev/null 2>&1 || true
sleep 1

ABI="${2:-$(adb -s "$TARGET" shell getprop ro.product.cpu.abi | tr -d '\r')}"
BIN="$HERE/dist/$ABI/hapaneld-helper"
[ -f "$BIN" ] || { echo "missing $BIN — run ./helper/build.sh first"; exit 1; }

echo "==> remounting /system rw"
adb -s "$TARGET" shell 'su 0 sh -c "mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null; true"'

# Migration: tear down a legacy `hapaneld-ledd` install (the daemon's former name). Without this the
# old init service (`hapaneld_ledd`) keeps booting the old `/system/bin/hapaneld-ledd` binary on the
# old `@hapaneld-ledd` socket, racing the new one. Stop the old service, kill the old process, and
# remove the old binary + .rc. (No-op on a clean panel.)
echo "==> removing any legacy hapaneld-ledd install"
adb -s "$TARGET" shell 'su 0 sh -c "
  stop hapaneld_ledd 2>/dev/null; pkill -f /system/bin/hapaneld-ledd 2>/dev/null
  rm -f /system/etc/init/hapaneld-ledd.rc /system/bin/hapaneld-ledd
"'

echo "==> installing binary -> /system/bin/hapaneld-helper ($ABI)"
adb -s "$TARGET" push "$BIN" /data/local/tmp/hapaneld-helper >/dev/null
# Replace cleanly even when a copy is already running: stop the init service (by its .rc name, which
# is `hapaneld_helper` with an underscore — NOT the binary name), kill any copy, then mv (atomic
# rename) onto the target. `cp` directly onto a running binary fails with "Text file busy" — and the
# init service can auto-restart it between stop and copy. rename() swaps the directory entry without
# touching the busy inode, so it succeeds regardless; the running process keeps the old inode until
# the restart below.
adb -s "$TARGET" shell 'su 0 sh -c "
  stop hapaneld_helper 2>/dev/null; pkill -f /system/bin/hapaneld-helper 2>/dev/null; sleep 1
  cp /data/local/tmp/hapaneld-helper /system/bin/hapaneld-helper.new
  chmod 755 /system/bin/hapaneld-helper.new
  chcon u:object_r:system_file:s0 /system/bin/hapaneld-helper.new 2>/dev/null
  mv -f /system/bin/hapaneld-helper.new /system/bin/hapaneld-helper
"'

echo "==> installing init service -> /system/etc/init/hapaneld-helper.rc"
adb -s "$TARGET" push "$HERE/hapaneld-helper.rc" /data/local/tmp/hapaneld-helper.rc >/dev/null
adb -s "$TARGET" shell 'su 0 sh -c "cp /data/local/tmp/hapaneld-helper.rc /system/etc/init/hapaneld-helper.rc; chmod 644 /system/etc/init/hapaneld-helper.rc; chcon u:object_r:system_file:s0 /system/etc/init/hapaneld-helper.rc"'

echo "==> starting now (init registers the service on next boot)"
# Prefer the init service (`start hapaneld_helper`) so the running copy is the supervised one; on a
# FIRST install running init hasn't parsed the new .rc yet and `start` fails (unknown service), so
# fall back to a direct su-domain start for this session. Either way the init service takes over
# after a reboot.
adb -s "$TARGET" shell 'su 0 sh -c "
  pkill -f /system/bin/hapaneld-helper 2>/dev/null
  start hapaneld_helper 2>/dev/null || ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
"'
sleep 1
# Health check: the daemon now listens on an abstract UNIX socket (not loopback TCP), and shell `nc`
# can't reliably speak the abstract namespace — so confirm liveness by process instead. (ha-paneld
# itself PINGs the socket and only it/root/shell are accepted; see helper/README.md Safety.)
adb -s "$TARGET" shell 'su 0 sh -c "pidof hapaneld-helper >/dev/null && echo \"   daemon running (pid \$(pidof hapaneld-helper))\" || echo \"   (daemon not running)\""'

echo "==> done. Reboot the panel to confirm the daemon auto-starts (init service)."
