#!/usr/bin/env bash
# Focused contracts for the F-Droid build and R2 publisher.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/fdroid.yml"
PUBLISHER="$ROOT/tools/fdroid/publish-r2.sh"
PREFLIGHT="$ROOT/tools/fdroid/public_origin_preflight.py"
PUBLIC_USER_AGENT_FILE="$ROOT/tools/fdroid/public-user-agent.txt"
REAL_CURL="$(command -v curl)"
REAL_TIMEOUT="$(command -v timeout)"
TMP="$(mktemp -d)"
retry_server_pid=
cleanup() {
  if [ -n "$retry_server_pid" ]; then
    kill "$retry_server_pid" 2>/dev/null || true
    wait "$retry_server_pid" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

passes=0
failures=0
pass() { passes=$((passes + 1)); printf 'ok %d - %s\n' "$passes" "$1"; }
fail_test() { failures=$((failures + 1)); printf 'not ok - %s\n' "$1" >&2; }

if bash -n "$PUBLISHER"; then
  pass "R2 publisher shell is syntactically valid"
else
  fail_test "R2 publisher shell is syntactically valid"
fi

if python3 -m py_compile "$PREFLIGHT"; then
  pass "public-origin preflight Python is syntactically valid"
else
  fail_test "public-origin preflight Python is syntactically valid"
fi

cat > "$TMP/retry-server.py" <<'PY'
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys

count_path = Path(sys.argv[1])
port_path = Path(sys.argv[2])

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        with count_path.open("a", encoding="ascii") as handle:
            handle.write("attempt\n")
        self.send_response(503)
        self.end_headers()

    def log_message(self, *_args):
        pass

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
port_path.write_text(str(server.server_port), encoding="ascii")
server.serve_forever()
PY
python3 "$TMP/retry-server.py" "$TMP/retry-attempts" "$TMP/retry-port" &
retry_server_pid=$!
for _ in $(seq 1 100); do
  [ -s "$TMP/retry-port" ] && break
  kill -0 "$retry_server_pid" 2>/dev/null || break
  sleep 0.01
done
retry_port="$(cat "$TMP/retry-port" 2>/dev/null || true)"
retry_started="$(date +%s)"
if [ -n "$retry_port" ]; then
  "$REAL_TIMEOUT" --foreground --signal=KILL 2s \
    "$REAL_CURL" --retry 20 --retry-all-errors --retry-delay 1 \
      --retry-max-time 20 --max-time 1 --fail --silent \
      "http://127.0.0.1:$retry_port/always-503" >/dev/null 2>&1
  retry_status=$?
else
  retry_status=1
fi
retry_elapsed=$(($(date +%s) - retry_started))
retry_attempts="$(wc -l < "$TMP/retry-attempts" 2>/dev/null | tr -d ' ' || true)"
kill "$retry_server_pid" 2>/dev/null || true
wait "$retry_server_pid" 2>/dev/null || true
retry_server_pid=
if [ "$retry_status" -eq 137 ] && [ "$retry_elapsed" -le 4 ] &&
   [ "${retry_attempts:-0}" -ge 1 ] && [ "${retry_attempts:-0}" -lt 21 ]; then
  pass "an outer watchdog enforces a hard operation bound across curl retries"
else
  fail_test "an outer watchdog enforces a hard operation bound across curl retries"
fi

expected_public_user_agent='ha-paneld-fdroid-public-verifier/1 (+https://github.com/maxlyth/ha-paneld)'
if [ "$(wc -l < "$PUBLIC_USER_AGENT_FILE" | tr -d ' ')" -eq 1 ] &&
   [ "$(< "$PUBLIC_USER_AGENT_FILE")" = "$expected_public_user_agent" ] &&
   grep -Fq 'PUBLIC_USER_AGENT = _load_public_user_agent()' "$PREFLIGHT" &&
   grep -Fq -- '--user-agent "$PUBLIC_USER_AGENT"' "$PUBLISHER"; then
  pass "preflight and publisher share one honest public User-Agent contract"
else
  fail_test "preflight and publisher share one honest public User-Agent contract"
fi

bad_user_agent_dir="$TMP/bad-user-agent"
mkdir -p "$bad_user_agent_dir"
cp "$PUBLISHER" "$bad_user_agent_dir/publish-r2.sh"
printf 'ha-paneld-fdroid-public-verifier/1\0 (+https://github.com/maxlyth/ha-paneld)\n' \
  > "$bad_user_agent_dir/public-user-agent.txt"
if ! bash "$bad_user_agent_dir/publish-r2.sh" > "$TMP/bad-user-agent.log" 2>&1 &&
   grep -Fq 'must be one bounded printable ASCII line' "$TMP/bad-user-agent.log"; then
  pass "publisher rejects a NUL-injected User-Agent file before Bash captures it"
else
  fail_test "publisher rejects a NUL-injected User-Agent file before Bash captures it"
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

preflight_job="$(awk '/^  preflight:$/ { in_job=1 } /^  build:$/ { exit } in_job' "$WORKFLOW")"
build_job="$(awk '/^  build:$/ { in_job=1 } /^  deploy:$/ { exit } in_job' "$WORKFLOW")"
deploy_job="$(awk '/^  deploy:$/ { in_job=1 } in_job' "$WORKFLOW")"
if ! grep -Eq 'FDROID_R2_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)|AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)' <<<"$build_job" &&
   grep -Fq "(github.event_name != 'workflow_dispatch' || inputs.publish_r2)" <<<"$deploy_job" &&
   grep -Fq "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)" <<<"$deploy_job"; then
  pass "R2 credentials are confined to a default-branch-only optional deploy job"
else
  fail_test "R2 credentials are confined to a default-branch-only optional deploy job"
fi

if grep -Fq 'run: python3 tools/fdroid/public_origin_preflight.py' <<<"$preflight_job" &&
   ! grep -Eq '^[[:space:]]+environment:|secrets\.|FDROID_R2_|AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)|GH_TOKEN|GITHUB_TOKEN' <<<"$preflight_job" &&
   grep -Fq 'needs: preflight' <<<"$build_job" &&
   grep -Fq 'needs: build' <<<"$deploy_job"; then
  pass "credential-free public-origin preflight gates signing, build and R2 publication"
else
  fail_test "credential-free public-origin preflight gates signing, build and R2 publication"
fi

deploy_preflight_line="$(grep -nF 'name: Preflight public F-Droid origin on publication runner' <<<"$deploy_job" | cut -d: -f1)"
download_line="$(grep -nF 'uses: actions/download-artifact@' <<<"$deploy_job" | cut -d: -f1)"
credential_guard_line="$(grep -nF 'name: Guard — R2 deployment configuration present' <<<"$deploy_job" | cut -d: -f1)"
publish_line="$(grep -nF 'name: Publish and verify F-Droid repository' <<<"$deploy_job" | cut -d: -f1)"
deploy_preflight_step="$({
  found=false
  while IFS= read -r line; do
    if [[ "$line" == *'name: Preflight public F-Droid origin on publication runner'* ]]; then
      found=true
    elif [ "$found" = true ] && [[ "$line" == '      - '* ]]; then
      break
    fi
    [ "$found" = false ] || printf '%s\n' "$line"
  done <<<"$deploy_job"
})"
if [ "$(grep -Fc 'run: python3 tools/fdroid/public_origin_preflight.py' "$WORKFLOW")" -eq 2 ] &&
   [ -n "$deploy_preflight_line" ] && [ "$deploy_preflight_line" -lt "$download_line" ] &&
   [ "$download_line" -lt "$credential_guard_line" ] && [ "$credential_guard_line" -lt "$publish_line" ] &&
   ! grep -Eq 'env:|secrets\.|FDROID_R2_|AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)|GH_TOKEN|GITHUB_TOKEN' <<<"$deploy_preflight_step"; then
  pass "actual publication runner is probed without credentials before artifacts and R2 access"
else
  fail_test "actual publication runner is probed without credentials before artifacts and R2 access"
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
headers= body= url= range=false user_agent= max_filesize= max_time= retry_max_time= retry_count= write_out=
while [ "$#" -gt 0 ]; do
  case "$1" in
    -D|--dump-header) headers="$2"; shift 2 ;;
    -o|--output) body="$2"; shift 2 ;;
    --range) range=true; shift 2 ;;
    --user-agent) user_agent="$2"; shift 2 ;;
    --max-filesize) max_filesize="$2"; shift 2 ;;
    --max-time) max_time="$2"; shift 2 ;;
    --retry-max-time) retry_max_time="$2"; shift 2 ;;
    --retry) retry_count="$2"; shift 2 ;;
    --write-out) write_out="$2"; shift 2 ;;
    --connect-timeout) shift 2 ;;
    --retry-all-errors|--fail-with-body|--silent|--show-error) shift ;;
    http*) url="$1"; shift ;;
    *) shift ;;
  esac
done
key="${url#https://fdroid.ha-paneld.com/}"
printf '%s\n' "$key" >> "$MOCK_R2_STATE/public-requests.log"
printf '%s|%s|%s|%s|%s|%s|%s|%s\n' \
  "$key" "$user_agent" "$max_filesize" "$max_time" "$retry_max_time" \
  "${MOCK_TIMEOUT_LIMIT:-}" "$retry_count" "$range" \
  >> "$MOCK_R2_STATE/public-request-contracts.log"
if [ "${MOCK_PUBLIC_TRANSPORT_FAIL_KEY:-}" = "$key" ]; then
  : > "$headers"
  : > "$body"
  [ "$write_out" = '%{http_code}' ] && printf '000'
  exit 7
fi
if [ "${MOCK_PUBLIC_STALE_THEN_TRANSPORT_KEY:-}" = "$key" ]; then
  printf 'HTTP/2 503\r\ncontent-type: text/html\r\ncache-control: private\r\ncf-ray: stale-terminal-LHR\r\nretry-after: 999\r\n\r\n' > "$headers"
  printf 'stale response body\n' > "$body"
  [ "$write_out" = '%{http_code}' ] && printf '000'
  exit 28
fi
if [ "${MOCK_PUBLIC_FAIL_KEY:-}" = "$key" ]; then
  long_ray_suffix="$(head -c 300 /dev/zero | tr '\0' x)"
  printf 'HTTP/2 503\r\ncontent-type: text/html\r\nretry-after: 999\r\ncf-ray: stale-retry-LHR\r\n\r\nHTTP/2 403\r\ncontent-type: text/plain; charset=UTF-8\r\ncontent-length: 17\r\ncache-control: private, no-store\r\nserver: cloudflare\r\ncf-cache-status: DYNAMIC\r\ncf-mitigated: challenge\r\ncf-ray: mock\033-LHR%sray-hidden-suffix\r\nset-cookie: must-not-be-logged\r\nx-private-detail: must-not-be-logged\r\n\r\n' \
    "$long_ray_suffix" > "$headers"
  printf 'error code: 1010\n' > "$body"
  [ "$write_out" = '%{http_code}' ] && printf '403'
  exit 22
fi
[ -f "$MOCK_R2_STATE/objects/$key" ] || exit 22
IFS='|' read -r sha size cache type < "$MOCK_R2_STATE/meta/$key"
if [ "$range" = true ]; then
  range_status=206
  range_bytes=1
  [ "${MOCK_PUBLIC_RANGE_STATUS_KEY:-}" != "$key" ] || range_status=200
  [ "${MOCK_PUBLIC_RANGE_BYTES_KEY:-}" != "$key" ] || range_bytes=2
  printf 'HTTP/2 %s\r\ncache-control: %s\r\ncontent-type: %s\r\ncontent-range: bytes 0-%s/%s\r\n\r\n' \
    "$range_status" "$cache" "$type" "$((range_bytes - 1))" "$size" > "$headers"
  head -c "$range_bytes" "$MOCK_R2_STATE/objects/$key" > "$body"
  [ "$write_out" = '%{http_code}' ] && printf '%s' "$range_status"
else
  response_cache="$cache"
  response_type="$type"
  [ "${MOCK_PUBLIC_BAD_CACHE_KEY:-}" != "$key" ] || response_cache='public, max-age=60'
  [ "${MOCK_PUBLIC_BAD_TYPE_KEY:-}" != "$key" ] || response_type='text/plain'
  : > "$headers"
  if [ "${MOCK_PUBLIC_RETRY_KEY:-}" = "$key" ]; then
    retry_cache="$cache"
    retry_type="$type"
    if [ "${MOCK_PUBLIC_RETRY_BAD_HEADERS:-false}" = true ]; then
      retry_cache='public, max-age=60'
      retry_type='text/plain'
    fi
    printf 'HTTP/2 503\r\ncache-control: %s\r\ncontent-type: %s\r\n\r\n' \
      "$retry_cache" "$retry_type" >> "$headers"
    printf '%s\n' "$key" >> "$MOCK_R2_STATE/retry-header-responses.log"
  fi
  printf 'HTTP/2 200\r\n' >> "$headers"
  if [ "${MOCK_PUBLIC_MISSING_CACHE_KEY:-}" != "$key" ]; then
    printf 'cache-control: %s\r\n' "$response_cache" >> "$headers"
  fi
  if [ "${MOCK_PUBLIC_MISSING_TYPE_KEY:-}" != "$key" ]; then
    printf 'content-type: %s\r\n' "$response_type" >> "$headers"
  fi
  printf '\r\n' >> "$headers"
  cp "$MOCK_R2_STATE/objects/$key" "$body"
  if [ "${MOCK_PUBLIC_CORRUPT_KEY:-}" = "$key" ]; then
    printf 'corrupt' >> "$body"
  fi
  [ "$write_out" = '%{http_code}' ] && printf '200'
fi
CURL

cat > "$mock_bin/timeout" <<'TIMEOUT'
#!/usr/bin/env bash
set -eu
limit=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --foreground) shift ;;
    --signal=KILL) shift ;;
    *s) limit="${1%s}"; shift; break ;;
    *) printf 'unexpected timeout option: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[ -n "$limit" ] || { printf 'missing timeout limit\n' >&2; exit 2; }
MOCK_TIMEOUT_LIMIT="$limit" exec "$@"
TIMEOUT
chmod +x "$mock_bin/aws" "$mock_bin/curl" "$mock_bin/timeout"

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

if [ -s "$state/public-request-contracts.log" ] &&
   awk -F '|' -v expected="$expected_public_user_agent" \
     '$2 != expected || $3 !~ /^[0-9]+$/ || $4 != 120 || $5 != 180 || $6 != 305 || $7 != 5 { exit 1 }' \
     "$state/public-request-contracts.log" &&
   grep -Fq "|$expected_public_user_agent|4096|120|180|305|5|false" "$state/public-request-contracts.log" &&
   grep -Fq "|$expected_public_user_agent|4096|120|180|305|5|true" "$state/public-request-contracts.log"; then
  pass "full and range verification use the shared identity and bounded retry operation limits"
else
  fail_test "full and range verification use the shared identity and bounded retry operation limits"
fi

if ! MOCK_PUBLIC_CORRUPT_KEY=fdroid/repo/index-v2.json \
     run_publisher > "$TMP/public-hash-failure.log" 2>&1 &&
   grep -Fq 'Public F-Droid object does not match the publication: fdroid/repo/index-v2.json' \
     "$TMP/public-hash-failure.log"; then
  pass "publisher still rejects a public object with the wrong hash"
else
  fail_test "publisher still rejects a public object with the wrong hash"
fi

if ! MOCK_PUBLIC_BAD_CACHE_KEY=fdroid/repo/index-v2.json \
     run_publisher > "$TMP/public-cache-failure.log" 2>&1 &&
   grep -Fq 'Public F-Droid object has the wrong cache policy: fdroid/repo/index-v2.json' \
     "$TMP/public-cache-failure.log"; then
  pass "publisher still rejects a public object with the wrong cache policy"
else
  fail_test "publisher still rejects a public object with the wrong cache policy"
fi

if ! MOCK_PUBLIC_BAD_TYPE_KEY=fdroid/repo/index-v2.json \
     run_publisher > "$TMP/public-type-failure.log" 2>&1 &&
   grep -Fq 'Public F-Droid object has the wrong content type: fdroid/repo/index-v2.json' \
     "$TMP/public-type-failure.log"; then
  pass "publisher still rejects a public object with the wrong content type"
else
  fail_test "publisher still rejects a public object with the wrong content type"
fi

if ! MOCK_PUBLIC_RANGE_STATUS_KEY=fdroid/repo/ha-paneld-v1.1.0.apk \
     run_publisher > "$TMP/public-range-status-failure.log" 2>&1 &&
   grep -Fq 'Public F-Droid APK does not support byte ranges' \
     "$TMP/public-range-status-failure.log"; then
  pass "publisher still requires HTTP 206 for APK byte ranges"
else
  fail_test "publisher still requires HTTP 206 for APK byte ranges"
fi

if ! MOCK_PUBLIC_RANGE_BYTES_KEY=fdroid/repo/ha-paneld-v1.1.0.apk \
     run_publisher > "$TMP/public-range-body-failure.log" 2>&1 &&
   grep -Fq 'Public F-Droid APK returned an invalid byte range' \
     "$TMP/public-range-body-failure.log"; then
  pass "publisher still requires a one-byte APK range response"
else
  fail_test "publisher still requires a one-byte APK range response"
fi

printf 'changed apk bytes\n' > "$site/fdroid/repo/ha-paneld-v1.0.0.apk"
if ! run_publisher > "$TMP/mismatch.log" 2>&1 &&
   grep -Fq 'Refusing to replace immutable F-Droid object' "$TMP/mismatch.log"; then
  pass "publisher refuses to replace an existing versioned APK with different bytes"
else
  fail_test "publisher refuses to replace an existing versioned APK with different bytes"
fi

printf 'apk bytes\n' > "$site/fdroid/repo/ha-paneld-v1.0.0.apk"
if MOCK_PUBLIC_RETRY_KEY=fdroid/repo/index-v2.json MOCK_PUBLIC_RETRY_BAD_HEADERS=true \
     run_publisher > "$TMP/public-final-headers-valid.log" 2>&1 &&
   grep -Fq 'F-Droid R2 publication verified at' "$TMP/public-final-headers-valid.log" &&
   [ "$(grep -Fxc 'fdroid/repo/index-v2.json' "$state/retry-header-responses.log")" -eq 1 ]; then
  pass "publisher accepts valid final headers after a retry with invalid headers"
else
  sed -n '1,120p' "$TMP/public-final-headers-valid.log" >&2
  fail_test "publisher accepts valid final headers after a retry with invalid headers"
fi

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
for final_header_case in bad-cache missing-cache bad-type missing-type; do
  case "$final_header_case" in
    bad-cache) failure_variable=MOCK_PUBLIC_BAD_CACHE_KEY; expected_failure='wrong cache policy' ;;
    missing-cache) failure_variable=MOCK_PUBLIC_MISSING_CACHE_KEY; expected_failure='wrong cache policy' ;;
    bad-type) failure_variable=MOCK_PUBLIC_BAD_TYPE_KEY; expected_failure='wrong content type' ;;
    missing-type) failure_variable=MOCK_PUBLIC_MISSING_TYPE_KEY; expected_failure='wrong content type' ;;
  esac
  header_log="$TMP/public-final-${final_header_case}.log"
  : > "$state/retry-header-responses.log"
  export "$failure_variable=fdroid/repo/index-v2.json"
  MOCK_PUBLIC_RETRY_KEY=fdroid/repo/index-v2.json run_publisher > "$header_log" 2>&1
  header_status=$?
  unset "$failure_variable"
  if [ "$header_status" -eq 1 ] &&
     grep -Fq "Public F-Droid object has the $expected_failure: fdroid/repo/index-v2.json" "$header_log" &&
     grep -Fq 'restoring the previous mutable repository state' "$header_log" &&
     ! grep -Fq 'F-Droid R2 publication verified at' "$header_log" &&
     [ "$(grep -Fxc 'fdroid/repo/index-v2.json' "$state/retry-header-responses.log")" -eq 1 ] &&
     [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
     [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ]; then
    pass "publisher rejects final $final_header_case after valid retry headers and restores prior state"
  else
    sed -n '1,120p' "$header_log" >&2
    fail_test "publisher rejects final $final_header_case after valid retry headers and restores prior state"
  fi
done

if ! MOCK_PUBLIC_TRANSPORT_FAIL_KEY=fdroid/repo/index-v2.json \
     run_publisher > "$TMP/transport-000-rollback.log" 2>&1 &&
   grep -Fq 'restoring the previous mutable repository state' "$TMP/transport-000-rollback.log" &&
   grep -Fq 'key=fdroid/repo/index-v2.json status=transport_error curl_status=7' \
     "$TMP/transport-000-rollback.log" &&
   ! grep -Fq 'status=000' "$TMP/transport-000-rollback.log" &&
   ! grep -Fq 'F-Droid public verification header:' "$TMP/transport-000-rollback.log" &&
   ! grep -Fq 'F-Droid public verification body:' "$TMP/transport-000-rollback.log" &&
   [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ]; then
  pass "publisher classifies curl status 000 as a transport error without response metadata"
else
  sed -n '1,160p' "$TMP/transport-000-rollback.log" >&2
  fail_test "publisher classifies curl status 000 as a transport error without response metadata"
fi

if ! MOCK_PUBLIC_STALE_THEN_TRANSPORT_KEY=fdroid/repo/index-v2.json \
     run_publisher > "$TMP/stale-then-transport-rollback.log" 2>&1 &&
   grep -Fq 'restoring the previous mutable repository state' "$TMP/stale-then-transport-rollback.log" &&
   grep -Fq 'key=fdroid/repo/index-v2.json status=transport_error curl_status=28' \
     "$TMP/stale-then-transport-rollback.log" &&
   ! grep -Fq 'status=000' "$TMP/stale-then-transport-rollback.log" &&
   ! grep -Fq 'stale-terminal-LHR' "$TMP/stale-then-transport-rollback.log" &&
   ! grep -Fq 'retry-after=999' "$TMP/stale-then-transport-rollback.log" &&
   ! grep -Fq 'F-Droid public verification header:' "$TMP/stale-then-transport-rollback.log" &&
   ! grep -Fq 'F-Droid public verification body:' "$TMP/stale-then-transport-rollback.log" &&
   [ "$(sha256sum "$state/objects/fdroid/repo/entry.jar" | awk '{print $1}')" = "$old_entry_sha" ] &&
   [ "$(sha256sum "$state/objects/fdroid/repo/index-v2.json" | awk '{print $1}')" = "$old_index_sha" ]; then
  pass "a headerless terminal transport failure cannot retain an earlier retry response"
else
  sed -n '1,180p' "$TMP/stale-then-transport-rollback.log" >&2
  fail_test "a headerless terminal transport failure cannot retain an earlier retry response"
fi

blocked_body_sha="$(printf 'error code: 1010\n' | sha256sum | awk '{print $1}')"
if ! MOCK_PUBLIC_FAIL_KEY=fdroid/repo/index-v2.json run_publisher > "$TMP/verification-rollback.log" 2>&1 &&
   grep -Fq 'restoring the previous mutable repository state' "$TMP/verification-rollback.log" &&
   grep -Fq 'key=fdroid/repo/index-v2.json status=403 curl_status=22' "$TMP/verification-rollback.log" &&
   grep -Fq 'content-type=text/plain; charset=UTF-8' "$TMP/verification-rollback.log" &&
   grep -Fq 'cache-control=private, no-store' "$TMP/verification-rollback.log" &&
   grep -Fq 'cf-cache-status=DYNAMIC' "$TMP/verification-rollback.log" &&
   grep -Fq 'cf-mitigated=challenge' "$TMP/verification-rollback.log" &&
   grep -Fq 'cf-ray=mock?-LHR' "$TMP/verification-rollback.log" &&
   grep -Fq "body: bytes=17 sha256=$blocked_body_sha" "$TMP/verification-rollback.log" &&
   grep -Fq 'body: class=cloudflare_error_1010' "$TMP/verification-rollback.log" &&
   ! grep -Fq 'stale-retry-LHR' "$TMP/verification-rollback.log" &&
   ! grep -Fq 'retry-after=999' "$TMP/verification-rollback.log" &&
   ! grep -Fq 'must-not-be-logged' "$TMP/verification-rollback.log" &&
   ! grep -Fq 'ray-hidden-suffix' "$TMP/verification-rollback.log" &&
   ! grep -Fq 'error code: 1010' "$TMP/verification-rollback.log" &&
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
