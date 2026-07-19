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
