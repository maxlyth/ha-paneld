#!/usr/bin/env bash
# Mutation battery for the Issue #96 staged-upload discard work.
#
# Runs every mutation in a THROWAWAY detached checkout of HEAD (never in place, so a killed run
# cannot strand a mutation in the working tree), one at a time, and credits a KILL only when the
# mutation's NAMED assertion goes red — classify.py reports WRONG-TEST-RED as its own verdict,
# because a red run alone never proves the right assertion fired. The no-op control must leave
# every suite green (CONTROL-OK). Verdicts: KILLED, SURVIVED, WRONG-TEST-RED, MUTATION-ABSENT,
# MUTATION-BROKE-COMPILE, CONTROL-OK, CONTROL-RED.
set -u -o pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC_ROOT="$(cd "$HERE/../.." && pwd)"
CHROME="${CHROME:-/usr/bin/chromium}"
BATTERY_DIR="$(mktemp -d "${TMPDIR:-/tmp}/issue96-battery.XXXXXX")"
WT="$BATTERY_DIR/checkout"
trap 'git -C "$SRC_ROOT" worktree remove --force "$WT" >/dev/null 2>&1; rm -rf "$BATTERY_DIR"' EXIT

git -C "$SRC_ROOT" worktree add --detach "$WT" HEAD >/dev/null 2>&1 || { echo "cannot create throwaway checkout"; exit 1; }
ln -s "$SRC_ROOT/test/node_modules" "$WT/test/node_modules"

KOTLIN_RESULTS="$WT/app/build/test-results/testDebugUnitTest"
BROWSER_PATTERN='APK preview|Choosing another APK|reload surfaces|upload-busy offers'
overall=0

run_kotlin() { # -> 0 green, 1 red, 2 compile failure
  local log="$BATTERY_DIR/gradle.log"
  rm -rf "$KOTLIN_RESULTS"
  (cd "$WT" && ./gradlew :app:testDebugUnitTest \
      --tests '*PendingUploadStoreTest' --tests '*ControlPlaneRoutesTest' \
      --tests '*HardenedApprovalAssetContractTest' -q) >"$log" 2>&1
  local status=$?
  if [ $status -ne 0 ] && { grep -q '^e: ' "$log" || grep -q 'Compilation error' "$log"; }; then
    return 2
  fi
  [ $status -eq 0 ]
}

run_browser() { # -> 0 green, 1 red, 2 harness/syntax failure; TAP lands in $1
  local tap="$1"
  (cd "$WT/test" && CHROME="$CHROME" node --test \
      --test-name-pattern="$BROWSER_PATTERN" browser-behavior.test.mjs) >"$tap" 2>&1
  local status=$?
  grep -q '^# tests ' "$tap" || return 2
  [ $status -eq 0 ]
}

verdict_line() { printf '%-34s %-8s %s\n' "$1" "$2" "$3"; }
printf '%-34s %-8s %s\n' MUTATION SUITE VERDICT
echo "----------------------------------------------------------------------"

while IFS=$'\t' read -r name suite named; do
  git -C "$WT" checkout -q -- app test

  if ! python3 "$HERE/mutations.py" apply "$name" "$WT" 2>"$BATTERY_DIR/apply.err"; then
    verdict_line "$name" "$suite" "MUTATION-ABSENT ($(cat "$BATTERY_DIR/apply.err"))"
    overall=1
    continue
  fi
  if git -C "$WT" diff --quiet -- app test; then
    verdict_line "$name" "$suite" "MUTATION-ABSENT (no diff after apply)"
    overall=1
    continue
  fi

  if [ "$suite" = kotlin ]; then
    run_kotlin
    status=$?
    if [ $status -eq 2 ]; then verdict="MUTATION-BROKE-COMPILE"
    else verdict="$(python3 "$HERE/classify.py" kotlin "$named" "$KOTLIN_RESULTS")"
    fi
  else
    tap="$BATTERY_DIR/$name.tap"
    run_browser "$tap"
    status=$?
    if [ $status -eq 2 ]; then verdict="MUTATION-BROKE-COMPILE"
    else verdict="$(python3 "$HERE/classify.py" browser "$named" "$tap")"
    fi
  fi

  if [ "$name" = control-no-op ]; then
    # The control must SURVIVE: a red control means the battery itself is broken.
    if [ "$verdict" = SURVIVED ]; then verdict=CONTROL-OK; else verdict="CONTROL-RED ($verdict)"; overall=1; fi
  elif [ "$verdict" != KILLED ]; then
    overall=1
  fi
  verdict_line "$name" "$suite" "$verdict"
done < <(python3 "$HERE/mutations.py" list)

echo "----------------------------------------------------------------------"
[ $overall -eq 0 ] && echo "BATTERY GREEN" || echo "BATTERY RED"
exit $overall
