// sysexec — the ONLY part of the daemon that executes commands, opens pipes, spawns threads, or
// reboots the device. Every host-effecting primitive funnels through this one interface for two
// reasons:
//
//   1. Security: the entire shell-exec + privilege surface is in one small, auditable file. Callers
//      must still validate/whitelist arguments (see util.h validators) — sysexec is the mechanism,
//      not the policy — but "everything that can exec" lives here and nowhere else.
//   2. Testability: the fuzz and unit-test builds link sysexec_stub.c instead of sysexec.c, so the
//      real parsing/dispatch/validator code runs unmodified while a valid REBOOT/RELOAD/WATCH can't
//      exec a command or spawn a looping thread on the build host — no per-call macro stubbing.
//
// The production implementation (sysexec.c) keeps shell execution restricted to audited,
// argument-free constant scripts. Request-derived values must use the argv functions below.
#ifndef HAPANELD_SYSEXEC_H
#define HAPANELD_SYSEXEC_H

#include <stddef.h>

// Run an audited, argument-free constant shell program, blocking until it exits. Never pass request,
// profile, environment, filesystem, or network-derived bytes. Returns the raw wait status.
int   sysexec_run_constant(const char *program);

// Execute an absolute path with a structural argument vector, without a shell. When quiet is true,
// stdin/stdout/stderr are connected to /dev/null. Returns the raw child wait status.
int   sysexec_run_argv(const char *path, const char *const argv[], int quiet);

// Execute an absolute path without a shell and capture bounded stdout. Output is always NUL
// terminated when capacity is non-zero. Returns the raw child wait status, or -1 on I/O/fork error.
int   sysexec_capture_argv(const char *path, const char *const argv[], char *output, size_t capacity);

// Execute an absolute path without a shell and copy stdout to an already-open descriptor. The child
// receives /dev/null on stdin and stderr. Returns the raw child wait status, or -1 on I/O/fork error.
int   sysexec_stream_argv(const char *path, const char *const argv[], int output_fd);

// Spawn a detached background thread running fn(arg). Returns 0 on success, non-zero on failure.
int   sysexec_spawn(void *(*fn)(void *), void *arg);

// Block this thread for [ms] milliseconds. Interruptions are absorbed so the caller gets the whole
// interval. Isolated here with the other host primitives so a policy that waits for a host-level
// effect (see sysctl.c's reboot escalation) stays deterministic and instant under the test stub.
void  sysexec_sleep_ms(unsigned ms);

#endif
