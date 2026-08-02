#!/usr/bin/env bash
set -euo pipefail

: "${DAT:?DAT is required}"
: "${BRANCH:?BRANCH is required}"
: "${REPO_URL:?REPO_URL is required}"
: "${COMMIT_MESSAGE:?COMMIT_MESSAGE is required}"

firmware_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
temp_root=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
prepared_dat=$(mktemp "$temp_root/shelly-prepared.XXXXXX.dat")
latest_main_dat=$(mktemp "$temp_root/shelly-main.XXXXXX.dat")
trap 'rm -f "$prepared_dat" "$latest_main_dat"' EXIT

cp "$DAT" "$prepared_dat"
git restore --source=HEAD -- "$DAT"

# Reconcile against main again after network archival, which can take minutes.
git fetch -q "$REPO_URL" +refs/heads/main:refs/remotes/shelly/main
git show refs/remotes/shelly/main:"$DAT" > "$latest_main_dat"
python3 "$firmware_dir/shelly_firmware.py" merge \
  --dat "$latest_main_dat" \
  --pending "$prepared_dat"
cp "$latest_main_dat" "$prepared_dat"

if git ls-remote --exit-code --heads "$REPO_URL" "$BRANCH" >/dev/null 2>&1; then
  git fetch -q "$REPO_URL" "+refs/heads/$BRANCH:refs/remotes/shelly/update"
  git checkout -q -B "$BRANCH" refs/remotes/shelly/update
  if ! git merge --no-commit --no-ff refs/remotes/shelly/main; then
    conflicts=$(git diff --name-only --diff-filter=U)
    if [ "$conflicts" != "$DAT" ]; then
      echo "Cannot update $BRANCH because merging main conflicts outside $DAT: $conflicts" >&2
      exit 1
    fi
    cp "$prepared_dat" "$DAT"
    git add "$DAT"
  fi
  if git rev-parse -q --verify MERGE_HEAD >/dev/null; then
    git commit -q -m "chore: sync Shelly firmware update branch with main [skip ci]"
  fi
else
  git checkout -q -B "$BRANCH" refs/remotes/shelly/main
fi

cp "$prepared_dat" "$DAT"
git add "$DAT"
if git diff --cached --quiet; then
  echo "nothing new to commit onto $BRANCH"
else
  git commit -q -m "$COMMIT_MESSAGE"
fi
git push -q "$REPO_URL" "HEAD:refs/heads/$BRANCH"
