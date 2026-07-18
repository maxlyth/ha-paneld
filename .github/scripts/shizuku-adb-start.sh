#!/bin/sh
# Start the Shizuku server on the connected emulator over adb.
# Shizuku v13.6.0 no longer exports a start.sh to external storage; its
# Starter.adbCommand is exactly "adb shell <nativeLibraryDir>/libshizuku.so",
# so this resolves the installed APK's native lib dir and runs the starter.
# The emulator-runner action runs each workflow script line in a separate
# shell, so this whole flow lives in one file.
set -eu

PKG=moe.shizuku.privileged.api

apk=$(adb shell pm path "$PKG" | sed -n 's/^package://p' | head -n 1 | tr -d '\r')
if [ -z "$apk" ]; then
  echo "::error::Shizuku manager package is not installed"
  exit 1
fi
appdir=${apk%/*}
abi=$(adb shell ls "$appdir/lib/" | head -n 1 | tr -d '\r')
starter="$appdir/lib/$abi/libshizuku.so"

echo "Starting Shizuku via $starter"
adb shell "$starter" || echo "starter exited with status $?"

started=0
i=0
while [ "$i" -lt 30 ]; do
  if adb shell ps -A 2>/dev/null | grep -q shizuku_server; then
    started=1
    break
  fi
  i=$((i + 1))
  sleep 1
done

if [ "$started" != 1 ]; then
  echo "::error::shizuku_server did not start"
  echo '--- starter binary ---'
  adb shell ls -la "$appdir/lib/$abi/" || true
  echo '--- processes ---'
  adb shell ps -A 2>/dev/null | grep -i shizuku || true
  echo '--- logcat (shizuku / crashes) ---'
  adb logcat -d | grep -iE 'shizuku|AndroidRuntime' | tail -n 120 || true
  exit 1
fi

echo "shizuku_server is running"
