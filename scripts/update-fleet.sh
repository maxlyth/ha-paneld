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
valid_release_tag() { printf '%s\n' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; }
release_apk_name() { printf 'ha-paneld-%s-manual-setup-required.apk\n' "$1"; }
release_apk_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$(release_apk_name "$1")"; }
[ -f "$PROVISION" ] || { echo "${RED}provision.sh not found next to this script${X}" >&2; exit 1; }
TEMP_PATHS=()
cleanup() { local path; for path in "${TEMP_PATHS[@]}"; do rm -rf "$path"; done; }
trap cleanup EXIT

# Split args at `--` into pass-through provision args and the panel list.
PARGS=(); PANELS=(); seen_dd=0
for a in "$@"; do
  if [ "$seen_dd" = 0 ] && [ "$a" = "--" ]; then seen_dd=1; continue; fi
  if [ "$seen_dd" = 1 ]; then PANELS+=("$a"); else PARGS+=("$a"); fi
done
# Panels may also arrive on stdin (one per line) — but ONLY when none were given as args, so a
# non-tty stdin (pipelines, CI) can't clobber an explicit `-- <ip> …` list.
if [ "${#PANELS[@]}" -eq 0 ] && [ ! -t 0 ]; then
  while IFS= read -r line; do line="${line%%#*}"; line="$(echo "$line" | tr -d '[:space:]')"; [ -n "$line" ] && PANELS+=("$line"); done
fi
[ "${#PANELS[@]}" -gt 0 ] || { echo "${RED}no panels given${X} (after -- or on stdin)" >&2; exit 2; }

# Normalize before parallel work starts. `panel` and `panel:5555` name the same adb endpoint; running
# both concurrently would race two install/config transactions against one device.
NORMALIZED=()
for p in "${PANELS[@]}"; do
  case "$p" in *:*) t="$p" ;; *) t="$p:5555" ;; esac
  duplicate=0
  for seen in "${NORMALIZED[@]}"; do [ "$seen" = "$t" ] && duplicate=1; done
  if [ "$duplicate" = 1 ]; then
    echo "${RED}duplicate panel target: $t${X} — list each panel only once" >&2
    exit 2
  fi
  NORMALIZED+=("$t")
done
PANELS=("${NORMALIZED[@]}")

# Resolve a single APK for the whole fleet. If --apk was passed through, reuse it; otherwise download
# the latest signed release ONCE and convert the pass-through args to --apk for every panel.
have_apk=0
want_prerelease=0
for ((i=0; i<${#PARGS[@]}; i++)); do [ "${PARGS[$i]}" = "--apk" ] && have_apk=1; done
for a in "${PARGS[@]}"; do case "$a" in --prerelease|--pre) want_prerelease=1 ;; esac; done
if [ "$have_apk" = 0 ]; then
  dir="$(mktemp -d)"; TEMP_PATHS+=("$dir")
  if [ "$want_prerelease" = 1 ]; then channel="latest release, including pre-releases"; else channel="latest stable release"; fi
  echo "${B}⬇️  fetching $channel (once for the fleet)${X}"
  tag=""; asset=""; expected_url=""
  if command -v gh >/dev/null 2>&1; then
    if [ "$want_prerelease" = 1 ]; then
      # `--prerelease` means the newest published release candidate, not merely the newest
      # release of either channel and never a maintainer-visible draft.
      tag="$(gh release list --repo "$REPO" --exclude-drafts --limit 100 \
        --json tagName,isPrerelease --jq 'map(select(.isPrerelease))[0].tagName // empty' 2>/dev/null || true)"
    else
      tag="$(gh release view --repo "$REPO" --json tagName -q .tagName 2>/dev/null || true)"
    fi
    if [ -n "$tag" ] && valid_release_tag "$tag"; then
      asset="$(release_apk_name "$tag")"
      gh release download "$tag" --repo "$REPO" --pattern "$asset" --dir "$dir" >/dev/null 2>&1 || true
    fi
  fi
  if [ -z "$asset" ] || [ ! -s "$dir/$asset" ]; then
    if [ "$want_prerelease" = 1 ]; then api="https://api.github.com/repos/$REPO/releases?per_page=100"; else api="https://api.github.com/repos/$REPO/releases/latest"; fi
    json="$(curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 "$api" 2>/dev/null || true)"
    if [ "$want_prerelease" = 1 ]; then
      # Split the GitHub release array at each top-level release URL, retain the first published
      # prerelease record, then extract its tag and APK from that record only.
      record="$(printf '%s' "$json" | tr -d '\r\n' | \
        sed 's#{[[:space:]]*"url":[[:space:]]*"https://api.github.com/repos/maxlyth/ha-paneld/releases/\([0-9][0-9]*\)"#\
&#g' | \
        awk '/"draft":[[:space:]]*false/ && /"prerelease":[[:space:]]*true/ { print; exit }')"
    else
      record="$json"
    fi
    tag="$(printf '%s' "$record" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    url="$(printf '%s' "$record" | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4 || true)"
    if [ -n "$tag" ] && valid_release_tag "$tag"; then
      asset="$(release_apk_name "$tag")"
      expected_url="$(release_apk_url "$tag")"
      [ "$url" = "$expected_url" ] && curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$url" -o "$dir/$asset" || true
    fi
  fi
  [ -n "$asset" ] && [ -s "$dir/$asset" ] && APK="$dir/$asset" || APK=""
  [ -n "$APK" ] || { echo "${RED}could not fetch the latest release APK from the expected GitHub release path${X}" >&2; exit 1; }
  # Strip channel selectors, then pin every panel to the exact one downloaded APK.
  NEW=(); for a in "${PARGS[@]}"; do case "$a" in --latest|--prerelease|--pre) ;; *) NEW+=("$a") ;; esac; done
  PARGS=("${NEW[@]}" --apk "$APK" --release-tag "$tag")
  echo "   ${GRN}✓${X} ${D}$(basename "$APK")${X}${tag:+ · $tag}"
fi

run_dir="$(mktemp -d)"; TEMP_PATHS+=("$run_dir")
targets=(); pids=()
for p in "${PANELS[@]}"; do
  t="$p"
  targets+=("$t")
  index=$((${#targets[@]} - 1))
  # One process per panel keeps an eight-panel home fleet fast while every panel still gets the full
  # install/start/verify transaction. Output is isolated and replayed per panel below.
  ( if bash "$PROVISION" "$t" "${PARGS[@]}" --force </dev/null; then echo 0 > "$run_dir/$index.status";
    else echo 1 > "$run_dir/$index.status"; fi ) > "$run_dir/$index.log" 2>&1 &
  pids+=("$!")
done

for pid in "${pids[@]}"; do wait "$pid" || true; done

ok=0; fail=0; failed=()
for index in "${!targets[@]}"; do
  t="${targets[$index]}"
  echo
  echo "${B}════════ $t ════════${X}"
  if [ -f "$run_dir/$index.log" ]; then cat "$run_dir/$index.log"
  else echo "${RED}✗ provisioning worker ended without a log${X}"; fi
  if [ -f "$run_dir/$index.status" ] && [ "$(cat "$run_dir/$index.status")" = 0 ]; then
    ok=$((ok+1))
  else
    [ -f "$run_dir/$index.status" ] || echo "${RED}✗ provisioning worker ended before reporting status${X}"
    fail=$((fail+1)); failed+=("$t")
  fi
done

echo
if [ "$fail" = 0 ]; then
  echo "${GRN}${B}✅ fleet update complete — $ok/$ok panels OK${X}"
else
  echo "${YEL}${B}fleet update: $ok OK, $fail failed${X} — ${RED}${failed[*]}${X}"
  echo "${D}   re-run for the failed panels (provision.sh is idempotent).${X}"
fi
[ "$fail" = 0 ]
