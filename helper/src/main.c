// hapaneld-helper — a tiny root helper that exposes a whitelisted control surface to ha-paneld over an
// authenticated UNIX socket, for capabilities a sandboxed Android app can't reach itself: the RGB LED
// (root-only sysfs or an app-denied ioctl), screen-backlight power, hardware-button instrumentation,
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
//     at runtime from /data/data/<pkg>, which changes per reinstall), plus root and shell for adb.
//   * Concurrent connections are capped (MAX_CONN) and idle non-subscribers time out (server.c), so a
//     connection flood can't exhaust the thread-per-conn model.
//   * The command parser is bounded and exact-match (dispatch.c / server.c); every verb's arguments
//     are width-bounded and validated, and all shell-exec is funnelled through sysexec.c.
//
// Protocol: see helper/README.md and the dispatch table in dispatch.c.

#define _GNU_SOURCE             // struct ucred / SO_PEERCRED
#include <errno.h>
#include <pthread.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include "led.h"
#include "screen.h"
#include "input.h"
#include "server.h"
#include "version.h"

// Abstract-namespace UNIX socket name (leading NUL added at bind time). Must match the app's
// LocalSocketAddress("hapaneld-helper", ABSTRACT) in HelperClient/EvdevButtonClient.
#define SOCK_NAME  "hapaneld-helper"
// ha-paneld's data dir — we stat() it to learn the app's uid (it changes on every reinstall).
#define APP_DATA   "/data/data/io.github.maxlyth.hapaneld"
// MAX_CONN (the concurrent-connection cap) + the conn_admit/release/active gate live in server.[ch]
// so the cap is unit-testable without this accept loop.

// --- peer authentication --------------------------------------------------------------------------
// ha-paneld's uid changes on every (re)install, so resolve it live by stat'ing its data dir rather
// than hardcoding it. Accept that uid, plus root (0) and shell (2000) so adb debugging still works.
static uid_t app_uid = (uid_t)-1;   // last resolved ha-paneld uid (cached across stat failures)

static int uid_allowed(uid_t uid) {
    if (uid == 0 || uid == 2000) return 1;                 // root, shell
    struct stat st;
    if (stat(APP_DATA, &st) == 0) app_uid = st.st_uid;     // refresh (data dir may be absent pre-install)
    return app_uid != (uid_t)-1 && uid == app_uid;
}

// One thread per connection, so a long-lived SUBSCRIBE stream doesn't block other commands. Removes
// the fd from the subscriber registry on disconnect.
static void *conn_thread(void *arg) {
    int cfd = *(int *)arg;
    free(arg);
    server_serve(cfd);
    input_unsubscribe(cfd);
    close(cfd);
    conn_release();
    return NULL;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts(helper_identity());
        return 0;
    }

    signal(SIGPIPE, SIG_IGN);   // a dead subscriber's socket must not kill the daemon
    input_init();
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

        // Authenticate the peer by uid — only possible because this is a UNIX socket. Reject (and
        // close) anything that isn't ha-paneld / root / shell before it can issue a single command.
        struct ucred cred; socklen_t cl = sizeof cred;
        if (getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cred, &cl) < 0 || !uid_allowed(cred.uid)) {
            close(cfd); continue;
        }

        // Cap concurrent connections so a connection flood can't exhaust the thread-per-conn model.
        if (!conn_admit()) { close(cfd); continue; }

        int *p = malloc(sizeof(int));
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
