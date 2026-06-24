#!/usr/bin/env bash
#
# Install the hapaneld-ledd root helper as a boot-persistent init service on a userdebug panel.
# Places the binary in /system/bin and the init .rc in /system/etc/init (both persist across
# reboot; the service runs in the `su` domain so it can write the root-only LED / backlight nodes).
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
BIN="$HERE/dist/$ABI/hapaneld-ledd"
[ -f "$BIN" ] || { echo "missing $BIN — run ./helper/build.sh first"; exit 1; }

echo "==> remounting /system rw"
adb -s "$TARGET" shell 'su 0 sh -c "mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null; true"'

echo "==> installing binary -> /system/bin/hapaneld-ledd ($ABI)"
adb -s "$TARGET" push "$BIN" /data/local/tmp/hapaneld-ledd >/dev/null
# Replace cleanly even when a copy is already running: stop it, then mv (atomic rename) onto the
# target. `cp` directly onto a running binary fails with "Text file busy" — and the init service can
# auto-restart it between stop and copy. rename() swaps the directory entry without touching the busy
# inode, so it succeeds regardless; the running process keeps the old inode until the restart below.
adb -s "$TARGET" shell 'su 0 sh -c "
  stop hapaneld-ledd 2>/dev/null; pkill -f hapaneld-ledd 2>/dev/null; sleep 1
  cp /data/local/tmp/hapaneld-ledd /system/bin/hapaneld-ledd.new
  chmod 755 /system/bin/hapaneld-ledd.new
  chcon u:object_r:system_file:s0 /system/bin/hapaneld-ledd.new 2>/dev/null
  mv -f /system/bin/hapaneld-ledd.new /system/bin/hapaneld-ledd
"'

echo "==> installing init service -> /system/etc/init/hapaneld-ledd.rc"
adb -s "$TARGET" push "$HERE/hapaneld-ledd.rc" /data/local/tmp/hapaneld-ledd.rc >/dev/null
adb -s "$TARGET" shell 'su 0 sh -c "cp /data/local/tmp/hapaneld-ledd.rc /system/etc/init/hapaneld-ledd.rc; chmod 644 /system/etc/init/hapaneld-ledd.rc; chcon u:object_r:system_file:s0 /system/etc/init/hapaneld-ledd.rc"'

echo "==> starting now (init registers the service on next boot)"
# Running init hasn't parsed the new .rc yet, so start a copy directly in the su domain for this
# session; the init service takes over after a reboot.
adb -s "$TARGET" shell 'su 0 sh -c "pkill -f hapaneld-ledd 2>/dev/null; ( /system/bin/hapaneld-ledd >/dev/null 2>&1 & )"'
sleep 1
# Health check: the daemon now listens on an abstract UNIX socket (not loopback TCP), and shell `nc`
# can't reliably speak the abstract namespace — so confirm liveness by process instead. (ha-paneld
# itself PINGs the socket and only it/root/shell are accepted; see helper/README.md Safety.)
adb -s "$TARGET" shell 'su 0 sh -c "pidof hapaneld-ledd >/dev/null && echo \"   daemon running (pid \$(pidof hapaneld-ledd))\" || echo \"   (daemon not running)\""'

echo "==> done. Reboot the panel to confirm the daemon auto-starts (init service)."
