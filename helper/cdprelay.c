// cdprelay <listen_port> <abstract_socket_name>
//
// Bridges 0.0.0.0:<listen_port>  <->  @<abstract_socket_name> (a Linux abstract-namespace AF_UNIX
// socket). ha-paneld uses it to expose the dashboard WebView's Chrome DevTools endpoint
// (`@webview_devtools_remote_<pid>`) to a browser on the LAN, so a user can open chrome://inspect
// against the panel with no adb. Must run as root: an untrusted app can't connect to another app's
// abstract socket under SELinux, but the (unconfined) su domain can.
//
// Dumb byte-pump by design: we verified the WebView's CDP HTTP handler accepts an IP `Host` header
// (only DNS names are rejected as rebinding), and chrome://inspect rewrites the ws host to the
// discovered target — so no HTTP/Host rewriting is needed here.
//
// SECURITY: while running it exposes full DevTools (read + control of the dashboard) to the whole
// LAN. ha-paneld starts it on demand and stops it; it is not run by default. The limits below bound
// the resources a hostile or broken LAN peer can retain without changing that opt-in exposure model.
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "cdprelay_policy.h"

#ifdef __linux__
#include <sys/prctl.h>
#endif

// Keep these compile-time overridable so the integration test can exercise expiry in milliseconds.
#ifndef CDPRELAY_MAX_CHILDREN
#define CDPRELAY_MAX_CHILDREN 8
#endif
#ifndef CDPRELAY_CONNECT_TIMEOUT_MS
#define CDPRELAY_CONNECT_TIMEOUT_MS 3000
#endif
#ifndef CDPRELAY_WRITE_TIMEOUT_MS
#define CDPRELAY_WRITE_TIMEOUT_MS 10000
#endif
#ifndef CDPRELAY_IDLE_TIMEOUT_MS
#define CDPRELAY_IDLE_TIMEOUT_MS 120000
#endif
#ifndef CDPRELAY_MAX_LIFETIME_MS
#define CDPRELAY_MAX_LIFETIME_MS 1800000
#endif

#if CDPRELAY_MAX_CHILDREN < 1
#error "CDPRELAY_MAX_CHILDREN must be positive"
#endif

static volatile sig_atomic_t child_changed = 0;
static volatile sig_atomic_t stop_requested = 0;
static pid_t children[CDPRELAY_MAX_CHILDREN];

static int64_t monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) < 0) return 0;
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int deadline_timeout(int64_t deadline) {
    int64_t remaining = deadline - monotonic_ms();
    if (remaining <= 0) return 0;
    if (remaining > INT32_MAX) return INT32_MAX;
    return (int)remaining;
}

static int earlier_timeout(int64_t first, int64_t second) {
    int first_ms = deadline_timeout(first);
    int second_ms = deadline_timeout(second);
    return first_ms < second_ms ? first_ms : second_ms;
}

static int set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) return -1;
    return 0;
}

static int set_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFD, 0);
    return flags >= 0 && fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0 ? 0 : -1;
}

static int valid_backend_name(const char *name) {
    static const char prefix[] = "webview_devtools_remote_";
    if (strncmp(name, prefix, sizeof prefix - 1) != 0) return 0;
    const char *p = name + sizeof prefix - 1;
    if (*p < '1' || *p > '9') return 0;
    for (; *p; p++) if (*p < '0' || *p > '9') return 0;
    return 1;
}

static int connect_abstract(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    if (set_cloexec(fd) < 0 || set_nonblocking(fd) < 0) {
        close(fd);
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0'; // abstract namespace: leading NUL
    size_t n = strlen(name);
    if (n > sizeof(addr.sun_path) - 2) n = sizeof(addr.sun_path) - 2;
    memcpy(addr.sun_path + 1, name, n);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);

    if (connect(fd, (struct sockaddr *)&addr, len) == 0) return fd;
    if (errno != EINPROGRESS) {
        close(fd);
        return -1;
    }

    struct pollfd pfd = {.fd = fd, .events = POLLOUT, .revents = 0};
    int64_t connect_deadline = monotonic_ms() + CDPRELAY_CONNECT_TIMEOUT_MS;
    int ready = -1;
    while (ready < 0) {
        int timeout = deadline_timeout(connect_deadline);
        if (timeout <= 0) break;
        ready = poll(&pfd, 1, timeout);
        if (ready < 0 && errno != EINTR) break;
    }
    if (ready <= 0) {
        close(fd);
        return -1;
    }

    int error = 0;
    socklen_t error_len = sizeof(error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &error_len) < 0 || error != 0) {
        if (error != 0) errno = error;
        close(fd);
        return -1;
    }
    return fd;
}

static int write_all_bounded(int fd, const char *buf, ssize_t length, int64_t lifetime_deadline) {
    ssize_t offset = 0;
    int64_t write_deadline = monotonic_ms() + CDPRELAY_WRITE_TIMEOUT_MS;
    while (offset < length) {
        ssize_t written = write(fd, buf + offset, (size_t)(length - offset));
        if (written > 0) {
            offset += written;
            continue;
        }
        if (written < 0 && errno == EINTR) continue;
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pfd = {.fd = fd, .events = POLLOUT, .revents = 0};
            int timeout = earlier_timeout(write_deadline, lifetime_deadline);
            if (timeout <= 0) return -1;
            int ready = -1;
            while (ready < 0) {
                ready = poll(&pfd, 1, timeout);
                if (ready < 0 && errno != EINTR) break;
                timeout = earlier_timeout(write_deadline, lifetime_deadline);
                if (timeout <= 0) break;
            }
            if (ready <= 0 || (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) return -1;
            continue;
        }
        return -1;
    }
    return 0;
}

// Pump bytes both directions until either side closes or a resource deadline expires.
static void pump(int a, int b) {
    if (set_nonblocking(a) < 0 || set_nonblocking(b) < 0) return;

    struct pollfd pf[2] = {{.fd = a}, {.fd = b}};
    char buf[16384];
    int64_t now = monotonic_ms();
    int64_t idle_deadline = now + CDPRELAY_IDLE_TIMEOUT_MS;
    int64_t lifetime_deadline = now + CDPRELAY_MAX_LIFETIME_MS;

    for (;;) {
        pf[0].events = pf[1].events = POLLIN;
        pf[0].revents = pf[1].revents = 0;
        int timeout = earlier_timeout(idle_deadline, lifetime_deadline);
        if (timeout <= 0) return;

        int ready = poll(pf, 2, timeout);
        if (ready < 0) {
            if (errno == EINTR) continue;
            return;
        }
        if (ready == 0) return;

        for (int i = 0; i < 2; i++) {
            if (pf[i].revents & POLLNVAL) return;
            if (pf[i].revents & (POLLIN | POLLHUP | POLLERR)) {
                int src = pf[i].fd;
                int dst = pf[1 - i].fd;
                ssize_t count = read(src, buf, sizeof(buf));
                if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
                    continue;
                }
                if (count <= 0) return;
                if (write_all_bounded(dst, buf, count, lifetime_deadline) < 0) return;
                idle_deadline = monotonic_ms() + CDPRELAY_IDLE_TIMEOUT_MS;
            }
        }
    }
}

static void on_sigchld(int signal_number) {
    (void)signal_number;
    child_changed = 1;
}

static void on_stop(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}

static int install_signal_handlers(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask);
    action.sa_handler = on_sigchld;
    if (sigaction(SIGCHLD, &action, NULL) < 0) return -1;
    action.sa_handler = on_stop;
    if (sigaction(SIGTERM, &action, NULL) < 0) return -1;
    if (sigaction(SIGINT, &action, NULL) < 0) return -1;
    return 0;
}

static void forget_child(pid_t pid) {
    for (size_t i = 0; i < CDPRELAY_MAX_CHILDREN; i++) {
        if (children[i] == pid) {
            children[i] = 0;
            return;
        }
    }
}

static void reap_children(void) {
    int status;
    pid_t pid;
    child_changed = 0;
    while ((pid = waitpid(-1, &status, WNOHANG)) > 0) forget_child(pid);
}

static int remember_child(pid_t pid) {
    for (size_t i = 0; i < CDPRELAY_MAX_CHILDREN; i++) {
        if (children[i] == 0) {
            children[i] = pid;
            return 0;
        }
    }
    return -1;
}

static int has_child_slot(void) {
    for (size_t i = 0; i < CDPRELAY_MAX_CHILDREN; i++) {
        if (children[i] == 0) return 1;
    }
    return 0;
}

static void stop_children(void) {
    reap_children();
    for (size_t i = 0; i < CDPRELAY_MAX_CHILDREN; i++) {
        if (children[i] > 0) kill(children[i], SIGTERM);
    }
    for (size_t i = 0; i < CDPRELAY_MAX_CHILDREN; i++) {
        if (children[i] <= 0) continue;
        while (waitpid(children[i], NULL, 0) < 0 && errno == EINTR) {}
        children[i] = 0;
    }
}

static void reset_child_signals(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask);
    action.sa_handler = SIG_DFL;
    (void)sigaction(SIGTERM, &action, NULL);
    (void)sigaction(SIGINT, &action, NULL);
    (void)sigaction(SIGCHLD, &action, NULL);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <port> <abstract_name>\n", argv[0]);
        return 2;
    }
    errno = 0;
    char *port_end = NULL;
    long parsed_port = strtol(argv[1], &port_end, 10);
    if (errno != 0 || port_end == argv[1] || *port_end != '\0' ||
        parsed_port < 1024 || parsed_port > 65535) {
        fprintf(stderr, "cdprelay: invalid port\n");
        return 2;
    }
    int port = (int)parsed_port;
    const char *name = argv[2];
    if (!valid_backend_name(name)) {
        fprintf(stderr, "cdprelay: invalid WebView DevTools socket\n");
        return 2;
    }
    signal(SIGPIPE, SIG_IGN);
    if (install_signal_handlers() < 0) {
        perror("sigaction");
        return 1;
    }

    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) {
        perror("socket");
        return 1;
    }
    if (set_cloexec(srv) < 0) { perror("fcntl"); close(srv); return 1; }
    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_ANY);
    sa.sin_port = htons((unsigned short)port);
    if (bind(srv, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        perror("bind");
        close(srv);
        return 1;
    }
    if (listen(srv, 16) < 0) {
        perror("listen");
        close(srv);
        return 1;
    }
    fprintf(stderr, "cdprelay: 0.0.0.0:%d <-> @%s (max %d clients)\n",
            port, name, CDPRELAY_MAX_CHILDREN);

    while (!stop_requested) {
        if (child_changed) reap_children();
        struct sockaddr_in peer;
        socklen_t peer_length = sizeof(peer);
        memset(&peer, 0, sizeof(peer));
        int cli = accept(srv, (struct sockaddr *)&peer, &peer_length);
        if (cli < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (set_cloexec(cli) < 0) { close(cli); continue; }
        if (stop_requested) {
            close(cli);
            break;
        }
        if (peer_length < sizeof(peer) || peer.sin_family != AF_INET ||
                !cdprelay_peer_allowed(peer.sin_addr.s_addr)) {
            close(cli);
            continue;
        }

        // Reap once more before applying the cap: a child may have exited while accept() returned.
        reap_children();
        if (!has_child_slot()) {
            close(cli);
            continue;
        }
        pid_t owner = getpid();
        pid_t pid = fork();
        if (pid == 0) {
            reset_child_signals();
#ifdef PR_SET_PDEATHSIG
            // Also remove exposure after an uncatchable parent death; close the small race explicitly.
            if (prctl(PR_SET_PDEATHSIG, SIGTERM) < 0 || getppid() != owner) _exit(1);
#endif
            close(srv);
            int up = connect_abstract(name);
            if (up >= 0) {
                pump(cli, up);
                close(up);
            }
            close(cli);
            _exit(0);
        }
        if (pid < 0) {
            close(cli);
            continue;
        }
        if (remember_child(pid) < 0) {
            // Defensive only: the parent is the sole slot writer, so the pre-fork check should win.
            kill(pid, SIGTERM);
            while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
        }
        close(cli);
    }

    close(srv);
    stop_children();
    return 0;
}
