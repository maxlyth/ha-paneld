#!/bin/sh
# Full Shizuku emulator exercise: install the manager, start its server,
# install the app and instrumentation APKs, and run the integration test.
# Lives in one file because the emulator-runner action executes each
# workflow script line in a separate shell.
set -eu

SHIZUKU_APK="${1:?usage: shizuku-emulator-suite.sh <shizuku.apk>}"
APP_PKG=io.github.maxlyth.hapaneld

# Streamed adb install reliably wedges on the API 27 image from the second
# install onward (the first always succeeds, later ones hang until the job
# timeout, surviving adb server restarts). Bypass the streamed path: push
# the APK and run pm install on-device, bounded and retried once.
adb_install() {
  base=$(basename "$1")
  timeout 120 adb push "$1" "/data/local/tmp/$base" < /dev/null
  if ! pm_install_once "$base"; then
    echo "pm install $base failed or timed out; restarting adb and retrying"
    adb kill-server || true
    adb start-server
    adb wait-for-device
    pm_install_once "$base"
  fi
  adb shell rm -f "/data/local/tmp/$base" < /dev/null || true
}

pm_install_once() {
  out=$(timeout 300 adb shell pm install -r -t "/data/local/tmp/$1" < /dev/null) || return 1
  printf '%s\n' "$out"
  case "$out" in
    *Success*) return 0 ;;
    *) return 1 ;;
  esac
}

# Install everything before starting the Shizuku server: on API 27 the
# running server reliably wedges any subsequent pm install session, while
# ordinary shell commands and instrumentation keep working.
adb_install "$SHIZUKU_APK"
adb_install app/build/outputs/apk/debug/app-debug.apk
adb_install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push "$SHIZUKU_APK" /data/local/tmp/shizuku.apk
# A freshly installed app has no files/ dir until first launch; create it
# before copying the manager APK into the app's private storage.
adb shell run-as "$APP_PKG" mkdir -p files
adb shell run-as "$APP_PKG" cp /data/local/tmp/shizuku.apk files/shizuku-test.apk

sh "$(dirname "$0")/shizuku-adb-start.sh"
timeout 30 adb shell echo device-responsive || echo "::warning::device shell unresponsive after Shizuku start"

adb shell am instrument -w \
  -e managerApk "/data/user/0/$APP_PKG/files/shizuku-test.apk" \
  -e class io.github.maxlyth.hapaneld.shizuku.ShizukuIntegrationTest \
  "$APP_PKG.test/androidx.test.runner.AndroidJUnitRunner"
