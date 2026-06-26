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

// System font scale (text size) via `settings system font_scale`; arg = a decimal, or "reset" to
// clear the override. Root-only on sandbox-walled panels (the app can't put a system setting there),
// so it's routed here — the daemon counterpart of the su-direct `settings put system font_scale`.
static int set_fontscale(const char *arg) {
    if (strcmp(arg, "reset") == 0) { sysexec_run("settings delete system font_scale"); return 0; }
    if (!valid_decimal(arg)) return -1;
    char cmd[64];
    snprintf(cmd, sizeof cmd, "settings put system font_scale %s", arg);
    sysexec_run(cmd);
    return 0;
}

// Reply the current font scale as "SCALE=<v>" parsed from `settings get system font_scale`
// (v="null" when unset → the app reads that as the 1.0 default).
static void get_fontscale(int fd) {
    FILE *p = sysexec_popen_r("settings get system font_scale 2>/dev/null");
    char val[32] = "null", out[48];
    if (p) {
        if (fgets(val, sizeof val, p)) { char *nl = strchr(val, '\n'); if (nl) *nl = '\0'; }
        if (val[0] == '\0') strcpy(val, "null");
        sysexec_pclose(p);
    }
    snprintf(out, sizeof out, "SCALE=%s\n", val);
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

// Force-stop a package WITHOUT relaunching it — the "tame" kill (RELOAD's force-stop+monkey is the
// dashboard reload). Refused for critical system packages.
static int stop_pkg(const char *pkg) {
    if (!valid_pkg(pkg) || is_critical_pkg(pkg)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "am force-stop %s", pkg);
    sysexec_run(cmd);
    return 0;
}

// Enable/disable a package for the primary user. Disable (`pm disable-user --user 0`) stops a vendor
// app relaunching on boot and is reversible via ENABLE (`pm enable`); disabling a critical package
// would brick the panel, so that's refused while enabling is always allowed.
static int set_pkg_enabled(const char *pkg, int enabled) {
    if (!valid_pkg(pkg)) return -1;
    if (!enabled && is_critical_pkg(pkg)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "pm %s --user 0 %s >/dev/null 2>&1",
             enabled ? "enable" : "disable-user", pkg);
    sysexec_run(cmd);
    return 0;
}

// Grant/deny a package the SYSTEM_ALERT_WINDOW (floating-overlay) app-op. `deny` strips a vendor
// app's ability to draw a widget over the dashboard; `allow` restores it. Deny refused for critical
// packages; any mode other than deny/allow is rejected.
static int set_overlay(const char *pkg, const char *mode) {
    if (!valid_pkg(pkg)) return -1;
    int deny = strcmp(mode, "deny") == 0;
    if (!deny && strcmp(mode, "allow") != 0) return -1;
    if (deny && is_critical_pkg(pkg)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "appops set %s SYSTEM_ALERT_WINDOW %s >/dev/null 2>&1", pkg, mode);
    sysexec_run(cmd);
    return 0;
}

// Set the default HOME (launcher) to [comp] (pkg/cls). ha-paneld re-asserts the dashboard app as the
// default home after a package change clears the association (declaring a new HOME activity resets it).
// Bounded to a valid component; the target package's own manifest still governs whether it can be home.
static int set_home(const char *comp) {
    if (!valid_component(comp)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "cmd package set-home-activity %s >/dev/null 2>&1", comp);
    sysexec_run(cmd);
    return 0;
}

// Report a package's state for the app watchdog: "DEAD" (no live process), "FG" (alive and the
// focused window), or "BG" (alive but not focused). Read-only: `pidof` for liveness, then a scan of
// `dumpsys window` for the "<pkg>/" component on the mCurrentFocus line. The pkg is validated.
static int app_state(const char *pkg, char *out, size_t outsz) {
    if (!valid_pkg(pkg)) return -1;
    char cmd[160];
    snprintf(cmd, sizeof cmd, "pidof %s 2>/dev/null", pkg);
    FILE *p = sysexec_popen_r(cmd);
    int alive = 0;
    if (p) {
        char b[64];
        if (fgets(b, sizeof b, p) && b[0] && b[0] != '\n') alive = 1;
        sysexec_pclose(p);
    }
    if (!alive) { snprintf(out, outsz, "DEAD"); return 0; }
    int fg = 0;
    p = sysexec_popen_r("dumpsys window 2>/dev/null");
    if (p) {
        char needle[160];
        snprintf(needle, sizeof needle, "%s/", pkg);
        char line[512];
        while (fgets(line, sizeof line, p)) {
            if (strstr(line, "mCurrentFocus") && strstr(line, needle)) { fg = 1; break; }
        }
        sysexec_pclose(p);
    }
    snprintf(out, outsz, "%s", fg ? "FG" : "BG");
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

void cmd_sethome(conn_ctx *ctx, const char *args) {
    char comp[160] = "";
    sscanf(args, "%159s", comp);
    reply(ctx->fd, set_home(comp) == 0 ? "OK\n" : "ERR\n");
}

void cmd_reboot(conn_ctx *ctx, const char *args) {
    (void)args;
    reply(ctx->fd, "OK\n");   // reply before we go down
    sysexec_reboot();
}

void cmd_appstate(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    char st[16];
    if (app_state(pkg, st, sizeof st) != 0) { reply(ctx->fd, "ERR\n"); return; }
    char line[24];
    snprintf(line, sizeof line, "%s\n", st);
    reply(ctx->fd, line);
}

void cmd_density(conn_ctx *ctx, const char *args) {
    char arg[16] = "";
    sscanf(args, "%15s", arg);
    if (arg[0] == '\0') get_density(ctx->fd);                                 // get -> "PHYS=.. OVER=.."
    else reply(ctx->fd, set_density(arg) == 0 ? "OK\n" : "ERR\n");            // set <n>|reset
}

void cmd_fontscale(conn_ctx *ctx, const char *args) {
    char arg[16] = "";
    sscanf(args, "%15s", arg);
    if (arg[0] == '\0') get_fontscale(ctx->fd);                                // get -> "SCALE=.."
    else reply(ctx->fd, set_fontscale(arg) == 0 ? "OK\n" : "ERR\n");           // set <scale>|reset
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

void cmd_stop(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    reply(ctx->fd, stop_pkg(pkg) == 0 ? "OK\n" : "ERR\n");
}

void cmd_disable(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    reply(ctx->fd, set_pkg_enabled(pkg, 0) == 0 ? "OK\n" : "ERR\n");
}

void cmd_enable(conn_ctx *ctx, const char *args) {
    char pkg[128] = "";
    sscanf(args, "%127s", pkg);
    reply(ctx->fd, set_pkg_enabled(pkg, 1) == 0 ? "OK\n" : "ERR\n");
}

void cmd_overlay(conn_ctx *ctx, const char *args) {
    char pkg[128] = "", mode[8] = "";
    sscanf(args, "%127s %7s", pkg, mode);
    reply(ctx->fd, set_overlay(pkg, mode) == 0 ? "OK\n" : "ERR\n");
}
