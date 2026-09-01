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
   grep -Fq "if: github.event_name != 'workflow_dispatch' || inputs.publish_r2" <<<"$deploy_job"; then
  pass "R2 credentials are confined to the optional deploy job"
else
  fail_test "R2 credentials are confined to the optional deploy job"
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
    printf '{"Metadata":{"sha256":"%s"},"ContentLength":%s}\n' "$sha" "$size"
    ;;
  s3api/list-objects-v2)
    [ "${MOCK_R2_LIST_FAILURE:-false}" != true ] || exit 255
    key="$(arg --prefix "$@")"
    if [ -f "$MOCK_R2_STATE/meta/$key" ]; then printf '{"Contents":[{"Key":"%s"}]}\n' "$key"; else printf '{"Contents":[]}\n'; fi
    ;;
  s3/cp)
    source="$1"; destination="$2"; shift 2
    if [[ "$source" = s3://ha-paneld-fdroid/* ]]; then
      key="${source#s3://ha-paneld-fdroid/}"
      cp "$MOCK_R2_STATE/objects/$key" "$destination"
    else
      key="${destination#s3://ha-paneld-fdroid/}"
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
   [ "$(tail -n 1 <<<"$upload_keys")" = 'fdroid/repo/entry.jar' ]; then
  pass "publisher uploads versioned APKs first and signed entry.jar last"
else
  fail_test "publisher uploads versioned APKs first and signed entry.jar last"
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
old_index_sha="$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')"
rm -f "$state/failure-used"
printf 'new signed entry\n' > "$site/fdroid/repo/entry.jar"
printf '{"repo":"new"}\n' > "$site/fdroid/repo/index-v2.json"
if ! MOCK_R2_FAIL_KEY=fdroid/repo/entry.jar run_publisher > "$TMP/rollback.log" 2>&1 &&
   grep -Fq 'restoring the previous signed index set' "$TMP/rollback.log" &&
   [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ]; then
  pass "publisher restores the prior signed index set when the final commit write fails"
else
  sed -n '1,120p' "$TMP/rollback.log" >&2
  fail_test "publisher restores the prior signed index set when the final commit write fails"
fi

printf 'verification-failure entry\n' > "$site/fdroid/repo/entry.jar"
printf '{"repo":"verification-failure"}\n' > "$site/fdroid/repo/index-v2.json"
if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json run_publisher > "$TMP/verification-rollback.log" 2>&1 &&
   grep -Fq 'restoring the previous signed index set' "$TMP/verification-rollback.log" &&
   [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ]; then
  pass "publisher restores the prior signed index set after public verification fails"
else
  sed -n '1,120p' "$TMP/verification-rollback.log" >&2
  fail_test "publisher restores the prior signed index set after public verification fails"
fi

rm -rf "$state/objects" "$state/meta"
: > "$state/uploads.log"
mkdir -p "$state/objects" "$state/meta"
if ! MOCK_R2_LIST_FAILURE=true run_publisher > "$TMP/list-failure.log" 2>&1 &&
   grep -Fq 'object existence could not be checked safely' "$TMP/list-failure.log" &&
   [ ! -s "$state/uploads.log" ]; then
  pass "publisher fails closed before writing when object existence cannot be checked"
else
  fail_test "publisher fails closed before writing when object existence cannot be checked"
fi

printf '1..%d\n' "$((passes + failures))"
exit "$failures"
