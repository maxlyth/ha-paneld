#!/usr/bin/env bash
#
# ha-paneld one-line installer — no repo checkout needed. Run:
#   curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
#
# For the latest PRE-RELEASE (release-candidate) build instead of the newest stable, add --prerelease:
#   curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
#
# Preflights adb + curl (with per-OS fix-it hints), prompts for the panel IP (and optional id / MQTT
# broker), downloads the release, and provisions the panel. No parameters required (except --prerelease).
# The release workflow fills RELEASE_TAG, RELEASE_APK_NAME and PROVISION_COMMIT in its downloadable copy so an installer attached to a historical release always installs that exact release using its matching immutable provisioner.
set -euo pipefail

RELEASE_TAG=""
RELEASE_APK_NAME=""
PROVISION_COMMIT=""

# --prerelease selects the newest release-candidate instead of the latest stable.
CHANNEL_ARG="--latest"
for a in "$@"; do case "$a" in
  --prerelease|--pre) CHANNEL_ARG="--prerelease" ;;
  -h|--help)
    echo "Usage: curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash"
    echo "       append: | bash -s -- --prerelease   to install the newest release candidate"
    exit 0 ;;
  *) echo "unknown option: $a (only --prerelease is accepted)"; exit 2 ;;
esac; done

if [ -t 1 ]; then B=$'\033[1m'; R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; X=$'\033[0m'
else B=; R=; G=; Y=; X=; fi
REPO="maxlyth/ha-paneld"
PROVISION_REF="${PROVISION_COMMIT:-${RELEASE_TAG:-main}}"
PROVISION_URL=""
RESOLVED_APK_URL=""
valid_release_tag() { printf '%s\n' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; }
valid_commit() { printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{40}$'; }
release_apk_name() { printf 'ha-paneld-%s-manual-setup-required.apk\n' "$1"; }
release_apk_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$(release_apk_name "$1")"; }
if [ -n "$RELEASE_TAG" ]; then
  valid_release_tag "$RELEASE_TAG" || { echo "${R}Release installer has an invalid tag.${X}" >&2; exit 1; }
  [ "$RELEASE_APK_NAME" = "$(release_apk_name "$RELEASE_TAG")" ] || { echo "${R}Release installer does not pair its tag with the expected APK asset.${X}" >&2; exit 1; }
  valid_commit "$PROVISION_COMMIT" || { echo "${R}Release installer is missing its immutable provisioner commit.${X}" >&2; exit 1; }
fi

if [ -n "$RELEASE_TAG" ]; then
  echo "${B}ha-paneld installer${X} ${Y}· $RELEASE_TAG${X}"
elif [ "$CHANNEL_ARG" = "--prerelease" ]; then
  echo "${B}ha-paneld installer${X} ${Y}· pre-release channel${X}"
else
  echo "${B}ha-paneld installer${X}"
fi

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

# Pair the provisioner and APK from one immutable release. Pulling provision.sh from moving main
# while installing an older stable APK can make a first-run script call APIs that APK does not have.
if [ -z "$RELEASE_TAG" ]; then
  if [ "$CHANNEL_ARG" = "--prerelease" ]; then api="https://api.github.com/repos/$REPO/releases?per_page=100";
  else api="https://api.github.com/repos/$REPO/releases/latest"; fi
  release_json="$(curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 "$api" 2>/dev/null || true)"
  if [ "$CHANNEL_ARG" = "--prerelease" ]; then
    release_record="$(printf '%s' "$release_json" | tr -d '\r\n' | \
      sed 's#{[[:space:]]*"url":[[:space:]]*"https://api.github.com/repos/maxlyth/ha-paneld/releases/\([0-9][0-9]*\)"#\
&#g' | \
      awk '/"draft":[[:space:]]*false/ && /"prerelease":[[:space:]]*true/ { print; exit }')"
  else
    release_record="$release_json"
  fi
  PROVISION_REF="$(printf '%s' "$release_record" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  RESOLVED_APK_URL="$(printf '%s' "$release_record" | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4 || true)"
  if [ -z "$PROVISION_REF" ] || ! valid_release_tag "$PROVISION_REF" || [ "$RESOLVED_APK_URL" != "$(release_apk_url "$PROVISION_REF")" ]; then
    echo "${R}Could not resolve a complete signed ha-paneld release.${X} Check internet/GitHub access and try again; no panel changes were made." >&2
    exit 1
  fi
fi
PROVISION_URL="https://raw.githubusercontent.com/$REPO/$PROVISION_REF/scripts/provision.sh"

# --- prompts: stdin is the curl pipe, so read from the terminal directly ---
TTY=/dev/tty
[ -r "$TTY" ] || { echo "${R}No terminal available for prompts.${X} Try: ${B}bash <(curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/install.sh)${X}"; exit 1; }
echo "${Y}First enable network ADB on the panel (Developer options → ADB / 'ADB debugging').${X}"
printf "Panel IP (or ip:port): " > "$TTY"; read -r IP < "$TTY"
[ -n "${IP:-}" ] || { echo "${R}No IP entered.${X}"; exit 1; }
# Loose sanity check (hostname/IPv4[:port]) — catch typos here rather than as an obscure adb error.
case "$IP" in
  *[!0-9a-zA-Z.:-]*|.*|-*) echo "${R}'$IP' doesn't look like an IP address or hostname (optionally :port).${X} Find it on the panel under Settings → About → Status, or in your router's client list."; exit 1 ;;
esac
case "$IP" in *:*) TARGET="$IP" ;; *) TARGET="$IP:5555" ;; esac
printf "Panel id [blank = auto from device name]: " > "$TTY"; read -r PID < "$TTY" || PID=""
printf "MQTT broker tcp://host:1883 [blank = auto-discover Home Assistant]: " > "$TTY"; read -r BROKER < "$TTY" || BROKER=""
echo "ha-paneld can disable known vendor overlays and factory-test apps which interfere with a wall panel." > "$TTY"
echo "This is reversible from the ha-paneld web UI." > "$TTY"
printf "Disable those recommended vendor apps? [Y/n]: " > "$TTY"; read -r TAME < "$TTY" || TAME=""

# --- fetch provision.sh and run it (release assets are pinned; the main script follows its channel) ---
echo "${B}→ provisioning $TARGET${X}"
TMP_DIR="$(mktemp -d)"; trap 'rm -rf "$TMP_DIR"' EXIT
SCRIPT="$TMP_DIR/provision.sh"
if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 "$PROVISION_URL" -o "$SCRIPT"; then
  echo "${R}Could not download the ha-paneld provisioning script.${X} Check this computer's internet connection and GitHub access, then paste the installer command again." >&2
  exit 1
fi
if [ -n "$RELEASE_TAG" ]; then
  [ -n "$RELEASE_APK_NAME" ] || { echo "${R}Release installer is missing its APK name.${X}"; exit 1; }
  APK="$TMP_DIR/$RELEASE_APK_NAME"
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$(release_apk_url "$RELEASE_TAG")" -o "$APK"; then
    echo "${R}Could not download the $RELEASE_TAG APK.${X} The release may be incomplete or GitHub may be unavailable; no panel changes were made." >&2
    exit 1
  fi
  ARGS=("$TARGET" --apk "$APK" --release-tag "$RELEASE_TAG")
else
  RELEASE_APK_NAME="$(release_apk_name "$PROVISION_REF")"
  APK="$TMP_DIR/$RELEASE_APK_NAME"
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$RESOLVED_APK_URL" -o "$APK"; then
    echo "${R}Could not download the $PROVISION_REF APK.${X} Check internet/GitHub access and try again; no panel changes were made." >&2
    exit 1
  fi
  ARGS=("$TARGET" --apk "$APK" --release-tag "$PROVISION_REF")
fi
[ -n "${PID:-}" ]    && ARGS+=(--id "$PID")
[ -n "${BROKER:-}" ] && ARGS+=(--mqtt "$BROKER")
case "${TAME:-}" in n|N|no|NO|No) ARGS+=(--no-tame) ;; esac
# Give provision.sh the terminal as stdin so its own prompts (e.g. downgrade confirm) work.
if ! bash "$SCRIPT" "${ARGS[@]}" < "$TTY"; then
  echo "${R}${B}ha-paneld installation did not complete.${X}" >&2
  echo "Read the failed item above, correct it, and run the same installer command again. Existing panel configuration was not deliberately removed." >&2
  exit 1
fi
