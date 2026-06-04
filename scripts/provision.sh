#!/usr/bin/env bash
# ha-paneld provisioning — install + grant all permissions over adb, no device UI, and (optionally)
# set the panel id + MQTT broker in one shot. Requires adb access and su/adb-root on the panel.
# For non-root panels, use the in-app setup screen instead.
#
# Usage:
#   scripts/provision.sh <panel-ip:5555> [APK] \
#       [--id PANEL_ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--apk PATH]
#   scripts/provision.sh <panel-ip:5555> --verify        # check end-state only, make no changes
#
# IDEMPOTENCY (safe to re-run after a cancel/failure — re-running converges to the full state):
#   - install -r / appops allow / pm grant / accessibility_enabled / am start  : no-ops on re-run
#   - enabled_accessibility_services : appends our service only if absent (guarded)
#   - POST /config                   : partial-merge — only sets the keys you pass
# `set -e` aborts cleanly on any failure; the ERR trap tells you to re-run, and every run ends with a
# self-verify checklist. `--verify` is the standing control: run any time to confirm a panel is set up.
set -euo pipefail

# Colours/emoji — only when writing to a terminal, so piped/redirected output stays clean.
if [ -t 1 ]; then
  B=$'\033[1m'; D=$'\033[2m'; X=$'\033[0m'
  RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYN=$'\033[36m'; MAG=$'\033[35m'
else B=; D=; X=; RED=; GRN=; YEL=; CYN=; MAG=; fi
step() { echo "${CYN}${B}$1${X} $2"; }

trap 'echo "${RED}${B}✗ provisioning incomplete${X} — re-run the SAME command to finish (it is idempotent)." >&2' ERR

TARGET="${1:?usage: provision.sh <panel-ip:5555> [APK] [--id ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--verify]}"
shift
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="io.github.maxlyth.hapaneld"
A11Y="$PKG/.input.PanelAccessibilityService"
PANEL_ID=""; MQTT=""; MQTT_USER=""; MQTT_PASS=""; VERIFY_ONLY=0

if [ "${1:-}" ] && [ "${1#--}" = "${1:-}" ]; then APK="$1"; shift; fi
while [ "${1:-}" ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --id) PANEL_ID="$2"; shift 2 ;;
    --mqtt) MQTT="$2"; shift 2 ;;
    --mqtt-user) MQTT_USER="$2"; shift 2 ;;
    --mqtt-pass) MQTT_PASS="$2"; shift 2 ;;
    --verify) VERIFY_ONLY=1; shift ;;
    *) echo "${RED}unknown arg: $1${X}" >&2; exit 2 ;;
  esac
done
IP="${TARGET%%:*}"
URL="http://$IP:8888"

verify() {
  step "🔎 verifying" "${D}$URL${X}"
  local diag cfg rc=0
  diag="$(curl -fsS --max-time 4 "$URL/diag" 2>/dev/null || true)"
  cfg="$(curl -fsS --max-time 3 "$URL/config" 2>/dev/null || true)"
  chk() { if printf '%s' "$2" | grep -q "$3"; then echo "   ${GRN}✓${X} $1"; else echo "   ${RED}✗ $1${X}"; rc=1; fi; }
  chk "HTTP server reachable"  "$diag" "ha-paneld diagnostics"
  chk "WRITE_SETTINGS granted" "$diag" "write_settings=true"
  chk "accessibility enabled"  "$diag" "a11y.enabled=true"
  # panel_id + MQTT (informational — install-only is valid). grep/cut so no python (Git Bash-friendly).
  local broker pid
  broker="$(printf '%s' "$cfg" | grep -o '"mqtt_broker":"[^"]*"' | head -1 | cut -d'"' -f4)"
  pid="$(printf '%s' "$cfg" | grep -o '"panel_id":"[^"]*"' | head -1 | cut -d'"' -f4)"
  if [ -n "$broker" ]; then echo "   ${GRN}✓${X} MQTT broker: ${B}$broker${X}"
  else echo "   ${YEL}ℹ${X} MQTT broker: ${YEL}not set${X} ${D}(set it on the page to enable HA discovery)${X}"; fi
  echo "   ${YEL}ℹ${X} panel_id: ${B}${pid:-?}${X}"
  return $rc
}

echo "${MAG}${B}🛠  ha-paneld provisioning${X} ${D}→ $TARGET${X}"

if [ "$VERIFY_ONLY" = 1 ]; then
  verify; exit $?
fi

step "🔌 connecting" "$TARGET"
adb connect "$TARGET" >/dev/null

step "📦 installing" "${D}$APK${X}"
adb -s "$TARGET" install -r -g "$APK" >/dev/null 2>&1 || adb -s "$TARGET" install -r "$APK"

step "🔑 permissions" "${D}notifications · WRITE_SETTINGS (brightness/screen) · a11y (buttons)${X}"
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

step "▶️  starting" "the panel agent"
adb -s "$TARGET" shell am start -n "$PKG/.MainActivity" >/dev/null

step "⏳ waiting" "for the web server…"
for _ in $(seq 1 20); do curl -fsS --max-time 2 "$URL/health" >/dev/null 2>&1 && break; sleep 1; done

ARGS=()
[ -n "$PANEL_ID" ]   && ARGS+=(--data-urlencode "panel_id=$PANEL_ID")
[ -n "$MQTT" ]       && ARGS+=(--data-urlencode "mqtt_broker=$MQTT")
[ -n "$MQTT_USER" ]  && ARGS+=(--data-urlencode "mqtt_user=$MQTT_USER")
[ -n "$MQTT_PASS" ]  && ARGS+=(--data-urlencode "mqtt_password=$MQTT_PASS")
if [ ${#ARGS[@]} -gt 0 ]; then
  step "⚙️  configuring" "${D}panel_id / MQTT${X}"
  curl -fsS -H 'Accept: application/json' -X POST "${ARGS[@]}" "$URL/config" >/dev/null && echo "   ${GRN}✓${X} applied"
fi

echo
verify && echo "${GRN}${B}✅ provisioned${X} — ${B}$URL/${X}" || echo "${YEL}↻ re-run the same command to finish (idempotent).${X}"
echo "${D}   LED: rk3576 app-direct; sysfs panels (TPA10) also need the root daemon (helper/README.md).${X}"
