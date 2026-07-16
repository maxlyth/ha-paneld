#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "../cdprelay_policy.h"

static void fail(const char *message) {
    perror(message);
    exit(1);
}

static void sleep_ms(int milliseconds) {
    struct timespec duration = {
        .tv_sec = milliseconds / 1000,
        .tv_nsec = (long)(milliseconds % 1000) * 1000000L,
    };
    while (nanosleep(&duration, &duration) < 0 && errno == EINTR) {}
}

static int abstract_listener(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) fail("backend socket");
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    size_t name_length = strlen(name);
    if (name_length > sizeof(address.sun_path) - 2) fail("backend name too long");
    memcpy(address.sun_path + 1, name, name_length);
    socklen_t length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_length);
    if (bind(fd, (struct sockaddr *)&address, length) < 0) fail("backend bind");
    if (listen(fd, 8) < 0) fail("backend listen");
    return fd;
}

static void echo_connection(int fd) {
    char buffer[1024];
    for (;;) {
        ssize_t count = read(fd, buffer, sizeof(buffer));
        if (count <= 0) break;
        ssize_t offset = 0;
        while (offset < count) {
            ssize_t written = write(fd, buffer + offset, (size_t)(count - offset));
            if (written <= 0) _exit(1);
            offset += written;
        }
    }
    close(fd);
    _exit(0);
}

static pid_t start_backend(const char *name) {
    int ready[2];
    if (pipe(ready) < 0) fail("ready pipe");
    pid_t pid = fork();
    if (pid < 0) fail("backend fork");
    if (pid == 0) {
        close(ready[0]);
        if (setpgid(0, 0) < 0) _exit(1);
        signal(SIGCHLD, SIG_IGN);
        int listener = abstract_listener(name);
        if (write(ready[1], "R", 1) != 1) _exit(1);
        close(ready[1]);
        for (;;) {
            int connection = accept(listener, NULL, NULL);
            if (connection < 0) {
                if (errno == EINTR) continue;
                _exit(1);
            }
            pid_t child = fork();
            if (child == 0) {
                close(listener);
                echo_connection(connection);
            }
            close(connection);
        }
    }
    close(ready[1]);
    char marker = 0;
    if (read(ready[0], &marker, 1) != 1 || marker != 'R') fail("backend readiness");
    close(ready[0]);
    return pid;
}

static unsigned short reserve_port(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) fail("port socket");
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) < 0) fail("port bind");
    socklen_t length = sizeof(address);
    if (getsockname(fd, (struct sockaddr *)&address, &length) < 0) fail("getsockname");
    unsigned short port = ntohs(address.sin_port);
    close(fd);
    return port;
}

static pid_t start_relay(const char *executable, unsigned short port, const char *name) {
    pid_t pid = fork();
    if (pid < 0) fail("relay fork");
    if (pid == 0) {
        char port_text[16];
        snprintf(port_text, sizeof(port_text), "%u", port);
        execl(executable, executable, port_text, name, (char *)NULL);
        _exit(127);
    }
    return pid;
}

static int connect_tcp(unsigned short port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct timeval timeout = {.tv_sec = 1, .tv_usec = 0};
    (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(port);
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int round_trip(int fd, char value) {
    if (write(fd, &value, 1) != 1) return -1;
    char response = 0;
    if (read(fd, &response, 1) != 1) return -1;
    return response == value ? 0 : -1;
}

static int connect_and_round_trip(unsigned short port, char value) {
    for (int attempt = 0; attempt < 30; attempt++) {
        int fd = connect_tcp(port);
        if (fd >= 0) {
            if (round_trip(fd, value) == 0) return fd;
            close(fd);
        }
        sleep_ms(25);
    }
    return -1;
}

static int closes_within(int fd, int timeout_ms) {
    struct pollfd descriptor = {.fd = fd, .events = POLLIN, .revents = 0};
    int ready;
    do {
        ready = poll(&descriptor, 1, timeout_ms);
    } while (ready < 0 && errno == EINTR);
    if (ready <= 0) return 0;
    char byte;
    ssize_t count = read(fd, &byte, 1);
    return count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK);
}

static int exits_within(pid_t pid, int timeout_ms) {
    for (int elapsed = 0; elapsed < timeout_ms; elapsed += 20) {
        pid_t result = waitpid(pid, NULL, WNOHANG);
        if (result == pid) return 1;
        if (result < 0) return 0;
        sleep_ms(20);
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s <test-relay-binary>\n", argv[0]);
        return 2;
    }
    signal(SIGPIPE, SIG_IGN);

    if (!cdprelay_peer_allowed(inet_addr("127.0.0.1")) ||
            !cdprelay_peer_allowed(inet_addr("10.20.30.40")) ||
            !cdprelay_peer_allowed(inet_addr("172.31.255.1")) ||
            !cdprelay_peer_allowed(inet_addr("192.168.1.2")) ||
            !cdprelay_peer_allowed(inet_addr("169.254.2.3")) ||
            cdprelay_peer_allowed(inet_addr("172.32.0.1")) ||
            cdprelay_peer_allowed(inet_addr("8.8.8.8"))) {
        fprintf(stderr, "relay peer policy failed\n");
        return 1;
    }

    char backend_name[80];
    snprintf(backend_name, sizeof(backend_name), "hapaneld_cdp_test_%ld", (long)getpid());
    pid_t backend = start_backend(backend_name);
    unsigned short port = reserve_port();
    pid_t relay = start_relay(argv[1], port, backend_name);

    int first = connect_and_round_trip(port, 'A');
    int second = connect_and_round_trip(port, 'B');
    if (first < 0 || second < 0) fail("relay initial round trip");

    // The test relay is compiled with two slots. A third peer must be rejected, not forked/retained.
    int saturated = connect_tcp(port);
    if (saturated < 0 || !closes_within(saturated, 1000)) fail("relay saturation cap");
    close(saturated);

    // Closing a peer must be reaped and make its fixed slot reusable.
    close(first);
    int replacement = connect_and_round_trip(port, 'C');
    if (replacement < 0) fail("relay child reap");

    // Both retained peers must be disconnected after the test build's short idle deadline.
    sleep_ms(650);
    if (!closes_within(second, 500) || !closes_within(replacement, 500)) {
        fail("relay idle cleanup");
    }
    close(second);
    close(replacement);

    int live = connect_and_round_trip(port, 'D');
    if (live < 0) fail("relay post-idle capacity");

    // Stopping the relay must also stop active workers so DevTools exposure actually ends.
    if (kill(relay, SIGTERM) < 0) fail("relay terminate");
    if (!exits_within(relay, 2000)) fail("relay graceful exit");
    if (!closes_within(live, 1000)) fail("relay child shutdown");
    close(live);

    // Linux parent-death signalling also bounds workers if the privileged relay is killed/crashes.
    relay = start_relay(argv[1], port, backend_name);
    int crash_live = connect_and_round_trip(port, 'E');
    if (crash_live < 0) fail("relay restart");
    if (kill(relay, SIGKILL) < 0) fail("relay kill");
    if (!exits_within(relay, 2000)) fail("relay killed exit");
    if (!closes_within(crash_live, 1000)) fail("relay parent-death cleanup");
    close(crash_live);

    (void)kill(-backend, SIGTERM);
    (void)waitpid(backend, NULL, 0);
    puts("CDP RELAY RESOURCE TEST OK");
    return 0;
}
