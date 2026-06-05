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

TARGET="${1:?usage: provision.sh <panel-ip:5555> [APK] [--id ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--latest] [--force] [--verify]}"
shift
REPO="maxlyth/ha-paneld"
LOCAL_APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="io.github.maxlyth.hapaneld"
A11Y="$PKG/.input.PanelAccessibilityService"
APK=""; PANEL_ID=""; MQTT=""; MQTT_USER=""; MQTT_PASS=""; VERIFY_ONLY=0; LATEST=0; FORCE=0; TOINSTALL_VER=""

if [ "${1:-}" ] && [ "${1#--}" = "${1:-}" ]; then APK="$1"; shift; fi
while [ "${1:-}" ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --id) PANEL_ID="$2"; shift 2 ;;
    --mqtt) MQTT="$2"; shift 2 ;;
    --mqtt-user) MQTT_USER="$2"; shift 2 ;;
    --mqtt-pass) MQTT_PASS="$2"; shift 2 ;;
    --latest) LATEST=1; shift ;;     # ignore any local build, fetch the newest GitHub release
    --force) FORCE=1; shift ;;       # skip the same/older-version prompt
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

# Fetch the newest signed release APK from GitHub (gh if present, else the API via curl). Sets APK + TOINSTALL_VER.
download_latest() {
  local dir tag url json
  dir="$(mktemp -d)"
  if command -v gh >/dev/null 2>&1; then
    tag="$(gh release view --repo "$REPO" --json tagName -q .tagName 2>/dev/null || true)"
    gh release download --repo "$REPO" --pattern '*.apk' --dir "$dir" >/dev/null 2>&1 || true
  fi
  if ! ls "$dir"/*.apk >/dev/null 2>&1; then
    json="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null || true)"
    tag="$(printf '%s' "$json" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4)"
    url="$(printf '%s' "$json" | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4)"
    [ -n "$url" ] && curl -fsSL "$url" -o "$dir/ha-paneld-latest.apk"
  fi
  APK="$(ls "$dir"/*.apk 2>/dev/null | head -1 || true)"
  [ -n "$APK" ] || { echo "${RED}could not fetch the latest release APK (need gh, or internet for the GitHub API)${X}" >&2; exit 1; }
  TOINSTALL_VER="${tag#v}"
  step "⬇️  downloaded" "${D}$(basename "$APK")${X} ${B}${tag:-latest}${X}"
}

# Pick the APK: explicit --apk wins; else the local build (unless --latest); else download the latest release.
resolve_apk() {
  if [ -n "$APK" ]; then :
  elif [ "$LATEST" = 1 ]; then download_latest
  elif [ -f "$LOCAL_APK" ]; then APK="$LOCAL_APK"; step "📂 using local build" "${D}$APK${X}"
  else download_latest; fi
  [ -f "$APK" ] || { echo "${RED}APK not found: $APK${X}" >&2; exit 1; }
  if [ -z "$TOINSTALL_VER" ]; then  # local/--apk: read the version via aapt if available (else the guard is skipped)
    for t in aapt aapt2; do
      if command -v "$t" >/dev/null 2>&1; then
        TOINSTALL_VER="$("$t" dump badging "$APK" 2>/dev/null | grep -o "versionName='[^']*'" | head -1 | cut -d"'" -f2 || true)"
        break
      fi
    done
  fi
  return 0  # never let the (possibly non-zero) probe above abort the script under set -e
}

# Warn (and prompt on a TTY) before reinstalling the same or an older version; --force skips it.
version_guard() {
  [ "$FORCE" = 1 ] && return 0
  [ -n "$TOINSTALL_VER" ] || return 0
  local installed newest
  installed="$(adb -s "$TARGET" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r ' || true)"
  [ -n "$installed" ] || return 0
  newest="$(printf '%s\n%s\n' "$installed" "$TOINSTALL_VER" | sort -V | tail -1)"
  if [ "$installed" = "$TOINSTALL_VER" ]; then
    echo "${YEL}ℹ panel already on $installed — reinstalling.${X}"
  elif [ "$newest" = "$installed" ]; then
    echo "${YEL}⚠ panel has $installed; about to install OLDER $TOINSTALL_VER (downgrade).${X}"
    if [ -t 0 ]; then printf "   continue? [y/N] "; read -r a; case "$a" in [yY]*) ;; *) echo "aborted."; exit 0 ;; esac; fi
  else
    echo "${GRN}↑ updating $installed → $TOINSTALL_VER${X}"
  fi
  return 0
}

echo "${MAG}${B}🛠  ha-paneld provisioning${X} ${D}→ $TARGET${X}"

if [ "$VERIFY_ONLY" = 1 ]; then
  verify; exit $?
fi

resolve_apk

step "🔌 connecting" "$TARGET"
adb connect "$TARGET" >/dev/null

version_guard

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
