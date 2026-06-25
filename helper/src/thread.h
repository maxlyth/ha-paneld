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
// Returns "JOINED\n" once THREAD_COMMISSION has succeeded, "THREAD\n" when Thread NCP firmware is
// flashed but the radio has not yet been commissioned onto a network, "EZSP\n" when the EFR32 is on
// factory Zigbee NCP firmware (no flash marker but gateway binary present), or "NONE\n" otherwise.
void cmd_thread_status(conn_ctx *ctx, const char *args);

// THREAD_COMMISSION <hex-dataset>
// Commission the OpenThread NCP onto the Thread network described by [hex-dataset] (even-length hex
// string of the raw Active Operational Dataset TLV, max 508 hex chars / 254 bytes). Resets the NCP,
// writes the dataset via Spinel PROP_THREAD_ACTIVE_DATASET, then brings the interface up.
// Replies "OK\n" on success; "ERR:hex\n" for an invalid dataset string; "ERR:spinel\n" on UART /
// Spinel communication failure. Blocks up to ~10 s for the three Spinel round-trips.
void cmd_thread_commission(conn_ctx *ctx, const char *args);

#endif
