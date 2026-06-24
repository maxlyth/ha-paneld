#include "sysctl.h"
#include "sysexec.h"
#include "util.h"

#include <stdio.h>
#include <string.h>
#include <unistd.h>

// Force-stop + relaunch a dashboard app (root via this daemon's su domain).
static int reload_pkg(const char *pkg) {
    if (!valid_pkg(pkg)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "am force-stop %s", pkg);
    sysexec_run(cmd);
    snprintf(cmd, sizeof cmd, "monkey -p %s -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1", pkg);
    sysexec_run(cmd);
    return 0;
}

// Launch an activity by component (pkg/cls) via `am start` — root, so it's not subject to the
// Android 10+ background-activity-launch limits that block an app's own startActivity from a service.
static int start_component(const char *comp) {
    if (!valid_component(comp)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "am start -n %s >/dev/null 2>&1", comp);
    sysexec_run(cmd);
    return 0;
}

// Forced display density (dpi) via `wm density`; arg = a number, or "reset" for the physical default.
static int set_density(const char *arg) {
    if (strcmp(arg, "reset") == 0) { sysexec_run("wm density reset"); return 0; }
    if (!valid_num(arg)) return -1;
    char cmd[64];
    snprintf(cmd, sizeof cmd, "wm density %s", arg);
    sysexec_run(cmd);
    return 0;
}

// Reply the current density as "PHYS=<n> OVER=<n|->" parsed from `wm density` (one line).
static void get_density(int fd) {
    FILE *p = sysexec_popen_r("wm density 2>/dev/null");
    char phys[16] = "?", over[16] = "-", buf[160], out[48];
    if (p) {
        while (fgets(buf, sizeof buf, p)) {
            char *c;
            if ((c = strstr(buf, "Physical density:"))) sscanf(c + 17, "%15s", phys);
            else if ((c = strstr(buf, "Override density:"))) sscanf(c + 17, "%15s", over);
        }
        sysexec_pclose(p);
    }
    snprintf(out, sizeof out, "PHYS=%s OVER=%s\n", phys, over);
    reply(fd, out);
}

// CPU scaling governor on all cores (cpufreq sysfs is root-writable; the app can read it itself).
static int set_governor(const char *gov) {
    if (!valid_gov(gov)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd,
      "for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo %s > \"$f\" 2>/dev/null; done",
      gov);
    sysexec_run(cmd);
    return 0;
}

// Capture the screen as PNG and stream the raw bytes to [fd]. Client half-closes then reads to EOF.
static void screencap_to(int fd) {
    FILE *p = sysexec_popen_r("screencap -p");
    if (!p) return;
    char buf[8192]; size_t n;
    while ((n = fread(buf, 1, sizeof buf, p)) > 0) (void)!write(fd, buf, n);
    sysexec_pclose(p);
}

void cmd_reload(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    reply(ctx->fd, reload_pkg(pkg) == 0 ? "OK\n" : "ERR\n");
}

void cmd_start(conn_ctx *ctx, const char *args) {
    char comp[160] = "";
    sscanf(args, "%159s", comp);
    reply(ctx->fd, start_component(comp) == 0 ? "OK\n" : "ERR\n");
}

void cmd_reboot(conn_ctx *ctx, const char *args) {
    (void)args;
    reply(ctx->fd, "OK\n");   // reply before we go down
    sysexec_reboot();
}

void cmd_density(conn_ctx *ctx, const char *args) {
    char arg[16] = "";
    sscanf(args, "%15s", arg);
    if (arg[0] == '\0') get_density(ctx->fd);                                 // get -> "PHYS=.. OVER=.."
    else reply(ctx->fd, set_density(arg) == 0 ? "OK\n" : "ERR\n");            // set <n>|reset
}

void cmd_gov(conn_ctx *ctx, const char *args) {
    char gov[32] = "";
    sscanf(args, "%31s", gov);
    reply(ctx->fd, set_governor(gov) == 0 ? "OK\n" : "ERR\n");
}

void cmd_screencap(conn_ctx *ctx, const char *args) {
    (void)args;
    screencap_to(ctx->fd);   // raw PNG bytes; server closes on the client half-close → client gets EOF
}
