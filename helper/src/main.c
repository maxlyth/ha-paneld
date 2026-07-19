// hapaneld-helper — a tiny root helper that exposes a whitelisted control surface to ha-paneld over an
// authenticated UNIX socket, for capabilities a sandboxed Android app can't reach itself: the RGB LED
// (root-only sysfs or an app-denied ioctl), screen-backlight power, hardware-button/GPIO instrumentation,
// display density / CPU governor / screencap / perf snapshots, and app reload/start/reboot.
//
// It began as an LED-only helper (the former `hapaneld-ledd` name) but is now the general control
// path for sandbox-walled panels, and was renamed `hapaneld-helper` to match. Its code is split by
// capability under helper/src/ — see helper/README.md. (Migration: a freshly installed daemon must
// tear down any old `hapaneld-ledd` binary + init service — install-daemon.sh does this.)
//
// Security model (this file owns the transport + auth; see each module for its own validation):
//   * Transport is an ABSTRACT-namespace UNIX socket (SOCK_NAME), so peer credentials are available
//     via SO_PEERCRED. Every connection is authenticated: we accept ONLY ha-paneld's own uid (resolved
//     at runtime from /data/data/<pkg>, which changes per reinstall), plus root. ADB operators can
//     still connect through a root shell; accepting Android's generic shell uid would let any app with
//     Shizuku access cross the documented shell-only boundary into this root daemon.
//   * Concurrent connections are capped (MAX_CONN) and idle non-subscribers time out (server.c), so a
//     connection flood can't exhaust the thread-per-conn model.
//   * The command parser is bounded and exact-match (dispatch.c / server.c); every verb's arguments
//     are width-bounded and validated. Request values reach only structural argv/direct operations;
//     the sole fixed shell program has no caller-controlled bytes.
//
// Protocol: see helper/README.md and the dispatch table in dispatch.c.

#define _GNU_SOURCE             // struct ucred / SO_PEERCRED
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "led.h"
#include "screen.h"
#include "input.h"
#include "gpio.h"
#include "server.h"
#include "version.h"

// Abstract-namespace UNIX socket name (leading NUL added at bind time). Must match the app's
// LocalSocketAddress("hapaneld-helper", ABSTRACT) in HelperClient/EvdevButtonClient.
#ifndef SOCK_NAME
#define SOCK_NAME  "hapaneld-helper"
#endif
#ifndef REQUEST_TIMEOUT_MS
#define REQUEST_TIMEOUT_MS 3000
#endif
// ha-paneld's data dir — we stat() it to learn the app's uid (it changes on every reinstall).
#define APP_DATA   "/data/data/io.github.maxlyth.hapaneld"
// MAX_CONN (the concurrent-connection cap) + the conn_admit/release/active gate live in server.[ch]
// so the cap is unit-testable without this accept loop.

// --- peer authentication --------------------------------------------------------------------------
// ha-paneld's uid changes on every (re)install, so resolve it live by stat'ing its data dir rather
// than hardcoding it. Resolve the directory on every connection and fail closed when it is absent:
// this daemon can outlive an app uninstall, and Android may later reuse the former numeric app uid.

static int uid_allowed(uid_t uid) {
    if (uid == 0) return 1;
    struct stat st;
    if (stat(APP_DATA, &st) != 0) return 0;                 // absent pre-install/after uninstall
    return uid == st.st_uid;
}

static int set_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFD, 0);
    return flags >= 0 && fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0 ? 0 : -1;
}

// Installer/provisioner probe mode. It is intentionally restricted to read-only identity verbs and
// is meant to be invoked through the panel's root path, so the daemon still authenticates uid 0 via
// SO_PEERCRED. Never broaden the daemon policy to Android's generic shell uid for adb forwarding.
static int probe_command_allowed(const char *command) {
    return strcmp(command, "PING") == 0 ||
           strcmp(command, "COMPANIONCAPS") == 0 ||
           strcmp(command, "BUILDID") == 0;
}

static long long monotonic_millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (long long)now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
}

static int wait_for_socket(int fd, short events, long long deadline_ms) {
    for (;;) {
        long long now_ms = monotonic_millis();
        if (now_ms < 0 || now_ms >= deadline_ms) {
            errno = ETIMEDOUT;
            return -1;
        }
        long long remaining_ms = deadline_ms - now_ms;
        int timeout_ms = remaining_ms > INT_MAX ? INT_MAX : (int)remaining_ms;
        struct pollfd pfd = { .fd = fd, .events = events };
        int ready = poll(&pfd, 1, timeout_ms);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) {
            if (ready == 0) errno = ETIMEDOUT;
            return -1;
        }
        if (pfd.revents & events) return 0;
        errno = (pfd.revents & POLLNVAL) ? EBADF : EIO;
        return -1;
    }
}

static int request_daemon(const char *command) {
    if (!probe_command_allowed(command)) return 2;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return 1;
    if (set_cloexec(fd) != 0) { close(fd); return 1; }
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        close(fd);
        return 1;
    }
    long long now_ms = monotonic_millis();
    if (now_ms < 0 || now_ms > LLONG_MAX - REQUEST_TIMEOUT_MS) {
        close(fd);
        return 1;
    }
    long long deadline_ms = now_ms + REQUEST_TIMEOUT_MS;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    socklen_t alen = offsetof(struct sockaddr_un, sun_path) + 1 + (sizeof SOCK_NAME - 1);
    if (connect(fd, (struct sockaddr *)&addr, alen) < 0) {
        if ((errno != EINPROGRESS && errno != EINTR && errno != EAGAIN) ||
                wait_for_socket(fd, POLLOUT, deadline_ms) != 0) {
            close(fd);
            return 1;
        }
        int socket_error = 0;
        socklen_t error_len = sizeof socket_error;
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &error_len) != 0 ||
                socket_error != 0) {
            if (socket_error != 0) errno = socket_error;
            close(fd);
            return 1;
        }
    }

    char request[64];
    int request_len = snprintf(request, sizeof request, "%s\n", command);
    if (request_len <= 0 || (size_t)request_len >= sizeof request) { close(fd); return 1; }
    size_t sent = 0;
    while (sent < (size_t)request_len) {
        ssize_t n = send(fd, request + sent, (size_t)request_len - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (wait_for_socket(fd, POLLOUT, deadline_ms) == 0) continue;
            close(fd);
            return 1;
        }
        if (n <= 0) { close(fd); return 1; }
        sent += (size_t)n;
    }

    char reply[256];
    size_t used = 0;
    while (used + 1 < sizeof reply) {
        ssize_t n = read(fd, reply + used, 1);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (wait_for_socket(fd, POLLIN, deadline_ms) == 0) continue;
            close(fd);
            return 1;
        }
        if (n <= 0) break;
        if (reply[used++] == '\n') break;
    }
    close(fd);
    if (used == 0 || reply[used - 1] != '\n') return 1;
    reply[used] = '\0';
    if (fputs(reply, stdout) == EOF || fflush(stdout) != 0) return 1;
    return 0;
}

// One thread per connection, so a long-lived async stream doesn't block other commands. Removes the
// fd from both independent subscriber registries on disconnect.
static void *conn_thread(void *arg) {
    int cfd = *(int *)arg;
    free(arg);
    server_serve(cfd);
    input_unsubscribe(cfd);
    gpio_unsubscribe(cfd);
    close(cfd);
    conn_release();
    return NULL;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts(helper_identity());
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "--request") == 0) {
        return request_daemon(argv[2]);
    }

    signal(SIGPIPE, SIG_IGN);   // a dead subscriber's socket must not kill the daemon
    input_init();
    gpio_init();
    screen_init();
    led_init();

    // Optional startup watches from args (per-device .rc may pass them): --grab/--watch <node>. The
    // app also sets these via the WATCH command, so args are not required.
    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], "--grab") == 0) input_watch(argv[++i], 1);
        else if (strcmp(argv[i], "--watch") == 0) input_watch(argv[++i], 0);
    }

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) { perror("socket"); return 1; }
    if (set_cloexec(sfd) != 0) { perror("fcntl"); close(sfd); return 1; }

    // Abstract-namespace UNIX socket: leading NUL in sun_path, name follows. No filesystem entry to
    // create, label (SELinux), permission, or clean up — and it's released automatically on close.
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    socklen_t alen = offsetof(struct sockaddr_un, sun_path) + 1 + (sizeof SOCK_NAME - 1);

    if (bind(sfd, (struct sockaddr *)&addr, alen) < 0) { perror("bind"); return 1; }
    if (listen(sfd, MAX_CONN) < 0) { perror("listen"); return 1; }
    fprintf(stderr, "hapaneld-helper listening on abstract unix socket @%s\n", SOCK_NAME);

    for (;;) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) { if (errno == EINTR) continue; perror("accept"); break; }
        if (set_cloexec(cfd) != 0) { close(cfd); continue; }

        // Authenticate the peer by uid — only possible because this is a UNIX socket. Reject (and
        // close) anything that isn't ha-paneld / root before it can issue a single command.
        struct ucred cred; socklen_t cl = sizeof cred;
        if (getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cred, &cl) < 0 || !uid_allowed(cred.uid)) {
            close(cfd); continue;
        }

        // Cap concurrent connections so a connection flood can't exhaust the thread-per-conn model.
        if (!conn_admit()) { close(cfd); continue; }

        int *p = malloc(sizeof(int));
        if (!p) { close(cfd); conn_release(); continue; }
        *p = cfd;
        pthread_t t;
        if (pthread_create(&t, NULL, conn_thread, p) != 0) {
            free(p); close(cfd);
            conn_release();
            continue;
        }
        pthread_detach(t);
    }
    close(sfd);
    return 0;
}
