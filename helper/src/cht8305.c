#include "cht8305.h"
#include "util.h"

#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

// Supported vendor climate drivers expose temperature and humidity as two separate input devices whose
// current values are the reading × 100. TPA10 uses "temperature" / "humidity" with ABS_THROTTLE for
// both. ZX-SMT156's GXHT30 driver uses "sun-ths" / "sun-hum", with ABS_THROTTLE for temperature and
// vendor axis 0x1d for humidity (GitHub #24 evidence, 2026-07-17). Read with EVIOCGABS — that returns the
// driver's last measurement immediately, so there's no blocking wait for the next event.
//
// The /dev/input/eventN number is NOT stable across boots (it depends on probe order), so match by the
// device name via EVIOCGNAME rather than a hardcoded node. Scan every node before selecting a pair: the
// helper must reject duplicate exact-name devices and mixtures of the two vendor layouts rather than
// silently combining whichever nodes happened to be visited first.

struct climate_input_pair {
    const char *temp_name;
    unsigned int temp_axis;
    const char *humidity_name;
    unsigned int humidity_axis;
};

// Exact allowlist only. Do not infer a climate sensor from arbitrary EV_ABS devices: a wrong match could
// publish touch, battery, or motion axes as room conditions.
static const struct climate_input_pair climate_inputs[] = {
    { "temperature", ABS_THROTTLE, "humidity", ABS_THROTTLE },
    { "sun-ths", ABS_THROTTLE, "sun-hum", 0x1d },
};

#define CLIMATE_INPUT_COUNT (sizeof climate_inputs / sizeof climate_inputs[0])

struct climate_reading {
    unsigned int matches;
    int readable;
    long value;
};

struct climate_pair_scan {
    struct climate_reading temp;
    struct climate_reading humidity;
};

struct climate_scan {
    struct climate_pair_scan pairs[CLIMATE_INPUT_COUNT];
};

typedef int (*climate_axis_reader)(void *opaque, unsigned int axis, long *out);

static void record_reading(struct climate_reading *reading, int readable, long value) {
    // Only the states zero, one, and ambiguous matter; saturating preserves fail-closed behaviour.
    if (reading->matches < 2) reading->matches++;
    if (reading->matches == 1 && readable) {
        reading->readable = 1;
        reading->value = value;
    }
}

static void observe_named_device(struct climate_scan *scan, const char *name,
                                 climate_axis_reader read_axis, void *opaque) {
    for (size_t i = 0; i < CLIMATE_INPUT_COUNT; i++) {
        const struct climate_input_pair *candidate = &climate_inputs[i];
        if (strcmp(name, candidate->temp_name) == 0) {
            long value = 0;
            int readable = read_axis(opaque, candidate->temp_axis, &value);
            record_reading(&scan->pairs[i].temp, readable, value);
        }
        if (strcmp(name, candidate->humidity_name) == 0) {
            long value = 0;
            int readable = read_axis(opaque, candidate->humidity_axis, &value);
            record_reading(&scan->pairs[i].humidity, readable, value);
        }
    }
}

static int resolve_climate_scan(const struct climate_scan *scan, long *temp, long *humidity) {
    int selected = -1;
    for (size_t i = 0; i < CLIMATE_INPUT_COUNT; i++) {
        const struct climate_pair_scan *pair = &scan->pairs[i];
        if (pair->temp.matches == 0 && pair->humidity.matches == 0) continue;

        // Any partial, unreadable, or duplicate layout makes the entire discovery ambiguous. This
        // also rejects a complete pair accompanied by one stray exact-name device from another pair.
        if (pair->temp.matches != 1 || pair->humidity.matches != 1 ||
            !pair->temp.readable || !pair->humidity.readable) return 0;
        if (selected >= 0) return 0;
        selected = (int)i;
    }
    if (selected < 0) return 0;

    *temp = scan->pairs[selected].temp.value;
    *humidity = scan->pairs[selected].humidity.value;
    return 1;
}

static int read_input_axis(void *opaque, unsigned int axis, long *out) {
    int fd = *(const int *)opaque;
    struct input_absinfo abs;
    if (ioctl(fd, EVIOCGABS(axis), &abs) < 0) return 0;
    *out = abs.value;
    return 1;
}

static int canonical_event_name(const char *name) {
    if (strncmp(name, "event", 5) != 0) return 0;
    const char *number = name + 5;
    size_t length = strlen(number);
    if (length < 1 || length > 3 || (length > 1 && number[0] == '0')) return 0;
    for (size_t i = 0; i < length; i++) {
        if (number[i] < '0' || number[i] > '9') return 0;
    }
    return 1;
}

static int scan_climate_inputs(long *temp, long *humidity) {
    DIR *d = opendir("/dev/input");
    if (!d) return 0;

    struct climate_scan scan = {0};
    struct dirent *e;
    char path[64], name[80];
    while ((e = readdir(d))) {
        if (!canonical_event_name(e->d_name)) continue;
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        name[0] = '\0';
        if (ioctl(fd, EVIOCGNAME(sizeof name), name) >= 0)
            observe_named_device(&scan, name, read_input_axis, &fd);
        close(fd);
    }
    closedir(d);
    return resolve_climate_scan(&scan, temp, humidity);
}

#ifdef HAPANELD_TEST
size_t cht8305_test_candidate_count(void) {
    return CLIMATE_INPUT_COUNT;
}

int cht8305_test_event_name(const char *name) {
    return canonical_event_name(name);
}

int cht8305_test_candidate(size_t index, const char **temp_name, unsigned int *temp_axis,
                           const char **humidity_name, unsigned int *humidity_axis) {
    if (index >= cht8305_test_candidate_count()) return 0;
    const struct climate_input_pair *candidate = &climate_inputs[index];
    *temp_name = candidate->temp_name;
    *temp_axis = candidate->temp_axis;
    *humidity_name = candidate->humidity_name;
    *humidity_axis = candidate->humidity_axis;
    return 1;
}

struct test_axis_read {
    int readable;
    long value;
};

static int read_test_axis(void *opaque, unsigned int axis, long *out) {
    (void)axis;
    const struct test_axis_read *read = opaque;
    if (!read->readable) return 0;
    *out = read->value;
    return 1;
}

int cht8305_test_resolve(const struct cht8305_test_input *inputs, size_t count,
                         long *temp, long *humidity) {
    struct climate_scan scan = {0};
    for (size_t i = 0; i < count; i++) {
        struct test_axis_read read = { inputs[i].readable, inputs[i].value };
        observe_named_device(&scan, inputs[i].name, read_test_axis, &read);
    }
    return resolve_climate_scan(&scan, temp, humidity);
}
#endif

void cmd_cht8305(conn_ctx *ctx, const char *args) {
    (void)args;
    long t = 0, h = 0;
    if (scan_climate_inputs(&t, &h)) {
        char out[48];
        snprintf(out, sizeof out, "T=%ld H=%ld\n", t, h);
        reply(ctx->fd, out);
        return;
    }
    reply(ctx->fd, "ERR\n");
}
