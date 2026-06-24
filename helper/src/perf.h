// PERFDUMP — a CPU/load/temp/gpu/process snapshot for sandboxed apps. An untrusted_app is SELinux-
// denied /proc/stat, /proc/loadavg, thermal, and other pids' stat, so it can't compute CPU/load/top
// itself; root here can. One marker-delimited snapshot is streamed (client half-closes then reads to
// EOF, like SCREENCAP); PerfReader on the app side parses it. Pure file reads — no shell, no exec.
#ifndef HAPANELD_PERF_H
#define HAPANELD_PERF_H

#include <stddef.h>
#include "cmd.h"

void cmd_perfdump(conn_ctx *ctx, const char *args);  // PERFDUMP

// Exposed for unit tests: utime(field14)+stime(field15) from a /proc/<pid>[/task/<tid>]/stat buffer;
// comm (the text in the parens) into [comm] when non-NULL. Returns -1 on a malformed buffer.
long stat_jiffies(const char *buf, char *comm, size_t commsz);

#endif
