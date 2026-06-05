#!/usr/bin/env bash
# Fleet update — update several ha-paneld panels in one go. Use this instead of a raw
# `adb install -r` loop: `adb install -r` leaves the app in Android's "stopped" state, so a plain
# install loop leaves panels installed-but-DEAD (their entities go `unavailable` in HA until each is
# launched). This wraps scripts/provision.sh, which installs AND launches AND verifies every panel.
#
# The APK is downloaded once (for --latest) and reused for the whole fleet, rather than re-fetched
# per panel.
#
# Usage:
#   scripts/update-fleet.sh [provision-args...] -- <ip|ip:port> [<ip> ...]
#   scripts/update-fleet.sh --latest -- 192.168.1.10 192.168.1.11:5555
#   scripts/update-fleet.sh --apk path/to.apk -- 192.168.1.10 192.168.1.11
#   printf '%s\n' 192.168.1.10 192.168.1.11 | scripts/update-fleet.sh --latest
#
# Panels are listed after `--` and/or on stdin (one per line). Args before `--` pass through to every
# provision.sh call (e.g. --apk PATH, --mqtt ...). Per-panel ids are NOT set here — a fleet update
# keeps each panel's existing id/config; use provision.sh directly to (re)set an individual id.
set -euo pipefail

if [ -t 1 ]; then B=$'\033[1m'; D=$'\033[2m'; X=$'\033[0m'; RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'
else B=; D=; X=; RED=; GRN=; YEL=; fi

HERE="$(cd "$(dirname "$0")" && pwd)"
PROVISION="$HERE/provision.sh"
REPO="maxlyth/ha-paneld"
[ -f "$PROVISION" ] || { echo "${RED}provision.sh not found next to this script${X}" >&2; exit 1; }

# Split args at `--` into pass-through provision args and the panel list.
PARGS=(); PANELS=(); seen_dd=0
for a in "$@"; do
  if [ "$seen_dd" = 0 ] && [ "$a" = "--" ]; then seen_dd=1; continue; fi
  if [ "$seen_dd" = 1 ]; then PANELS+=("$a"); else PARGS+=("$a"); fi
done
# Panels may also arrive on stdin (one per line) — e.g. piped from a host list.
if [ ! -t 0 ]; then while IFS= read -r line; do line="${line%%#*}"; line="$(echo "$line" | tr -d '[:space:]')"; [ -n "$line" ] && PANELS+=("$line"); done; fi
[ "${#PANELS[@]}" -gt 0 ] || { echo "${RED}no panels given${X} (after -- or on stdin)" >&2; exit 2; }

# Resolve a single APK for the whole fleet. If --apk was passed through, reuse it; otherwise download
# the latest signed release ONCE and convert the pass-through args to --apk for every panel.
have_apk=0
for ((i=0; i<${#PARGS[@]}; i++)); do [ "${PARGS[$i]}" = "--apk" ] && have_apk=1; done
if [ "$have_apk" = 0 ]; then
  dir="$(mktemp -d)"; trap 'rm -rf "$dir"' EXIT
  echo "${B}⬇️  fetching latest release (once for the fleet)${X}"
  if command -v gh >/dev/null 2>&1; then
    gh release download --repo "$REPO" --pattern '*.apk' --dir "$dir" >/dev/null 2>&1 || true
  fi
  if ! ls "$dir"/*.apk >/dev/null 2>&1; then
    url="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4 || true)"
    [ -n "$url" ] && curl -fsSL "$url" -o "$dir/ha-paneld-latest.apk"
  fi
  APK="$(ls "$dir"/*.apk 2>/dev/null | head -1 || true)"
  [ -n "$APK" ] || { echo "${RED}could not fetch the latest release APK (need gh, or internet for the GitHub API)${X}" >&2; exit 1; }
  # Strip any stray --latest, then pin every panel to the one downloaded APK.
  NEW=(); for a in "${PARGS[@]}"; do [ "$a" = "--latest" ] || NEW+=("$a"); done
  PARGS=("${NEW[@]}" --apk "$APK")
  echo "   ${GRN}✓${X} ${D}$(basename "$APK")${X}"
fi

ok=0; fail=0; failed=()
for p in "${PANELS[@]}"; do
  case "$p" in *:*) t="$p" ;; *) t="$p:5555" ;; esac
  echo
  echo "${B}════════ $t ════════${X}"
  # --force: never block a batch on the same/older-version prompt. </dev/null: no interactive prompts.
  if bash "$PROVISION" "$t" "${PARGS[@]}" --force </dev/null; then ok=$((ok+1)); else fail=$((fail+1)); failed+=("$t"); fi
done

echo
if [ "$fail" = 0 ]; then
  echo "${GRN}${B}✅ fleet update complete — $ok/$ok panels OK${X}"
else
  echo "${YEL}${B}fleet update: $ok OK, $fail failed${X} — ${RED}${failed[*]}${X}"
  echo "${D}   re-run for the failed panels (provision.sh is idempotent).${X}"
fi
[ "$fail" = 0 ]
