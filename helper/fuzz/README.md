# hapaneld-helper parser fuzzing

A small harness that fuzzes the daemon's command parser (`server_serve()` / `dispatch()` in `../src/`) for memory safety against hostile or malformed input on the socket.

## Run

```bash
./helper/fuzz/run.sh            # 1,000,000 random iters + corpus + length-boundary cases
./helper/fuzz/run.sh 5000000    # longer run
make -C helper fuzz             # same thing, via the Makefile
```

Only `gcc` is needed (host toolchain; no clang/NDK). A clean run ends with `FUZZ OK` and exit 0; any overflow / out-of-bounds / undefined behaviour / leak aborts with an ASan/UBSan/LSan report.

## What it does

`fuzz_parser.c` **links the real daemon modules** together with `test/sysexec_stub.c`. The modules are `helper/src/*.c` via the Makefile's `CORE_SRCS` — the exact set the binary ships, so the fuzzer can't drift from the daemon. The stub is the trick: every host-effecting call (`system`/`popen`/thread-spawn/`reboot`) is funnelled through `sysexec.c` in the daemon, so swapping one object neutralises all of them **at the link layer** — no per-call macro stubbing. A valid `REBOOT`/`RELOAD`/`WATCH` runs through the real handler and does nothing on the host. Everything that matters for memory safety runs for real under ASan+UBSan+LSan:

- the bounded line accumulator in `server_serve()` (split reads, overlong-line drop, `MAX_LINE` boundary),
- the verb split + exact-match dispatch in `dispatch()` and every argument `sscanf` in the handlers,
- the `snprintf` shell-command builders and the `valid_pkg`/`valid_num`/`valid_decimal`/`valid_gov`/`valid_component`/`is_critical_pkg` validators.

Inputs: a hand-written adversarial corpus (oversized args, embedded NULs, partial lines, CRLF mixes, `%d` integer-overflow values, shell metacharacters), lines sized at `MAX_LINE ± 1` / 64 KB for every verb, then millions of random byte streams (biased to start with real verbs so deep paths are hit). The RNG seed is fixed, so runs are reproducible.

## Scope

This fuzzes the **socket attack surface** — bytes an attacker controls. It does **not** test:

- **Peer authentication** (`SO_PEERCRED` uid gate) — a separate, kernel-enforced control.
- The `/proc` parsers in `PERFDUMP`, which read *trusted* kernel files, not attacker input. (The `stat_jiffies` parser does have dedicated unit tests — `make -C helper test`.)
- Concurrency / the `MAX_CONN` cap under parallel load.

LeakSanitizer is **on**: the stub's `sysexec_spawn` returns failure, so `input_watch()` frees its per-node allocation itself instead of handing it to a (never-spawned) thread — no harness leak. You will see a few `sysexec_spawn: …` lines on stderr from that expected stub failure; they're benign.
