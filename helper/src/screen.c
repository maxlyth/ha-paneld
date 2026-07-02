#include "screen.h"
#include "util.h"

#include <dirent.h>
#include <stdio.h>
#include <string.h>

// Resolved at startup: the first /sys/class/backlight/<dev>/ directory (empty if none).
static char bl_dir[240];
static char bl_power_path[256];

void screen_init(void) {
    const char *dir = "/sys/class/backlight";
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        snprintf(bl_dir, sizeof bl_dir, "%s/%s", dir, e->d_name);
        snprintf(bl_power_path, sizeof bl_power_path, "%s/bl_power", bl_dir);
        break;  // first backlight device
    }
    closedir(d);
}

// Read one small integer node from the backlight dir; -1 on any failure.
static int read_bl_int(const char *leaf) {
    if (bl_dir[0] == '\0') return -1;
    char path[256];
    snprintf(path, sizeof path, "%s/%s", bl_dir, leaf);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    int v = -1;
    if (fscanf(f, "%d", &v) != 1) v = -1;
    fclose(f);
    return v;
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

// BLREAD — reply "<actual> <max>" from the backlight sysfs. The app is SELinux-denied on these
// nodes on newer Android (TPA10/Android 11), so effective-brightness reporting needs the daemon.
void cmd_blread(conn_ctx *ctx, const char *args) {
    (void)args;
    int actual = read_bl_int("actual_brightness");
    int max = read_bl_int("max_brightness");
    if (actual < 0 || max <= 0) { reply(ctx->fd, "ERR\n"); return; }
    char out[48];
    snprintf(out, sizeof out, "%d %d\n", actual, max);
    reply(ctx->fd, out);
}

// BLSET <n> — write the hardware brightness node (0..max, clamped; bounded parse). Lets the app
// drive the real backlight past a firmware idle-dim on panels where it has no su.
void cmd_blset(conn_ctx *ctx, const char *args) {
    int v = -1;
    if (sscanf(args, "%7d", &v) != 1 || v < 0) { reply(ctx->fd, "ERR\n"); return; }
    int max = read_bl_int("max_brightness");
    if (max <= 0 || bl_dir[0] == '\0') { reply(ctx->fd, "ERR\n"); return; }
    if (v > max) v = max;
    char path[256], buf[16];
    snprintf(path, sizeof path, "%s/brightness", bl_dir);
    snprintf(buf, sizeof buf, "%d\n", v);
    reply(ctx->fd, write_node(path, buf) == 0 ? "OK\n" : "ERR\n");
}
