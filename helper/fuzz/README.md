# hapaneld-ledd parser fuzzing

A small harness that fuzzes the daemon's command parser (`serve()` / `handle()` in `../ledd.c`) for
memory safety against hostile or malformed input on the socket.

## Run

```bash
./helper/fuzz/run.sh            # 1,000,000 random iters + corpus + length-boundary cases
./helper/fuzz/run.sh 5000000    # longer run
```

Only `gcc` is needed (host toolchain; no clang/NDK). A clean run ends with `FUZZ OK` and exit 0;
any overflow / out-of-bounds / undefined behaviour aborts with an ASan/UBSan report.

## What it does

`fuzz_ledd.c` compiles the **real `ledd.c`** (so it can't drift from the daemon) and macro-stubs only
the calls with host side effects — `system`/`popen` and `pthread_create`/`pthread_detach` — so a valid
`REBOOT`/`RELOAD`/`WATCH` can't exec a command or spawn a looping thread on the build host. Everything
that matters for memory safety runs for real under ASan+UBSan:

- the bounded line accumulator in `serve()` (split reads, overlong-line drop, `MAX_LINE` boundary),
- the prefix dispatch and every argument `sscanf` in `handle()`,
- the `snprintf` shell-command builders and the `valid_pkg`/`valid_num`/`valid_gov` validators.

Inputs: a hand-written adversarial corpus (oversized args, embedded NULs, partial lines, CRLF mixes,
`%d` integer-overflow values, shell metacharacters), lines sized at `MAX_LINE ± 1` / 64 KB for every
verb, then millions of random byte streams (biased to start with real verbs so deep paths are hit).
The RNG seed is fixed, so runs are reproducible.

## Scope

This fuzzes the **socket attack surface** — bytes an attacker controls. It does **not** test:

- **Peer authentication** (`SO_PEERCRED` uid gate) — a separate, kernel-enforced control.
- The `/proc` parsers in `PERFDUMP`, which read *trusted* kernel files, not attacker input.
- Concurrency / the `MAX_CONN` cap under parallel load.

## The one expected note

`run.sh` disables LeakSanitizer. Because the harness stubs `pthread_create`, `watch_node()`'s
per-node `calloc` — which the real daemon hands to a lifetime evdev thread (deduped, capped at
`MAX_WATCH`) — has no thread to own it here and would report as a leak. It is a harness artifact, not
a daemon leak.
