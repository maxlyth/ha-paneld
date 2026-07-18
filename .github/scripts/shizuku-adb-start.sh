#!/bin/sh
# Launch the Shizuku manager on the connected emulator and start its ADB
# service via the start.sh the manager exports to its external data dir.
# The emulator-runner action runs each workflow script line in a separate
# shell, so this whole flow lives in one file.
set -eu

PKG=moe.shizuku.privileged.api
START_SH="/storage/emulated/0/Android/data/$PKG/start.sh"

# Launch the manager UI explicitly; monkey's single event does not reliably
# bring up the activity that exports start.sh.
comp=$(adb shell cmd package resolve-activity --brief "$PKG" 2>/dev/null | tail -n 1 | tr -d '\r')
case "$comp" in
  */*) adb shell am start -W -n "$comp" ;;
  *) adb shell monkey -p "$PKG" 1 ;;
esac

found=0
i=0
while [ "$i" -lt 90 ]; do
  if adb shell test -f "$START_SH"; then
    found=1
    break
  fi
  i=$((i + 1))
  sleep 1
done

if [ "$found" != 1 ]; then
  echo "::error::Shizuku manager never exported start.sh"
  echo '--- resolved launcher component ---'
  printf '%s\n' "$comp"
  echo '--- shizuku activity state ---'
  adb shell dumpsys activity activities | grep -i shizuku || true
  echo '--- external data dir ---'
  adb shell ls -la "/storage/emulated/0/Android/data/$PKG" || true
  adb shell ls -la /storage/emulated/0/Android/data/ || true
  echo '--- logcat (shizuku / crashes) ---'
  adb logcat -d | grep -iE 'shizuku|AndroidRuntime' | tail -n 120 || true
  exit 1
fi

adb shell sh "$START_SH"
