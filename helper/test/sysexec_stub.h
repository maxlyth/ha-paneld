#ifndef HAPANELD_SYSEXEC_STUB_H
#define HAPANELD_SYSEXEC_STUB_H

// Deterministic failure/output controls for host tests. Rules match a substring of the command.
void sysexec_stub_reset(void);
void sysexec_stub_fail_run(const char *needle, int status);
void sysexec_stub_add_popen(const char *needle, const char *output, int close_status);

#endif
