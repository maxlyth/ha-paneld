#!/usr/bin/env bash
set -Eeuo pipefail

EXPECTED_BUCKET=ha-paneld-fdroid
FORBIDDEN_BUCKET=ha-paneld-assets
EXPECTED_ORIGIN=https://fdroid.ha-paneld.com
IMMUTABLE_CACHE='public, max-age=31536000, immutable'
MUTABLE_CACHE='no-store'
MIN_PUBLIC_RESPONSE_LIMIT=4096
MAX_DIAGNOSTIC_HEADER_VALUE_BYTES=256
PUBLIC_REQUEST_ATTEMPT_MAX_TIME=120
PUBLIC_REQUEST_RETRY_MAX_TIME=180
PUBLIC_REQUEST_OPERATION_MAX_TIME=305
PUBLIC_USER_AGENT_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/public-user-agent.txt"

if [ ! -f "$PUBLIC_USER_AGENT_FILE" ]; then
  echo "The F-Droid public User-Agent contract could not be read." >&2
  exit 1
fi
public_user_agent_bytes="$(wc -c < "$PUBLIC_USER_AGENT_FILE" | tr -d ' ')"
public_user_agent_lines="$(wc -l < "$PUBLIC_USER_AGENT_FILE" | tr -d ' ')"
if [ "$public_user_agent_bytes" -gt 129 ] || [ "$public_user_agent_lines" -ne 1 ] ||
   ! LC_ALL=C grep -axEq \
     '^ha-paneld-fdroid-public-verifier/[1-9][0-9]* \(\+https://github\.com/maxlyth/ha-paneld\)$' \
     "$PUBLIC_USER_AGENT_FILE"; then
  echo "The F-Droid public User-Agent contract must be one bounded printable ASCII line." >&2
  exit 1
fi
IFS= read -r PUBLIC_USER_AGENT < "$PUBLIC_USER_AGENT_FILE"
if [ "$public_user_agent_bytes" -ne "$((${#PUBLIC_USER_AGENT} + 1))" ]; then
  echo "The F-Droid public User-Agent contract is malformed." >&2
  exit 1
fi

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 SITE_DIRECTORY" >&2
  exit 2
fi

site_dir="${1%/}"
if [ ! -d "$site_dir" ]; then
  echo "F-Droid site directory does not exist: $site_dir" >&2
  exit 1
fi
site_dir="$(cd "$site_dir" && pwd -P)"

: "${FDROID_R2_ENDPOINT:?FDROID_R2_ENDPOINT is required}"
: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID is required}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY is required}"

if [[ ! "$FDROID_R2_ENDPOINT" =~ ^https://[0-9a-fA-F]{32}\.r2\.cloudflarestorage\.com$ ]]; then
  echo "FDROID_R2_ENDPOINT must be the account-specific Cloudflare R2 S3 endpoint." >&2
  exit 1
fi

for command in aws curl jq sha256sum timeout; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command is unavailable: $command" >&2
    exit 1
  fi
done

required_files=(
  index.html
  fdroid/repo/entry.jar
  fdroid/repo/entry.json
  fdroid/repo/index-v1.jar
  fdroid/repo/index-v2.json
)
for key in "${required_files[@]}"; do
  if [ ! -s "$site_dir/$key" ]; then
    echo "F-Droid publication is missing required file: $key" >&2
    exit 1
  fi
done

if find "$site_dir" -type l -print -quit | grep -q .; then
  echo "F-Droid publication must contain regular files only; symlinks are refused." >&2
  exit 1
fi

mapfile -d '' all_files < <(find "$site_dir" -type f -print0 | sort -z)
if [ "${#all_files[@]}" -eq 0 ]; then
  echo "F-Droid publication is empty." >&2
  exit 1
fi

apk_files=()
support_files=()
index_files=()
entry_files=()
legacy_index_jar=
entry_jar=
uploaded_apks=()
for file in "${all_files[@]}"; do
  key="${file#"$site_dir"/}"
  case "$key" in
    fdroid/repo/*.apk)
      apk_files+=("$file")
      ;;
    fdroid/repo/index-v1.jar)
      legacy_index_jar="$file"
      ;;
    fdroid/repo/index*)
      index_files+=("$file")
      ;;
    fdroid/repo/entry.jar)
      entry_jar="$file"
      ;;
    fdroid/repo/entry.*)
      entry_files+=("$file")
      ;;
    *)
      support_files+=("$file")
      ;;
  esac
done

if [ "${#apk_files[@]}" -eq 0 ]; then
  echo "F-Droid publication contains no APKs." >&2
  exit 1
fi
if [ -z "$entry_jar" ]; then
  echo "F-Droid publication contains no signed entry.jar." >&2
  exit 1
fi
if [ -z "$legacy_index_jar" ]; then
  echo "F-Droid publication contains no signed index-v1.jar." >&2
  exit 1
fi

export AWS_DEFAULT_REGION=auto
export AWS_EC2_METADATA_DISABLED=true

aws_r2() {
  aws --no-cli-pager --region auto --endpoint-url "$FDROID_R2_ENDPOINT" "$@"
}

content_type() {
  case "$1" in
    *.apk) printf '%s\n' application/vnd.android.package-archive ;;
    *.css) printf '%s\n' 'text/css; charset=utf-8' ;;
    *.html) printf '%s\n' 'text/html; charset=utf-8' ;;
    *.jar) printf '%s\n' application/java-archive ;;
    *.json) printf '%s\n' application/json ;;
    *.png) printf '%s\n' image/png ;;
    *.svg) printf '%s\n' image/svg+xml ;;
    *.txt) printf '%s\n' 'text/plain; charset=utf-8' ;;
    *.webp) printf '%s\n' image/webp ;;
    *.xml) printf '%s\n' application/xml ;;
    *) printf '%s\n' application/octet-stream ;;
  esac
}

object_head() {
  aws_r2 s3api head-object --bucket "$EXPECTED_BUCKET" --key "$1" --output json
}

object_exists() {
  local key="$1"
  local listing jq_status
  if ! listing="$(aws_r2 s3api list-objects-v2 --bucket "$EXPECTED_BUCKET" --prefix "$key" --output json)"; then
    return 2
  fi
  jq -e --arg key "$key" 'any(.Contents[]?; .Key == $key)' <<<"$listing" >/dev/null
  jq_status=$?
  case "$jq_status" in
    0) return 0 ;;
    1) return 1 ;;
    *) return 2 ;;
  esac
}

upload_file() {
  local file="$1"
  local cache_control="$2"
  local immutable="$3"
  local key sha size head remote_sha remote_size object_status

  last_upload_performed=false
  key="${file#"$site_dir"/}"
  sha="$(sha256sum "$file" | awk '{print $1}')"
  size="$(wc -c < "$file" | tr -d ' ')"

  if head="$(object_head "$key" 2>/dev/null)"; then
    remote_sha="$(jq -r '.Metadata.sha256 // empty' <<<"$head")"
    remote_size="$(jq -r '.ContentLength // empty' <<<"$head")"
    if [ "$immutable" = true ]; then
      if [ "$remote_sha" = "$sha" ] && [ "$remote_size" = "$size" ]; then
        printf 'retain immutable %s\n' "$key"
        return
      fi
      echo "Refusing to replace immutable F-Droid object: $key" >&2
      return 1
    fi
  else
    if object_exists "$key"; then
      echo "Existing F-Droid object could not be authenticated before replacement: $key" >&2
      return 1
    else
      object_status=$?
      if [ "$object_status" -ne 1 ]; then
        echo "F-Droid object existence could not be checked safely: $key" >&2
        return 1
      fi
    fi
  fi

  printf 'publish %s\n' "$key"
  aws_r2 s3 cp "$file" "s3://$EXPECTED_BUCKET/$key" \
    --only-show-errors \
    --no-progress \
    --content-type "$(content_type "$key")" \
    --cache-control "$cache_control" \
    --metadata "sha256=$sha"
  last_upload_performed=true
}

snapshot_file() {
  local file="$1"
  local key destination head_file head object_status

  key="${file#"$site_dir"/}"
  destination="$rollback_dir/$key"
  head_file="$rollback_dir/.heads/${#rollback_keys[@]}.json"
  if head="$(object_head "$key" 2>/dev/null)"; then
    mkdir -p "${destination%/*}"
    mkdir -p "${head_file%/*}"
    aws_r2 s3 cp "s3://$EXPECTED_BUCKET/$key" "$destination" --only-show-errors --no-progress
    printf '%s\n' "$head" > "$head_file"
    rollback_states+=(present)
    rollback_backups+=("$destination")
    rollback_heads+=("$head_file")
    rollback_keys+=("$key")
    return
  fi
  if object_exists "$key"; then
    echo "Existing mutable F-Droid object could not be snapshotted: $key" >&2
    return 1
  else
    object_status=$?
    if [ "$object_status" -ne 1 ]; then
      echo "Mutable F-Droid object existence could not be checked safely: $key" >&2
      return 1
    fi
  fi
  rollback_states+=(absent)
  rollback_backups+=("")
  rollback_heads+=("")
  rollback_keys+=("$key")
}

delete_object() {
  aws_r2 s3api delete-object --bucket "$EXPECTED_BUCKET" --key "$1" >/dev/null
}

verify_absent() {
  local key="$1"
  local object_status

  object_exists "$key"
  object_status=$?
  case "$object_status" in
    0) return 1 ;;
    1) return 0 ;;
    *) return 1 ;;
  esac
}

restore_snapshot() {
  local index="$1"
  local key state backup head_file cache type sha size restored_head restored_sha restored_size

  key="${rollback_keys[$index]}"
  state="${rollback_states[$index]}"
  if [ "$state" = absent ]; then
    echo "remove $key" >&2
    if ! delete_object "$key"; then
      return 1
    fi
    if ! verify_absent "$key"; then
      return 1
    fi
    return 0
  fi

  backup="${rollback_backups[$index]}"
  head_file="${rollback_heads[$index]}"
  cache="$(jq -r '.CacheControl // empty' "$head_file")"
  type="$(jq -r '.ContentType // empty' "$head_file")"
  sha="$(jq -r '.Metadata.sha256 // empty' "$head_file")"
  [ -n "$cache" ] || cache="$MUTABLE_CACHE"
  [ -n "$type" ] || type="$(content_type "$key")"
  [ -n "$sha" ] || sha="$(sha256sum "$backup" | awk '{print $1}')"
  size="$(wc -c < "$backup" | tr -d ' ')"

  echo "restore $key" >&2
  if ! aws_r2 s3 cp "$backup" "s3://$EXPECTED_BUCKET/$key" \
      --only-show-errors --no-progress \
      --content-type "$type" \
      --cache-control "$cache" \
      --metadata "sha256=$sha"; then
    return 1
  fi
  if ! restored_head="$(object_head "$key")"; then
    return 1
  fi
  restored_sha="$(jq -r '.Metadata.sha256 // empty' <<<"$restored_head")"
  restored_size="$(jq -r '.ContentLength // empty' <<<"$restored_head")"
  [ "$restored_sha" = "$sha" ] && [ "$restored_size" = "$size" ]
}

rollback_publication() {
  local failed discovery_hidden index root_index
  local -a discovery_indexes

  failed=false
  discovery_hidden=true
  discovery_indexes=()
  for index in "${!rollback_keys[@]}"; do
    case "${rollback_keys[$index]}" in
      fdroid/repo/index-v1.jar|fdroid/repo/entry.jar)
        discovery_indexes+=("$index")
        ;;
    esac
  done

  # Both signed indexes are direct discovery roots. Prove that every root is absent before changing
  # anything it can reference; a partial hide must not start an underlying rollback.
  for root_index in "${discovery_indexes[@]}"; do
    echo "hide ${rollback_keys[$root_index]}" >&2
    if ! delete_object "${rollback_keys[$root_index]}" ||
       ! verify_absent "${rollback_keys[$root_index]}"; then
      echo "Rollback could not prove ${rollback_keys[$root_index]} is hidden." >&2
      discovery_hidden=false
    fi
  done
  if [ "$discovery_hidden" = false ]; then
    echo "Underlying objects were left unchanged because every discovery root could not be hidden." >&2
    return 1
  fi

  for index in "${!rollback_keys[@]}"; do
    case "${rollback_keys[$index]}" in
      fdroid/repo/index-v1.jar|fdroid/repo/entry.jar) continue ;;
    esac
    if ! restore_snapshot "$index"; then
      echo "Rollback action failed for ${rollback_keys[$index]}." >&2
      failed=true
    fi
  done

  # Restore the legacy root and then entry.jar only after every dependency is coherent. If either
  # root restore fails, re-hide both: a successfully restored first root is not safe on its own.
  if [ "$failed" = false ]; then
    for root_index in "${discovery_indexes[@]}"; do
      if ! restore_snapshot "$root_index"; then
        echo "Rollback action failed for ${rollback_keys[$root_index]}." >&2
        failed=true
        break
      fi
    done
  fi
  if [ "$failed" = true ]; then
    for root_index in "${discovery_indexes[@]}"; do
      echo "leave ${rollback_keys[$root_index]} hidden because rollback is incomplete" >&2
      if ! delete_object "${rollback_keys[$root_index]}" ||
         ! verify_absent "${rollback_keys[$root_index]}"; then
        echo "Rollback could not re-hide ${rollback_keys[$root_index]}." >&2
      fi
    done
  fi
  [ "$failed" = false ]
}

handle_failure() {
  local status="$1"
  trap - ERR INT TERM HUP
  set +e
  if [ "$rollback_active" = true ]; then
    echo "F-Droid publication failed; restoring the previous mutable repository state." >&2
    rollback_publication || echo "F-Droid rollback failed; the repository requires immediate repair." >&2
  fi
  rm -rf "$rollback_dir"
  if [ -n "${verify_dir:-}" ]; then
    rm -rf "$verify_dir"
  fi
  exit "$status"
}

verify_object() {
  local file="$1"
  local key sha size head remote_sha remote_size

  key="${file#"$site_dir"/}"
  sha="$(sha256sum "$file" | awk '{print $1}')"
  size="$(wc -c < "$file" | tr -d ' ')"
  head="$(object_head "$key")"
  remote_sha="$(jq -r '.Metadata.sha256 // empty' <<<"$head")"
  remote_size="$(jq -r '.ContentLength // empty' <<<"$head")"
  if [ "$remote_sha" != "$sha" ] || [ "$remote_size" != "$size" ]; then
    echo "Published F-Droid object failed S3 identity verification: $key" >&2
    return 1
  fi
}

public_response_header() {
  local headers="$1"
  local wanted="$2"

  awk -v wanted="$wanted" '
    {
      line = $0
      sub(/\r$/, "", line)
      if (line ~ /^HTTP\/[^ ]+ [0-9][0-9][0-9]([ ]|$)/) {
        found = ""
        in_response = 1
        next
      }
      if (in_response && line == "") {
        in_response = 0
        next
      }
      colon = index(line, ":")
      if (in_response && colon > 0 && tolower(substr(line, 1, colon - 1)) == wanted) {
        value = substr(line, colon + 1)
        sub(/^[ \t]+/, "", value)
        found = value
      }
    }
    END { if (found != "") print found }
  ' "$headers"
}

sanitize_public_diagnostic() {
  local value="${1:0:$MAX_DIAGNOSTIC_HEADER_VALUE_BYTES}"
  LC_ALL=C printf '%s' "$value" | tr -c ' -~' '?'
}

write_public_failure_diagnostics() {
  local key="$1"
  local http_status="$2"
  local curl_status="$3"
  local headers="$4"
  local body="$5"
  local header value body_bytes body_sha256 cloudflare_code has_http_response

  has_http_response=true
  if [[ ! "$http_status" =~ ^[1-5][0-9]{2}$ ]]; then
    http_status=transport_error
    has_http_response=false
  fi
  printf 'F-Droid public verification failed: key=%s status=%s curl_status=%s\n' \
    "$key" "$http_status" "$curl_status" >&2
  if [ "$has_http_response" = true ] && [ -f "$headers" ]; then
    for header in content-type content-length cache-control server cf-cache-status cf-mitigated cf-ray retry-after; do
      value="$(public_response_header "$headers" "$header")"
      if [ -n "$value" ]; then
        printf 'F-Droid public verification header: %s=%s\n' \
          "$header" "$(sanitize_public_diagnostic "$value")" >&2
      fi
    done
  fi

  if [ "$has_http_response" = false ]; then
    return
  elif [ -f "$body" ]; then
    body_bytes="$(wc -c < "$body" | tr -d ' ')"
    body_sha256="$(sha256sum "$body" | awk '{print $1}')"
  else
    body_bytes=0
    body_sha256="$(printf '' | sha256sum | awk '{print $1}')"
  fi
  printf 'F-Droid public verification body: bytes=%s sha256=%s\n' \
    "$body_bytes" "$body_sha256" >&2

  if [ -f "$body" ] && [ "$body_bytes" -le 64 ]; then
    cloudflare_code="$(
      LC_ALL=C sed -nE 's/^error code: ([0-9]{3,6})\r?$/\1/p' "$body" | head -n 1
    )"
    if [[ "$cloudflare_code" =~ ^[0-9]{3,6}$ ]]; then
      printf 'F-Droid public verification body: class=cloudflare_error_%s\n' \
        "$cloudflare_code" >&2
    fi
  fi
}

public_curl() {
  local headers="$1"
  local body="$2"
  local max_body_bytes="$3"
  shift 3

  timeout --foreground --signal=KILL "${PUBLIC_REQUEST_OPERATION_MAX_TIME}s" \
    curl --retry 5 --retry-all-errors --retry-max-time "$PUBLIC_REQUEST_RETRY_MAX_TIME" \
      --connect-timeout 15 --max-time "$PUBLIC_REQUEST_ATTEMPT_MAX_TIME" \
      --fail-with-body --silent --show-error \
      --user-agent "$PUBLIC_USER_AGENT" --max-filesize "$max_body_bytes" \
      --dump-header "$headers" --output "$body" --write-out '%{http_code}' "$@"
}

verify_public_file() {
  local file="$1"
  local cache_pattern="$2"
  local key expected_sha expected_size max_body_bytes actual_sha headers body http_status curl_status

  key="${file#"$site_dir"/}"
  expected_sha="$(sha256sum "$file" | awk '{print $1}')"
  expected_size="$(wc -c < "$file" | tr -d ' ')"
  max_body_bytes="$expected_size"
  if [ "$max_body_bytes" -lt "$MIN_PUBLIC_RESPONSE_LIMIT" ]; then
    max_body_bytes="$MIN_PUBLIC_RESPONSE_LIMIT"
  fi
  headers="$verify_dir/headers"
  body="$verify_dir/body"
  if http_status="$(
    public_curl "$headers" "$body" "$max_body_bytes" "$EXPECTED_ORIGIN/$key"
  )"; then
    :
  else
    curl_status=$?
    write_public_failure_diagnostics "$key" "$http_status" "$curl_status" "$headers" "$body"
    return "$curl_status"
  fi
  actual_sha="$(sha256sum "$body" | awk '{print $1}')"
  if [ "$actual_sha" != "$expected_sha" ]; then
    echo "Public F-Droid object does not match the publication: $key" >&2
    return 1
  fi
  tr -d '\r' < "$headers" | grep -Eiq "^cache-control:.*$cache_pattern" || {
    echo "Public F-Droid object has the wrong cache policy: $key" >&2
    return 1
  }
  tr -d '\r' < "$headers" | grep -Fiq "content-type: $(content_type "$key")" || {
    echo "Public F-Droid object has the wrong content type: $key" >&2
    return 1
  }
}

verify_public_range() {
  local file="$1"
  local key headers body http_status curl_status

  key="${file#"$site_dir"/}"
  headers="$verify_dir/range-headers"
  body="$verify_dir/range-body"
  if http_status="$(
    public_curl "$headers" "$body" "$MIN_PUBLIC_RESPONSE_LIMIT" \
      --range 0-0 "$EXPECTED_ORIGIN/$key"
  )"; then
    :
  else
    curl_status=$?
    write_public_failure_diagnostics "$key" "$http_status" "$curl_status" "$headers" "$body"
    return "$curl_status"
  fi
  tr -d '\r' < "$headers" | grep -Eq '^HTTP/[^ ]+ 206($| )' || {
    echo "Public F-Droid APK does not support byte ranges: $key" >&2
    return 1
  }
  if [ "$(wc -c < "$body" | tr -d ' ')" != 1 ]; then
    echo "Public F-Droid APK returned an invalid byte range: $key" >&2
    return 1
  fi
}

aws_r2 s3api head-bucket --bucket "$EXPECTED_BUCKET" >/dev/null
if forbidden_output="$(aws_r2 s3api head-bucket --bucket "$FORBIDDEN_BUCKET" 2>&1)"; then
  echo "F-Droid deployment credentials unexpectedly access another project bucket." >&2
  exit 1
elif ! grep -Eiq 'AccessDenied|Forbidden|403' <<<"$forbidden_output"; then
  echo "F-Droid deployment credential scope could not be verified safely." >&2
  exit 1
fi

rollback_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/ha-paneld-fdroid-rollback.XXXXXX")"
rollback_keys=()
rollback_states=()
rollback_backups=()
rollback_heads=()
rollback_active=false
trap 'handle_failure $?' ERR
trap 'handle_failure 129' HUP
trap 'handle_failure 130' INT
trap 'handle_failure 143' TERM

# APK names are versioned and must never change bytes. Everything referenced by an index is present
# before any mutable object is replaced. APKs uploaded by a failed run are safe to retain because no
# restored or absent signed discovery roots can discover them.
for file in "${apk_files[@]}"; do
  upload_file "$file" "$IMMUTABLE_CACHE" true
  if [ "$last_upload_performed" = true ]; then
    uploaded_apks+=("$file")
  fi
done

# Snapshot every mutable object before the first mutable write. A missing key is a real rollback
# state: if this publication fails, any object newly created at that key must be deleted again.
for file in "${support_files[@]}" "${index_files[@]}" "${entry_files[@]}" "$legacy_index_jar" "$entry_jar"; do
  snapshot_file "$file"
done
rollback_active=true

# Generated support files are mutable too. They are part of the same transaction as the signed index
# set so a failed changed publication cannot leave a new landing page, icon or status file behind.
for file in "${support_files[@]}"; do
  upload_file "$file" "$MUTABLE_CACHE" false
done

for file in "${index_files[@]}"; do
  upload_file "$file" "$MUTABLE_CACHE" false
done
for file in "${entry_files[@]}"; do
  upload_file "$file" "$MUTABLE_CACHE" false
done

# Publish both signed discovery roots only after their dependencies. entry.jar is the index-v2
# discovery commit and remains the final write.
upload_file "$legacy_index_jar" "$MUTABLE_CACHE" false
upload_file "$entry_jar" "$MUTABLE_CACHE" false

for file in "${all_files[@]}"; do
  verify_object "$file"
done

verify_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/ha-paneld-fdroid-verify.XXXXXX")"
for file in "${index_files[@]}" "${entry_files[@]}" "$legacy_index_jar" "$entry_jar"; do
  verify_public_file "$file" 'no-store'
done
if [ "${#uploaded_apks[@]}" -eq 0 ]; then
  uploaded_apks+=("${apk_files[-1]}")
fi
for file in "${uploaded_apks[@]}"; do
  verify_public_file "$file" 'max-age=31536000.*immutable'
  verify_public_range "$file"
done
verify_public_file "$site_dir/index.html" 'no-store'

rollback_active=false
trap - ERR INT TERM HUP
rm -rf "$rollback_dir" "$verify_dir"

printf 'F-Droid R2 publication verified at %s/fdroid/repo\n' "$EXPECTED_ORIGIN"
