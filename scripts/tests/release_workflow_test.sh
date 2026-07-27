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

extract_integrated_checks_step() {
  awk '
    $0 == "      - name: Require clean integrated checks for the source commit" { in_step=1; next }
    in_step && $0 == "        run: |" { in_script=1; next }
    in_script && (/^      - name: / || /^  package:$/) { exit }
    in_script { print substr($0, 11) }
  ' "$WORKFLOW"
}

run_integrated_checks_step() {
  checks_file="$1"
  output_file="$2"
  mock_bin="$TMP/mock-bin"
  mkdir -p "$mock_bin"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'case "$*" in' \
    '  *check-runs*) sed -n "p" "$MOCK_CHECKS_FILE" ;;' \
    '  *code-scanning/alerts*) printf "%s\n" 0 ;;' \
    '  *) printf "unexpected gh invocation: %s\n" "$*" >&2; exit 2 ;;' \
    'esac' > "$mock_bin/gh"
  chmod +x "$mock_bin/gh"
  PATH="$mock_bin:$PATH" \
    MOCK_CHECKS_FILE="$checks_file" \
    SOURCE_COMMIT=1111111111111111111111111111111111111111 \
    GITHUB_REPOSITORY=maxlyth/ha-paneld \
    GITHUB_ENV="$TMP/github-env" \
    bash <(extract_integrated_checks_step | sed -e 's/max_attempts=60/max_attempts=1/' -e 's/sleep_seconds=10/sleep_seconds=0/') > "$output_file" 2>&1
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

required_check_names='["Android build","Host contracts","Dependency integrity","Privileged helper","CodeQL · actions","CodeQL · c-cpp","CodeQL · java-kotlin","CodeQL · javascript-typescript","CodeQL · python"]'
latest_success_checks="$TMP/latest-success-checks.json"
jq -cn --argjson names "$required_check_names" '
  {check_runs: [$names[] as $name |
    {id: 100, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:00:00Z", status: "completed", conclusion: "failure"},
    {id: 200, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:01:00Z", status: "completed", conclusion: "success"},
    {id: 300, name: $name, head_sha: "2222222222222222222222222222222222222222", app: {slug: "github-actions"}, started_at: "2026-07-27T00:02:00Z", status: "completed", conclusion: "failure"},
    {id: 400, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "untrusted-check-writer"}, started_at: "2026-07-27T00:03:00Z", status: "completed", conclusion: "failure"}
  ]}' > "$latest_success_checks"
if run_integrated_checks_step "$latest_success_checks" "$TMP/latest-success-output.log"; then
  pass "integrated gate selects the latest exact-source check instead of an older or foreign result"
else
  sed -n '1,20p' "$TMP/latest-success-output.log" >&2
  fail_test "integrated gate selects the latest exact-source check instead of an older or foreign result"
fi

latest_failure_checks="$TMP/latest-failure-checks.json"
jq -cn --argjson names "$required_check_names" '
  {check_runs: [$names[] as $name |
    {id: 100, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:00:00Z", status: "completed", conclusion: "success"},
    {id: 200, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:01:00Z", status: "completed", conclusion: "failure"}
  ]}' > "$latest_failure_checks"
if ! run_integrated_checks_step "$latest_failure_checks" "$TMP/latest-failure-output.log" && \
   grep -Fq 'Latest required Android build check' "$TMP/latest-failure-output.log"; then
  pass "integrated gate rejects a latest exact-source failure even when an older run passed"
else
  fail_test "integrated gate rejects a latest exact-source failure even when an older run passed"
fi

latest_queued_checks="$TMP/latest-queued-checks.json"
jq -cn --argjson names "$required_check_names" '
  {check_runs: [$names[] as $name |
    {id: 100, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:00:00Z", status: "completed", conclusion: "success"},
    {id: 200, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: null, status: "queued", conclusion: null}
  ]}' > "$latest_queued_checks"
if ! run_integrated_checks_step "$latest_queued_checks" "$TMP/latest-queued-output.log" && \
   grep -Fq 'Timed out waiting for required checks' "$TMP/latest-queued-output.log"; then
  pass "integrated gate waits for a newer queued exact-source check instead of accepting an older success"
else
  fail_test "integrated gate waits for a newer queued exact-source check instead of accepting an older success"
fi

verify_job="$(awk '/^  verify:$/ { in_job=1 } /^  package:$/ { exit } in_job' "$WORKFLOW")"
package_job="$(awk '/^  package:$/ { in_job=1 } /^  sign-and-publish:$/ { exit } in_job' "$WORKFLOW")"
publish_job="$(awk '/^  sign-and-publish:$/ { in_job=1 } in_job' "$WORKFLOW")"
if grep -Fq 'Require clean integrated checks for the source commit' "$WORKFLOW" && \
   grep -Fq 'commits/$SOURCE_COMMIT/check-runs' "$WORKFLOW" && \
   grep -Fq 'code-scanning/alerts?state=open' "$WORKFLOW" && \
   grep -Fq '"Android build"' "$WORKFLOW" && \
   grep -Fq '"Host contracts"' "$WORKFLOW" && \
   grep -Fq '"Dependency integrity"' "$WORKFLOW" && \
   grep -Fq '"Privileged helper"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · actions"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · c-cpp"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · java-kotlin"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · javascript-typescript"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · python"' "$WORKFLOW" && \
   grep -Fq 'select(.name == $name and .head_sha == $source and .app.slug == "github-actions")' "$WORKFLOW" && \
   grep -Fq '| max_by(.id) // {}' "$WORKFLOW" && \
   grep -Fq 'max_attempts=60' "$WORKFLOW" && \
   grep -Fq 'sleep_seconds=10' "$WORKFLOW" && \
   grep -Fq 'if [ "$conclusion" != success ]; then' "$WORKFLOW" && \
   grep -Fq 'Timed out waiting for required checks' "$WORKFLOW" && \
   grep -Fqx '      checks: read' <<<"$verify_job" && \
   grep -Fqx '      security-events: read' <<<"$verify_job" && \
   ! grep -Fq 'Require clean integrated checks for the source commit' <<<"$package_job"; then
  pass "release consumes the latest successful exact-source CI and CodeQL checks and fails closed"
else
  fail_test "release consumes the latest successful exact-source CI and CodeQL checks and fails closed"
fi

if grep -Fq 'Test release workflow contracts' <<<"$verify_job" && \
   grep -Fq 'Require clean integrated checks for the source commit' <<<"$verify_job" && \
   ! grep -Fq 'Set up JDK 17' <<<"$verify_job" && \
   ! grep -Fq 'Set up Android SDK' <<<"$verify_job" && \
   ! grep -Fq 'Install Android build toolchain' <<<"$verify_job" && \
   ! grep -Fq 'Test privileged helper boundaries and app contract' <<<"$verify_job" && \
   ! grep -Fq 'Test installer and provisioning contracts' <<<"$verify_job" && \
   ! grep -Fq 'Run JVM tests and Android lint' <<<"$verify_job" && \
   grep -Fq 'Build release APK' <<<"$package_job" && \
   grep -Fq 'Upload sealed release inputs' <<<"$package_job" && \
   ! grep -Fq 'Require clean integrated checks for the source commit' <<<"$package_job"; then
  pass "release exact-source gate stays lightweight while packaging performs a clean exact-tag build"
else
  fail_test "release exact-source gate stays lightweight while packaging performs a clean exact-tag build"
fi

if grep -Fqx 'concurrency:' "$WORKFLOW" && \
   grep -Fq 'group: release-${{ inputs.release_tag || github.ref_name }}' "$WORKFLOW" && \
   grep -Fqx '  cancel-in-progress: false' "$WORKFLOW"; then
  pass "same-tag release runs serialize without cancelling publication"
else
  fail_test "same-tag release runs serialize without cancelling publication"
fi

if grep -Fqx '    needs: [verify, package]' <<<"$publish_job" && \
   grep -Fq 'EXPECTED_MANIFEST_SHA256: ${{ needs.package.outputs.input-manifest-sha256 }}' <<<"$publish_job" && \
   grep -Fqx "    if: github.event_name == 'push'" <<<"$publish_job"; then
  pass "publication requires both parallel jobs and the package manifest"
else
  fail_test "publication requires both parallel jobs and the package manifest"
fi

if grep -Fqx '      contents: read' <<<"$verify_job" && \
   grep -Fqx '      contents: read' <<<"$package_job" && \
   ! grep -Fq 'contents: write' <<<"$verify_job" && \
   ! grep -Fq 'contents: write' <<<"$package_job" && \
   ! grep -Fq 'environment: release' <<<"$verify_job" && \
   ! grep -Fq 'environment: release' <<<"$package_job" && \
   grep -Fqx '      contents: write' <<<"$publish_job" && \
   grep -Fqx '    environment: release' <<<"$publish_job"; then
  pass "parallel jobs stay read-only and release credentials remain publish-only"
else
  fail_test "parallel jobs stay read-only and release credentials remain publish-only"
fi

if grep -Fq 'git merge-base --is-ancestor "$source_commit" refs/remotes/origin/main' <<<"$verify_job" && \
   grep -Fq 'git merge-base --is-ancestor "$source_commit" refs/remotes/origin/main' <<<"$package_job" && \
   grep -Fq 'echo "SOURCE_COMMIT=$source_commit" >> "$GITHUB_ENV"' <<<"$verify_job" && \
   grep -Fq 'echo "SOURCE_COMMIT=$source_commit" >> "$GITHUB_ENV"' <<<"$package_job"; then
  pass "both parallel jobs validate and bind the same release source"
else
  fail_test "both parallel jobs validate and bind the same release source"
fi

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
