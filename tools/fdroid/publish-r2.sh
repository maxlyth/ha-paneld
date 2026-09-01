#!/usr/bin/env bash
set -Eeuo pipefail

EXPECTED_BUCKET=ha-paneld-fdroid
FORBIDDEN_BUCKET=ha-paneld-assets
EXPECTED_ORIGIN=https://fdroid.ha-paneld.com
IMMUTABLE_CACHE='public, max-age=31536000, immutable'
MUTABLE_CACHE='no-store'

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

for command in aws curl jq sha256sum; do
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
entry_jar=
uploaded_apks=()
for file in "${all_files[@]}"; do
  key="${file#"$site_dir"/}"
  case "$key" in
    fdroid/repo/*.apk)
      apk_files+=("$file")
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
  local key destination object_status

  key="${file#"$site_dir"/}"
  destination="$rollback_dir/$key"
  if object_head "$key" >/dev/null 2>&1; then
    mkdir -p "${destination%/*}"
    aws_r2 s3 cp "s3://$EXPECTED_BUCKET/$key" "$destination" --only-show-errors --no-progress
    rollback_files+=("$destination")
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
}

rollback_publication() {
  local backup key sha failed

  failed=false
  for backup in "${rollback_files[@]}"; do
    key="${backup#"$rollback_dir"/}"
    sha="$(sha256sum "$backup" | awk '{print $1}')"
    echo "restore $key" >&2
    if ! aws_r2 s3 cp "$backup" "s3://$EXPECTED_BUCKET/$key" \
      --only-show-errors --no-progress \
      --content-type "$(content_type "$key")" \
      --cache-control "$MUTABLE_CACHE" \
      --metadata "sha256=$sha"; then
      failed=true
    fi
  done
  [ "$failed" = false ]
}

handle_failure() {
  local status="$1"
  trap - ERR INT TERM HUP
  set +e
  if [ "$rollback_active" = true ]; then
    echo "F-Droid publication failed; restoring the previous signed index set." >&2
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

verify_public_file() {
  local file="$1"
  local cache_pattern="$2"
  local key expected_sha actual_sha headers body

  key="${file#"$site_dir"/}"
  expected_sha="$(sha256sum "$file" | awk '{print $1}')"
  headers="$verify_dir/headers"
  body="$verify_dir/body"
  curl --retry 5 --retry-all-errors --connect-timeout 15 -fsS \
    -D "$headers" -o "$body" "$EXPECTED_ORIGIN/$key"
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
  local key headers body

  key="${file#"$site_dir"/}"
  headers="$verify_dir/range-headers"
  body="$verify_dir/range-body"
  curl --retry 5 --retry-all-errors --connect-timeout 15 -fsS --range 0-0 \
    -D "$headers" -o "$body" "$EXPECTED_ORIGIN/$key"
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
rollback_files=()
rollback_active=false
trap 'handle_failure $?' ERR
trap 'handle_failure 129' HUP
trap 'handle_failure 130' INT
trap 'handle_failure 143' TERM

# APK names are versioned and must never change bytes. Everything referenced by an index is present
# before any signed index is replaced. Other generated files are mutable and bypass edge caching.
for file in "${apk_files[@]}"; do
  upload_file "$file" "$IMMUTABLE_CACHE" true
  if [ "$last_upload_performed" = true ]; then
    uploaded_apks+=("$file")
  fi
done
for file in "${support_files[@]}"; do
  upload_file "$file" "$MUTABLE_CACHE" false
done

# Preserve the currently signed index set so normal command failures and termination signals can put
# it back. Newly introduced index files need no deletion: the previous entry.jar cannot reference them.
for file in "${index_files[@]}" "${entry_files[@]}" "$entry_jar"; do
  snapshot_file "$file"
done
rollback_active=true
for file in "${index_files[@]}"; do
  upload_file "$file" "$MUTABLE_CACHE" false
done
for file in "${entry_files[@]}"; do
  upload_file "$file" "$MUTABLE_CACHE" false
done

# entry.jar is the signed index-v2 discovery commit and is deliberately the final write.
upload_file "$entry_jar" "$MUTABLE_CACHE" false

for file in "${all_files[@]}"; do
  verify_object "$file"
done

verify_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/ha-paneld-fdroid-verify.XXXXXX")"
for file in "${index_files[@]}" "${entry_files[@]}" "$entry_jar"; do
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
