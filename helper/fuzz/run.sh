#!/usr/bin/env bash
#
# Build + run the hapaneld-helper parser fuzz harness under AddressSanitizer + UndefinedBehaviorSanitizer.
#
#   ./helper/fuzz/run.sh [iterations]      # default 1,000,000 random iters (+ corpus + boundary cases)
#
# It links the REAL daemon modules (helper/src/*.c — the Makefile's CORE_SRCS, the exact set the
# daemon ships) with test/sysexec_stub.c, so the parsing/dispatch/validator code runs unmodified while
# nothing execs or spawns on the host. A clean run prints "FUZZ OK" and exits 0; any memory-safety or
# UB issue aborts with a sanitizer report. Only the host toolchain (gcc) is required — no clang/NDK.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="$(cd "$HERE/.." && pwd)"
OUT="$HERE/build"
mkdir -p "$OUT"

ITERS="${1:-1000000}"

# Reuse the single source-of-truth module list from the Makefile so the fuzzer can't drift from the
# daemon (add a module to CORE_SRCS and it's covered here automatically).
CORE_SRCS="$(cd "$HELPER" && make -s print-core)"

echo ">> compiling fuzz_parser (real src/*.c + sysexec stub, ASan+UBSan)…"
( cd "$HELPER" && \
  gcc -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all -Wno-format-truncation \
      -Isrc $CORE_SRCS test/sysexec_stub.c "$HERE/fuzz_parser.c" -lpthread -o "$OUT/fuzz_parser" )

echo ">> running $ITERS iterations…"
ASAN_OPTIONS=abort_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  "$OUT/fuzz_parser" "$ITERS"

echo "FUZZ OK"
