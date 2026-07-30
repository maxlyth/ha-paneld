#!/usr/bin/env bash
#
# ha-paneld one-line installer — no repo checkout needed. Run:
#   curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
#
# For the latest PRE-RELEASE (release-candidate) build instead of the newest stable, add --prerelease:
#   curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
#
# Preflights adb + curl (with per-OS fix-it hints), prompts for the panel IP (and optional id / MQTT
# broker), downloads the release, and provisions the panel. On rooted panels the authenticated
# provisioner also installs or upgrades the matching sealed root-helper asset. No parameters required
# (except --prerelease). Advanced checkout-free provisioning is also available with:
#   curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh |
#     bash -s -- --provision panel-ip:5555 [provision options]
# The release workflow fills RELEASE_TAG, RELEASE_APK_NAME and PROVISION_COMMIT
# in its downloadable copy so an installer attached to a historical release always installs that exact
# release using its matching authenticated provisioner asset.
set -euo pipefail
umask 077

# Create private storage before parsing advanced arguments. Legacy literal credential flags remain
# accepted for compatibility, but are immediately rewritten to file-backed provisioner arguments so
# their values are not copied into the downloaded provisioner's argv. The original installer command
# line cannot be scrubbed portably; callers should use the corresponding --*-file options instead.
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RELEASE_TAG=""
RELEASE_APK_NAME=""
PROVISION_COMMIT=""

# --prerelease selects the newest release-candidate instead of the latest stable.
CHANNEL_ARG="--latest"
ADVANCED_PROVISION=0
ADVANCED_TARGET=""
PROVISION_ARGS=()
PROVISION_NEEDS_APK=1
PROVISION_SECRET_KINDS='|'
claim_provision_secret() {
  local kind="$1"
  case "$PROVISION_SECRET_KINDS" in
    *"|$kind|"*) echo "credential source supplied more than once: --$kind-file" >&2; exit 2 ;;
  esac
  PROVISION_SECRET_KINDS="${PROVISION_SECRET_KINDS}${kind}|"
}
materialize_provision_secret() {
  local option="$1" value="$2" secret_file
  secret_file="$(mktemp "$TMP_DIR/${option#--}.XXXXXX")"
  printf '%s' "$value" > "$secret_file"
  chmod 600 "$secret_file" 2>/dev/null || true
  PROVISION_ARGS+=("${option}-file" "$secret_file")
}
show_usage() {
  echo "Usage: curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash"
  echo "       append: | bash -s -- --prerelease"
  echo "       advanced: | bash -s -- [--prerelease] --provision PANEL-IP[:PORT] [options]"
  echo
  echo "Advanced options cover configuration, backup, restore, verification, and exceptional"
  echo "access setup. APK/channel overrides (--apk, --release-tag, --latest, or a second"
  echo "--prerelease) are rejected so the authenticated provisioner stays paired with its release."
  echo "Use --mqtt-pass-file, --ha-token-file, or --ha-pass-file for credentials. The older literal"
  echo "flags remain compatible but expose their value in the original shell command and process list."
  echo
  echo "--reset-config erases the panel's existing ha-paneld configuration and starts guided setup"
  echo "from scratch. It backs the configuration up first and asks for confirmation before erasing."
}
while [ "$#" -gt 0 ]; do case "$1" in
  --prerelease|--pre)
    [ "$ADVANCED_PROVISION" = 0 ] || { echo "channel selection must appear before --provision" >&2; exit 2; }
    CHANNEL_ARG="--prerelease"
    shift
    ;;
  --provision)
    [ "$ADVANCED_PROVISION" = 0 ] || { echo "--provision may only be supplied once" >&2; exit 2; }
    [ "$#" -ge 2 ] && [ -n "${2:-}" ] && [ "${2#--}" = "$2" ] ||
      { echo "--provision needs a panel IP or hostname" >&2; exit 2; }
    ADVANCED_PROVISION=1
    ADVANCED_TARGET="$2"
    shift 2
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --mqtt-pass|--ha-token|--ha-pass)
          [ "$#" -ge 2 ] && [ -n "${2:-}" ] && [ "${2#--}" = "$2" ] ||
            { echo "$1 needs a value" >&2; exit 2; }
          claim_provision_secret "${1#--}"
          materialize_provision_secret "$1" "$2"
          shift 2
          ;;
        --mqtt-pass-file|--ha-token-file|--ha-pass-file)
          [ "$#" -ge 2 ] && [ -n "${2:-}" ] && [ "${2#--}" = "$2" ] ||
            { echo "$1 needs a value" >&2; exit 2; }
          file_kind="${1#--}"; file_kind="${file_kind%-file}"
          claim_provision_secret "$file_kind"
          PROVISION_ARGS+=("$1" "$2")
          shift 2
          ;;
        --id|--mqtt|--mqtt-user|--log-host|--log-port|--log-proto|--ha-url|--ha-user|--export|--restore|--restore-fleet)
          [ "$#" -ge 2 ] && [ -n "${2:-}" ] && [ "${2#--}" = "$2" ] ||
            { echo "$1 needs a value" >&2; exit 2; }
          PROVISION_ARGS+=("$1" "$2")
          shift 2
          ;;
        --force|--persist-adb|--strip-vendor|--no-tame|--shizuku|--log-off|--builtin|--reset-config|--allow-missing-db-snapshot)
          PROVISION_ARGS+=("$1")
          shift
          ;;
        --verify)
          PROVISION_ARGS+=("$1")
          PROVISION_NEEDS_APK=0
          shift
          ;;
        --apk|--release-tag|--latest|--prerelease|--pre)
          echo "$1 is not accepted after --provision; the installer selects a matching authenticated release" >&2
          exit 2
          ;;
        -h|--help)
          show_usage
          exit 0
          ;;
        *)
          echo "unknown provisioning option: $1" >&2
          exit 2
          ;;
      esac
    done
    ;;
  -h|--help)
    show_usage
    exit 0
    ;;
  *)
    echo "unknown option: $1"
    exit 2
    ;;
esac; done

if [ "$ADVANCED_PROVISION" = 1 ]; then
  HAS_EXPORT=0
  HAS_VERIFY=0
  HAS_OTHER=0
  i=0
  while [ "$i" -lt "${#PROVISION_ARGS[@]}" ]; do
    option="${PROVISION_ARGS[$i]}"
    case "$option" in
      --export) HAS_EXPORT=1; i=$((i + 2)) ;;
      --verify) HAS_VERIFY=1; i=$((i + 1)) ;;
      --id|--mqtt|--mqtt-user|--mqtt-pass-file|--log-host|--log-port|--log-proto|--ha-url|--ha-token-file|--ha-user|--ha-pass-file|--restore|--restore-fleet)
        HAS_OTHER=1; i=$((i + 2)) ;;
      *) HAS_OTHER=1; i=$((i + 1)) ;;
    esac
  done
  if [ "$HAS_VERIFY" = 1 ] && [ "$HAS_OTHER" = 1 ]; then
    echo "--verify may only be combined with --export because verification is read-only" >&2
    exit 2
  fi
  if [ "$HAS_VERIFY" = 1 ] || { [ "$HAS_EXPORT" = 1 ] && [ "$HAS_OTHER" = 0 ]; }; then
    PROVISION_NEEDS_APK=0
  fi
fi

if [ -t 1 ]; then B=$'\033[1m'; R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; X=$'\033[0m'
else B=; R=; G=; Y=; X=; fi
REPO="maxlyth/ha-paneld"
PROVISION_REF="${RELEASE_TAG:-}"
PROVISION_URL=""
RESOLVED_APK_URL=""
valid_release_tag() { printf '%s\n' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; }
valid_commit() { printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{40}$'; }
release_apk_name() { printf 'ha-paneld-%s-manual-setup-required.apk\n' "$1"; }
release_apk_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$(release_apk_name "$1")"; }
provision_asset_name() { printf 'ha-paneld-provision-%s.sh\n' "$1"; }
provision_asset_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$(provision_asset_name "$1")"; }
release_has_authenticated_provisioner() {
  local version major minor patch
  version="${1#v}"; version="${version%%-*}"
  IFS=. read -r major minor patch <<< "$version"
  [ "$major" -gt 0 ] || [ "$minor" -gt 9 ] || { [ "$minor" -eq 9 ] && [ "$patch" -ge 3 ]; }
}
write_release_public_key() {
  cat > "$1" <<'EOF'
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3LH+db6kzNld/ERP612x
UOOG6TINFvuKJKinQAWi6Gfm2jCmW4plhw+w4vXgP8B8FpY0SLatUVo3EeAi+f1K
EHj0syPi7Sx781o1oc9LicQG4LjWVZPe+m4AkPl9ByopobQwYTXOjaq6ZFpFgAZe
NwQ44hg5o9iVKtxpnnjHEc/m6o9TBySQvxDWF3RxCDyPLNBqhrsgKsDlAyh+dtA8
aJpQsDUJoX42xsRvA1hkRCpnWdEs1Bwfyv0ztlOxj7MxeFrFxWc3mnUyGhsn6rCT
O+ygQ2m7FHp3D5t1+wFIendluEzUC+y9MpUHmoyq/lFrVuA8EOiy1U+z7Lr1vBWf
LQIDAQAB
-----END PUBLIC KEY-----
EOF
}
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
if ! command -v openssl >/dev/null 2>&1 || ! openssl version >/dev/null 2>&1; then
  miss=1
  echo "${R}✗ OpenSSL not found.${X} It authenticates the release before anything is installed. Install it, then re-run:"
  case "$(uname -s 2>/dev/null)" in
    Darwin) echo "    xcode-select --install   ·   or: brew install openssl" ;;
    Linux)  echo "    Debian/Ubuntu: sudo apt install openssl   ·   Fedora: sudo dnf install openssl   ·   Arch: sudo pacman -S openssl" ;;
    MINGW*|MSYS*|CYGWIN*) echo "    Update Git for Windows, then reopen Git Bash   ·   or run the installer in WSL" ;;
    *) echo "    Windows: use a current Git Bash or WSL terminal   ·   macOS/Linux: install the openssl package" ;;
  esac
fi
[ "$miss" = 0 ] || { echo "${Y}Resolve the above and paste the one-liner again.${X}"; exit 1; }
echo "${G}✓ adb, curl, and OpenSSL present${X}"

# Pair the provisioner and APK from one immutable release. The generated installer's source commit is
# retained as release provenance, while executable bytes come from the matching signed release asset.
# Pulling provision.sh from moving main while installing an older stable APK can make a first-run
# script call APIs that APK does not have.
if [ -z "$RELEASE_TAG" ]; then
  if [ "$CHANNEL_ARG" = "--prerelease" ]; then api="https://api.github.com/repos/$REPO/releases?per_page=100";
  else api="https://api.github.com/repos/$REPO/releases/latest"; fi
  release_json="$(curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 "$api" 2>/dev/null || true)"
  if [ "$CHANNEL_ARG" = "--prerelease" ]; then
    release_record="$(printf '%s' "$release_json" | tr -d '\r\n' | \
      sed 's#{[[:space:]]*"url":[[:space:]]*"https://api.github.com/repos/maxlyth/ha-paneld/releases/\([0-9][0-9]*\)"#\
&#g' | \
      awk '/"draft":[[:space:]]*false/ && /"prerelease":[[:space:]]*true/ && !found { print; found=1 }')"
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
AUTHENTICATE_PROVISIONER=0
if [ -n "$RELEASE_TAG" ] || release_has_authenticated_provisioner "$PROVISION_REF"; then
  AUTHENTICATE_PROVISIONER=1
  PROVISION_URL="$(provision_asset_url "$PROVISION_REF")"
else
  # Compatibility for channel installs of releases published before provisioner proof assets existed.
  # v0.9.3 and newer never fall back to this transport-only legacy path.
  PROVISION_URL="https://raw.githubusercontent.com/$REPO/$PROVISION_REF/scripts/provision.sh"
fi

# New release code gets an independent release-key check before prompts or panel contact. HTTPS and
# an immutable tag prevent accidental drift; the detached signature also fails closed if the
# provisioner or its checksum is damaged, replaced, or served from an incomplete release.
SCRIPT="$TMP_DIR/provision.sh"
PROVISION_CHECKSUM="$TMP_DIR/provision.sha256"
PROVISION_SIGNATURE="$TMP_DIR/provision.sha256.sig"
PROVISION_PUBLIC_KEY="$TMP_DIR/release-public-key.pem"
if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 "$PROVISION_URL" -o "$SCRIPT"; then
  echo "${R}Could not download the $PROVISION_REF ha-paneld provisioning script.${X} The release may be incomplete or GitHub may be unavailable; no panel changes were made." >&2
  exit 1
fi
if [ "$AUTHENTICATE_PROVISIONER" = 1 ]; then
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 "$PROVISION_URL.sha256" -o "$PROVISION_CHECKSUM"; then
    echo "${R}Could not download the signed provisioner checksum for $PROVISION_REF.${X} No panel changes were made." >&2
    exit 1
  fi
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 "$PROVISION_URL.sha256.sig" -o "$PROVISION_SIGNATURE"; then
    echo "${R}Could not download the provisioner checksum signature for $PROVISION_REF.${X} No panel changes were made." >&2
    exit 1
  fi
  write_release_public_key "$PROVISION_PUBLIC_KEY" || { echo "${R}Could not prepare the trusted ha-paneld release key.${X} No panel changes were made." >&2; exit 1; }
  if ! openssl dgst -sha256 -verify "$PROVISION_PUBLIC_KEY" -signature "$PROVISION_SIGNATURE" "$PROVISION_CHECKSUM" >/dev/null 2>&1; then
    echo "${R}The $PROVISION_REF provisioner checksum signature is invalid.${X} Nothing was installed, started, or privileged." >&2
    exit 1
  fi
  PROVISION_NAME="$(provision_asset_name "$PROVISION_REF")"
  PROVISION_RECORD="$(cat "$PROVISION_CHECKSUM")"
  PROVISION_EXPECTED_HASH="${PROVISION_RECORD%% *}"
  if ! printf '%s\n' "$PROVISION_EXPECTED_HASH" | grep -Eq '^[0-9A-Fa-f]{64}$' || \
     [ "$PROVISION_RECORD" != "$PROVISION_EXPECTED_HASH  $PROVISION_NAME" ]; then
    echo "${R}The signed provisioner checksum record for $PROVISION_REF is malformed.${X} Nothing was installed, started, or privileged." >&2
    exit 1
  fi
  if ! PROVISION_ACTUAL_HASH="$(openssl dgst -sha256 -r "$SCRIPT" 2>/dev/null | awk '{print tolower($1)}')" || \
     ! printf '%s\n' "$PROVISION_ACTUAL_HASH" | grep -Eq '^[0-9a-f]{64}$' || \
     [ "$PROVISION_ACTUAL_HASH" != "$(printf '%s' "$PROVISION_EXPECTED_HASH" | tr '[:upper:]' '[:lower:]')" ]; then
    echo "${R}The downloaded $PROVISION_REF provisioner does not match its signed checksum.${X} Nothing was installed, started, or privileged." >&2
    exit 1
  fi
  echo "${G}✓ authenticated $PROVISION_REF provisioner${X}"
fi

# --- prompts: stdin is the curl pipe, so interactive installs read from the terminal directly ---
TTY=/dev/tty
if [ "$ADVANCED_PROVISION" = 1 ]; then
  IP="$ADVANCED_TARGET"
else
  [ -r "$TTY" ] || { echo "${R}No terminal available for prompts.${X} Try: ${B}bash <(curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/install.sh)${X}"; exit 1; }
  echo "${Y}First enable network ADB on the panel (Developer options → ADB / 'ADB debugging').${X}"
  printf "Panel IP (or ip:port): " > "$TTY"; read -r IP < "$TTY"
  [ -n "${IP:-}" ] || { echo "${R}No IP entered.${X}"; exit 1; }
fi
# Loose sanity check (hostname/IPv4[:port]) — catch typos here rather than as an obscure adb error.
case "$IP" in
  *[!0-9a-zA-Z.:-]*|.*|-*) echo "${R}'$IP' doesn't look like an IP address or hostname (optionally :port).${X} Find it on the panel under Settings → About → Status, or in your router's client list."; exit 1 ;;
esac
case "$IP" in *:*) TARGET="$IP" ;; *) TARGET="$IP:5555" ;; esac
if [ "$ADVANCED_PROVISION" = 0 ]; then
  printf "Panel id [blank = auto from device name]: " > "$TTY"; read -r PID < "$TTY" || PID=""
  printf "MQTT broker tcp://host:1883 [blank = auto-discover Home Assistant]: " > "$TTY"; read -r BROKER < "$TTY" || BROKER=""
fi

# --- fetch the APK and run the already-authenticated provisioner ---
echo "${B}→ provisioning $TARGET${X}"
ARGS=("$TARGET")
if [ "$PROVISION_NEEDS_APK" = 1 ] && [ -n "$RELEASE_TAG" ]; then
  [ -n "$RELEASE_APK_NAME" ] || { echo "${R}Release installer is missing its APK name.${X}"; exit 1; }
  APK="$TMP_DIR/$RELEASE_APK_NAME"
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$(release_apk_url "$RELEASE_TAG")" -o "$APK"; then
    echo "${R}Could not download the $RELEASE_TAG APK.${X} The release may be incomplete or GitHub may be unavailable; no panel changes were made." >&2
    exit 1
  fi
  ARGS+=(--apk "$APK" --release-tag "$RELEASE_TAG")
elif [ "$PROVISION_NEEDS_APK" = 1 ]; then
  RELEASE_APK_NAME="$(release_apk_name "$PROVISION_REF")"
  APK="$TMP_DIR/$RELEASE_APK_NAME"
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$RESOLVED_APK_URL" -o "$APK"; then
    echo "${R}Could not download the $PROVISION_REF APK.${X} Check internet/GitHub access and try again; no panel changes were made." >&2
    exit 1
  fi
  ARGS+=(--apk "$APK" --release-tag "$PROVISION_REF")
fi
if [ "$ADVANCED_PROVISION" = 1 ]; then
  ARGS+=("${PROVISION_ARGS[@]}")
else
  [ -n "${PID:-}" ]    && ARGS+=(--id "$PID")
  [ -n "${BROKER:-}" ] && ARGS+=(--mqtt "$BROKER")
fi
# Give provision.sh the terminal as stdin so its own prompts (e.g. downgrade confirm) work.
PROVISION_STDIN=/dev/null
if { : < "$TTY"; } 2>/dev/null; then PROVISION_STDIN="$TTY"; fi
if ! bash "$SCRIPT" "${ARGS[@]}" < "$PROVISION_STDIN"; then
  echo "${R}${B}ha-paneld installation did not complete.${X}" >&2
  echo "Read the failed item above, correct it, and run the same installer command again. Existing panel configuration was not deliberately removed." >&2
  exit 1
fi
