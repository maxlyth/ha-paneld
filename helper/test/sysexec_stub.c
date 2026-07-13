// Stub sysexec for the fuzz + unit-test builds: the real parser/dispatch/validator code links and
// runs unmodified, but nothing here execs a command, opens a pipe, spawns a thread, or reboots the
// host. This is what lets a valid REBOOT/RELOAD/WATCH/GOV run through the real handlers on the build
// machine with no effect — replacing the per-call macro stubbing the fuzz harness used to need.
#include "sysexec.h"
#include "sysexec_stub.h"

#include <string.h>

#define MAX_POPEN_RULES 8
#define MAX_OPEN_PIPES  8

typedef struct {
    char needle[128];
    char output[1024];
    int close_status;
} popen_rule;

typedef struct {
    FILE *pipe;
    int close_status;
} open_pipe;

static char run_fail_needle[128];
static int run_fail_status;
static popen_rule popen_rules[MAX_POPEN_RULES];
static int popen_rule_count;
static open_pipe open_pipes[MAX_OPEN_PIPES];

void sysexec_stub_reset(void) {
    for (int i = 0; i < MAX_OPEN_PIPES; i++) {
        if (open_pipes[i].pipe) fclose(open_pipes[i].pipe);
    }
    memset(run_fail_needle, 0, sizeof run_fail_needle);
    run_fail_status = 0;
    memset(popen_rules, 0, sizeof popen_rules);
    popen_rule_count = 0;
    memset(open_pipes, 0, sizeof open_pipes);
}

void sysexec_stub_fail_run(const char *needle, int status) {
    snprintf(run_fail_needle, sizeof run_fail_needle, "%s", needle);
    run_fail_status = status;
}

void sysexec_stub_add_popen(const char *needle, const char *output, int close_status) {
    if (popen_rule_count >= MAX_POPEN_RULES) return;
    popen_rule *rule = &popen_rules[popen_rule_count++];
    snprintf(rule->needle, sizeof rule->needle, "%s", needle);
    snprintf(rule->output, sizeof rule->output, "%s", output);
    rule->close_status = close_status;
}

int sysexec_run(const char *cmd) {
    if (run_fail_needle[0] && strstr(cmd, run_fail_needle)) return run_fail_status;
    return 0;
}

FILE *sysexec_popen_r(const char *cmd) {
    const popen_rule *rule = NULL;
    for (int i = 0; i < popen_rule_count; i++) {
        if (strstr(cmd, popen_rules[i].needle)) { rule = &popen_rules[i]; break; }
    }
    if (!rule) return NULL;

    FILE *pipe = tmpfile();
    if (!pipe) return NULL;
    if (fputs(rule->output, pipe) == EOF || fflush(pipe) != 0) { fclose(pipe); return NULL; }
    rewind(pipe);
    for (int i = 0; i < MAX_OPEN_PIPES; i++) {
        if (!open_pipes[i].pipe) {
            open_pipes[i].pipe = pipe;
            open_pipes[i].close_status = rule->close_status;
            return pipe;
        }
    }
    fclose(pipe);
    return NULL;
}

int sysexec_pclose(FILE *p) {
    for (int i = 0; i < MAX_OPEN_PIPES; i++) {
        if (open_pipes[i].pipe == p) {
            int status = open_pipes[i].close_status;
            fclose(p);
            open_pipes[i].pipe = NULL;
            return status;
        }
    }
    return -1;
}

// Return failure so input_watch() frees its per-node arg itself (the real daemon hands it to a
// lifetime evdev thread). No thread is spawned here, and nothing leaks — so the fuzz build can run
// with LeakSanitizer ON.
int   sysexec_spawn(void *(*fn)(void *), void *arg) { (void)fn; (void)arg; return -1; }

void  sysexec_reboot(void)                { }
