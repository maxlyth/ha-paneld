#ifndef HAPANELD_SYSEXEC_STUB_H
#define HAPANELD_SYSEXEC_STUB_H

// Deterministic failure/output controls for host tests. Failure rules match either a shell command
// or an argv display, while the counters keep those execution paths distinct.
void sysexec_stub_reset(void);
void sysexec_stub_fail_run(const char *needle, int status);
int  sysexec_stub_count_run(const char *needle);
int  sysexec_stub_count_argv(const char *path, const char *const argv[], int quiet);
int  sysexec_stub_count_argv_calls(void);
void sysexec_stub_block_run(const char *needle);
void sysexec_stub_wait_blocked(void);
void sysexec_stub_release_run(void);
void sysexec_stub_add_popen(const char *needle, const char *output, int close_status);
long sysexec_stub_last_pclose_offset(void);
void sysexec_stub_set_spawn_result(int status);
void sysexec_stub_set_spawn_real(int enabled);
int  sysexec_stub_count_sleep(void);
unsigned long sysexec_stub_total_sleep_ms(void);

#endif
