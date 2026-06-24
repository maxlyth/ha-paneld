#!/usr/bin/env bash
#
# Build + run the hapaneld-ledd parser fuzz harness under AddressSanitizer + UndefinedBehaviorSanitizer.
#
#   ./helper/fuzz/run.sh [iterations]      # default 1,000,000 random iters (+ corpus + boundary cases)
#
# A clean run prints "FUZZ OK" and exits 0. Any memory-safety or UB issue aborts with a sanitizer
# report. Only the host toolchain (gcc) is required — no clang/NDK.
#
# LeakSanitizer is disabled on purpose: the harness stubs pthread_create, so watch_node()'s per-node
# calloc (which the real daemon hands to a lifetime evdev thread, capped at MAX_WATCH) shows up as a
# leak that exists only because no thread runs here. It is not a daemon leak. See README.md.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="$(cd "$HERE/.." && pwd)"
OUT="$HERE/build"
mkdir -p "$OUT"

ITERS="${1:-1000000}"

echo ">> compiling fuzz_ledd (real ledd.c, ASan+UBSan)…"
gcc -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all \
    -I "$HELPER" "$HERE/fuzz_ledd.c" -lpthread -o "$OUT/fuzz_ledd"

echo ">> running $ITERS iterations…"
ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  "$OUT/fuzz_ledd" "$ITERS"

echo "FUZZ OK"
