// Stub sysexec for the fuzz + unit-test builds: the real parser/dispatch/validator code links and
// runs unmodified, but nothing here execs a command, opens a pipe, spawns a thread, or reboots the
// host. This is what lets a valid REBOOT/RELOAD/WATCH/GOV run through the real handlers on the build
// machine with no effect — replacing the per-call macro stubbing the fuzz harness used to need.
#include "sysexec.h"
#include "sysexec_stub.h"

#include <pthread.h>
#include <string.h>

#define MAX_POPEN_RULES 8
#define MAX_OPEN_PIPES  8
#define MAX_RUN_HISTORY 32

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
static char run_block_needle[128];
static int run_blocked;
static int run_released;
static pthread_mutex_t run_block_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t run_block_cond = PTHREAD_COND_INITIALIZER;
static char run_history[MAX_RUN_HISTORY][600];
static int run_history_count;
static popen_rule popen_rules[MAX_POPEN_RULES];
static int popen_rule_count;
static open_pipe open_pipes[MAX_OPEN_PIPES];
static int spawn_status = -1;

void sysexec_stub_reset(void) {
    for (int i = 0; i < MAX_OPEN_PIPES; i++) {
        if (open_pipes[i].pipe) fclose(open_pipes[i].pipe);
    }
    memset(run_fail_needle, 0, sizeof run_fail_needle);
    run_fail_status = 0;
    pthread_mutex_lock(&run_block_lock);
    memset(run_block_needle, 0, sizeof run_block_needle);
    run_blocked = 0;
    run_released = 0;
    memset(run_history, 0, sizeof run_history);
    run_history_count = 0;
    pthread_mutex_unlock(&run_block_lock);
    memset(popen_rules, 0, sizeof popen_rules);
    popen_rule_count = 0;
    memset(open_pipes, 0, sizeof open_pipes);
    spawn_status = -1;
}

void sysexec_stub_set_spawn_result(int status) { spawn_status = status; }

void sysexec_stub_block_run(const char *needle) {
    pthread_mutex_lock(&run_block_lock);
    snprintf(run_block_needle, sizeof run_block_needle, "%s", needle);
    run_blocked = 0;
    run_released = 0;
    pthread_mutex_unlock(&run_block_lock);
}

void sysexec_stub_wait_blocked(void) {
    pthread_mutex_lock(&run_block_lock);
    while (!run_blocked) pthread_cond_wait(&run_block_cond, &run_block_lock);
    pthread_mutex_unlock(&run_block_lock);
}

void sysexec_stub_release_run(void) {
    pthread_mutex_lock(&run_block_lock);
    run_released = 1;
    pthread_cond_broadcast(&run_block_cond);
    pthread_mutex_unlock(&run_block_lock);
}

void sysexec_stub_fail_run(const char *needle, int status) {
    pthread_mutex_lock(&run_block_lock);
    snprintf(run_fail_needle, sizeof run_fail_needle, "%s", needle);
    run_fail_status = status;
    pthread_mutex_unlock(&run_block_lock);
}

int sysexec_stub_count_run(const char *needle) {
    int count = 0;
    pthread_mutex_lock(&run_block_lock);
    for (int i = 0; i < run_history_count; i++)
        if (strstr(run_history[i], needle)) count++;
    pthread_mutex_unlock(&run_block_lock);
    return count;
}

void sysexec_stub_add_popen(const char *needle, const char *output, int close_status) {
    if (popen_rule_count >= MAX_POPEN_RULES) return;
    popen_rule *rule = &popen_rules[popen_rule_count++];
    snprintf(rule->needle, sizeof rule->needle, "%s", needle);
    snprintf(rule->output, sizeof rule->output, "%s", output);
    rule->close_status = close_status;
}

int sysexec_run(const char *cmd) {
    pthread_mutex_lock(&run_block_lock);
    if (run_history_count < MAX_RUN_HISTORY)
        snprintf(run_history[run_history_count++], sizeof run_history[0], "%s", cmd);
    if (run_fail_needle[0] && strstr(cmd, run_fail_needle)) {
        int status = run_fail_status;
        pthread_mutex_unlock(&run_block_lock);
        return status;
    }
    if (run_block_needle[0] && strstr(cmd, run_block_needle)) {
        run_blocked = 1;
        pthread_cond_broadcast(&run_block_cond);
        while (!run_released) pthread_cond_wait(&run_block_cond, &run_block_lock);
    }
    pthread_mutex_unlock(&run_block_lock);
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

// No thread is spawned on the host. input.c keeps watcher state in a bounded static registry, so a
// configured success is safe for unit tests and the default failure stays leak-free for fuzzing.
int sysexec_spawn(void *(*fn)(void *), void *arg) { (void)fn; (void)arg; return spawn_status; }

void  sysexec_reboot(void)                { }
