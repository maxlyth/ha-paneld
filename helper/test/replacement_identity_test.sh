#!/usr/bin/env bash
set -euo pipefail

OLD_HELPER="${1:?old helper path is required}"
NEW_HELPER="${2:?new helper path is required}"
OLD_BUILD_ID="$(printf 'a%.0s' {1..64})"
NEW_BUILD_ID="$(printf 'b%.0s' {1..64})"
TMP="$(mktemp -d)"
OLD_PID=""
NEW_PID=""

cleanup() {
  [ -z "$NEW_PID" ] || kill "$NEW_PID" >/dev/null 2>&1 || true
  [ -z "$OLD_PID" ] || kill "$OLD_PID" >/dev/null 2>&1 || true
  [ -z "$NEW_PID" ] || wait "$NEW_PID" >/dev/null 2>&1 || true
  [ -z "$OLD_PID" ] || wait "$OLD_PID" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

wait_for_identity() {
  local helper="$1" expected="$2" reply="" attempt
  for attempt in {1..100}; do
    reply="$($helper --request BUILDID 2>/dev/null || true)"
    [ "$reply" = "BUILDID $expected" ] && return 0
    sleep 0.02
  done
  printf 'expected %s, observed %s\n' "$expected" "${reply:-<no reply>}" >&2
  return 1
}

"$OLD_HELPER" >"$TMP/old.log" 2>&1 &
OLD_PID=$!
wait_for_identity "$NEW_HELPER" "$OLD_BUILD_ID"
kill -0 "$OLD_PID"

# Leave the old executable actively serving the singleton socket. The replacement must recognize
# that its inode differs, retire it, take ownership, and make its own immutable identity observable.
"$NEW_HELPER" >"$TMP/new.log" 2>&1 &
NEW_PID=$!
wait_for_identity "$NEW_HELPER" "$NEW_BUILD_ID"
kill -0 "$NEW_PID"
if kill -0 "$OLD_PID" >/dev/null 2>&1; then
  echo "old helper still owns or competes for the replacement socket" >&2
  exit 1
fi
wait "$OLD_PID" >/dev/null 2>&1 || true
OLD_PID=""

echo "replacement helper identity tests passed"
