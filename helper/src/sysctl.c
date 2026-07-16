#include "sysctl.h"
#include "companion.h"
#include "sysexec.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static int command_ok(int status) {
    return status == 0;
}

static int command_exited_with(int status, int code) {
    return status >= 0 && WIFEXITED(status) && WEXITSTATUS(status) == code;
}

// Force-stop + relaunch a dashboard app (root via this daemon's su domain).
static int reload_pkg(const char *pkg) {
    if (!valid_pkg(pkg)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "am force-stop %s", pkg);
    if (!command_ok(sysexec_run(cmd))) return -1;
    snprintf(cmd, sizeof cmd, "monkey -p %s -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1", pkg);
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
}

// Launch an activity by component (pkg/cls) via `am start` — root, so it's not subject to the
// Android 10+ background-activity-launch limits that block an app's own startActivity from a service.
static int start_component(const char *comp) {
    if (!valid_component(comp)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "am start -n %s >/dev/null 2>&1", comp);
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
}

// Forced display density (dpi) via `wm density`; arg = a number, or "reset" for the physical default.
static int set_density(const char *arg) {
    if (strcmp(arg, "reset") == 0)
        return command_ok(sysexec_run("wm density reset")) ? 0 : -1;
    if (!valid_num(arg)) return -1;
    char cmd[64];
    snprintf(cmd, sizeof cmd, "wm density %s", arg);
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
}

// Read the current density as "PHYS=<n> OVER=<n|->" parsed from `wm density` (one line).
static int get_density(char *out, size_t outsz) {
    FILE *p = sysexec_popen_r("wm density 2>/dev/null");
    if (!p) return -1;
    char phys[16] = "", over[16] = "-", buf[160];
    while (fgets(buf, sizeof buf, p)) {
        char *c;
        if ((c = strstr(buf, "Physical density:"))) sscanf(c + 17, "%15s", phys);
        else if ((c = strstr(buf, "Override density:"))) sscanf(c + 17, "%15s", over);
    }
    int status = sysexec_pclose(p);
    if (!command_ok(status) || phys[0] == '\0') return -1;
    snprintf(out, outsz, "PHYS=%s OVER=%s\n", phys, over);
    return 0;
}

// System font scale (text size) via `settings system font_scale`; arg = a decimal, or "reset" to
// clear the override. Root-only on sandbox-walled panels (the app can't put a system setting there),
// so it's routed here — the daemon counterpart of the su-direct `settings put system font_scale`.
static int set_fontscale(const char *arg) {
    if (strcmp(arg, "reset") == 0)
        return command_ok(sysexec_run("settings delete system font_scale")) ? 0 : -1;
    if (!valid_decimal(arg)) return -1;
    char cmd[64];
    snprintf(cmd, sizeof cmd, "settings put system font_scale %s", arg);
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
}

// Read the current font scale as "SCALE=<v>" parsed from `settings get system font_scale`
// (v="null" when unset → the app reads that as the 1.0 default).
static int get_fontscale(char *out, size_t outsz) {
    FILE *p = sysexec_popen_r("settings get system font_scale 2>/dev/null");
    if (!p) return -1;
    char val[32] = "";
    int read_value = fgets(val, sizeof val, p) != NULL;
    int status = sysexec_pclose(p);
    if (!command_ok(status) || !read_value) return -1;
    char *nl = strpbrk(val, "\r\n");
    if (nl) *nl = '\0';
    if (val[0] == '\0') strcpy(val, "null");
    snprintf(out, outsz, "SCALE=%s\n", val);
    return 0;
}

// CPU scaling governor on all cores (cpufreq sysfs is root-writable; the app can read it itself).
static int set_governor(const char *gov) {
    if (!valid_gov(gov)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd,
      "found=0; failed=0; for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; "
      "do found=1; { echo %s > \"$f\"; } 2>/dev/null || failed=1; done; "
      "[ \"$found\" -eq 1 ] && [ \"$failed\" -eq 0 ]",
      gov);
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
}

// Force-stop a package WITHOUT relaunching it — the "tame" kill (RELOAD's force-stop+monkey is the
// dashboard reload). Refused for critical system packages.
static int stop_pkg(const char *pkg) {
    if (!valid_pkg(pkg) || is_critical_pkg(pkg)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "am force-stop %s", pkg);
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
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
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
}

// Read or set a package's SYSTEM_ALERT_WINDOW (floating-overlay) app-op. Taming records the exact
// prior mode before denying it, so undo can restore default/ignore/foreground rather than blindly
// granting a permission the package did not have. Restrictive modes remain refused for critical apps.
static int valid_overlay_mode(const char *mode) {
    return strcmp(mode, "allow") == 0 || strcmp(mode, "deny") == 0 ||
           strcmp(mode, "ignore") == 0 || strcmp(mode, "default") == 0 ||
           strcmp(mode, "foreground") == 0;
}

static int set_overlay(const char *pkg, const char *mode) {
    if (!valid_pkg(pkg) || !valid_overlay_mode(mode)) return -1;
    if (is_critical_pkg(pkg) && strcmp(mode, "allow") != 0 && strcmp(mode, "default") != 0) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "appops set %s SYSTEM_ALERT_WINDOW %s >/dev/null 2>&1", pkg, mode);
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
}

static int get_overlay(const char *pkg, char *out, size_t outsz) {
    if (!valid_pkg(pkg)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "appops get %s SYSTEM_ALERT_WINDOW 2>/dev/null", pkg);
    FILE *p = sysexec_popen_r(cmd);
    if (!p) return -1;
    char mode[16] = "";
    char line[256];
    while (fgets(line, sizeof line, p)) {
        char *op = strstr(line, "SYSTEM_ALERT_WINDOW");
        if (!op) continue;
        char *colon = strchr(op, ':');
        if (!colon) continue;
        colon++;
        while (*colon == ' ' || *colon == '\t') colon++;
        size_t n = strcspn(colon, " ;\t\r\n");
        if (n == 0 || n >= sizeof mode) continue;
        memcpy(mode, colon, n);
        mode[n] = '\0';
        if (!valid_overlay_mode(mode)) mode[0] = '\0';
        else break;
    }
    int status = sysexec_pclose(p);
    if (!command_ok(status)) return -1;
    // A successful query with no explicit entry means the package is using the platform default.
    if (mode[0] == '\0') strcpy(mode, "default");
    snprintf(out, outsz, "MODE=%s\n", mode);
    return 0;
}

// Set the default HOME (launcher) to [comp] (pkg/cls). ha-paneld re-asserts the dashboard app as the
// default home after a package change clears the association (declaring a new HOME activity resets it).
// Bounded to a valid component; the target package's own manifest still governs whether it can be home.
static int set_home(const char *comp) {
    if (!valid_component(comp)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "cmd package set-home-activity %s >/dev/null 2>&1", comp);
    return command_ok(sysexec_run(cmd)) ? 0 : -1;
}

// Report a package's state for the app watchdog: "DEAD" (no live process), "FG" (alive and the
// focused window), or "BG" (alive but not focused). Read-only: `pidof` for liveness, then a scan of
// `dumpsys window` for the "<pkg>/" component on the mCurrentFocus line. The pkg is validated.
static int app_state(const char *pkg, char *out, size_t outsz) {
    if (!valid_pkg(pkg)) return -1;
    char cmd[160];
    snprintf(cmd, sizeof cmd, "pidof %s 2>/dev/null", pkg);
    FILE *p = sysexec_popen_r(cmd);
    if (!p) return -1;
    int alive = 0;
    char b[64];
    if (fgets(b, sizeof b, p) && b[0] && b[0] != '\n') alive = 1;
    int status = sysexec_pclose(p);
    // pidof exits 1 when there is no matching process; that is a truthful DEAD result, not a probe
    // failure. Any other non-zero status means the liveness probe itself failed.
    if (!command_ok(status) && !(command_exited_with(status, 1) && !alive)) return -1;
    if (!alive) { snprintf(out, outsz, "DEAD"); return 0; }
    int fg = 0;
    p = sysexec_popen_r("dumpsys window 2>/dev/null");
    if (!p) return -1;
    char needle[160];
    snprintf(needle, sizeof needle, "%s/", pkg);
    char line[512];
    while (fgets(line, sizeof line, p)) {
        if (strstr(line, "mCurrentFocus") && strstr(line, needle)) { fg = 1; break; }
    }
    if (!command_ok(sysexec_pclose(p))) return -1;
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
    char pkg[128] = "", extra[2] = "";
    if (sscanf(args, "%127s %1s", pkg, extra) != 1) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    int guard = companion_guard_package_launch(pkg);
    if (guard == COMPANION_GUARD_BUSY) {
        reply(ctx->fd, "BUSY\n");
        return;
    }
    int result = reload_pkg(pkg);
    companion_guard_release(guard);
    reply(ctx->fd, result == 0 ? "OK\n" : "ERR\n");
}

void cmd_start(conn_ctx *ctx, const char *args) {
    char comp[160] = "", extra[2] = "";
    if (sscanf(args, "%159s %1s", comp, extra) != 1) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    int guard = companion_guard_component_launch(comp);
    if (guard == COMPANION_GUARD_BUSY) {
        reply(ctx->fd, "BUSY\n");
        return;
    }
    int result = start_component(comp);
    companion_guard_release(guard);
    reply(ctx->fd, result == 0 ? "OK\n" : "ERR\n");
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
    if (arg[0] == '\0') {
        char out[48];
        reply(ctx->fd, get_density(out, sizeof out) == 0 ? out : "ERR\n");    // get -> "PHYS=.. OVER=.."
    } else reply(ctx->fd, set_density(arg) == 0 ? "OK\n" : "ERR\n");        // set <n>|reset
}

void cmd_fontscale(conn_ctx *ctx, const char *args) {
    char arg[16] = "";
    sscanf(args, "%15s", arg);
    if (arg[0] == '\0') {
        char out[48];
        reply(ctx->fd, get_fontscale(out, sizeof out) == 0 ? out : "ERR\n");  // get -> "SCALE=.."
    } else reply(ctx->fd, set_fontscale(arg) == 0 ? "OK\n" : "ERR\n");      // set <scale>|reset
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
    char pkg[128] = "", mode[16] = "", extra[2] = "";
    int fields = sscanf(args, "%127s %15s %1s", pkg, mode, extra);
    if (fields == 1) {
        char out[32];
        reply(ctx->fd, get_overlay(pkg, out, sizeof out) == 0 ? out : "ERR\n");
    } else if (fields == 2) {
        reply(ctx->fd, set_overlay(pkg, mode) == 0 ? "OK\n" : "ERR\n");
    } else {
        reply(ctx->fd, "ERR\n");
    }
}

// Root install of an APK that ha-paneld has ALREADY downloaded to its own data dir AND verified (the
// app checks the pinned signer + allowlisted package before calling this — see CompanionInstaller).
// The daemon's independent layer: peer-uid (only ha-paneld connects) + valid_apk_path (path must be an
// .apk inside ha-paneld's own data dir — no arbitrary /system, /sdcard or vendor APK). We copy it into
// /data/local/tmp (world-readable label the installer can always read) and pm-install from there. `-d`
// (allow downgrade) is deliberate — future stable<->pre-release channel switching must move either way.
static pthread_mutex_t install_lock = PTHREAD_MUTEX_INITIALIZER;
// A cleanup request may win the mutex before an old INSTALL worker that was submitted but not yet
// scheduled. Keep a bounded daemon-lifetime tombstone so that late worker must fail after the app
// deletes its own input. Retaining a file is safer than evicting a tombstone when this bound is full.
#define MAX_CANCELLED_INSTALLS 64
static char cancelled_installs[MAX_CANCELLED_INSTALLS][256];
static size_t cancelled_install_count;

#ifdef HAPANELD_TEST
#define INSTALL_STAGE_PARENT "/tmp"
#define INSTALL_STAGE_NAME   ".hapaneld-helper-test"
#else
#define INSTALL_STAGE_PARENT "/data/local"
#define INSTALL_STAGE_NAME   ".hapaneld-helper"
#endif
#define INSTALL_STAGE_DIR INSTALL_STAGE_PARENT "/" INSTALL_STAGE_NAME
#define INSTALL_APK_NAME "hapaneld-install.apk"
#define INSTALL_STREAM_STAGE INSTALL_STAGE_DIR "/" INSTALL_APK_NAME
#define MAX_INSTALL_STREAM_BYTES (1024ULL * 1024ULL * 1024ULL)

/* Build a root-owned 0700 compartment below root-controlled /data/local (production). Opening the
 * directory itself with O_NOFOLLOW rejects a preplanted link. Do not put this below /data/local/tmp:
 * Android's shell user owns that parent and could rename even a root-owned child. Both streaming and
 * legacy cp may safely reopen the stage pathname inside this protected directory. */
static int open_install_dir(void) {
    int parent = open(INSTALL_STAGE_PARENT, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0) return -1;
    struct stat parent_st;
    if (fstat(parent, &parent_st) != 0 || !S_ISDIR(parent_st.st_mode) ||
        (parent_st.st_uid != 0 && parent_st.st_uid != geteuid()) ||
        ((parent_st.st_mode & (S_IWGRP | S_IWOTH)) != 0 && (parent_st.st_mode & S_ISVTX) == 0)) {
        close(parent);
        return -1;
    }
    if (mkdirat(parent, INSTALL_STAGE_NAME, 0700) != 0 && errno != EEXIST) {
        close(parent);
        return -1;
    }
    int fd = openat(parent, INSTALL_STAGE_NAME, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    close(parent);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != geteuid() || fchmod(fd, 0700) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int open_install_stage(void) {
    int dir = open_install_dir();
    if (dir < 0) return -1;
    if (unlinkat(dir, INSTALL_APK_NAME, 0) != 0 && errno != ENOENT) {
        close(dir);
        return -1;
    }
    int fd = openat(
        dir,
        INSTALL_APK_NAME,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
        0644
    );
    close(dir);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() || st.st_nlink != 1) {
        close(fd);
        unlink(INSTALL_STREAM_STAGE);
        return -1;
    }
    return fd;
}

static int install_cancelled(const char *src) {
    for (size_t i = 0; i < cancelled_install_count; i++)
        if (strcmp(cancelled_installs[i], src) == 0) return 1;
    return 0;
}

static int cancel_install(const char *src) {
    if (install_cancelled(src)) return 0;
    if (cancelled_install_count >= MAX_CANCELLED_INSTALLS) return -1;
    snprintf(cancelled_installs[cancelled_install_count++], 256, "%s", src);
    return 0;
}

static int install_apk(const char *src) {
    if (!valid_apk_path(src)) return -1;
    // Every connection has its own worker thread, but the root staging pathname is intentionally fixed.
    // Reject overlap instead of letting two copies/pm installs replace one another's bytes or queue long
    // enough for the second client's ownership timeout to expire.
    if (pthread_mutex_trylock(&install_lock) != 0) return -1;
    if (install_cancelled(src)) {
        pthread_mutex_unlock(&install_lock);
        return -1;
    }
    int stage = open_install_stage();
    if (stage < 0) {
        pthread_mutex_unlock(&install_lock);
        return -1;
    }
    close(stage);
    char cmd[600];
    snprintf(cmd, sizeof cmd,
        "cp '%s' " INSTALL_STREAM_STAGE " 2>/dev/null && chmod 644 " INSTALL_STREAM_STAGE, src);
    int result = -1;
    if (sysexec_run(cmd) == 0) {
        int rc = sysexec_run("pm install -r -d " INSTALL_STREAM_STAGE " 2>&1 | grep -q Success");
        result = rc == 0 ? 0 : -1;
    }
    // Also remove a partial/stale destination when copy or chmod failed after creating it.
    unlink(INSTALL_STREAM_STAGE);
    pthread_mutex_unlock(&install_lock);
    return result;
}

void cmd_install(conn_ctx *ctx, const char *args) {
    char path[256] = "";
    sscanf(args, "%255s", path);
    reply(ctx->fd, install_apk(path) == 0 ? "OK\n" : "ERR\n");
}

static int parse_install_stream_size(const char *args, uint64_t *size) {
    if (!args || !*args) return -1;
    errno = 0;
    char *end = NULL;
    unsigned long long value = strtoull(args, &end, 10);
    if (errno != 0 || end == args || *end != '\0' || value == 0 || value > MAX_INSTALL_STREAM_BYTES)
        return -1;
    *size = (uint64_t)value;
    return 0;
}

static int copy_exact(int input, int output, uint64_t remaining) {
    char buf[65536];
    while (remaining > 0) {
        size_t wanted = remaining < sizeof buf ? (size_t)remaining : sizeof buf;
        ssize_t n = read(input, buf, wanted);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        size_t offset = 0;
        while (offset < (size_t)n) {
            ssize_t written = write(output, buf + offset, (size_t)n - offset);
            if (written < 0) {
                if (errno == EINTR) continue;
                return -1;
            }
            if (written == 0) return -1;
            offset += (size_t)written;
        }
        remaining -= (uint64_t)n;
    }
    char extra;
    for (;;) {
        ssize_t n = read(input, &extra, 1);
        if (n < 0 && errno == EINTR) continue;
        return n == 0 ? 0 : -1;
    }
}

// Two-phase upload keeps binary bytes out of server.c's line accumulator: the client sends only this
// command, waits for READY, then writes exactly <bytes>. The app opened its own private file, while
// this SELinux domain reads only the authenticated socket and writes the root staging path.
void cmd_installstream(conn_ctx *ctx, const char *args) {
    uint64_t size;
    if (parse_install_stream_size(args, &size) != 0) {
        reply(ctx->fd, "STREAMERR\n");
        return;
    }
    if (pthread_mutex_trylock(&install_lock) != 0) {
        reply(ctx->fd, "BUSY\n");
        return;
    }

    int output = open_install_stage();
    if (output < 0 || fchmod(output, 0644) != 0) {
        if (output >= 0) close(output);
        unlink(INSTALL_STREAM_STAGE);
        pthread_mutex_unlock(&install_lock);
        reply(ctx->fd, "STREAMERR\n");
        return;
    }

    reply(ctx->fd, "READY\n");
    // EOF is part of the frame: it proves the client sent neither fewer nor more bytes than declared.
    int copied = copy_exact(ctx->fd, output, size) == 0;
    int closed = close(output) == 0;
    int installed = 0;
    if (copied && closed) {
        char cmd[320];
        snprintf(cmd, sizeof cmd, "pm install -r -d %s 2>&1 | grep -q Success", INSTALL_STREAM_STAGE);
        installed = sysexec_run(cmd) == 0;
    }
    unlink(INSTALL_STREAM_STAGE);
    pthread_mutex_unlock(&install_lock);
    reply(ctx->fd, installed ? "OK\n" : "ERR\n");
}

// Authorise the app to delete a retained input while owning the same mutex as INSTALL. A status-only
// probe would race a worker that received INSTALL but has not acquired the mutex yet. The tombstone
// makes both orders safe without requiring this SELinux domain to write inside the app's data dir:
// an active worker yields BUSY; a late worker acquires the mutex, sees cancellation, and aborts.
void cmd_installgc(conn_ctx *ctx, const char *args) {
    char path[256] = "";
    sscanf(args, "%255s", path);
    if (!valid_apk_path(path)) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    if (pthread_mutex_trylock(&install_lock) != 0) {
        reply(ctx->fd, "BUSY\n");
        return;
    }
    int ok = cancel_install(path) == 0;
    pthread_mutex_unlock(&install_lock);
    reply(ctx->fd, ok ? "OK\n" : "ERR\n");
}
