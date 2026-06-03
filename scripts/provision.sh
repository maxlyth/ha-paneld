#!/usr/bin/env bash
# ha-paneld provisioning — install + grant all permissions over adb, no device UI.
# Requires adb access to the panel and that the panel has su / adb-root (every grant below is
# scriptable without a tap on a rooted/userdebug panel). For non-root panels, use the in-app
# setup screen instead.
#
# Usage:  scripts/provision.sh <panel-ip:5555> [path-to-apk]
#
# Config-as-code: run the SAME script against every panel. There is no per-device local UI.
set -euo pipefail

TARGET="${1:?usage: provision.sh <panel-ip:5555> [apk]}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="io.github.maxlyth.hapaneld"
ADMIN="$PKG/.control.PanelAdminReceiver"
A11Y="$PKG/.input.PanelAccessibilityService"

echo "==> connecting $TARGET"
adb connect "$TARGET"

echo "==> installing $APK"
adb -s "$TARGET" install -r -g "$APK" || adb -s "$TARGET" install -r "$APK"

echo "==> notifications"
adb -s "$TARGET" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

echo "==> brightness (WRITE_SETTINGS)"
adb -s "$TARGET" shell appops set "$PKG" WRITE_SETTINGS allow

echo "==> sleep (device-admin force-lock)"
adb -s "$TARGET" shell dpm set-active-admin "$ADMIN" || \
  echo "   (set-active-admin failed — may need: adb shell su -c 'dpm set-active-admin $ADMIN')"

echo "==> buttons (accessibility key capture) — optional, comment out if unused"
EXISTING="$(adb -s "$TARGET" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [ "$EXISTING" = "null" ] || [ -z "$EXISTING" ]; then
  adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$A11Y"
else
  case "$EXISTING" in
    *"$A11Y"*) : ;;  # already present
    *) adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$EXISTING:$A11Y" ;;
  esac
fi
adb -s "$TARGET" shell settings put secure accessibility_enabled 1

echo "==> starting service"
adb -s "$TARGET" shell am start -n "$PKG/.MainActivity"

echo "==> done. Configure the MQTT broker in the app prefs (or push panel.json) to enable discovery."
echo "    LED on rk3576 panels additionally needs the vendor libjnielc.so installed (load-if-present)."
