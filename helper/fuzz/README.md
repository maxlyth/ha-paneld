# hapaneld-helper sanitizer smoke harness

This harness feeds malformed and adversarial bytes through the daemon's real command parser and handlers under AddressSanitizer, UndefinedBehaviorSanitizer, and LeakSanitizer. It is a reproducible developer smoke test, not independent security validation, a coverage-guided fuzzer, or a semantic correctness oracle.

## Run

```bash
./helper/fuzz/run.sh            # corpus + length boundaries + 100,000 deterministic random inputs
./helper/fuzz/run.sh 1000000    # optional longer smoke run
make -C helper fuzz             # same thing, via the Makefile
```

Only `gcc` is needed. A clean run ends with `SANITIZER SMOKE OK` and exit 0; a sanitizer-visible overflow, out-of-bounds access, undefined behaviour, or leak aborts with a diagnostic. A clean run does not prove that every path was reached or that accepted commands have the correct semantics.

## What it does

`fuzz_parser.c` links the production capability, parser, and transport modules with `test/sysexec_stub.c`. The stub replaces real command execution, pipes, thread spawning, and reboot at the link boundary. `src/commands.def` supplies both the live dispatch table and the random-input verb bias, preventing a command from being wired into production while remaining absent from that bias.

- The bounded line accumulator in `server_serve()`, including split reads, overlong-line dropping, and `MAX_LINE` boundaries.
- Exact-match dispatch, argument parsing, command construction, and validators reached by the supplied inputs.
- A hand-written adversarial corpus, deterministic cases around parser length boundaries, and fixed-seed pseudo-random byte streams biased toward every live command verb.

The fixed seed makes regressions reproducible but also limits exploration: repeated runs with the same iteration count exercise the same inputs. Iteration count is therefore a runtime parameter, not an assurance score.

## Scope

The harness does not test:

- Peer authentication, the accept loop, process lifecycle, or the production `sysexec` implementation.
- Semantic correctness of replies, command ordering, resource ownership, or failure propagation; deterministic unit and integration tests own those assertions.
- Concurrency, connection admission, idle timeouts, or races between handlers.
- Coverage-guided mutation, corpus evolution, coverage thresholds, independent implementation, or third-party review.

The stub makes host-effecting calls succeed or fail according to test rules, which can hide behaviours that depend on the real operating system. Those behaviours require native unit tests, Android integration tests, hardware exercises, or independent review rather than stronger claims about this harness.
