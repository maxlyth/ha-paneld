#!/usr/bin/env bash
#
# Build ha-paneld in a hermetic container — no local JDK / Android SDK / NDK / CMake required,
# and no access to this repo's CI. Only Docker is needed.
#
#   ./tools/build/build.sh                       # debug APK -> app/build/outputs/apk/debug/
#   ./tools/build/build.sh :app:assembleRelease  # any Gradle task(s) instead
#
# The toolchain lives in the image (built once, then cached). Gradle's caches persist in a named
# Docker volume (ha-paneld-gradle) so repeat builds are fast.
#
# HOST_WORKDIR override: Docker bind-mount paths are resolved by the daemon, not this shell. If you
# run from inside a container that talks to an outer/host daemon (docker-in-docker, a remote
# DOCKER_HOST, some WSL setups), set HOST_WORKDIR to the repo's path *as the daemon sees it*:
#   HOST_WORKDIR=/host/path/to/ha-paneld ./tools/build/build.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

IMAGE="ha-paneld-build:latest"
HOST_WORK="${HOST_WORKDIR:-$REPO_ROOT}"
GRADLE_TASKS=("${@:-:app:assembleDebug}")

echo ">> Building toolchain image ($IMAGE) if missing/changed…"
docker build -t "$IMAGE" tools/build

echo ">> Running: ./gradlew ${GRADLE_TASKS[*]}"
docker run --rm \
  -v "$HOST_WORK":/work \
  -v ha-paneld-gradle:/root/.gradle \
  -w /work "$IMAGE" \
  ./gradlew "${GRADLE_TASKS[@]}" --no-daemon --stacktrace

echo ">> Done. APK(s):"
find app/build/outputs/apk -name '*.apk' 2>/dev/null || true
