// RGB LED + button-backlight control. Two backends, auto-detected once at startup:
//   * sysfs avsux animation node (e.g. Tuya TPA10), root-only sysfs_lights label, OR
//   * /dev/ledjni per-channel ioctl (e.g. ZHICAI SMT1019 rk3576), app-denied so the daemon drives it.
// The two never coexist on a panel. See led.c for the wire formats and the firmware-node CAUTION.
#ifndef HAPANELD_LED_H
#define HAPANELD_LED_H

#include "cmd.h"

void led_init(void);                 // pick the backend (call once at startup)
const char *led_backend_name(void);  // "ledjni" | "sysfs" | "none" (for LEDPROBE)

// Command handlers (registered in dispatch.c):
void cmd_rgb(conn_ctx *ctx, const char *args);       // RGB <r> <g> <b>
void cmd_off(conn_ctx *ctx, const char *args);       // OFF
void cmd_btn(conn_ctx *ctx, const char *args);       // BTN <0..255>
void cmd_ledprobe(conn_ctx *ctx, const char *args);  // LEDPROBE

#endif
