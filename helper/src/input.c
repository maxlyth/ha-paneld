#include "input.h"
#include "sysexec.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

// --- subscriber registry --------------------------------------------------------------------------
// SUBSCRIBEd client fds receive async event lines. A small fixed registry under a mutex; writes that
// fail (client gone) just drop — the connection thread removes the fd on disconnect.
#define MAX_SUBS 8
static int subs[MAX_SUBS];
static pthread_mutex_t subs_lock = PTHREAD_MUTEX_INITIALIZER;

void input_init(void) {
    for (int i = 0; i < MAX_SUBS; i++) subs[i] = -1;
}

static void sub_add(int fd) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < MAX_SUBS; i++) if (subs[i] == fd) { pthread_mutex_unlock(&subs_lock); return; }
    for (int i = 0; i < MAX_SUBS; i++) if (subs[i] < 0) { subs[i] = fd; break; }
    pthread_mutex_unlock(&subs_lock);
}

void input_unsubscribe(int fd) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < MAX_SUBS; i++) if (subs[i] == fd) subs[i] = -1;
    pthread_mutex_unlock(&subs_lock);
}

static void sub_broadcast(const char *line) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < MAX_SUBS; i++)
        if (subs[i] >= 0 && write(subs[i], line, strlen(line)) < 0) subs[i] = -1;  // drop dead fd
    pthread_mutex_unlock(&subs_lock);
}

// --- evdev reader threads -------------------------------------------------------------------------
// WATCHed nodes, deduped so a reconnecting app doesn't double-open (the second EVIOCGRAB would fail).
#define MAX_WATCH 8
static char watched[MAX_WATCH][128];
static int  watch_n = 0;
static pthread_mutex_t watch_lock = PTHREAD_MUTEX_INITIALIZER;

struct watch_arg { char path[128]; int grab; };

// Open an evdev node, optionally grab it exclusively, stream EV_KEY/EV_SW events to subscribers.
// Re-opens on error (node not ready at boot / device unplug) so it self-heals.
static void *evdev_thread(void *arg) {
    struct watch_arg *w = arg;
    for (;;) {
        int fd = open(w->path, O_RDONLY);
        if (fd < 0) { sleep(2); continue; }
        // EVIOCGRAB MUST succeed for grab-to-suppress to work: if another process already holds the
        // grab it fails with EBUSY and we'd read non-exclusively (events reach us AND Android — the
        // key still acts). Surface that instead of silently degrading.
        if (w->grab && ioctl(fd, EVIOCGRAB, (void *)1) < 0)
            fprintf(stderr, "hapaneld-helper: EVIOCGRAB %s failed (%s) — NOT exclusive\n",
                    w->path, strerror(errno));
        struct input_event ev;
        char line[48];
        while (read(fd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
            // EV_KEY = momentary keys; EV_SW = latching switches (e.g. the TPA10 orange button reports
            // SW_MUTE_DEVICE on gpio-keys, not a key). Stream both; the app decides what each means.
            if (ev.type == EV_KEY || ev.type == EV_SW) {
                snprintf(line, sizeof line, "%s %d %d\n",
                         ev.type == EV_SW ? "SW" : "KEY", ev.code, ev.value);
                sub_broadcast(line);
            }
        }
        close(fd);   // device went away — reopen (grab is released by the close)
        sleep(1);
    }
    return NULL;
}

// Start watching a node once. grab=1 takes exclusive ownership (suppresses the default Android action).
int input_watch(const char *path, int grab) {
    if (!*path) return -1;
    pthread_mutex_lock(&watch_lock);
    for (int i = 0; i < watch_n; i++)
        if (strcmp(watched[i], path) == 0) { pthread_mutex_unlock(&watch_lock); return 0; }  // already
    if (watch_n >= MAX_WATCH) { pthread_mutex_unlock(&watch_lock); return -1; }
    snprintf(watched[watch_n++], 128, "%s", path);
    pthread_mutex_unlock(&watch_lock);
    struct watch_arg *a = calloc(1, sizeof *a);
    snprintf(a->path, sizeof a->path, "%s", path);
    a->grab = grab;
    if (sysexec_spawn(evdev_thread, a) != 0) { perror("sysexec_spawn"); free(a); return -1; }
    fprintf(stderr, "hapaneld-helper: %s %s\n", grab ? "grab" : "watch", path);
    return 0;
}

void cmd_watch(conn_ctx *ctx, const char *args) {
    char path[128] = ""; int grab = 0;
    sscanf(args, "%127s %d", path, &grab);
    // only absolute /dev/input/ paths — defends against opening arbitrary files
    int ok = strncmp(path, "/dev/input/", 11) == 0 && input_watch(path, grab ? 1 : 0) == 0;
    reply(ctx->fd, ok ? "OK\n" : "ERR\n");
}

void cmd_subscribe(conn_ctx *ctx, const char *args) {
    (void)args;
    sub_add(ctx->fd);
    ctx->subscribed = 1;   // exempt from the idle timeout from here on
    reply(ctx->fd, "OK\n");  // KEY/SW lines now stream on this connection until it closes
}
