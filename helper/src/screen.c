#include "screen.h"
#include "util.h"

#include <dirent.h>
#include <stdio.h>
#include <string.h>

// Resolved at startup: first /sys/class/backlight/<dev>/bl_power (empty if none).
static char bl_power_path[256];

void screen_init(void) {
    const char *dir = "/sys/class/backlight";
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        snprintf(bl_power_path, sizeof bl_power_path, "%s/%s/bl_power", dir, e->d_name);
        break;  // first backlight device
    }
    closedir(d);
}

// FB_BLANK: 0 = unblank (on), 4 = powerdown (off).
static int set_screen(int on) {
    if (bl_power_path[0] == '\0') return -1;
    return write_node(bl_power_path, on ? "0\n" : "4\n");
}

void cmd_screen(conn_ctx *ctx, const char *args) {
    char w[8] = "";
    sscanf(args, "%7s", w);
    int on = strcasecmp(w, "OFF") != 0;  // anything but OFF -> on
    reply(ctx->fd, set_screen(on) == 0 ? "OK\n" : "ERR\n");
}
