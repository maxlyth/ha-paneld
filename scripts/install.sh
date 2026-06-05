#!/usr/bin/env bash
#
# ha-paneld one-line installer — no repo checkout needed. Run:
#   curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
#
# Preflights adb + curl (with per-OS fix-it hints), prompts for the panel IP (and optional id / MQTT
# broker), downloads the latest signed release, and provisions the panel. No parameters required.
set -euo pipefail

if [ -t 1 ]; then B=$'\033[1m'; R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; X=$'\033[0m'
else B=; R=; G=; Y=; X=; fi
REPO="maxlyth/ha-paneld"
PROVISION_URL="https://raw.githubusercontent.com/$REPO/main/scripts/provision.sh"

echo "${B}ha-paneld installer${X}"

# --- preflight: required tools, with actionable install hints ---
miss=0
if ! command -v adb >/dev/null 2>&1; then
  miss=1
  echo "${R}✗ adb (Android Platform Tools) not found.${X} Install it, then re-run:"
  case "$(uname -s 2>/dev/null)" in
    Darwin) echo "    brew install android-platform-tools" ;;
    Linux)  echo "    Debian/Ubuntu: sudo apt install adb   ·   Fedora: sudo dnf install android-tools   ·   Arch: sudo pacman -S android-tools" ;;
    MINGW*|MSYS*|CYGWIN*) echo "    winget install Google.PlatformTools   (then reopen Git Bash)" ;;
    *) echo "    Windows: winget install Google.PlatformTools  (run this in Git Bash or WSL)" ;;
  esac
  echo "    …or download platform-tools: https://developer.android.com/tools/releases/platform-tools"
fi
if ! command -v curl >/dev/null 2>&1; then miss=1; echo "${R}✗ curl not found${X} — install curl, then re-run."; fi
[ "$miss" = 0 ] || { echo "${Y}Resolve the above and paste the one-liner again.${X}"; exit 1; }
echo "${G}✓ adb and curl present${X}"

# --- prompts: stdin is the curl pipe, so read from the terminal directly ---
TTY=/dev/tty
[ -r "$TTY" ] || { echo "${R}No terminal available for prompts.${X} Try: ${B}bash <(curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/install.sh)${X}"; exit 1; }
echo "${Y}First enable network ADB on the panel (Developer options → ADB / 'ADB debugging').${X}"
printf "Panel IP (or ip:port): " > "$TTY"; read -r IP < "$TTY"
[ -n "${IP:-}" ] || { echo "${R}No IP entered.${X}"; exit 1; }
case "$IP" in *:*) TARGET="$IP" ;; *) TARGET="$IP:5555" ;; esac
printf "Panel id [blank = auto from device name]: " > "$TTY"; read -r PID < "$TTY" || PID=""
printf "MQTT broker tcp://host:1883 [blank = auto-discover Home Assistant]: " > "$TTY"; read -r BROKER < "$TTY" || BROKER=""

# --- fetch provision.sh and run it (--latest pulls the newest signed release APK) ---
echo "${B}→ provisioning $TARGET${X}"
SCRIPT="$(mktemp)"; trap 'rm -f "$SCRIPT"' EXIT
curl -fsSL "$PROVISION_URL" -o "$SCRIPT"
ARGS=("$TARGET" --latest)
[ -n "${PID:-}" ]    && ARGS+=(--id "$PID")
[ -n "${BROKER:-}" ] && ARGS+=(--mqtt "$BROKER")
# Give provision.sh the terminal as stdin so its own prompts (e.g. downgrade confirm) work.
bash "$SCRIPT" "${ARGS[@]}" < "$TTY"
