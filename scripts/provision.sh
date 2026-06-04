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
# `set -e` aborts cleanly on any failure (no half-written grant corrupts); the ERR trap tells you to
# re-run, and every run ends with a self-verify checklist so you can SEE the state is complete. The
# `--verify` mode is the standing control: run it any time to confirm a panel is fully provisioned.
set -euo pipefail
trap 'echo "✗ provisioning incomplete — re-run the SAME command to finish (it is idempotent)." >&2' ERR

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
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
IP="${TARGET%%:*}"
URL="http://$IP:8888"

# Self-verify the end state. Returns non-zero if any required grant is missing, so it doubles as a
# scriptable health-check (run with --verify, or after every provision). Re-running provision then
# converges; this is the control that keeps provisioning idempotent + complete.
verify() {
  echo "==> verifying ($URL)"
  local diag cfg rc=0
  diag="$(curl -fsS --max-time 4 "$URL/diag" 2>/dev/null || true)"
  cfg="$(curl -fsS --max-time 3 "$URL/config" 2>/dev/null || true)"
  chk() { if printf '%s' "$2" | grep -q "$3"; then echo "  ✓ $1"; else echo "  ✗ $1"; rc=1; fi; }
  chk "HTTP server reachable"  "$diag" "ha-paneld diagnostics"
  chk "WRITE_SETTINGS granted" "$diag" "write_settings=true"
  chk "accessibility enabled"  "$diag" "a11y.enabled=true"
  # panel_id + MQTT (informational — install-only is valid, so these don't fail the check).
  # Parsed with grep/cut (no python) so the script runs on macOS + Windows Git Bash unchanged.
  local broker pid
  broker="$(printf '%s' "$cfg" | grep -o '"mqtt_broker":"[^"]*"' | head -1 | cut -d'"' -f4)"
  pid="$(printf '%s' "$cfg" | grep -o '"panel_id":"[^"]*"' | head -1 | cut -d'"' -f4)"
  if [ -n "$broker" ]; then echo "  ✓ MQTT broker: $broker"
  else echo "  ℹ MQTT broker: not set (set on the page to enable HA discovery)"; fi
  echo "  ℹ panel_id: ${pid:-?}"
  return $rc
}

if [ "$VERIFY_ONLY" = 1 ]; then
  verify; exit $?
fi

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

ARGS=()
[ -n "$PANEL_ID" ]   && ARGS+=(--data-urlencode "panel_id=$PANEL_ID")
[ -n "$MQTT" ]       && ARGS+=(--data-urlencode "mqtt_broker=$MQTT")
[ -n "$MQTT_USER" ]  && ARGS+=(--data-urlencode "mqtt_user=$MQTT_USER")
[ -n "$MQTT_PASS" ]  && ARGS+=(--data-urlencode "mqtt_password=$MQTT_PASS")
if [ ${#ARGS[@]} -gt 0 ]; then
  echo "==> applying config"
  curl -fsS -H 'Accept: application/json' -X POST "${ARGS[@]}" "$URL/config" >/dev/null && echo "    applied"
fi

echo
verify || echo "  → re-run the same command to finish (idempotent)."
echo "  Config page: $URL/   ·   LED: rk3576 app-direct; sysfs (TPA10) needs the root daemon."
