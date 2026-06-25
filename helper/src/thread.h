#ifndef HAPANELD_THREAD_H
#define HAPANELD_THREAD_H

#include "cmd.h"

// THREAD_FLASH <src_gbl_path>
// Flash the EFR32 NCP with a new firmware image using zgateway's startup OTA mechanism:
//   1. Kills any running zgateway/mosquitto (releases /dev/ttyS5).
//   2. Copies the .gbl to the trigger path the gateway polls at startup.
//   3. Starts zgateway; it flashes the NCP via EZSP bootloader + XMODEM, then removes the file.
//   4. Polls for file removal (= success) with a 90 s deadline.
// Replies "OK\n" on success; "ERR:path\n" for invalid src; "ERR:flash\n" on timeout or copy failure.
// The connection is held open for the duration (up to 90 s); the client must use a matching timeout.
void cmd_thread_flash(conn_ctx *ctx, const char *args);

// THREAD_STATUS
// Returns "THREAD\n" when a Thread NCP state marker exists (written by a successful THREAD_FLASH),
// "EZSP\n" when the EFR32 is presumed to be on factory Zigbee NCP firmware (no marker but the
// gateway binary is present), or "NONE\n" when no EFR32 gateway is installed on this panel.
void cmd_thread_status(conn_ctx *ctx, const char *args);

#endif
