// Range verb for the Evisionics VI530x time-of-flight sensor (SMT1019 / WF2489T boards).
//
// Unlike every other sensor this helper reads, the VI530x publishes NOTHING until userspace starts it:
// the driver registers its input device and its /dev/vi530x misc node at probe, but reports no
// measurement until POWER_ON, CHIP_INIT, PERIOD and START have been issued as ioctls. Watching the
// input node alone therefore looks exactly like dead hardware, which is the trap this verb exists to
// remove. Sequence and struct layout are taken from the vendor's own driver and userspace sample
// published with GitHub #106.
//
// Measurements are read back with the driver's MZ_DATA ioctl rather than from the input device: it
// returns the whole record — range, status, confidence — in one call on the descriptor already held,
// with no name matching, axis mapping or wait for the next event.
#ifndef HAPANELD_VI530X_H
#define HAPANELD_VI530X_H

#include "cmd.h"

#include <stdint.h>
#include <sys/ioctl.h>

// The driver ABI, replicated verbatim from the vendor driver's vi530x.h and its userspace sample,
// which agree byte for byte. It lives in the header so the tests can pin the real layout rather than
// a copy of it: _IOR encodes only sizeof, so a reordering that preserves the size would still read
// every field into the wrong place, and only an offset assertion catches that.
struct vi530x_measurement {
    int16_t range_tof;
    uint32_t time_usec;
    uint32_t range_noise;
    uint32_t range_peak;
    uint32_t range_confidence;
    uint8_t range_status;
    uint32_t range_integral;
    uint16_t range_cg_count;
};

#define VI530X_IOCTL_PERIOD    _IOW('p', 0x01, uint32_t)
#define VI530X_IOCTL_POWER_ON  _IO('p', 0x06)
#define VI530X_IOCTL_CHIP_INIT _IO('p', 0x07)
#define VI530X_IOCTL_START     _IO('p', 0x08)
#define VI530X_IOCTL_MZ_DATA   _IOR('p', 0x0a, struct vi530x_measurement)

// VI530X — reply "D=<range> S=<status> C=<confidence>\n" or "ERR\n" when the sensor is absent, cannot
// be started, or returns no measurement. Values are reported exactly as the driver gives them: the
// range is the vendor's signed RangeTof in millimetres, and no near/far threshold is applied here.
// Status/confidence interpretation and the usable band have not yet been established on hardware;
// deciding what counts as "near" remains the caller's job.
void cmd_vi530x(conn_ctx *ctx, const char *args);

#ifdef HAPANELD_TEST
#include <stddef.h>
// The exact ordered start sequence, so a test can pin it without a device.
size_t vi530x_test_start_request_count(void);
unsigned long vi530x_test_start_request(size_t index);
unsigned int vi530x_test_default_period(void);
// Formatting and the publishable/refuse decision, separated from the I/O so both are testable.
int vi530x_test_format(long range, long status, long confidence, char *out, size_t outsz);
void vi530x_test_reset(void);
#endif

#endif
