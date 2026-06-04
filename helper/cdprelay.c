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
// LAN. ha-paneld starts it on demand and stops it; it is not run by default.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <poll.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>

static int connect_abstract(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0'; // abstract namespace: leading NUL
    size_t n = strlen(name);
    if (n > sizeof(addr.sun_path) - 2) n = sizeof(addr.sun_path) - 2;
    memcpy(addr.sun_path + 1, name, n);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) { close(fd); return -1; }
    return fd;
}

// Pump bytes both directions until either side closes.
static void pump(int a, int b) {
    struct pollfd pf[2];
    pf[0].fd = a; pf[1].fd = b;
    char buf[16384];
    for (;;) {
        pf[0].events = pf[1].events = POLLIN;
        pf[0].revents = pf[1].revents = 0;
        if (poll(pf, 2, -1) < 0) { if (errno == EINTR) continue; return; }
        for (int i = 0; i < 2; i++) {
            if (pf[i].revents & (POLLIN | POLLHUP | POLLERR)) {
                int src = pf[i].fd, dst = pf[1 - i].fd;
                ssize_t r = read(src, buf, sizeof(buf));
                if (r <= 0) return;
                ssize_t off = 0;
                while (off < r) {
                    ssize_t w = write(dst, buf + off, r - off);
                    if (w <= 0) return;
                    off += w;
                }
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: %s <port> <abstract_name>\n", argv[0]); return 2; }
    int port = atoi(argv[1]);
    const char *name = argv[2];
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_IGN); // auto-reap children

    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) { perror("socket"); return 1; }
    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_ANY);
    sa.sin_port = htons((unsigned short)port);
    if (bind(srv, (struct sockaddr *)&sa, sizeof(sa)) < 0) { perror("bind"); return 1; }
    if (listen(srv, 16) < 0) { perror("listen"); return 1; }
    fprintf(stderr, "cdprelay: 0.0.0.0:%d <-> @%s\n", port, name);

    for (;;) {
        int cli = accept(srv, NULL, NULL);
        if (cli < 0) { if (errno == EINTR) continue; break; }
        pid_t pid = fork();
        if (pid == 0) {
            close(srv);
            int up = connect_abstract(name);
            if (up >= 0) { pump(cli, up); close(up); }
            close(cli);
            _exit(0);
        }
        close(cli);
    }
    close(srv);
    return 0;
}
