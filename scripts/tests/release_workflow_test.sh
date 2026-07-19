#!/usr/bin/env bash
# Focused behavioral checks for release-workflow shell contracts.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/release.yml"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passes=0
failures=0

pass() {
  passes=$((passes + 1))
  printf 'ok %d - %s\n' "$passes" "$1"
}

fail_test() {
  failures=$((failures + 1))
  printf 'not ok - %s\n' "$1" >&2
}

extract_release_notes_step() {
  awk '
    $0 == "      - name: Extract release notes from CHANGELOG" { in_step=1; next }
    in_step && $0 == "        run: |" { in_script=1; next }
    in_script && /^      - name: / { exit }
    in_script { print substr($0, 11) }
  ' "$WORKFLOW"
}

run_release_notes_step() {
  event_name="$1"
  release_tag="$2"
  case_dir="$3"
  (
    cd "$case_dir" || exit 1
    GITHUB_EVENT_NAME="$event_name" \
      RELEASE_TAG="$release_tag" \
      APK_NAME=test.apk \
      bash <(extract_release_notes_step)
  ) > "$case_dir/output.log" 2>&1
}

if extract_release_notes_step | bash -n; then
  pass "release-notes workflow shell is syntactically valid"
else
  fail_test "release-notes workflow shell is syntactically valid"
fi

stable_missing="$TMP/stable-missing"
mkdir -p "$stable_missing/release-input"
printf '## v1.0.0\n\nExisting notes.\n' > "$stable_missing/CHANGELOG.md"
run_release_notes_step push v9.9.9 "$stable_missing"
stable_missing_status=$?
if [ "$stable_missing_status" -ne 0 ] && \
   grep -Fq "::error::No CHANGELOG.md section for stable release v9.9.9." "$stable_missing/output.log" && \
   ! grep -Fq "_No changelog entry for v9.9.9._" "$stable_missing/release-input/release-body.md"; then
  pass "stable tag publication fails closed without an exact changelog entry"
else
  fail_test "stable tag publication fails closed without an exact changelog entry"
fi

dry_run_missing="$TMP/dry-run-missing"
mkdir -p "$dry_run_missing/release-input"
printf '## v1.0.0\n\nExisting notes.\n' > "$dry_run_missing/CHANGELOG.md"
run_release_notes_step workflow_dispatch v9.9.9 "$dry_run_missing"
dry_run_status=$?
if [ "$dry_run_status" -eq 0 ] && \
   grep -Fq "::warning::Dry run has no CHANGELOG.md section for stable candidate v9.9.9." "$dry_run_missing/output.log" && \
   grep -Fq "_No changelog entry for v9.9.9._" "$dry_run_missing/release-input/release-body.md"; then
  pass "manual stable dry run preserves diagnostic missing-changelog behavior"
else
  fail_test "manual stable dry run preserves diagnostic missing-changelog behavior"
fi

prerelease_missing="$TMP/prerelease-missing"
mkdir -p "$prerelease_missing/release-input"
printf '## v9.9.9\n\nStable notes.\n' > "$prerelease_missing/CHANGELOG.md"
run_release_notes_step push v9.9.9-rc1 "$prerelease_missing"
prerelease_missing_status=$?
if [ "$prerelease_missing_status" -ne 0 ] && \
   grep -Fq "each RC needs its own ## v9.9.9-rc1 entry" "$prerelease_missing/output.log"; then
  pass "prerelease tag still requires its own exact changelog entry"
else
  fail_test "prerelease tag still requires its own exact changelog entry"
fi

stable_present="$TMP/stable-present"
mkdir -p "$stable_present/release-input"
printf '## v9.9.9\n\nStable release notes.\n' > "$stable_present/CHANGELOG.md"
run_release_notes_step push v9.9.9 "$stable_present"
stable_present_status=$?
if [ "$stable_present_status" -eq 0 ] && \
   grep -Fq "Stable release notes." "$stable_present/release-input/release-body.md"; then
  pass "stable tag with an exact changelog entry remains publishable"
else
  fail_test "stable tag with an exact changelog entry remains publishable"
fi

if grep -Fq "body_path: release-input/release-body.md" "$WORKFLOW" && \
   ! grep -Eq '^[[:space:]]*generate_release_notes:' "$WORKFLOW"; then
  pass "curated changelog remains the sole release prose source"
else
  fail_test "curated changelog remains the sole release prose source"
fi

prepare_job="$(awk '/^  prepare:$/ { in_prepare=1 } /^  sign-and-publish:$/ { exit } in_prepare' "$WORKFLOW")"
if grep -Fq 'Require clean security analysis for the source commit' "$WORKFLOW" && \
   grep -Fq 'commits/$SOURCE_COMMIT/check-runs' "$WORKFLOW" && \
   grep -Fq 'code-scanning/alerts?state=open' "$WORKFLOW" && \
   grep -Fqx '      checks: read' <<<"$prepare_job" && \
   grep -Fqx '      security-events: read' <<<"$prepare_job"; then
  pass "release publication is gated by successful CodeQL checks and zero open alerts"
else
  fail_test "release publication is gated by successful CodeQL checks and zero open alerts"
fi

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
