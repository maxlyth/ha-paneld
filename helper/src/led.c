#include "led.h"
#include "util.h"

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

// --- sysfs backend (e.g. Tuya TPA10): write "<hold_ms>:RRGGBB" to the avsux animation node --------
#define NODE_ANIM  "/sys/class/leds/avs-pwm-led/avsux_animation"
#define NODE_BTN   "/sys/class/leds/button-backlight/brightness"
// avsux reverts to the idle animation after <duration_ms>; use ~24h so a set colour holds until the
// next command re-issues it.
#define HOLD_MS    86400000L
//
// CAUTION: never write avs-pwm-led/avsux_select or custom_animation — those are firmware-backed and
// reliably reboot the TPA10 (verified 2026-06-03).

// --- ioctl backend (e.g. ZHICAI SMT1019 rk3576): per-channel ioctl on /dev/ledjni, value 0..15 ----
// The node is system:system 0664 with the SELinux-generic `device` label, so an untrusted_app is
// denied the ioctl (EACCES) — but this daemon runs as root. Clean-room protocol, the same one the
// app's led_jni.c uses app-direct on panels that allow it.
#define DEV_LEDJNI  "/dev/ledjni"
#define LEDJNI_R    0xa1
#define LEDJNI_G    0xa2
#define LEDJNI_B    0xa3
#define LEDJNI_OFF  0x99

enum { LED_NONE, LED_SYSFS, LED_LEDJNI };
static int led_backend = LED_NONE;

static int set_rgb_sysfs(int r, int g, int b) {
    char buf[48];
    snprintf(buf, sizeof buf, "%ld:%02X%02X%02X\n", HOLD_MS, clamp(r), clamp(g), clamp(b));
    return write_node(NODE_ANIM, buf);
}

static int set_off_sysfs(void) {
    char buf[48];
    snprintf(buf, sizeof buf, "%ld:000000\n", HOLD_MS);
    return write_node(NODE_ANIM, buf);
}

// 0..255 (HA range) -> 0..15 (the rk3576 ledjni per-channel range), matching led_jni.c's scaler.
int led15(int v) { return (clamp(v) * 15) / 255; }

// Emit the per-channel ioctls on an already-open fd. Split from the open() so unit tests can drive it
// with their own fd (and a wrapped ioctl) — the SMT1019 hardware isn't available to test against.
int led_ledjni_rgb(int fd, int r, int g, int b) {
    int rc = 0;
    if (ioctl(fd, LEDJNI_R, led15(r)) < 0 && rc == 0) rc = -1;
    if (ioctl(fd, LEDJNI_G, led15(g)) < 0 && rc == 0) rc = -1;
    if (ioctl(fd, LEDJNI_B, led15(b)) < 0 && rc == 0) rc = -1;
    return rc;
}

int led_ledjni_off(int fd) {
    return ioctl(fd, LEDJNI_OFF, 0) < 0 ? -1 : 0;
}

static int set_rgb_ledjni(int r, int g, int b) {
    int fd = open(DEV_LEDJNI, O_RDONLY | O_NOCTTY);   // vendor's open flags; the driver acts on the ioctl
    if (fd < 0) return -1;
    int rc = led_ledjni_rgb(fd, r, g, b);
    close(fd);
    return rc;
}

static int set_off_ledjni(void) {
    int fd = open(DEV_LEDJNI, O_RDONLY | O_NOCTTY);
    if (fd < 0) return -1;
    int rc = led_ledjni_off(fd);
    close(fd);
    return rc;
}

// Pick the backend once: the rk3576 ioctl char-dev if present (app-denied, so it needs us), else the
// sysfs animation node, else none. The two node types don't coexist on a panel.
void led_init(void) {
    int fd = open(DEV_LEDJNI, O_RDONLY | O_NOCTTY);
    if (fd >= 0) { close(fd); led_backend = LED_LEDJNI; return; }
    if (access(NODE_ANIM, F_OK) == 0) { led_backend = LED_SYSFS; return; }
    led_backend = LED_NONE;
}

const char *led_backend_name(void) {
    return led_backend == LED_LEDJNI ? "ledjni" : led_backend == LED_SYSFS ? "sysfs" : "none";
}

static int set_rgb(int r, int g, int b) {
    return led_backend == LED_LEDJNI ? set_rgb_ledjni(r, g, b) : set_rgb_sysfs(r, g, b);
}
static int set_off(void) {
    return led_backend == LED_LEDJNI ? set_off_ledjni() : set_off_sysfs();
}
static int set_btn(int level) {
    char buf[16];
    snprintf(buf, sizeof buf, "%d\n", clamp(level));
    return write_node(NODE_BTN, buf);
}

void cmd_rgb(conn_ctx *ctx, const char *args) {
    int r, g, b;
    if (sscanf(args, "%d %d %d", &r, &g, &b) == 3)
        reply(ctx->fd, set_rgb(r, g, b) == 0 ? "OK\n" : "ERR\n");
    else
        reply(ctx->fd, "ERR\n");
}

void cmd_off(conn_ctx *ctx, const char *args) {
    (void)args;
    reply(ctx->fd, set_off() == 0 ? "OK\n" : "ERR\n");
}

void cmd_btn(conn_ctx *ctx, const char *args) {
    int level;
    if (sscanf(args, "%d", &level) == 1)
        reply(ctx->fd, set_btn(level) == 0 ? "OK\n" : "ERR\n");
    else
        reply(ctx->fd, "ERR\n");
}

// Which RGB-LED backend this panel actually has — lets the app gate the LED entity on a *reachable*
// node, not merely "a daemon is up". (An old daemon doesn't know this verb and replies "ERR", which
// the app reads as "assume present", preserving its prior behaviour.)
void cmd_ledprobe(conn_ctx *ctx, const char *args) {
    (void)args;
    char out[16];
    snprintf(out, sizeof out, "%s\n", led_backend_name());
    reply(ctx->fd, out);
}
