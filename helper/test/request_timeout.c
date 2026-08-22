#define _GNU_SOURCE

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define SOCK_NAME "hapaneld-helper-request-timeout-test"
#define REQUEST_TIMEOUT_MS 150
#define main hapaneld_helper_main_for_request_timeout_test
#include "../src/main.c"
#undef main

/* main.c's daemon entry point references these modules; this test only calls request_daemon(). */
void input_init(void) {}
void gpio_init(void) {}
void screen_init(void) {}
void led_init(void) {}
int input_watch(const char *path, int grab) { (void)path; (void)grab; return 0; }
void server_serve(int fd) { (void)fd; }
void input_unsubscribe(int fd) { (void)fd; }
void gpio_unsubscribe(int fd) { (void)fd; }
void conn_release(void) {}
int conn_admit(void) { return 0; }
int guard_maintenance_init(void) { return 0; }
int guard_maintenance_replacement_safe(void) { return 0; }
int guard_maintenance_replacement_parent_grant(char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_export_lease(void) { return -1; }
int guard_maintenance_replacement_parent_abort(const char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_stage_app(const char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_supervisor_adopt_app(const char nonce[65]) {
    (void)nonce; return -1;
}
int guard_maintenance_replacement_worker_commit_app(const char nonce[65]) {
    (void)nonce; return -1;
}
int guard_maintenance_replacement_startup_reconcile_app(char nonce[65]) {
    (void)nonce; return 0;
}
void guard_maintenance_set_supervised(int supervised) { (void)supervised; }
void guard_maintenance_set_supervisor_owner(void) {}
int guard_maintenance_supervisor_tick(void) { return 0; }
int guard_maintenance_supervisor_work_deadline(enum guard_supervisor_work work,
        uint64_t *deadline_ms) { (void)work; (void)deadline_ms; return -1; }
int guard_maintenance_supervisor_start_work(enum guard_supervisor_work work, pid_t *pid) {
    (void)work; (void)pid; return -1;
}
int guard_maintenance_supervisor_complete(enum guard_supervisor_work work,
        enum guard_execution_result result, int status) {
    (void)work; (void)result; (void)status; return -1;
}
int sysexec_poll_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }
int sysexec_terminate_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }

#define CHECK(condition, message) do { \
    if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); return 1; } \
} while (0)

static long long elapsed_millis(struct timespec start, struct timespec end) {
    return (long long)(end.tv_sec - start.tv_sec) * 1000LL +
           (end.tv_nsec - start.tv_nsec) / 1000000LL;
}

static int create_listener(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un address;
    memset(&address, 0, sizeof address);
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    socklen_t length = offsetof(struct sockaddr_un, sun_path) + 1 + (sizeof SOCK_NAME - 1);
    if (bind(fd, (struct sockaddr *)&address, length) != 0 || listen(fd, 1) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

int main(void) {
    CHECK(supervisor_executor_deadline(100, 1000000) == 60100,
        "supervisor executor applies the fixed command cap");
    CHECK(supervisor_executor_deadline(100, 60099) == 60099,
        "persisted phase deadline tightens the command cap");
    CHECK(supervisor_executor_deadline(100, 100) == 100,
        "phase-deadline equality cannot receive fresh executor time");
    CHECK(supervisor_executor_deadline(LLONG_MAX - 10, UINT64_MAX) == LLONG_MAX - 10,
        "clock overflow fails closed without extending executor authority");
    CHECK(supervisor_executor_deadline(-1, 1000) == -1,
        "invalid monotonic time cannot create executor authority");

    int listener = create_listener();
    CHECK(listener >= 0, "partial-reply test listener must bind");

    pid_t server = fork();
    CHECK(server >= 0, "partial-reply test server must fork");
    if (server == 0) {
        int client = accept(listener, NULL, NULL);
        if (client < 0) _exit(2);
        char request[64];
        if (read(client, request, sizeof request) <= 0) _exit(3);
        if (write(client, "P", 1) != 1) _exit(4);
        sleep(2);
        close(client);
        _exit(0);
    }

    struct timespec start;
    struct timespec end;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &start) == 0, "start time must be readable");
    int result = request_daemon("PING");
    CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0, "end time must be readable");
    long long elapsed_ms = elapsed_millis(start, end);

    kill(server, SIGKILL);
    int status;
    CHECK(waitpid(server, &status, 0) == server, "partial-reply server must be reaped");
    close(listener);

    CHECK(result == 1, "a partial reply that stalls must fail");
    CHECK(elapsed_ms >= 75, "request must wait for its bounded reply deadline");
    CHECK(elapsed_ms < 1000, "partial reply must not leave the request blocked");
    puts("request timeout tests passed");
    return 0;
}
