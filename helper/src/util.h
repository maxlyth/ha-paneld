// Small dependency-free helpers shared across the daemon: byte clamping, sysfs/file IO primitives,
// the socket reply writer, and the input validators that defend the shell-out command builders.
// Nothing here execs or spawns — that surface lives in sysexec.c, so this file stays pure and is
// unit-testable host-native.
#ifndef HAPANELD_UTIL_H
#define HAPANELD_UTIL_H

#include <stddef.h>

int  clamp(int v);                                  // clamp to 0..255

// Write a NUL-terminated string to a (sysfs) node. Returns 0 on success, -1 on any failure.
int  write_node(const char *path, const char *val);

// Write a NUL-terminated string to a socket fd (errors ignored — a dead peer is the caller's worry).
void reply(int fd, const char *s);

// First line of [path] into [dst] (NUL-terminated, newline stripped). dst[0]='\0' on failure.
void first_line(const char *path, char *dst, size_t dstsz);

// Copy the whole contents of [path] to fd [out] (best-effort; silent on open failure).
void cat_to(int out, const char *path);

// Argument validators — every value passed to a sysexec_run() shell string must clear one of these.
int  valid_pkg(const char *s);        // Android package: [A-Za-z0-9._]+
int  valid_component(const char *s);  // pkg/class component: package chars plus '/'
int  valid_num(const char *s);        // non-empty decimal digits only
int  valid_decimal(const char *s);    // decimal with at most one dot, >=1 digit (e.g. "1.15")
int  valid_gov(const char *s);        // CPU governor name: [a-z0-9_]+

#endif
