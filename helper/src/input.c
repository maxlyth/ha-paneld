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
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <unistd.h>

// --- subscriber registry --------------------------------------------------------------------------
// SUBSCRIBEd client fds receive async event lines. A small fixed registry under a mutex owns the
// lifetime of exclusive grabs; a dead/stalled subscriber is terminated and releases its ownership.
static int subs[INPUT_MAX_SUBSCRIBERS];
static int subscriber_count = 0;
static pthread_mutex_t subs_lock = PTHREAD_MUTEX_INITIALIZER;

static int set_grabs(int active);

static int sub_add(int fd) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < INPUT_MAX_SUBSCRIBERS; i++)
        if (subs[i] == fd) { pthread_mutex_unlock(&subs_lock); return 1; }
    for (int i = 0; i < INPUT_MAX_SUBSCRIBERS; i++) if (subs[i] < 0) {
        if (subscriber_count == 0 && !set_grabs(1)) {
            pthread_mutex_unlock(&subs_lock);
            return 0;
        }
        subs[i] = fd;
        __atomic_add_fetch(&subscriber_count, 1, __ATOMIC_SEQ_CST);
        pthread_mutex_unlock(&subs_lock);
        return 1;
    }
    pthread_mutex_unlock(&subs_lock);
    return 0;
}

void input_unsubscribe(int fd) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < INPUT_MAX_SUBSCRIBERS; i++) if (subs[i] == fd) {
        subs[i] = -1;
        if (__atomic_sub_fetch(&subscriber_count, 1, __ATOMIC_SEQ_CST) == 0) set_grabs(0);
    }
    pthread_mutex_unlock(&subs_lock);
}

static void sub_broadcast(const char *line) {
    size_t len = strlen(line);
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < INPUT_MAX_SUBSCRIBERS; i++) if (subs[i] >= 0) {
        // A client that stops reading must not block the evdev thread (and every other subscriber)
        // while this mutex is held. Records are small and bounded; any short/error write makes the
        // stream unusable, so terminate that connection and let the app reconnect.
        ssize_t n = send(subs[i], line, len, MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n != (ssize_t)len) {
            shutdown(subs[i], SHUT_RDWR);
            subs[i] = -1;
            __atomic_sub_fetch(&subscriber_count, 1, __ATOMIC_SEQ_CST);
        }
    }
    if (__atomic_load_n(&subscriber_count, __ATOMIC_SEQ_CST) == 0) set_grabs(0);
    pthread_mutex_unlock(&subs_lock);
}

// --- evdev reader threads -------------------------------------------------------------------------
// WATCHed nodes, deduped so a reconnecting app doesn't double-open (the second EVIOCGRAB would fail).
struct watch_state { char path[128]; int grab; int fd; int ready; int grabbed; };
#define MAX_WATCH 8
static struct watch_state watched[MAX_WATCH];
static int  watch_n = 0;
static pthread_mutex_t watch_lock = PTHREAD_MUTEX_INITIALIZER;

static int valid_evdev_path(const char *path) {
    static const char prefix[] = "/dev/input/event";
    if (strncmp(path, prefix, sizeof prefix - 1) != 0) return 0;
    const char *p = path + sizeof prefix - 1;
    if (!*p) return 0;
    for (; *p; p++) if (*p < '0' || *p > '9') return 0;
    return 1;
}

static int open_evdev(const struct watch_state *watch, int activate_grab, int verify_grab) {
    int fd = open(watch->path, O_RDONLY);
    if (fd < 0) return -1;
    if (watch->grab && (activate_grab || verify_grab)) {
        if (ioctl(fd, EVIOCGRAB, (void *)1) < 0) {
            close(fd);
            return -1;
        }
        if (!activate_grab && ioctl(fd, EVIOCGRAB, (void *)0) < 0) {
            close(fd);
            return -1;
        }
    }
    return fd;
}

/** Called with watch_lock held. Closing is the fallback that makes a failed explicit ungrab terminal. */
static void release_grab(struct watch_state *watch) {
    if (watch->fd >= 0 && ioctl(watch->fd, EVIOCGRAB, (void *)0) < 0) {
        int stale = watch->fd;
        watch->fd = -1;
        watch->ready = 0;
        close(stale);
    }
    watch->grabbed = 0;
}

/** Activate grabs while at least one event subscriber exists; release them at the terminal boundary. */
static int set_grabs(int active) {
    int changed[MAX_WATCH];
    int changed_n = 0;
    pthread_mutex_lock(&watch_lock);
    for (int i = 0; i < watch_n; i++) {
        struct watch_state *watch = &watched[i];
        if (!watch->grab || watch->grabbed == active) continue;
        if (!watch->ready || watch->fd < 0) {
            if (active) {
                for (int j = 0; j < changed_n; j++) {
                    struct watch_state *rollback = &watched[changed[j]];
                    release_grab(rollback);
                }
                pthread_mutex_unlock(&watch_lock);
                return 0;
            }
            continue;
        }
        if (ioctl(watch->fd, EVIOCGRAB, (void *)(long)active) < 0) {
            if (active) {
                for (int j = 0; j < changed_n; j++) {
                    struct watch_state *rollback = &watched[changed[j]];
                    release_grab(rollback);
                }
                pthread_mutex_unlock(&watch_lock);
                return 0;
            }
            // The reader sees EBADF if release_grab had to close, then reopens without a grab.
            release_grab(watch);
            continue;
        }
        watch->grabbed = active;
        changed[changed_n++] = i;
    }
    pthread_mutex_unlock(&watch_lock);
    return 1;
}

void input_init(void) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < INPUT_MAX_SUBSCRIBERS; i++) subs[i] = -1;
    subscriber_count = 0;
    pthread_mutex_unlock(&subs_lock);

    // Production calls this once before any watcher starts. Closing unclaimed initial descriptors also
    // makes repeated host-test/fuzz resets deterministic when the spawn seam reports success.
    pthread_mutex_lock(&watch_lock);
    for (int i = 0; i < watch_n; i++) if (watched[i].fd >= 0) close(watched[i].fd);
    memset(watched, 0, sizeof watched);
    watch_n = 0;
    pthread_mutex_unlock(&watch_lock);
}

// Open an evdev node, optionally grab it exclusively, stream EV_KEY/EV_SW events to subscribers.
// Re-opens on error (node not ready at boot / device unplug) so it self-heals.
static void *evdev_thread(void *arg) {
    struct watch_state *watch = arg;
    for (;;) {
        pthread_mutex_lock(&watch_lock);
        int fd = watch->fd;
        pthread_mutex_unlock(&watch_lock);
        if (fd < 0) {
            int activate_grab = __atomic_load_n(&subscriber_count, __ATOMIC_SEQ_CST) > 0;
            fd = open_evdev(watch, activate_grab, 0);
            if (fd < 0) { sleep(2); continue; }
            pthread_mutex_lock(&watch_lock);
            int should_grab = __atomic_load_n(&subscriber_count, __ATOMIC_SEQ_CST) > 0;
            if (watch->grab && should_grab != activate_grab &&
                ioctl(fd, EVIOCGRAB, (void *)(long)should_grab) < 0) {
                pthread_mutex_unlock(&watch_lock);
                close(fd);
                sleep(2);
                continue;
            }
            watch->fd = fd;
            watch->ready = 1;
            watch->grabbed = watch->grab && should_grab;
            pthread_mutex_unlock(&watch_lock);
        }
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
        pthread_mutex_lock(&watch_lock);
        int owned = watch->fd == fd;
        if (owned) {
            watch->fd = -1;
            watch->ready = 0;
            watch->grabbed = 0;
        }
        pthread_mutex_unlock(&watch_lock);
        if (owned) close(fd); // device went away — close releases the grab, then reopen
        sleep(1);
    }
    return NULL;
}

// Start watching a node once. grab=1 takes exclusive ownership (suppresses the default Android action).
int input_watch(const char *path, int grab) {
    if (!valid_evdev_path(path) || (grab != 0 && grab != 1)) return -1;
    pthread_mutex_lock(&watch_lock);
    for (int i = 0; i < watch_n; i++)
        if (strcmp(watched[i].path, path) == 0) {
            int same = watched[i].grab == grab && watched[i].ready;
            pthread_mutex_unlock(&watch_lock);
            return same ? 0 : -1;
        }
    if (watch_n >= MAX_WATCH) { pthread_mutex_unlock(&watch_lock); return -1; }

    struct watch_state *watch = &watched[watch_n];
    snprintf(watch->path, sizeof watch->path, "%s", path);
    watch->grab = grab;
    int active_subscribers = __atomic_load_n(&subscriber_count, __ATOMIC_SEQ_CST) > 0;
    watch->fd = open_evdev(watch, active_subscribers, 1);
    if (watch->fd < 0) {
        memset(watch, 0, sizeof *watch);
        pthread_mutex_unlock(&watch_lock);
        return -1;
    }
    watch->ready = 1;
    watch->grabbed = watch->grab && active_subscribers;
    if (sysexec_spawn(evdev_thread, watch) != 0) {
        close(watch->fd);
        memset(watch, 0, sizeof *watch);
        pthread_mutex_unlock(&watch_lock);
        return -1;
    }
    watch_n++;
    pthread_mutex_unlock(&watch_lock);
    fprintf(stderr, "hapaneld-helper: %s %s\n", grab ? "grab" : "watch", path);
    return 0;
}

void cmd_watch(conn_ctx *ctx, const char *args) {
    char path[128] = "", extra = '\0'; int grab = -1;
    int fields = sscanf(args, "%127s %d %c", path, &grab, &extra);
    int ok = fields == 2 && input_watch(path, grab) == 0;
    reply(ctx->fd, ok ? "OK\n" : "ERR\n");
}

void cmd_inputv2(conn_ctx *ctx, const char *args) {
    (void)args;
    reply(ctx->fd, "OK\n");
}

void cmd_subscribe(conn_ctx *ctx, const char *args) {
    (void)args;
    if (!sub_add(ctx->fd)) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    ctx->subscribed = 1;      // exempt from the idle timeout from here on
    reply(ctx->fd, "OK\n"); // KEY/SW lines now stream on this connection until it closes
}
