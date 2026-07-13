#ifndef HAPANELD_SYSEXEC_STUB_H
#define HAPANELD_SYSEXEC_STUB_H

// Deterministic failure/output controls for host tests. Rules match a substring of the command.
void sysexec_stub_reset(void);
void sysexec_stub_fail_run(const char *needle, int status);
int  sysexec_stub_count_run(const char *needle);
void sysexec_stub_block_run(const char *needle);
void sysexec_stub_wait_blocked(void);
void sysexec_stub_release_run(void);
void sysexec_stub_add_popen(const char *needle, const char *output, int close_status);
void sysexec_stub_set_spawn_result(int status);

#endif
