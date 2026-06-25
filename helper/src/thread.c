#include "thread.h"
#include "sysexec.h"
#include "util.h"

#include <string.h>
#include <unistd.h>

// The Sonoff/CoolKit gateway stack lives here on NSPanel Pro (Rockchip PX30, Android 8.1).
#define EFR32_GW_DIR    "/vendor/bin/siliconlabs_host"
// zgateway checks for this file at startup; if present it flashes it to the EFR32 via EZSP
// standalone bootloader + XMODEM, then removes the file on success (confirmed in zgateway.log).
#define EFR32_OTA_FILE  "/data/vendor/siliconlabs_host/ota-files/device_ota.zigbee"
// Written after a successful THREAD_FLASH so THREAD_STATUS can answer without live UART probing.
#define THREAD_STATE    "/data/vendor/siliconlabs_host/.thread-provisioned"

static int do_thread_flash(const char *src) {
    char cmd[512];

    // Release /dev/ttyS5 so the EZSP bootloader path has exclusive UART access
    sysexec_run("killall zgateway 2>/dev/null; killall mosquitto 2>/dev/null");
    sysexec_run("sleep 1");

    // Place the firmware at the trigger path (zgateway detects this file at startup)
    snprintf(cmd, sizeof cmd,
             "mkdir -p /data/vendor/siliconlabs_host/ota-files"
             " && cp '%s' " EFR32_OTA_FILE
             " && chmod 600 " EFR32_OTA_FILE,
             src);
    if (sysexec_run(cmd) != 0) return -1;

    // Start zgateway — it reads the file, enters standalone bootloader mode via EZSP, then transfers
    // the image via XMODEM (~256 KB at 115200 baud, ~30–60 s), and removes the file on success
    sysexec_run("cd " EFR32_GW_DIR
                " && LD_LIBRARY_PATH=" EFR32_GW_DIR
                " ./zgateway >>/data/vendor/siliconlabs_host/zgateway.log 2>&1 &");

    // Poll for the trigger file's removal (= flash complete). 90 s deadline.
    for (int i = 0; i < 90; i++) {
        sysexec_run("sleep 1");
        if (access(EFR32_OTA_FILE, F_OK) != 0) {
            // File gone — EFR32 is now running the new firmware; kill the stuck gateway
            sysexec_run("killall zgateway 2>/dev/null; killall mosquitto 2>/dev/null");
            sysexec_run("touch " THREAD_STATE);
            return 0;
        }
    }

    // Timeout — clean up (leave EFR32 state unknown; caller should retry or recover manually)
    sysexec_run("killall zgateway 2>/dev/null; killall mosquitto 2>/dev/null");
    sysexec_run("rm -f " EFR32_OTA_FILE);
    return -1;
}

void cmd_thread_flash(conn_ctx *ctx, const char *args) {
    char src[512] = "";
    sscanf(args, "%511s", src);
    if (!valid_gbl_path(src)) { reply(ctx->fd, "ERR:path\n"); return; }
    reply(ctx->fd, do_thread_flash(src) == 0 ? "OK\n" : "ERR:flash\n");
}

void cmd_thread_status(conn_ctx *ctx, const char *args) {
    (void)args;
    if (access(THREAD_STATE, F_OK) == 0)              { reply(ctx->fd, "THREAD\n"); return; }
    if (access(EFR32_GW_DIR "/zgateway", F_OK) != 0)  { reply(ctx->fd, "NONE\n");   return; }
    reply(ctx->fd, "EZSP\n");
}
