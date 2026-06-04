#!/usr/bin/env bash
# ha-paneld provisioning — install + grant all permissions over adb, no device UI, and (optionally)
# set the panel id + MQTT broker in one shot so the panel is fully configured by the time the script
# returns. Requires adb access and su/adb-root on the panel (every grant is scriptable on a
# rooted/userdebug panel). For non-root panels, use the in-app setup screen instead.
#
# Usage:
#   scripts/provision.sh <panel-ip:5555> [APK] \
#       [--id PANEL_ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--apk PATH]
#
# Examples:
#   scripts/provision.sh 172.31.12.10:5555 --id kitchen --mqtt tcp://mqtt.lan:1883 \
#       --mqtt-user integration --mqtt-pass secret
#   scripts/provision.sh 172.31.12.10:5555           # install+grant only; configure on the web page
#
# Config-as-code: run the SAME script (vary --id) against every panel. No per-device local UI.
set -euo pipefail

TARGET="${1:?usage: provision.sh <panel-ip:5555> [APK] [--id ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P]}"
shift
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="io.github.maxlyth.hapaneld"
A11Y="$PKG/.input.PanelAccessibilityService"
PANEL_ID=""; MQTT=""; MQTT_USER=""; MQTT_PASS=""

# Back-compat: a bare first remaining arg (not a --flag) is the APK path.
if [ "${1:-}" ] && [ "${1#--}" = "${1:-}" ]; then APK="$1"; shift; fi
while [ "${1:-}" ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --id) PANEL_ID="$2"; shift 2 ;;
    --mqtt) MQTT="$2"; shift 2 ;;
    --mqtt-user) MQTT_USER="$2"; shift 2 ;;
    --mqtt-pass) MQTT_PASS="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
IP="${TARGET%%:*}"
URL="http://$IP:8888"

echo "==> connecting $TARGET"
adb connect "$TARGET" >/dev/null

echo "==> installing $APK"
adb -s "$TARGET" install -r -g "$APK" >/dev/null 2>&1 || adb -s "$TARGET" install -r "$APK"

echo "==> permissions (notifications, WRITE_SETTINGS for brightness/screen, a11y for buttons)"
adb -s "$TARGET" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb -s "$TARGET" shell appops set "$PKG" WRITE_SETTINGS allow
EXISTING="$(adb -s "$TARGET" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [ "$EXISTING" = "null" ] || [ -z "$EXISTING" ]; then
  adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$A11Y"
else
  case "$EXISTING" in
    *"$A11Y"*) : ;;
    *) adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$EXISTING:$A11Y" ;;
  esac
fi
adb -s "$TARGET" shell settings put secure accessibility_enabled 1

echo "==> starting service"
adb -s "$TARGET" shell am start -n "$PKG/.MainActivity" >/dev/null

echo "==> waiting for the panel web server…"
for _ in $(seq 1 20); do curl -fsS --max-time 2 "$URL/health" >/dev/null 2>&1 && break; sleep 1; done

# Apply config (panel id + MQTT) if any were supplied — partial-merge, so unset fields are untouched.
ARGS=()
[ -n "$PANEL_ID" ]   && ARGS+=(--data-urlencode "panel_id=$PANEL_ID")
[ -n "$MQTT" ]       && ARGS+=(--data-urlencode "mqtt_broker=$MQTT")
[ -n "$MQTT_USER" ]  && ARGS+=(--data-urlencode "mqtt_user=$MQTT_USER")
[ -n "$MQTT_PASS" ]  && ARGS+=(--data-urlencode "mqtt_password=$MQTT_PASS")
if [ ${#ARGS[@]} -gt 0 ]; then
  echo "==> applying config"
  curl -fsS -H 'Accept: application/json' -X POST "${ARGS[@]}" "$URL/config" >/dev/null && echo "    applied"
fi

# Summary — read back the live config so it reflects the auto-derived default id + MQTT state.
echo
CFG="$(curl -fsS --max-time 3 "$URL/config" 2>/dev/null || true)"
read -r RID RMQTT RPWSET <<EOF
$(printf '%s' "$CFG" | python3 -c "import sys,json
try: d=json.load(sys.stdin); print(d['panel_id'], (d['mqtt_broker'] or '-'), d['mqtt_password_set'])
except Exception: print('? - False')" 2>/dev/null)
EOF
echo "  ┌─ ha-paneld ready on $IP"
echo "  │  Config page : $URL/"
echo "  │  panel_id    : ${RID:-?}$([ -z "$PANEL_ID" ] && echo '   (auto default — set one on the page if you want a friendly id)')"
echo "  │  MQTT broker : ${RMQTT:--}$([ "${RMQTT:--}" = "-" ] && echo '   (not set — set it on the page to enable HA discovery)')"
echo "  │  MQTT pass   : $([ "${RPWSET:-False}" = "True" ] && echo set || echo 'not set')"
echo "  └─ LED: rk3576 works app-direct; sysfs-LED panels (TPA10) need the root daemon (helper/README.md)."
