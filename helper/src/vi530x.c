#include "vi530x.h"
#include "util.h"

#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define VI530X_NODE "/dev/vi530x"

// The vendor sample uses 30. Keep their number rather than inventing one: the units are not documented
// and a value chosen for a different cadence could leave the sensor reporting too slowly to be useful
// or fast enough to matter for power, and neither has been measured on hardware.
#define VI530X_DEFAULT_PERIOD 30u

// Ordered because the driver rejects them out of order — CHIP_INIT before POWER_ON returns an error
// rather than initialising anything. PERIOD is applied before START so the first measurement already
// uses the configured cadence.
static const unsigned long start_requests[] = {
    VI530X_IOCTL_POWER_ON,
    VI530X_IOCTL_CHIP_INIT,
    VI530X_IOCTL_PERIOD,
    VI530X_IOCTL_START,
};
#define START_REQUEST_COUNT (sizeof start_requests / sizeof start_requests[0])

// One descriptor for the process. Starting is comparatively expensive (the driver uploads firmware on
// CHIP_INIT), so it is done once and reused; every entry point is serialised because a second caller
// arriving mid-start must not observe a half-started sensor as ready.
static pthread_mutex_t vi530x_lock = PTHREAD_MUTEX_INITIALIZER;
static int vi530x_fd = -1;
static int vi530x_started = 0;

/** Drop any cached descriptor. Called whenever a step fails, so a failed start is never cached as a
 *  started sensor and the next request retries from the beginning rather than reading a dead node. */
static void vi530x_close_locked(void) {
    if (vi530x_fd >= 0) close(vi530x_fd);
    vi530x_fd = -1;
    vi530x_started = 0;
}

/** Open and run the vendor start sequence. Returns 1 when the sensor is measuring. Idempotent. */
static int vi530x_start_locked(void) {
    if (vi530x_started && vi530x_fd >= 0) return 1;
    vi530x_close_locked();

    int fd = open(VI530X_NODE, O_RDWR | O_CLOEXEC);
    if (fd < 0) return 0;

    uint32_t period = VI530X_DEFAULT_PERIOD;
    for (size_t i = 0; i < START_REQUEST_COUNT; i++) {
        unsigned long request = start_requests[i];
        int rc = (request == VI530X_IOCTL_PERIOD) ? ioctl(fd, request, &period)
                                                  : ioctl(fd, request, NULL);
        if (rc < 0) {
            close(fd);
            return 0;
        }
    }
    vi530x_fd = fd;
    vi530x_started = 1;
    return 1;
}

/** Render one measurement. Separated from the I/O so the wire format is testable without a device. */
static int vi530x_format(long range, long status, long confidence, char *out, size_t outsz) {
    int written = snprintf(out, outsz, "D=%ld S=%ld C=%ld\n", range, status, confidence);
    return written > 0 && (size_t)written < outsz;
}

void cmd_vi530x(conn_ctx *ctx, const char *args) {
    (void)args;
    struct vi530x_measurement measurement;
    int have = 0;

    pthread_mutex_lock(&vi530x_lock);
    if (vi530x_start_locked()) {
        memset(&measurement, 0, sizeof measurement);
        if (ioctl(vi530x_fd, VI530X_IOCTL_MZ_DATA, &measurement) >= 0) {
            have = 1;
        } else {
            // A read that fails after a successful start means the descriptor is no longer usable
            // (driver unbound, sensor reset). Drop it so the next request starts cleanly instead of
            // failing forever against a stale fd.
            vi530x_close_locked();
        }
    }
    pthread_mutex_unlock(&vi530x_lock);

    char out[64];
    if (have && vi530x_format((long)measurement.range_tof, (long)measurement.range_status,
                              (long)measurement.range_confidence, out, sizeof out)) {
        reply(ctx->fd, out);
        return;
    }
    reply(ctx->fd, "ERR\n");
}

#ifdef HAPANELD_TEST
size_t vi530x_test_start_request_count(void) {
    return START_REQUEST_COUNT;
}

unsigned long vi530x_test_start_request(size_t index) {
    return index < START_REQUEST_COUNT ? start_requests[index] : 0;
}

unsigned int vi530x_test_default_period(void) {
    return VI530X_DEFAULT_PERIOD;
}

int vi530x_test_format(long range, long status, long confidence, char *out, size_t outsz) {
    return vi530x_format(range, status, confidence, out, outsz);
}

void vi530x_test_reset(void) {
    pthread_mutex_lock(&vi530x_lock);
    vi530x_close_locked();
    pthread_mutex_unlock(&vi530x_lock);
}
#endif
