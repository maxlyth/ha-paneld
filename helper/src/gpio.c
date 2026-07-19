#include "gpio.h"
#include "sysexec.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

// Edge-capable legacy sysfs GPIOs wake immediately through POLLPRI. Some vendor kernels expose only
// a readable value node (or refuse edge configuration), so those use a fixed two-samples-per-second
// fallback on the SAME held descriptor. Even edge mode rechecks occasionally so a driver that
// accepts `edge=both` but misses a notification cannot leave the reported state stale indefinitely.
#define GPIO_FALLBACK_MS 500
#define GPIO_EDGE_RECHECK_MS 5000
#define GPIO_REOPEN_MS 2000

// --- held GPIO descriptors -----------------------------------------------------------------------
struct gpio_watch_state {
    unsigned gpio;
    int fd;
    int ready;
    int edge;
    int value;
    int value_valid;
    char prior_edge[8];
    unsigned generation;
};

struct gpio_thread {
    struct gpio_watch_state *state;
    unsigned gpio;
    unsigned generation;
};

static struct gpio_watch_state gpio_watches[GPIO_MAX_WATCHES];
static int gpio_watch_count;
static pthread_mutex_t gpio_watch_lock = PTHREAD_MUTEX_INITIALIZER;

// --- GPIO-only subscriber registry ---------------------------------------------------------------
// This is deliberately separate from evdev SUBSCRIBE: the sensor reporter can reconnect and reset
// its GPIO watches while the hardware-button client continues to own its grabs, and vice versa.
static int gpio_subs[GPIO_MAX_SUBSCRIBERS];
static int gpio_subscriber_count;
static pthread_mutex_t gpio_subs_lock = PTHREAD_MUTEX_INITIALIZER;
static int gpio_has_watches(void);

static int gpio_sub_add(int fd) {
    pthread_mutex_lock(&gpio_subs_lock);
    // Admission and reset share the subscribers -> watches lock order. If a final disconnect cleared
    // a just-prepared watch, rejecting here makes the client replay RESET/WATCH/SUBSCRIBE instead of
    // accepting a permanently silent stream.
    if (!gpio_has_watches()) {
        pthread_mutex_unlock(&gpio_subs_lock);
        return 0;
    }
    for (int i = 0; i < GPIO_MAX_SUBSCRIBERS; i++) {
        if (gpio_subs[i] == fd) {
            pthread_mutex_unlock(&gpio_subs_lock);
            return 1;
        }
    }
    for (int i = 0; i < GPIO_MAX_SUBSCRIBERS; i++) {
        if (gpio_subs[i] < 0) {
            gpio_subs[i] = fd;
            gpio_subscriber_count++;
            pthread_mutex_unlock(&gpio_subs_lock);
            return 1;
        }
    }
    pthread_mutex_unlock(&gpio_subs_lock);
    return 0;
}

void gpio_unsubscribe(int fd) {
    if (fd < 0) return;
    int removed = 0;
    int remaining = 0;
    pthread_mutex_lock(&gpio_subs_lock);
    for (int i = 0; i < GPIO_MAX_SUBSCRIBERS; i++) {
        if (gpio_subs[i] == fd) {
            gpio_subs[i] = -1;
            gpio_subscriber_count--;
            removed = 1;
        }
    }
    remaining = gpio_subscriber_count;
    pthread_mutex_unlock(&gpio_subs_lock);
    // gpio_reset_watches() rechecks ownership after acquiring the subscriber lock, so a replacement
    // subscriber that wins this race keeps the watches it now owns.
    if (removed && remaining == 0) (void)gpio_reset_watches();
}

enum gpio_record_kind { GPIO_RECORD_VALUE, GPIO_RECORD_UNAVAILABLE };

static void gpio_broadcast_record(
    const char *line,
    size_t len,
    struct gpio_watch_state *watch,
    unsigned generation,
    int fd,
    int value,
    enum gpio_record_kind kind
) {
    int removed = 0;
    int remaining = 0;
    pthread_mutex_lock(&gpio_subs_lock);
    // Teardown acquires these locks in the same order. Holding the subscriber lock after validation
    // prevents reset/replacement from changing generations before this nonblocking send completes.
    pthread_mutex_lock(&gpio_watch_lock);
    int current = watch->generation == generation &&
        (kind == GPIO_RECORD_VALUE
            ? watch->fd == fd && watch->ready && watch->value_valid && watch->value == value
            : watch->fd < 0 && !watch->ready && !watch->value_valid);
    pthread_mutex_unlock(&gpio_watch_lock);
    if (!current) {
        pthread_mutex_unlock(&gpio_subs_lock);
        return;
    }
    for (int i = 0; i < GPIO_MAX_SUBSCRIBERS; i++) {
        if (gpio_subs[i] < 0) continue;
        ssize_t n = send(gpio_subs[i], line, (size_t)len, MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n < 0 || (size_t)n != len) {
            // A stalled/dead subscriber must not block the sensor thread. Terminating the socket wakes
            // server_serve(); its connection teardown removes this fd from both subscription domains.
            shutdown(gpio_subs[i], SHUT_RDWR);
            gpio_subs[i] = -1;
            gpio_subscriber_count--;
            removed = 1;
        }
    }
    remaining = gpio_subscriber_count;
    pthread_mutex_unlock(&gpio_subs_lock);
    if (removed && remaining == 0) (void)gpio_reset_watches();
}

static void gpio_broadcast_value(
    struct gpio_watch_state *watch,
    unsigned generation,
    int fd,
    unsigned gpio,
    int value
) {
    char line[40];
    int len = snprintf(line, sizeof line, "GPIO %u %d\n", gpio, value);
    if (len > 0 && (size_t)len < sizeof line)
        gpio_broadcast_record(
            line, (size_t)len, watch, generation, fd, value, GPIO_RECORD_VALUE);
}

static void gpio_broadcast_unavailable(
    struct gpio_watch_state *watch,
    unsigned generation,
    unsigned gpio
) {
    char line[40];
    int len = snprintf(line, sizeof line, "GPIOUNAVAILABLE %u\n", gpio);
    if (len > 0 && (size_t)len < sizeof line)
        gpio_broadcast_record(
            line, (size_t)len, watch, generation, -1, 0, GPIO_RECORD_UNAVAILABLE);
}

static int gpio_has_watches(void) {
    int present;
    pthread_mutex_lock(&gpio_watch_lock);
    present = gpio_watch_count > 0;
    pthread_mutex_unlock(&gpio_watch_lock);
    return present;
}

static int gpio_path(char *path, size_t capacity, unsigned gpio, const char *node) {
    int n = snprintf(path, capacity, "/sys/class/gpio/gpio%u/%s", gpio, node);
    return n > 0 && (size_t)n < capacity ? 0 : -1;
}

static int read_gpio_value(int fd, int *value) {
    char buf[8];
    if (lseek(fd, 0, SEEK_SET) < 0) return -1;
    ssize_t n;
    do n = read(fd, buf, sizeof buf); while (n < 0 && errno == EINTR);
    if (n <= 0) return -1;
    if (buf[0] != '0' && buf[0] != '1') return -1;
    for (ssize_t i = 1; i < n; i++) {
        if (buf[i] != '\n' && buf[i] != '\r' && buf[i] != ' ' && buf[i] != '\t') return -1;
    }
    *value = buf[0] - '0';
    return 0;
}

static int read_edge(unsigned gpio, char prior[8]) {
    char path[64];
    if (gpio_path(path, sizeof path, gpio, "edge") != 0) return -1;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char buf[16];
    ssize_t n;
    do n = read(fd, buf, sizeof buf - 1); while (n < 0 && errno == EINTR);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r' ||
                     buf[n - 1] == ' ' || buf[n - 1] == '\t')) buf[--n] = '\0';
    if (strcmp(buf, "none") != 0 && strcmp(buf, "rising") != 0 &&
        strcmp(buf, "falling") != 0 && strcmp(buf, "both") != 0) return -1;
    snprintf(prior, 8, "%s", buf);
    return 0;
}

static int write_edge(unsigned gpio, const char *edge) {
    char path[64];
    if (gpio_path(path, sizeof path, gpio, "edge") != 0) return -1;
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    int result = write_complete(fd, edge, strlen(edge));
    close(fd);
    return result;
}

// Configure both edges only when the kernel exposes a recognised edge attribute. The prior value is
// retained and restored on GPIORESET, so a short app runtime does not leave unrelated interrupt policy
// behind. Failure is not fatal: the bounded descriptor-sampling path remains fully functional.
static int configure_edges(unsigned gpio, char prior[8]) {
    prior[0] = '\0';
    if (read_edge(gpio, prior) != 0) return 0;
    if (strcmp(prior, "both") == 0) return 1;
    if (write_edge(gpio, "both") == 0) return 1;
    prior[0] = '\0';
    return 0;
}

static void restore_edges(const struct gpio_watch_state *watch) {
    if (watch->edge && watch->prior_edge[0] && strcmp(watch->prior_edge, "both") != 0)
        (void)write_edge(watch->gpio, watch->prior_edge);
}

static int open_gpio(unsigned gpio, int *edge, int *value, char prior_edge[8]) {
    char path[64];
    if (gpio_path(path, sizeof path, gpio, "value") != 0) return -1;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;

    *edge = configure_edges(gpio, prior_edge);
    if (read_gpio_value(fd, value) != 0) {
        if (*edge && prior_edge[0] && strcmp(prior_edge, "both") != 0)
            (void)write_edge(gpio, prior_edge);
        close(fd);
        return -1;
    }
    return fd;
}

static int wait_millis(int millis) {
    int result;
    do result = poll(NULL, 0, millis); while (result < 0 && errno == EINTR);
    return result;
}

static void record_value(struct gpio_watch_state *watch, unsigned generation, int fd, int value) {
    int changed = 0;
    unsigned gpio = 0;
    pthread_mutex_lock(&gpio_watch_lock);
    if (watch->generation == generation && watch->fd == fd) {
        changed = !watch->value_valid || watch->value != value;
        gpio = watch->gpio;
        watch->value = value;
        watch->value_valid = 1;
    }
    pthread_mutex_unlock(&gpio_watch_lock);
    if (changed) gpio_broadcast_value(watch, generation, fd, gpio, value);
}

// One allocation-free read per edge (or fallback interval), always through the held value descriptor.
// A disappearing/unexported GPIO is retried at a fixed slow cadence and resumes without app action.
static void *gpio_reader_thread(void *arg) {
    struct gpio_thread *thread = arg;
    struct gpio_watch_state *watch = thread->state;
    for (;;) {
        pthread_mutex_lock(&gpio_watch_lock);
        if (watch->generation != thread->generation) {
            pthread_mutex_unlock(&gpio_watch_lock);
            break;
        }
        int fd = watch->fd;
        int edge = watch->edge;
        pthread_mutex_unlock(&gpio_watch_lock);

        if (fd < 0) {
            int value = 0;
            char prior_edge[8];
            int reopened_edge = 0;
            int reopened = open_gpio(thread->gpio, &reopened_edge, &value, prior_edge);
            if (reopened < 0) {
                (void)wait_millis(GPIO_REOPEN_MS);
                continue;
            }
            pthread_mutex_lock(&gpio_watch_lock);
            if (watch->generation != thread->generation) {
                pthread_mutex_unlock(&gpio_watch_lock);
                if (reopened_edge && prior_edge[0] && strcmp(prior_edge, "both") != 0)
                    (void)write_edge(thread->gpio, prior_edge);
                close(reopened);
                break;
            }
            watch->fd = reopened;
            watch->ready = 1;
            watch->edge = reopened_edge;
            snprintf(watch->prior_edge, sizeof watch->prior_edge, "%s", prior_edge);
            fd = reopened;
            edge = reopened_edge;
            pthread_mutex_unlock(&gpio_watch_lock);
            record_value(watch, thread->generation, fd, value);
        }

        int ready;
        if (edge) {
            struct pollfd descriptor = { .fd = fd, .events = POLLPRI | POLLERR };
            do ready = poll(&descriptor, 1, GPIO_EDGE_RECHECK_MS);
            while (ready < 0 && errno == EINTR);
            if (ready > 0 && (descriptor.revents & POLLNVAL)) ready = -1;
        } else {
            ready = wait_millis(GPIO_FALLBACK_MS);
        }

        int value = 0;
        if (ready >= 0 && read_gpio_value(fd, &value) == 0) {
            record_value(watch, thread->generation, fd, value);
            continue;
        }

        pthread_mutex_lock(&gpio_watch_lock);
        int owned = watch->generation == thread->generation && watch->fd == fd;
        if (owned) {
            restore_edges(watch);
            watch->fd = -1;
            watch->ready = 0;
            watch->edge = 0;
            watch->value_valid = 0;
            watch->prior_edge[0] = '\0';
        }
        pthread_mutex_unlock(&gpio_watch_lock);
        if (owned) {
            gpio_broadcast_unavailable(watch, thread->generation, thread->gpio);
            close(fd);
        }
        else break;
        (void)wait_millis(GPIO_REOPEN_MS);
    }
    free(thread);
    return NULL;
}

#ifdef HAPANELD_TEST
void gpio_test_broadcast_invalid_generation(unsigned gpio) {
    struct gpio_watch_state *watch = NULL;
    unsigned invalid_generation = 0;
    int fd = -1;
    int value = 0;
    pthread_mutex_lock(&gpio_watch_lock);
    for (int i = 0; i < gpio_watch_count; i++) {
        if (gpio_watches[i].gpio == gpio) {
            watch = &gpio_watches[i];
            invalid_generation = watch->generation + 1;
            fd = watch->fd;
            value = watch->value;
            break;
        }
    }
    pthread_mutex_unlock(&gpio_watch_lock);
    if (watch == NULL) return;
    gpio_broadcast_value(watch, invalid_generation, fd, gpio, value);
    gpio_broadcast_unavailable(watch, invalid_generation, gpio);
}
#endif

int gpio_watch(unsigned gpio) {
    if (gpio > GPIO_MAX_NUMBER) return -1;
    pthread_mutex_lock(&gpio_watch_lock);
    for (int i = 0; i < gpio_watch_count; i++) {
        if (gpio_watches[i].gpio == gpio) {
            int ready = gpio_watches[i].ready;
            pthread_mutex_unlock(&gpio_watch_lock);
            return ready ? 0 : -1;
        }
    }
    if (gpio_watch_count >= GPIO_MAX_WATCHES) {
        pthread_mutex_unlock(&gpio_watch_lock);
        return -1;
    }

    struct gpio_watch_state *watch = &gpio_watches[gpio_watch_count];
    unsigned generation = watch->generation + 1;
    int edge = 0;
    int value = 0;
    char prior_edge[8];
    int fd = open_gpio(gpio, &edge, &value, prior_edge);
    if (fd < 0) {
        pthread_mutex_unlock(&gpio_watch_lock);
        return -1;
    }

    struct gpio_thread *thread = calloc(1, sizeof *thread);
    if (thread == NULL) {
        if (edge && prior_edge[0] && strcmp(prior_edge, "both") != 0)
            (void)write_edge(gpio, prior_edge);
        close(fd);
        pthread_mutex_unlock(&gpio_watch_lock);
        return -1;
    }
    watch->gpio = gpio;
    watch->fd = fd;
    watch->ready = 1;
    watch->edge = edge;
    watch->value = value;
    watch->value_valid = 1;
    snprintf(watch->prior_edge, sizeof watch->prior_edge, "%s", prior_edge);
    watch->generation = generation;
    thread->state = watch;
    thread->gpio = gpio;
    thread->generation = generation;
    if (sysexec_spawn(gpio_reader_thread, thread) != 0) {
        restore_edges(watch);
        close(fd);
        watch->fd = -1;
        watch->ready = 0;
        watch->edge = 0;
        watch->value_valid = 0;
        watch->prior_edge[0] = '\0';
        free(thread);
        pthread_mutex_unlock(&gpio_watch_lock);
        return -1;
    }
    gpio_watch_count++;
    pthread_mutex_unlock(&gpio_watch_lock);
    fprintf(stderr, "hapaneld-helper: watch gpio%u (%s)\n", gpio, edge ? "edge" : "sampled");
    return 0;
}

static void clear_gpio_watches(void) {
    pthread_mutex_lock(&gpio_watch_lock);
    for (int i = 0; i < gpio_watch_count; i++) {
        struct gpio_watch_state *watch = &gpio_watches[i];
        watch->generation++;
        restore_edges(watch);
        if (watch->fd >= 0) close(watch->fd);
        watch->gpio = 0;
        watch->fd = -1;
        watch->ready = 0;
        watch->edge = 0;
        watch->value = 0;
        watch->value_valid = 0;
        watch->prior_edge[0] = '\0';
    }
    gpio_watch_count = 0;
    pthread_mutex_unlock(&gpio_watch_lock);
}

void gpio_init(void) {
    pthread_mutex_lock(&gpio_subs_lock);
    for (int i = 0; i < GPIO_MAX_SUBSCRIBERS; i++) gpio_subs[i] = -1;
    gpio_subscriber_count = 0;
    pthread_mutex_unlock(&gpio_subs_lock);
    clear_gpio_watches();
}

int gpio_reset_watches(void) {
    pthread_mutex_lock(&gpio_subs_lock);
    if (gpio_subscriber_count != 0) {
        pthread_mutex_unlock(&gpio_subs_lock);
        return -1;
    }
    clear_gpio_watches();
    pthread_mutex_unlock(&gpio_subs_lock);
    return 0;
}

static void gpio_send_current(int fd) {
    // Keep the watch lock through these bounded nonblocking sends. Otherwise a changed value could be
    // broadcast after we captured an older snapshot but before that stale snapshot was written, leaving
    // the new subscriber on the wrong terminal value until the next hardware edge.
    pthread_mutex_lock(&gpio_watch_lock);
    for (int i = 0; i < gpio_watch_count; i++) {
        {
            char line[40];
            int len = gpio_watches[i].ready && gpio_watches[i].value_valid
                ? snprintf(line, sizeof line, "GPIO %u %d\n",
                           gpio_watches[i].gpio, gpio_watches[i].value)
                : snprintf(line, sizeof line, "GPIOUNAVAILABLE %u\n", gpio_watches[i].gpio);
            if (len <= 0 || (size_t)len >= sizeof line ||
                send(fd, line, (size_t)len, MSG_DONTWAIT | MSG_NOSIGNAL) != len) {
                shutdown(fd, SHUT_RDWR);
                break;
            }
        }
    }
    pthread_mutex_unlock(&gpio_watch_lock);
}

void cmd_gpiov1(conn_ctx *ctx, const char *args) {
    reply(ctx->fd, *args == '\0' ? "OK\n" : "ERR\n");
}

void cmd_gpiowatch(conn_ctx *ctx, const char *args) {
    char number[16] = "";
    char extra = '\0';
    int fields = sscanf(args, "%15s %c", number, &extra);
    errno = 0;
    int valid = fields == 1 && valid_num(number);
    unsigned long parsed = valid ? strtoul(number, NULL, 10) : 0;
    int ok = valid && errno == 0 && parsed <= GPIO_MAX_NUMBER && gpio_watch((unsigned)parsed) == 0;
    reply(ctx->fd, ok ? "OK\n" : "ERR\n");
}

void cmd_gpiosubscribe(conn_ctx *ctx, const char *args) {
    if (*args != '\0' || !gpio_sub_add(ctx->fd)) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    ctx->subscribed = 1;
    reply(ctx->fd, "OK\n");
    gpio_send_current(ctx->fd);
}

void cmd_gpioreset(conn_ctx *ctx, const char *args) {
    if (*args != '\0') {
        reply(ctx->fd, "ERR\n");
        return;
    }
    reply(ctx->fd, gpio_reset_watches() == 0 ? "OK\n" : "ERR\n");
}
