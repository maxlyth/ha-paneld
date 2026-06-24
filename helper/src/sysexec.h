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
// The production implementation (sysexec.c) is a thin wrapper over system()/popen()/pthread.
#ifndef HAPANELD_SYSEXEC_H
#define HAPANELD_SYSEXEC_H

#include <stdio.h>

// Run a shell command, blocking until it exits. Returns its raw status (0 typically = success).
int   sysexec_run(const char *cmd);

// Run a shell command and return a read pipe of its stdout (NULL on failure — callers handle NULL).
FILE *sysexec_popen_r(const char *cmd);
int   sysexec_pclose(FILE *p);

// Spawn a detached background thread running fn(arg). Returns 0 on success, non-zero on failure.
int   sysexec_spawn(void *(*fn)(void *), void *arg);

// Reboot the panel (svc power reboot, falling back to reboot(8)).
void  sysexec_reboot(void);

#endif
