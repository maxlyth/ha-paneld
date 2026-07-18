#!/bin/sh
# Full Shizuku emulator exercise: install the manager, start its server,
# install the app and instrumentation APKs, and run the integration test.
# Lives in one file because the emulator-runner action executes each
# workflow script line in a separate shell.
set -eu

SHIZUKU_APK="${1:?usage: shizuku-emulator-suite.sh <shizuku.apk>}"
APP_PKG=io.github.maxlyth.hapaneld

# adb install can wedge indefinitely on emulators (observed on API 27 right
# after the Shizuku server start); bound it and retry once across an adb
# server restart.
adb_install() {
  if ! timeout 300 adb install -r "$1"; then
    echo "adb install $1 failed or timed out; restarting adb and retrying"
    adb kill-server || true
    adb start-server
    adb wait-for-device
    timeout 300 adb install -r "$1"
  fi
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
