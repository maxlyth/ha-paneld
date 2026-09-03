#!/usr/bin/env bash
# Focused behavioral checks for release-workflow shell contracts.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="${RELEASE_WORKFLOW_UNDER_TEST:-$ROOT/.github/workflows/release.yml}"
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

extract_named_step() {
  step_name="$1"
  awk -v wanted="$step_name" '
    $0 == "      - name: " wanted { in_step=1; next }
    in_step && $0 == "        run: |" { in_script=1; next }
    in_script && (/^      - name: / || /^  [A-Za-z0-9_-]+:$/) { exit }
    in_script { print substr($0, 11) }
  ' "$WORKFLOW"
}

extract_named_step_yaml() {
  step_name="$1"
  awk -v wanted="$step_name" '
    $0 == "      - name: " wanted { in_step=1 }
    in_step && /^      - name: / && $0 != "      - name: " wanted { exit }
    in_step { print }
  ' "$WORKFLOW"
}

extract_release_notes_step() {
  extract_named_step "Extract release notes from CHANGELOG"
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
  extract_named_step "Require clean integrated checks for the source commit"
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

descriptor_step="$(extract_named_step 'Generate bounded install descriptor without release credentials')"
proof_step="$(extract_named_step 'Sign and authenticate release proofs')"
final_step="$(extract_named_step 'Final exact verification before publication')"
if grep -Fq 'cp scripts/generate_install_descriptor.py release-input/generate_install_descriptor.py' <<<"$package_job" && \
   [ "$(grep -Fc 'generate_install_descriptor.py \' <<<"$package_job")" -eq 1 ] && \
   grep -Fq 'sha256sum android-gradle-runtime.cdx.json generate_install_descriptor.py' <<<"$package_job" && \
   grep -Fq 'generate_install_descriptor.py \' <<<"$publish_job" && \
   grep -Fq '(cd release-input && sha256sum --check MANIFEST.sha256)' <<<"$publish_job" && \
   grep -Fq '/usr/bin/setpriv \' <<<"$descriptor_step" && \
   grep -Fq -- '--reuid=65534 \' <<<"$descriptor_step" && \
   grep -Fq 'SYS_landlock_restrict_self' <<<"$descriptor_step" && \
   grep -Fq '/usr/bin/env -i \' <<<"$descriptor_step" && \
   grep -Fq '/usr/bin/python3 "$workspace/release-input/generate_install_descriptor.py" \' <<<"$descriptor_step" && \
   ! grep -Fq 'KEYSTORE_B64: ${{' <<<"$(extract_named_step_yaml 'Generate bounded install descriptor without release credentials')" && \
   ! grep -Eq '(^|[[:space:]])(python3|release-input/[^[:space:]]+\.py)([[:space:]]|$)' <<<"$proof_step" && \
   ! grep -Fq -- '-srcstorepass "$KEYSTORE_PASSWORD"' <<<"$proof_step" && \
   ! grep -Fq -- '-srckeypass "$KEY_PASSWORD"' <<<"$proof_step" && \
   ! grep -Fq -- '-passin "pass:' <<<"$proof_step" && \
   grep -Fq 'Install descriptor is not the exact canonical 13-field APK contract.' <<<"$proof_step" && \
   grep -Fq '/usr/bin/openssl dgst -sha256 -sign "$private_key" -out "dist/$descriptor_name.sig"' <<<"$proof_step" && \
   grep -Fq '/usr/bin/openssl dgst -sha256 -verify "$public_key" -signature "dist/$descriptor_name.sig"' <<<"$proof_step" && \
   grep -Fq 'Final exact verification before publication' <<<"$publish_job" && \
   grep -Fq '/usr/bin/openssl dgst -sha256 -verify "$public_key" -signature "dist/$descriptor_name.sig"' <<<"$final_step" && \
   grep -Fq 'files: dist/*' <<<"$publish_job"; then
  pass "release seals, isolates, revalidates, signs and publishes the exact APK install descriptor"
else
  fail_test "release seals, isolates, revalidates, signs and publishes the exact APK install descriptor"
fi

if [ "$(grep -Fc 'build-tools/36.0.0' <<<"$descriptor_step")" -eq 1 ] && \
   [ "$(grep -Fc 'build-tools/36.0.0' <<<"$proof_step")" -eq 1 ] && \
   [ "$(grep -Fc 'build-tools/36.0.0' <<<"$final_step")" -eq 2 ] && \
   [ "$(grep -Fc 'build-tools;36.0.0' <<<"$publish_job")" -eq 1 ] && \
   ! grep -Eiq 'build-tools/(latest|[0-9]+\.[0-9]+\.[1-9][0-9]*)' <<<"$descriptor_step$proof_step$final_step"; then
  pass "descriptor generation and verification stay pinned to Android Build-Tools 36.0.0"
else
  fail_test "descriptor generation and verification stay pinned to Android Build-Tools 36.0.0"
fi

descriptor_step_yaml="$(extract_named_step_yaml 'Generate bounded install descriptor without release credentials')"
proof_step_yaml="$(extract_named_step_yaml 'Sign and authenticate release proofs')"
final_step_yaml="$(extract_named_step_yaml 'Final exact verification before publication')"
asset_step="$(extract_named_step 'Sign and validate release APK')"
if grep -Fq '/usr/bin/install -d -m 0755 dist' <<<"$asset_step" && \
   grep -Fq 'apk_idsig="$signed_apk.idsig"' <<<"$asset_step" && \
   grep -Fq 'APK Signature Scheme v4 sidecar is not one regular nofollow file.' <<<"$asset_step" && \
   grep -Fq 'APK Signature Scheme v4 sidecar is empty or exceeds 1 MiB.' <<<"$asset_step" && \
   [ "$(grep -Fc -- '--v4-signature-file "$apk_idsig"' <<<"$asset_step")" -eq 2 ] && \
   grep -Fq '/usr/bin/chmod 0644 "$signed_apk" "$apk_idsig"' <<<"$asset_step" && \
   grep -Fq 'apk_idsig="$signed_apk.idsig"' <<<"$proof_step" && \
   [ "$(grep -Fc -- '--v4-signature-file "$apk_idsig"' <<<"$proof_step")" -eq 2 ] && \
   grep -Fq '"$apk_name.idsig" \' <<<"$final_step" && \
   grep -Fq 'apk_idsig="dist/$apk_name.idsig"' <<<"$final_step" && \
   [ "$(grep -Fc -- '--v4-signature-file "$apk_idsig"' <<<"$final_step")" -eq 2 ] && \
   grep -Fq 'Final APK Signature Scheme v4 sidecar is empty or exceeds 1 MiB.' <<<"$final_step"; then
  pass "signed APK and bounded V4 sidecar remain in the exact readable release set"
else
  fail_test "signed APK and bounded V4 sidecar remain in the exact readable release set"
fi
if [ "$(grep -Fc 'if [ "${#RELEASE_TAG}" -gt 64 ] || [[ ! "$RELEASE_TAG" =~ ^v(0|[1-9][0-9]*)' "$WORKFLOW")" -eq 2 ] && \
   ! grep -Eq '^[[:space:]]*if:[[:space:]]*(\$\{\{[[:space:]]*)?false' <<<"$descriptor_step_yaml$proof_step_yaml$final_step_yaml" && \
   ! grep -Eq '^[[:space:]]*continue-on-error:[[:space:]]*true' <<<"$descriptor_step_yaml$proof_step_yaml$final_step_yaml" && \
   ! grep -Eq '^[[:space:]]*if[[:space:]]+false([[:space:];]|$)' <<<"$descriptor_step" && \
   grep -Fq 'scripts/tests/release_workflow_test.sh' "$ROOT/.github/workflows/ci.yml"; then
  pass "release tag grammar, active descriptor step, and regular host-CI coverage are explicit"
else
  fail_test "release tag grammar, active descriptor step, and regular host-CI coverage are explicit"
fi

for shell_step in "$descriptor_step" "$proof_step" "$final_step"; do
  if ! bash -n <<<"$shell_step"; then
    fail_test "descriptor release workflow shell is syntactically valid"
    shell_step_syntax_failed=1
    break
  fi
done
if [ "${shell_step_syntax_failed:-0}" -eq 0 ]; then
  pass "descriptor release workflow shell is syntactically valid"
fi

descriptor_case="$TMP/descriptor-contract"
mkdir -p "$descriptor_case/release-input" "$descriptor_case/dist" \
  "$descriptor_case/android/build-tools/36.0.0" "$descriptor_case/runner-temp" \
  "$descriptor_case/forbidden-write"
chmod 0755 "$TMP" "$descriptor_case" "$descriptor_case/release-input" "$descriptor_case/dist" \
  "$descriptor_case/android" "$descriptor_case/android/build-tools" \
  "$descriptor_case/android/build-tools/36.0.0" "$descriptor_case/runner-temp"
chmod 0777 "$descriptor_case/forbidden-write"
cp "$ROOT/scripts/generate_install_descriptor.py" "$descriptor_case/release-input/generate_install_descriptor.py"
apk_name=ha-paneld-v1.2.3-rc1-manual-setup-required.apk
descriptor_name=ha-paneld-v1.2.3-rc1-install.json
printf 'authenticated release APK fixture\n' > "$descriptor_case/dist/$apk_name"
cat > "$descriptor_case/android/build-tools/36.0.0/aapt" <<'EOF'
#!/usr/bin/env bash
set -eu
for argument in "$@"; do
  case "$argument" in
    /proc/self/fd/*)
      [ -z "${POISON+x}" ] && [ -z "${KEYSTORE_B64+x}" ] || exit 90
      escape_path="$(dirname "$0")/../../../../forbidden-write/escape"
      if printf 'escaped\n' > "$escape_path" 2>/dev/null; then
        exit 92
      fi
      ;;
  esac
done
case "$*" in
  *"dump badging"*)
    cat <<'BADGING'
package: name='io.github.maxlyth.hapaneld' versionCode='701' versionName='1.2.3-rc1'
sdkVersion:'26'
launchable-activity: name='io.github.maxlyth.hapaneld.MainActivity' label='ha-paneld' icon=''
native-code: 'armeabi-v7a' 'arm64-v8a'
BADGING
    ;;
  *"dump xmltree"*)
    if [ "${MOCK_XMLTREE_MODE:-}" = foreign-root ]; then
      cat <<'FOREIGN_XMLTREE'
E: manifest (line=2)
  E: application (line=8)
E: foreign-root (line=20)
  E: application (line=21)
    E: meta-data (line=22)
      A: android:name(0x01010003)="io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY"
      A: android:value(0x01010024)="hapaneld-db:v1:ha-paneld.db:11:14"
FOREIGN_XMLTREE
      exit 0
    fi
    cat <<'XMLTREE'
E: manifest (line=2)
  E: application (line=8)
    E: meta-data (line=10)
      A: android:name(0x01010003)="io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY" (Raw: "io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY")
      A: android:value(0x01010024)="hapaneld-db:v1:ha-paneld.db:11:14" (Raw: "hapaneld-db:v1:ha-paneld.db:11:14")
    E: activity (line=20)
XMLTREE
    ;;
  *) exit 91 ;;
esac
EOF
cat > "$descriptor_case/android/build-tools/36.0.0/apksigner" <<'EOF'
#!/usr/bin/env bash
set -eu
v4_signature_file=
apk=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --v4-signature-file)
      [ "$#" -ge 2 ] || exit 93
      v4_signature_file=$2
      shift 2
      ;;
    --*) shift ;;
    *) apk=$1; shift ;;
  esac
done
if [ -n "$v4_signature_file" ]; then
  [ "$v4_signature_file" = "$apk.idsig" ] || exit 94
  if ! grep -Fxq 'APK Signature Scheme v4 fixture' "$v4_signature_file"; then
    printf 'V4 signature fixture is invalid.\n' >&2
    exit 95
  fi
fi
printf '%s\n' 'Signer #1 certificate SHA-256 digest: ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339'
EOF
chmod 0755 "$descriptor_case/android/build-tools/36.0.0/aapt" \
  "$descriptor_case/android/build-tools/36.0.0/apksigner"

java_home="$(dirname "$(dirname "$(readlink -f "$(command -v keytool)")")")"
if (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    POISON=must-not-reach-generator \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <(extract_named_step 'Generate bounded install descriptor without release credentials')
) > "$descriptor_case/generate.log" 2>&1 && \
   [ -f "$descriptor_case/dist/$descriptor_name" ] && \
   [ ! -L "$descriptor_case/dist/$descriptor_name" ] && \
   [ ! -e "$descriptor_case/forbidden-write/escape" ] && \
   [ "$(jq 'keys | length' "$descriptor_case/dist/$descriptor_name")" -eq 13 ] && \
   jq -e --arg apk_name "$apk_name" '
     .schema == "io.github.maxlyth.hapaneld.install.v1" and
     .releaseTag == "v1.2.3-rc1" and
     .versionName == "1.2.3-rc1" and
     .versionCode == 701 and
     .apkName == $apk_name and
     .minSdk == 26 and
     .supportedAbis == ["arm64-v8a", "armeabi-v7a"] and
     .databaseCompatibility == "hapaneld-db:v1:ha-paneld.db:11:14"
   ' "$descriptor_case/dist/$descriptor_name" >/dev/null; then
  pass "credential-free uid-65534 step behaviorally generates the exact 13-field descriptor"
else
  sed -n '1,80p' "$descriptor_case/generate.log" >&2
  fail_test "credential-free uid-65534 step behaviorally generates the exact 13-field descriptor"
fi

key_store="$descriptor_case/test-release.p12"
key_password=test-release-password
key_alias=test-release
"$java_home/bin/keytool" -genkeypair \
  -alias "$key_alias" \
  -keyalg RSA \
  -keysize 2048 \
  -dname 'CN=release workflow contract test' \
  -validity 2 \
  -storetype PKCS12 \
  -keystore "$key_store" \
  -storepass "$key_password" \
  -keypass "$key_password" \
  -noprompt >/dev/null 2>&1
"$java_home/bin/keytool" -exportcert -rfc \
  -alias "$key_alias" \
  -keystore "$key_store" \
  -storepass "$key_password" \
  > "$descriptor_case/test-release-certificate.pem"
openssl x509 -pubkey -noout \
  -in "$descriptor_case/test-release-certificate.pem" \
  > "$descriptor_case/test-release-public-key.pem"
test_public_key_sha256=$(openssl pkey -pubin \
  -in "$descriptor_case/test-release-public-key.pem" \
  -outform DER | sha256sum | cut -d' ' -f1)
for release_script in \
  "ha-paneld-installer-v1.2.3-rc1.sh" \
  "ha-paneld-provision-v1.2.3-rc1.sh"; do
  {
    printf '%s\n' '#!/usr/bin/env bash' 'write_release_public_key() {'
    cat "$descriptor_case/test-release-public-key.pem"
    printf '%s\n' '}'
  } > "$descriptor_case/dist/$release_script"
  chmod 0755 "$descriptor_case/dist/$release_script"
done
printf 'arm helper fixture\n' > "$descriptor_case/dist/ha-paneld-helper-v1.2.3-rc1-armeabi-v7a"
printf 'arm64 helper fixture\n' > "$descriptor_case/dist/ha-paneld-helper-v1.2.3-rc1-arm64-v8a"
printf '{}\n' > "$descriptor_case/dist/ha-paneld-v1.2.3-rc1-android-gradle-runtime.cdx.json"
printf '{}\n' > "$descriptor_case/dist/ha-paneld-v1.2.3-rc1-profile-editor-runtime.cdx.json"
printf 'APK Signature Scheme v4 fixture\n' > "$descriptor_case/dist/$apk_name.idsig"
(
  cd "$descriptor_case/dist" || exit 1
  for subject in \
    "$apk_name" \
    ha-paneld-provision-v1.2.3-rc1.sh \
    ha-paneld-helper-v1.2.3-rc1-armeabi-v7a \
    ha-paneld-helper-v1.2.3-rc1-arm64-v8a; do
    sha256sum "$subject" > "$subject.sha256"
  done
)
keystore_b64=$(base64 -w 0 "$key_store")
test_proof_step=$(sed \
  "s/502bf38874682ff337f187022b904adbc3ab0b387fd7ceb4043ce722997273f3/$test_public_key_sha256/g" \
  <<<"$proof_step")
if (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" \
    KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" \
    KEY_PASSWORD="$key_password" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_proof_step"
) > "$descriptor_case/proof.log" 2>&1 && \
   openssl dgst -sha256 \
     -verify "$descriptor_case/test-release-public-key.pem" \
     -signature "$descriptor_case/dist/$descriptor_name.sig" \
     "$descriptor_case/dist/$descriptor_name" >/dev/null; then
  pass "proof step behaviorally rechecks content, signs the descriptor, and verifies its signature"
else
  sed -n '1,120p' "$descriptor_case/proof.log" >&2
  fail_test "proof step behaviorally rechecks content, signs the descriptor, and verifies its signature"
fi

cp "$descriptor_case/dist/$descriptor_name" "$descriptor_case/original-descriptor.json"
jq -cS '.versionCode = 702' "$descriptor_case/original-descriptor.json" \
  > "$descriptor_case/dist/$descriptor_name"
rm -f "$descriptor_case/dist/$descriptor_name.sig"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" \
    KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" \
    KEY_PASSWORD="$key_password" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_proof_step"
) > "$descriptor_case/tampered-proof.log" 2>&1 && \
   [ ! -e "$descriptor_case/dist/$descriptor_name.sig" ] && \
   grep -Fq 'exact canonical 13-field APK contract' "$descriptor_case/tampered-proof.log"; then
  pass "proof signing refuses a descriptor whose authenticated APK content binding was changed"
else
  sed -n '1,80p' "$descriptor_case/tampered-proof.log" >&2
  fail_test "proof signing refuses a descriptor whose authenticated APK content binding was changed"
fi

cp "$descriptor_case/original-descriptor.json" "$descriptor_case/dist/$descriptor_name"
rm -f "$descriptor_case/dist/$descriptor_name.sig"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" \
    KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" \
    KEY_PASSWORD="$key_password" \
    MOCK_XMLTREE_MODE=foreign-root \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_proof_step"
) > "$descriptor_case/foreign-root-proof.log" 2>&1 && \
   [ ! -e "$descriptor_case/dist/$descriptor_name.sig" ] && \
   grep -Fq 'database compatibility contract is invalid' "$descriptor_case/foreign-root-proof.log"; then
  pass "proof signing rejects database metadata from a same-indent foreign application node"
else
  sed -n '1,80p' "$descriptor_case/foreign-root-proof.log" >&2
  fail_test "proof signing rejects database metadata from a same-indent foreign application node"
fi

(
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" \
    KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" \
    KEY_PASSWORD="$key_password" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_proof_step"
) > "$descriptor_case/restored-proof.log" 2>&1
test_final_step=$(sed \
  "s/502bf38874682ff337f187022b904adbc3ab0b387fd7ceb4043ce722997273f3/$test_public_key_sha256/g" \
  <<<"$final_step")
if (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/final.log" 2>&1; then
  pass "final pre-upload step behaviorally verifies the exact asset set, checksums, APK signer, and signatures"
else
  sed -n '1,120p' "$descriptor_case/final.log" >&2
  fail_test "final pre-upload step behaviorally verifies the exact asset set, checksums, APK signer, and signatures"
fi

cp "$descriptor_case/dist/$apk_name.idsig" "$descriptor_case/original.idsig"
printf 'corrupt V4 signature fixture\n' > "$descriptor_case/dist/$apk_name.idsig"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/corrupt-idsig-final.log" 2>&1 && \
   grep -Fq 'V4 signature fixture is invalid.' "$descriptor_case/corrupt-idsig-final.log"; then
  pass "final pre-upload verification rejects a corrupt V4 signature sidecar"
else
  fail_test "final pre-upload verification rejects a corrupt V4 signature sidecar"
fi
mv "$descriptor_case/original.idsig" "$descriptor_case/dist/$apk_name.idsig"

openssl pkcs12 \
  -in "$key_store" \
  -nodes \
  -nocerts \
  -passin "pass:$key_password" \
  -out "$descriptor_case/test-release-private-key.pem" >/dev/null 2>&1
printf 'different valid same-tag APK fixture\n' > "$descriptor_case/foreign.apk"
foreign_apk_sha256=$(sha256sum "$descriptor_case/foreign.apk" | cut -d' ' -f1)
foreign_apk_size=$(stat --format='%s' "$descriptor_case/foreign.apk")
jq -cS \
  --arg apk_sha256 "$foreign_apk_sha256" \
  --argjson apk_size "$foreign_apk_size" \
  '.apkSha256 = $apk_sha256 | .apkSize = $apk_size' \
  "$descriptor_case/original-descriptor.json" > "$descriptor_case/dist/$descriptor_name"
openssl dgst -sha256 \
  -sign "$descriptor_case/test-release-private-key.pem" \
  -out "$descriptor_case/dist/$descriptor_name.sig" \
  "$descriptor_case/dist/$descriptor_name"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/mixed-valid-final.log" 2>&1 && \
   grep -Fq 'does not bind the exact final release APK' "$descriptor_case/mixed-valid-final.log"; then
  pass "final pre-upload verification rejects a valid signed descriptor for another same-tag APK"
else
  sed -n '1,80p' "$descriptor_case/mixed-valid-final.log" >&2
  fail_test "final pre-upload verification rejects a valid signed descriptor for another same-tag APK"
fi

cp "$descriptor_case/original-descriptor.json" "$descriptor_case/dist/$descriptor_name"
openssl dgst -sha256 \
  -sign "$descriptor_case/test-release-private-key.pem" \
  -out "$descriptor_case/dist/$descriptor_name.sig" \
  "$descriptor_case/dist/$descriptor_name"
printf '\n' >> "$descriptor_case/dist/$descriptor_name"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/tampered-final.log" 2>&1; then
  pass "final pre-upload verification refuses a descriptor changed after proof signing"
else
  fail_test "final pre-upload verification refuses a descriptor changed after proof signing"
fi

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
