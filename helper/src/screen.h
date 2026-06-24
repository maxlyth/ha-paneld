// Screen backlight power via /sys/class/backlight/<dev>/bl_power (FB_BLANK 0=on, 4=powerdown). A true
// hardware-level off that leaves the device Awake (no keyguard), so the screen goes dark and wakes
// without a PIN — unreachable from a sandboxed app. The backlight device is auto-discovered once.
#ifndef HAPANELD_SCREEN_H
#define HAPANELD_SCREEN_H

#include "cmd.h"

void screen_init(void);                          // discover the backlight device (call once)
void cmd_screen(conn_ctx *ctx, const char *args);  // SCREEN ON|OFF

#endif
