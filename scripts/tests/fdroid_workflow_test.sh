#!/usr/bin/env bash
# Focused contracts for the F-Droid build and R2 publisher.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/fdroid.yml"
PUBLISHER="$ROOT/tools/fdroid/publish-r2.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passes=0
failures=0
pass() { passes=$((passes + 1)); printf 'ok %d - %s\n' "$passes" "$1"; }
fail_test() { failures=$((failures + 1)); printf 'not ok - %s\n' "$1" >&2; }

if bash -n "$PUBLISHER"; then
  pass "R2 publisher shell is syntactically valid"
else
  fail_test "R2 publisher shell is syntactically valid"
fi

if [ -x "$ROOT/tools/fdroid/publish-r2.sh" ] &&
   [ ! -e "$ROOT/fdroid" ] &&
   grep -Fq 'working-directory: tools/fdroid' "$WORKFLOW" &&
   grep -Fq 'mkdir -p ../../_site/fdroid' "$WORKFLOW" &&
   grep -Fq 'run: tools/fdroid/publish-r2.sh _site' "$WORKFLOW"; then
  pass "F-Droid source lives under tools while the publication retains its fdroid path"
else
  fail_test "F-Droid source lives under tools while the publication retains its fdroid path"
fi

if grep -Fq 'https://fdroid.ha-paneld.com/fdroid/repo' "$WORKFLOW" &&
   grep -Fq 'publish_r2:' "$WORKFLOW" &&
   grep -Fq 'name: fdroid' "$WORKFLOW" &&
   grep -Fq 'FDROID_R2_ACCESS_KEY_ID' "$WORKFLOW" &&
   grep -Fq 'FDROID_R2_SECRET_ACCESS_KEY' "$WORKFLOW" &&
   grep -Fq 'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a' "$WORKFLOW" &&
   grep -Fq 'actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c' "$WORKFLOW"; then
  pass "workflow uses the dedicated hostname, environment and ordinary short-lived artifact"
else
  fail_test "workflow uses the dedicated hostname, environment and ordinary short-lived artifact"
fi

if ! grep -Eq 'upload-pages-artifact|deploy-pages|github-pages|pages:[[:space:]]*write|publish_pages|maxlyth\.github\.io' "$WORKFLOW"; then
  pass "workflow has no GitHub Pages deployment path or legacy repository URL"
else
  fail_test "workflow has no GitHub Pages deployment path or legacy repository URL"
fi

if grep -Fq 'cancel-in-progress: false' "$WORKFLOW" &&
   ! grep -Eq 'cancel-in-progress:.*\$\{\{' "$WORKFLOW"; then
  pass "production publishers queue and cannot cancel a partial index update"
else
  fail_test "production publishers queue and cannot cancel a partial index update"
fi

build_job="$(awk '/^  build:$/ { in_job=1 } /^  deploy:$/ { exit } in_job' "$WORKFLOW")"
deploy_job="$(awk '/^  deploy:$/ { in_job=1 } in_job' "$WORKFLOW")"
if ! grep -Eq 'FDROID_R2_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)|AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)' <<<"$build_job" &&
   grep -Fq "(github.event_name != 'workflow_dispatch' || inputs.publish_r2)" <<<"$deploy_job" &&
   grep -Fq "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)" <<<"$deploy_job"; then
  pass "R2 credentials are confined to a default-branch-only optional deploy job"
else
  fail_test "R2 credentials are confined to a default-branch-only optional deploy job"
fi

site="$TMP/site"
state="$TMP/state"
mock_bin="$TMP/bin"
mkdir -p "$site/fdroid/repo" "$state/objects" "$state/meta" "$mock_bin"
printf 'landing\n' > "$site/index.html"
printf 'apk bytes\n' > "$site/fdroid/repo/ha-paneld-v1.0.0.apk"
printf 'newer apk bytes\n' > "$site/fdroid/repo/ha-paneld-v1.1.0.apk"
printf 'signed entry\n' > "$site/fdroid/repo/entry.jar"
printf '{"entry":true}\n' > "$site/fdroid/repo/entry.json"
printf 'v1 index\n' > "$site/fdroid/repo/index-v1.jar"
printf '{"repo":true}\n' > "$site/fdroid/repo/index-v2.json"
printf 'icon\n' > "$site/fdroid/repo/icon.png"

cat > "$mock_bin/aws" <<'AWS'
#!/usr/bin/env bash
set -eu
while [ "$#" -gt 0 ] && [ "$1" != s3api ] && [ "$1" != s3 ]; do shift; done
service="$1"; shift
operation="$1"; shift
arg() { local wanted="$1"; shift; while [ "$#" -gt 0 ]; do if [ "$1" = "$wanted" ]; then printf '%s' "$2"; return; fi; shift; done; }
case "$service/$operation" in
  s3api/head-bucket)
    bucket="$(arg --bucket "$@")"
    if [ "$bucket" = ha-paneld-assets ]; then printf '403 Forbidden\n' >&2; exit 255; fi
    [ "$bucket" = ha-paneld-fdroid ]
    ;;
  s3api/head-object)
    key="$(arg --key "$@")"; meta="$MOCK_R2_STATE/meta/$key"
    [ -f "$meta" ] || exit 255
    IFS='|' read -r sha size cache type < "$meta"
    printf '{"Metadata":{"sha256":"%s"},"ContentLength":%s,"CacheControl":"%s","ContentType":"%s"}\n' "$sha" "$size" "$cache" "$type"
    ;;
  s3api/list-objects-v2)
    [ "${MOCK_R2_LIST_FAILURE:-false}" != true ] || exit 255
    key="$(arg --prefix "$@")"
    if [ -f "$MOCK_R2_STATE/meta/$key" ]; then printf '{"Contents":[{"Key":"%s"}]}\n' "$key"; else printf '{"Contents":[]}\n'; fi
    ;;
  s3api/delete-object)
    key="$(arg --key "$@")"
    if [ "${MOCK_R2_FAIL_DELETE_KEY:-}" = "$key" ]; then
      exit 255
    fi
    if [ "${MOCK_R2_DELETE_NOOP_KEY:-}" != "$key" ]; then
      rm -f "$MOCK_R2_STATE/objects/$key" "$MOCK_R2_STATE/meta/$key"
    fi
    printf '%s\n' "$key" >> "$MOCK_R2_STATE/deletes.log"
    printf '{}\n'
    ;;
  s3/cp)
    source="$1"; destination="$2"; shift 2
    if [[ "$source" = s3://ha-paneld-fdroid/* ]]; then
      key="${source#s3://ha-paneld-fdroid/}"
      cp "$MOCK_R2_STATE/objects/$key" "$destination"
    else
      key="${destination#s3://ha-paneld-fdroid/}"
      if [ "${MOCK_R2_SIGNAL_KEY:-}" = "$key" ] && [ ! -f "$MOCK_R2_STATE/signal-used" ]; then
        : > "$MOCK_R2_STATE/signal-used"
        kill -s "${MOCK_R2_SIGNAL:-TERM}" "$PPID"
        exit 255
      fi
      if [ "${MOCK_R2_FAIL_RESTORE_KEY:-}" = "$key" ] && [[ "$source" = *ha-paneld-fdroid-rollback* ]]; then
        exit 255
      fi
      if [ "${MOCK_R2_FAIL_KEY:-}" = "$key" ] && [ ! -f "$MOCK_R2_STATE/failure-used" ]; then
        : > "$MOCK_R2_STATE/failure-used"
        exit 255
      fi
      type="$(arg --content-type "$@")"; cache="$(arg --cache-control "$@")"; metadata="$(arg --metadata "$@")"; sha="${metadata#sha256=}"
      mkdir -p "$MOCK_R2_STATE/objects/$(dirname "$key")" "$MOCK_R2_STATE/meta/$(dirname "$key")"
      cp "$source" "$MOCK_R2_STATE/objects/$key"
      printf '%s|%s|%s|%s\n' "$sha" "$(wc -c < "$source" | tr -d ' ')" "$cache" "$type" > "$MOCK_R2_STATE/meta/$key"
      printf '%s|%s|%s\n' "$key" "$cache" "$type" >> "$MOCK_R2_STATE/uploads.log"
    fi
    ;;
  *) printf 'unexpected aws invocation: %s/%s\n' "$service" "$operation" >&2; exit 2 ;;
esac
AWS

cat > "$mock_bin/curl" <<'CURL'
#!/usr/bin/env bash
set -eu
headers= body= url= range=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    -D) headers="$2"; shift 2 ;;
    -o) body="$2"; shift 2 ;;
    --range) range=true; shift 2 ;;
    http*) url="$1"; shift ;;
    *) shift ;;
  esac
done
key="${url#https://fdroid.ha-paneld.com/}"
printf '%s\n' "$key" >> "$MOCK_R2_STATE/public-requests.log"
[ "${MOCK_PUBLIC_FAIL_KEY:-}" != "$key" ] || exit 22
[ -f "$MOCK_R2_STATE/objects/$key" ] || exit 22
IFS='|' read -r sha size cache type < "$MOCK_R2_STATE/meta/$key"
if [ "$range" = true ]; then
  printf 'HTTP/2 206\r\ncache-control: %s\r\ncontent-type: %s\r\ncontent-range: bytes 0-0/%s\r\n\r\n' "$cache" "$type" "$size" > "$headers"
  head -c 1 "$MOCK_R2_STATE/objects/$key" > "$body"
else
  printf 'HTTP/2 200\r\ncache-control: %s\r\ncontent-type: %s\r\n\r\n' "$cache" "$type" > "$headers"
  cp "$MOCK_R2_STATE/objects/$key" "$body"
fi
CURL
chmod +x "$mock_bin/aws" "$mock_bin/curl"

run_publisher() {
  PATH="$mock_bin:$PATH" MOCK_R2_STATE="$state" RUNNER_TEMP="$TMP" \
    AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
    FDROID_R2_ENDPOINT=https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com \
    "$PUBLISHER" "$site"
}

if run_publisher > "$TMP/publish.log" 2>&1; then
  pass "publisher completes against a bucket-compatible test double"
else
  sed -n '1,120p' "$TMP/publish.log" >&2
  fail_test "publisher completes against a bucket-compatible test double"
fi

upload_keys="$(cut -d '|' -f 1 "$state/uploads.log" 2>/dev/null || true)"
if [ "$(head -n 1 <<<"$upload_keys")" = 'fdroid/repo/ha-paneld-v1.0.0.apk' ] &&
   [ "$(tail -n 2 <<<"$upload_keys" | head -n 1)" = 'fdroid/repo/index-v1.jar' ] &&
   [ "$(tail -n 1 <<<"$upload_keys")" = 'fdroid/repo/entry.jar' ]; then
  pass "publisher uploads versioned APKs first and both signed discovery roots last"
else
  fail_test "publisher uploads versioned APKs first and both signed discovery roots last"
fi

if grep -Fq 'fdroid/repo/ha-paneld-v1.0.0.apk|public, max-age=31536000, immutable|application/vnd.android.package-archive' "$state/uploads.log" &&
   grep -Fq 'fdroid/repo/index-v2.json|no-store|application/json' "$state/uploads.log" &&
   grep -Fq 'index.html|no-store|text/html; charset=utf-8' "$state/uploads.log"; then
  pass "publisher assigns immutable APK and no-store mutable-object policies"
else
  fail_test "publisher assigns immutable APK and no-store mutable-object policies"
fi

if grep -Fxq 'fdroid/repo/ha-paneld-v1.0.0.apk' "$state/public-requests.log" &&
   grep -Fxq 'fdroid/repo/ha-paneld-v1.1.0.apk' "$state/public-requests.log"; then
  pass "publisher verifies every newly uploaded APK through the public hostname"
else
  fail_test "publisher verifies every newly uploaded APK through the public hostname"
fi

printf 'changed apk bytes\n' > "$site/fdroid/repo/ha-paneld-v1.0.0.apk"
if ! run_publisher > "$TMP/mismatch.log" 2>&1 &&
   grep -Fq 'Refusing to replace immutable F-Droid object' "$TMP/mismatch.log"; then
  pass "publisher refuses to replace an existing versioned APK with different bytes"
else
  fail_test "publisher refuses to replace an existing versioned APK with different bytes"
fi

printf 'apk bytes\n' > "$site/fdroid/repo/ha-paneld-v1.0.0.apk"
old_entry_sha="$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')"
old_legacy_index_sha="$(sha256sum "$state/objects/fdroid/repo/index-v1.jar" | awk '{print $1}')"
old_index_sha="$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')"
old_landing_sha="$(sha256sum "$state/objects/index.html" | awk '{print $1}')"
old_icon_sha="$(sha256sum "$state/objects/fdroid/repo/icon.png" | awk '{print $1}')"
old_landing_meta="$(< "$state/meta/index.html")"
old_icon_meta="$(< "$state/meta/fdroid/repo/icon.png")"
old_legacy_index_meta="$(< "$state/meta/fdroid/repo/index-v1.jar")"
rm -f "$state/failure-used"
printf 'new signed entry\n' > "$site/fdroid/repo/entry.jar"
printf '{"repo":"new"}\n' > "$site/fdroid/repo/index-v2.json"
printf 'new landing\n' > "$site/index.html"
printf 'new icon\n' > "$site/fdroid/repo/icon.png"
if ! MOCK_R2_FAIL_KEY=fdroid/repo/entry.jar run_publisher > "$TMP/rollback.log" 2>&1 &&
   grep -Fq 'restoring the previous mutable repository state' "$TMP/rollback.log" &&
   [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v1.jar" | awk '{print $1}')" = "$old_legacy_index_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ] &&
   [ "$(sha256sum "$state/objects/index.html" | awk '{print $1}')" = "$old_landing_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/icon.png" | awk '{print $1}')" = "$old_icon_sha" ] &&
   [ "$(< "$state/meta/index.html")" = "$old_landing_meta" ] &&
   [ "$(< "$state/meta/fdroid/repo/icon.png")" = "$old_icon_meta" ] &&
   [ "$(< "$state/meta/fdroid/repo/index-v1.jar")" = "$old_legacy_index_meta" ] &&
   [ "$(grep -E '^(restore|remove) ' "$TMP/rollback.log" | tail -n 2 | head -n 1)" = 'restore fdroid/repo/index-v1.jar' ] &&
   [ "$(grep -E '^(restore|remove) ' "$TMP/rollback.log" | tail -n 1)" = 'restore fdroid/repo/entry.jar' ]; then
  pass "publisher restores support and both signed roots last when the commit write fails"
else
  sed -n '1,120p' "$TMP/rollback.log" >&2
  fail_test "publisher restores support and both signed roots last when the commit write fails"
fi

printf 'verification-failure entry\n' > "$site/fdroid/repo/entry.jar"
printf '{"repo":"verification-failure"}\n' > "$site/fdroid/repo/index-v2.json"
printf 'verification-failure landing\n' > "$site/index.html"
printf 'verification-failure icon\n' > "$site/fdroid/repo/icon.png"
if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json run_publisher > "$TMP/verification-rollback.log" 2>&1 &&
   grep -Fq 'restoring the previous mutable repository state' "$TMP/verification-rollback.log" &&
   [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v1.jar" | awk '{print $1}')" = "$old_legacy_index_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ] &&
   [ "$(sha256sum "$state/objects/index.html" | awk '{print $1}')" = "$old_landing_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/icon.png" | awk '{print $1}')" = "$old_icon_sha" ] &&
   [ "$(< "$state/meta/index.html")" = "$old_landing_meta" ] &&
   [ "$(< "$state/meta/fdroid/repo/icon.png")" = "$old_icon_meta" ]; then
  pass "publisher restores overwritten mutable support files after public verification fails"
else
  sed -n '1,120p' "$TMP/verification-rollback.log" >&2
  fail_test "publisher restores overwritten mutable support files after public verification fails"
fi

rm -f "$state/failure-used"
printf 'support-write-failure entry\n' > "$site/fdroid/repo/entry.jar"
printf '{"repo":"support-write-failure"}\n' > "$site/fdroid/repo/index-v2.json"
printf 'support-write-failure landing\n' > "$site/index.html"
printf 'support-write-failure icon\n' > "$site/fdroid/repo/icon.png"
if ! MOCK_R2_FAIL_KEY=index.html run_publisher > "$TMP/support-write-rollback.log" 2>&1 &&
   grep -Fq 'restoring the previous mutable repository state' "$TMP/support-write-rollback.log" &&
   [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ] &&
   [ "$(sha256sum "$state/objects/index.html" | awk '{print $1}')" = "$old_landing_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/icon.png" | awk '{print $1}')" = "$old_icon_sha" ]; then
  pass "publisher rolls back when a mutable support-file write fails"
else
  sed -n '1,120p' "$TMP/support-write-rollback.log" >&2
  fail_test "publisher rolls back when a mutable support-file write fails"
fi

phase_failures_ok=true
for failure_key in fdroid/repo/index-v2.json fdroid/repo/entry.json; do
  rm -f "$state/failure-used"
  phase_name="${failure_key##*/}"
  if MOCK_R2_FAIL_KEY="$failure_key" run_publisher > "$TMP/${phase_name}-write-rollback.log" 2>&1 ||
     [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" != "$old_entry_sha" ] ||
     [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" != "$old_index_sha" ] ||
     [ "$(sha256sum "$state/objects/index.html" | awk '{print $1}')" != "$old_landing_sha" ]; then
    phase_failures_ok=false
    sed -n '1,140p' "$TMP/${phase_name}-write-rollback.log" >&2
  fi
done
if [ "$phase_failures_ok" = true ]; then
  pass "publisher rolls back direct failures in index and pre-commit entry writes"
else
  fail_test "publisher rolls back direct failures in index and pre-commit entry writes"
fi

rm -f "$state/objects/fdroid/repo/icon.png" "$state/meta/fdroid/repo/icon.png"
rm -f "$state/objects/fdroid/repo/entry.json" "$state/meta/fdroid/repo/entry.json"
printf 'mixed-state entry\n' > "$site/fdroid/repo/entry.jar"
printf '{"entry":"mixed-state"}\n' > "$site/fdroid/repo/entry.json"
printf '{"repo":"mixed-state"}\n' > "$site/fdroid/repo/index-v2.json"
printf 'mixed-state landing\n' > "$site/index.html"
printf 'mixed-state icon\n' > "$site/fdroid/repo/icon.png"
if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json run_publisher > "$TMP/mixed-state-rollback.log" 2>&1 &&
   [ ! -e "$state/objects/fdroid/repo/icon.png" ] &&
   [ ! -e "$state/meta/fdroid/repo/icon.png" ] &&
   [ ! -e "$state/objects/fdroid/repo/entry.json" ] &&
   [ ! -e "$state/meta/fdroid/repo/entry.json" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ] &&
   [ "$(sha256sum "$state/objects/index.html" | awk '{print $1}')" = "$old_landing_sha" ]; then
  pass "publisher restores present keys and deletes absent keys in one failed publication"
else
  sed -n '1,160p' "$TMP/mixed-state-rollback.log" >&2
  fail_test "publisher restores present keys and deletes absent keys in one failed publication"
fi

discovery_hide_failures_ok=true
for discovery_root in fdroid/repo/index-v1.jar fdroid/repo/entry.jar; do
  for hide_failure in delete proof; do
    if ! run_publisher > "$TMP/reset-before-hide-failure.log" 2>&1; then
      discovery_hide_failures_ok=false
      break 2
    fi
    current_index_sha="$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')"
    current_landing_sha="$(sha256sum "$state/objects/index.html" | awk '{print $1}')"
    root_name="${discovery_root##*/}"
    hide_log="$TMP/discovery-hide-${root_name}-${hide_failure}.log"
    if [ "$hide_failure" = delete ]; then
      MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json MOCK_R2_FAIL_DELETE_KEY="$discovery_root" \
        run_publisher > "$hide_log" 2>&1
    else
      MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json MOCK_R2_DELETE_NOOP_KEY="$discovery_root" \
        run_publisher > "$hide_log" 2>&1
    fi
    hide_status=$?
    if [ "$hide_status" -eq 0 ] ||
       ! grep -Fq 'Underlying objects were left unchanged' "$hide_log" ||
       [ ! -e "$state/objects/$discovery_root" ] ||
       [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" != "$current_index_sha" ] ||
       [ "$(sha256sum "$state/objects/index.html" | awk '{print $1}')" != "$current_landing_sha" ] ||
       grep -Eq '^(restore|remove) ' "$hide_log"; then
      discovery_hide_failures_ok=false
      sed -n '1,220p' "$hide_log" >&2
    fi
  done
done
if [ "$discovery_hide_failures_ok" = true ]; then
  pass "publisher does not alter underlying state unless deletion and absence of both roots are proved"
else
  fail_test "publisher does not alter underlying state unless deletion and absence of both roots are proved"
fi

if ! run_publisher > "$TMP/reset-before-underlying-failure.log" 2>&1; then
  sed -n '1,220p' "$TMP/reset-before-underlying-failure.log" >&2
  fail_test "publisher re-establishes discovery before underlying rollback failure injection"
fi
if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json MOCK_R2_FAIL_RESTORE_KEY=fdroid/repo/index-v2.json \
     run_publisher > "$TMP/incomplete-rollback.log" 2>&1 &&
   grep -Fq 'F-Droid rollback failed; the repository requires immediate repair' "$TMP/incomplete-rollback.log" &&
   grep -Fq 'leave fdroid/repo/index-v1.jar hidden because rollback is incomplete' "$TMP/incomplete-rollback.log" &&
   grep -Fq 'leave fdroid/repo/entry.jar hidden because rollback is incomplete' "$TMP/incomplete-rollback.log" &&
   [ ! -e "$state/objects/fdroid/repo/index-v1.jar" ] &&
   [ ! -e "$state/meta/fdroid/repo/index-v1.jar" ] &&
   [ ! -e "$state/objects/fdroid/repo/entry.jar" ] &&
   [ ! -e "$state/meta/fdroid/repo/entry.jar" ]; then
  pass "publisher leaves both discovery roots hidden when an underlying rollback write fails"
else
  sed -n '1,220p' "$TMP/incomplete-rollback.log" >&2
  fail_test "publisher leaves both discovery roots hidden when an underlying rollback write fails"
fi

if run_publisher > "$TMP/reset-after-underlying-failure.log" 2>&1; then
  pass "publisher can establish a complete repository after an incomplete rollback"
else
  sed -n '1,220p' "$TMP/reset-after-underlying-failure.log" >&2
  fail_test "publisher can establish a complete repository after an incomplete rollback"
fi

if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json MOCK_R2_FAIL_RESTORE_KEY=fdroid/repo/index-v1.jar \
     run_publisher > "$TMP/legacy-root-restore-failure.log" 2>&1 &&
   grep -Fq 'Rollback action failed for fdroid/repo/index-v1.jar' "$TMP/legacy-root-restore-failure.log" &&
   grep -Fq 'F-Droid rollback failed; the repository requires immediate repair' "$TMP/legacy-root-restore-failure.log" &&
   [ ! -e "$state/objects/fdroid/repo/index-v1.jar" ] &&
   [ ! -e "$state/meta/fdroid/repo/index-v1.jar" ] &&
   [ ! -e "$state/objects/fdroid/repo/entry.jar" ] &&
   [ ! -e "$state/meta/fdroid/repo/entry.jar" ]; then
  pass "publisher leaves both roots hidden when restoring index-v1.jar fails"
else
  sed -n '1,220p' "$TMP/legacy-root-restore-failure.log" >&2
  fail_test "publisher leaves both roots hidden when restoring index-v1.jar fails"
fi

if ! run_publisher > "$TMP/reset-after-legacy-root-failure.log" 2>&1; then
  sed -n '1,220p' "$TMP/reset-after-legacy-root-failure.log" >&2
  fail_test "publisher re-establishes discovery before entry.jar restore failure injection"
fi
if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json MOCK_R2_FAIL_RESTORE_KEY=fdroid/repo/entry.jar \
     run_publisher > "$TMP/entry-restore-failure.log" 2>&1 &&
   grep -Fq 'Rollback action failed for fdroid/repo/entry.jar' "$TMP/entry-restore-failure.log" &&
   grep -Fq 'F-Droid rollback failed; the repository requires immediate repair' "$TMP/entry-restore-failure.log" &&
   grep -Fq 'leave fdroid/repo/index-v1.jar hidden because rollback is incomplete' "$TMP/entry-restore-failure.log" &&
   [ ! -e "$state/objects/fdroid/repo/index-v1.jar" ] &&
   [ ! -e "$state/meta/fdroid/repo/index-v1.jar" ] &&
   [ ! -e "$state/objects/fdroid/repo/entry.jar" ] &&
   [ ! -e "$state/meta/fdroid/repo/entry.jar" ]; then
  pass "publisher re-hides index-v1.jar when restoring entry.jar fails"
else
  sed -n '1,220p' "$TMP/entry-restore-failure.log" >&2
  fail_test "publisher re-hides index-v1.jar when restoring entry.jar fails"
fi

if ! run_publisher > "$TMP/reset-after-entry-failure.log" 2>&1; then
  sed -n '1,220p' "$TMP/reset-after-entry-failure.log" >&2
  fail_test "publisher re-establishes discovery before mixed discovery-state injection"
fi

mixed_discovery_states_ok=true
for absent_root in fdroid/repo/index-v1.jar fdroid/repo/entry.jar; do
  if ! run_publisher > "$TMP/reset-before-mixed-discovery.log" 2>&1; then
    mixed_discovery_states_ok=false
    break
  fi
  if [ "$absent_root" = fdroid/repo/index-v1.jar ]; then
    present_root=fdroid/repo/entry.jar
  else
    present_root=fdroid/repo/index-v1.jar
  fi
  present_sha="$(sha256sum "$state/objects/$present_root" | awk '{print $1}')"
  rm -f "$state/objects/$absent_root" "$state/meta/$absent_root"
  root_name="${absent_root##*/}"
  if MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json \
       run_publisher > "$TMP/mixed-discovery-${root_name}.log" 2>&1 ||
     [ -e "$state/objects/$absent_root" ] ||
     [ -e "$state/meta/$absent_root" ] ||
     [ "$(sha256sum "$state/objects/$present_root" | awk '{print $1}')" != "$present_sha" ]; then
    mixed_discovery_states_ok=false
    sed -n '1,220p' "$TMP/mixed-discovery-${root_name}.log" >&2
  fi
done
if [ "$mixed_discovery_states_ok" = true ]; then
  pass "publisher independently restores present and absent states for both discovery roots"
else
  fail_test "publisher independently restores present and absent states for both discovery roots"
fi

if ! run_publisher > "$TMP/reset-before-absent-delete-failure.log" 2>&1; then
  sed -n '1,220p' "$TMP/reset-before-absent-delete-failure.log" >&2
  fail_test "publisher re-establishes discovery before absent-delete failure injection"
fi
rm -f "$state/objects/fdroid/repo/icon.png" "$state/meta/fdroid/repo/icon.png"
if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json MOCK_R2_FAIL_DELETE_KEY=fdroid/repo/icon.png \
     run_publisher > "$TMP/absent-delete-failure.log" 2>&1 &&
   grep -Fq 'Rollback action failed for fdroid/repo/icon.png' "$TMP/absent-delete-failure.log" &&
   grep -Fq 'leave fdroid/repo/index-v1.jar hidden because rollback is incomplete' "$TMP/absent-delete-failure.log" &&
   grep -Fq 'leave fdroid/repo/entry.jar hidden because rollback is incomplete' "$TMP/absent-delete-failure.log" &&
   [ ! -e "$state/objects/fdroid/repo/index-v1.jar" ] &&
   [ ! -e "$state/meta/fdroid/repo/index-v1.jar" ] &&
   [ ! -e "$state/objects/fdroid/repo/entry.jar" ] &&
   [ ! -e "$state/meta/fdroid/repo/entry.jar" ]; then
  pass "publisher leaves both roots hidden when deleting an originally absent key fails"
else
  sed -n '1,240p' "$TMP/absent-delete-failure.log" >&2
  fail_test "publisher leaves both roots hidden when deleting an originally absent key fails"
fi

if ! run_publisher > "$TMP/reset-before-signals.log" 2>&1; then
  sed -n '1,220p' "$TMP/reset-before-signals.log" >&2
  fail_test "publisher re-establishes discovery before signal failure injection"
fi
signal_rollbacks_ok=true
for signal_case in HUP:129 INT:130 TERM:143; do
  signal_name="${signal_case%%:*}"
  expected_status="${signal_case##*:}"
  prior_entry_sha="$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')"
  prior_legacy_index_sha="$(sha256sum "$state/objects/fdroid/repo/index-v1.jar" | awk '{print $1}')"
  rm -f "$state/signal-used"
  MOCK_R2_SIGNAL="$signal_name" MOCK_R2_SIGNAL_KEY=fdroid/repo/index-v2.json \
    run_publisher > "$TMP/signal-${signal_name}.log" 2>&1
  signal_status=$?
  if [ "$signal_status" -ne "$expected_status" ] ||
     [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" != "$prior_entry_sha" ] ||
     [ "$(sha256sum "$state/objects/fdroid/repo/index-v1.jar" | awk '{print $1}')" != "$prior_legacy_index_sha" ] ||
     ! grep -Fq 'restoring the previous mutable repository state' "$TMP/signal-${signal_name}.log"; then
    signal_rollbacks_ok=false
    sed -n '1,220p' "$TMP/signal-${signal_name}.log" >&2
  fi
done
if [ "$signal_rollbacks_ok" = true ]; then
  pass "publisher restores the prior publication on HUP, INT and TERM"
else
  fail_test "publisher restores the prior publication on HUP, INT and TERM"
fi

rm -rf "$state/objects" "$state/meta"
: > "$state/deletes.log"
: > "$state/uploads.log"
mkdir -p "$state/objects" "$state/meta"
if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v1.jar run_publisher > "$TMP/first-publication-rollback.log" 2>&1 &&
   grep -Fq 'remove index.html' "$TMP/first-publication-rollback.log" &&
   grep -Fq 'remove fdroid/repo/index-v1.jar' "$TMP/first-publication-rollback.log" &&
   grep -Fq 'remove fdroid/repo/entry.jar' "$TMP/first-publication-rollback.log" &&
   [ -z "$(find "$state/objects" -type f ! -name '*.apk' -print -quit)" ] &&
   [ -z "$(find "$state/meta" -type f ! -name '*.apk' -print -quit)" ]; then
  pass "publisher removes every mutable object and both roots after a failed first publication"
else
  sed -n '1,200p' "$TMP/first-publication-rollback.log" >&2
  fail_test "publisher removes every mutable object and both roots after a failed first publication"
fi

: > "$state/uploads.log"
if ! MOCK_R2_LIST_FAILURE=true run_publisher > "$TMP/list-failure.log" 2>&1 &&
   grep -Fq 'object existence could not be checked safely' "$TMP/list-failure.log" &&
   [ ! -s "$state/uploads.log" ]; then
  pass "publisher fails closed before mutable writes when an absent rollback state cannot be proven"
else
  fail_test "publisher fails closed before mutable writes when an absent rollback state cannot be proven"
fi

printf '1..%d\n' "$((passes + failures))"
exit "$failures"
