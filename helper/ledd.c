// hapaneld-ledd — tiny root helper that drives a panel's RGB LED (root-only sysfs node OR an
// app-denied /dev/ledjni ioctl, auto-detected at startup) on behalf of ha-paneld.
//
// Why this exists: on some panels (e.g. Tuya TPA10) the RGB LED is a sysfs node labelled
// `sysfs_lights` (SELinux), writable only by the lights HAL / system / root. A normal Android app
// (`untrusted_app` domain) cannot write it — and cannot exec `su` to escalate. So this small
// daemon runs OUTSIDE the app sandbox, in a root domain that *can* write the node, and exposes a
// minimal whitelisted command surface on a UNIX-domain socket. ha-paneld connects to it and asks it
// to set colours. The app stays a single uniform API; the privilege lives here.
//
// Security:
//   * Transport is an ABSTRACT-namespace UNIX socket (SOCK_NAME), NOT loopback TCP — so peer
//     credentials are available via SO_PEERCRED. Every connection is authenticated: we accept ONLY
//     ha-paneld's own uid (resolved at runtime from /data/data/<pkg>, which changes per (re)install),
//     plus root and shell for adb debugging. Any other local app is rejected and closed. (The old
//     127.0.0.1:8889 TCP listener had NO auth: any app with INTERNET could REBOOT/SCREENCAP it.)
//   * A fixed, tiny command set; touches ONLY the safe LED/button sysfs nodes below plus the rk3576
//     /dev/ledjni LED ioctl. It NEVER writes avs-pwm-led/avsux_select or custom_animation — those are
//     firmware-backed and reliably reboot the TPA10.
//   * Airtight line parsing: a bounded per-connection line buffer, overlong lines are dropped, and
//     every command argument is width-bounded. Unknown verbs -> "ERR\n".
//   * Resource limits: a cap on concurrent connections and a per-connection idle timeout (which
//     exempts long-lived SUBSCRIBE streams), so a flood can't exhaust the thread-per-conn model.
//
// It also powers the panel backlight on/off (bl_power) for a true, lock-free screen-off: a
// sandboxed app can set Settings brightness but cannot fully power the backlight down, and
// DevicePolicyManager.lockNow() engages the keyguard (PIN on wake). Writing bl_power leaves the
// device Awake (no keyguard), so the screen goes truly dark and wakes without a PIN.
//
// It also instruments hardware buttons the Android input pipeline doesn't deliver to the app: a
// reader thread opens an evdev node and (optionally) EVIOCGRABs it — exclusive grab stops Android
// from acting on the key (e.g. the WF1589T power button no longer sleeps the panel) — then streams
// each key event to SUBSCRIBEd clients. The app decides the node/grab from its DeviceProfile.
//
// Protocol (newline-terminated ASCII; one or more commands per connection):
//   RGB <r> <g> <b>   set colour, each 0..255 (sysfs node OR /dev/ledjni ioctl, auto-detected) -> "OK\n"
//   OFF               LED off                          -> "OK\n"
//   LEDPROBE          which LED backend exists         -> "ledjni\n" | "sysfs\n" | "none\n"
//   BTN <0..255>      button-backlight brightness      -> "OK\n"
//   SCREEN ON|OFF     backlight power (bl_power 0|4)    -> "OK\n"
//   RELOAD <pkg>      force-stop + relaunch an app      -> "OK\n"
//   START <pkg/cls>   launch an activity by component   -> "OK\n"
//   WATCH <evdev> <0|1>  read an input node (1 = EVIOCGRAB it exclusively, suppressing the default
//                        Android action); idempotent per node    -> "OK\n"
//   SUBSCRIBE         this connection then receives async  "KEY <code> <value>\n"  lines for every
//                     key event from WATCHed nodes (held open until the client disconnects) -> "OK\n"
//   REBOOT            reboot the panel                  -> "OK\n" (then goes down)
//   PERFDUMP          CPU/load/temp/gpu/proc snapshot for sandboxed apps -> marker-delimited stream
//   PING              liveness probe                   -> "OK\n"
//   <anything else>                                    -> "ERR\n"

#define _GNU_SOURCE             // struct ucred / SO_PEERCRED
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stddef.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h>
#include <signal.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <linux/input.h>

// Abstract-namespace UNIX socket name (leading NUL added at bind time). Must match the app's
// LocalSocketAddress("hapaneld-ledd", ABSTRACT) in HelperClient/EvdevButtonClient.
#define SOCK_NAME  "hapaneld-ledd"
// ha-paneld's data dir — we stat() it to learn the app's uid (it changes on every reinstall).
#define APP_DATA   "/data/data/io.github.maxlyth.hapaneld"
#define MAX_CONN   16            // concurrent connections cap (thread-per-conn backstop)
#define IDLE_SEC   30            // drop a non-SUBSCRIBE connection that sends nothing for this long
#define MAX_LINE   512           // longest accepted command line; longer lines are dropped
#define NODE_ANIM  "/sys/class/leds/avs-pwm-led/avsux_animation"
#define NODE_BTN   "/sys/class/leds/button-backlight/brightness"
// avsux reverts to the idle animation after <duration_ms>; use ~24h so a set colour holds until
// the next command re-issues it.
#define HOLD_MS    86400000L

// Some rk3576 panels (e.g. ZHICAI SMT1019) drive the RGB LED through an ioctl char-device, NOT sysfs.
// The node is system:system 0664 with the SELinux-generic `device` label, so an `untrusted_app` is
// denied the ioctl (EACCES/rc=-13) — but this daemon runs as root and can. Clean-room protocol, the
// same one the app's led_jni.c uses app-direct on panels that allow it: per-channel ioctl, value
// 0..15; off = ioctl(fd, 0x99, 0).
#define DEV_LEDJNI  "/dev/ledjni"
#define LEDJNI_R    0xa1
#define LEDJNI_G    0xa2
#define LEDJNI_B    0xa3
#define LEDJNI_OFF  0x99

// Resolved once at startup by find_led_backend(): which RGB-LED node this panel actually has.
enum { LED_NONE, LED_SYSFS, LED_LEDJNI };
static int led_backend = LED_NONE;

static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

// Write a NUL-terminated string to a sysfs node. Returns 0 on success.
static int write_node(const char *path, const char *val) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    ssize_t n = write(fd, val, strlen(val));
    close(fd);
    return n < 0 ? -1 : 0;
}

// --- sysfs LED backend (e.g. Tuya TPA10): write "<hold_ms>:RRGGBB" to the avsux animation node ----
static int set_rgb_sysfs(int r, int g, int b) {
    char buf[48];
    snprintf(buf, sizeof buf, "%ld:%02X%02X%02X\n", HOLD_MS, clamp(r), clamp(g), clamp(b));
    return write_node(NODE_ANIM, buf);
}

static int set_off_sysfs(void) {
    char buf[48];
    snprintf(buf, sizeof buf, "%ld:000000\n", HOLD_MS);
    return write_node(NODE_ANIM, buf);
}

// --- ioctl LED backend (e.g. ZHICAI SMT1019): per-channel ioctl on /dev/ledjni, value 0..15 --------
// 0..255 (HA range) -> 0..15 (the rk3576 ledjni per-channel range), matching led_jni.c's scaler.
static int led15(int v) { return (clamp(v) * 15) / 255; }

static int set_rgb_ledjni(int r, int g, int b) {
    int fd = open(DEV_LEDJNI, O_RDONLY | O_NOCTTY);   // the vendor's open flags; the driver acts on the ioctl
    if (fd < 0) return -1;
    int rc = 0;
    if (ioctl(fd, LEDJNI_R, led15(r)) < 0 && rc == 0) rc = -1;
    if (ioctl(fd, LEDJNI_G, led15(g)) < 0 && rc == 0) rc = -1;
    if (ioctl(fd, LEDJNI_B, led15(b)) < 0 && rc == 0) rc = -1;
    close(fd);
    return rc;
}

static int set_off_ledjni(void) {
    int fd = open(DEV_LEDJNI, O_RDONLY | O_NOCTTY);
    if (fd < 0) return -1;
    int rc = ioctl(fd, LEDJNI_OFF, 0) < 0 ? -1 : 0;
    close(fd);
    return rc;
}

// Pick the RGB-LED backend once at startup: the rk3576 ioctl char-dev if present (app-denied, so it
// needs us), else the sysfs animation node, else none. The two node types don't coexist on a panel.
static void find_led_backend(void) {
    int fd = open(DEV_LEDJNI, O_RDONLY | O_NOCTTY);
    if (fd >= 0) { close(fd); led_backend = LED_LEDJNI; return; }
    if (access(NODE_ANIM, F_OK) == 0) { led_backend = LED_SYSFS; return; }
    led_backend = LED_NONE;
}

static int set_rgb(int r, int g, int b) {
    return led_backend == LED_LEDJNI ? set_rgb_ledjni(r, g, b) : set_rgb_sysfs(r, g, b);
}

static int set_off(void) {
    return led_backend == LED_LEDJNI ? set_off_ledjni() : set_off_sysfs();
}

static int set_btn(int level) {
    char buf[16];
    snprintf(buf, sizeof buf, "%d\n", clamp(level));
    return write_node(NODE_BTN, buf);
}

// Resolved at startup: first /sys/class/backlight/<dev>/bl_power (empty if none).
static char bl_power_path[256];

static void find_backlight(void) {
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

// Android package name chars only — defends the system() calls below against injection.
static int valid_pkg(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_'))
            return 0;
    return 1;
}

// Force-stop + relaunch a dashboard app (root via this daemon's su domain).
static int reload_pkg(const char *pkg) {
    if (!valid_pkg(pkg)) return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "am force-stop %s", pkg);
    system(cmd);
    snprintf(cmd, sizeof cmd, "monkey -p %s -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1", pkg);
    system(cmd);
    return 0;
}

// Launch an activity by component (pkg/cls) via `am start` — root, so it's not subject to the
// Android 10+ background-activity-launch limits that block an app's own startActivity from a service.
static int start_component(const char *comp) {
    if (!*comp) return -1;
    for (const char *p = comp; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_' || *p == '/'))
            return -1;
    char cmd[256];
    snprintf(cmd, sizeof cmd, "am start -n %s >/dev/null 2>&1", comp);
    system(cmd);
    return 0;
}

static void reply(int fd, const char *s) { (void)!write(fd, s, strlen(s)); }

// Numeric (display-density arg) — defends the system() call below against injection.
static int valid_num(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++) if (!(*p >= '0' && *p <= '9')) return 0;
    return 1;
}
// Lowercase-alnum CPU governor names (schedutil/performance/powersave/interactive/ondemand…).
static int valid_gov(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9') || *p == '_')) return 0;
    return 1;
}

// Forced display density (dpi) via `wm density`; arg = a number, or "reset" for the physical default.
static int set_density(const char *arg) {
    if (strcmp(arg, "reset") == 0) { system("wm density reset"); return 0; }
    if (!valid_num(arg)) return -1;
    char cmd[64];
    snprintf(cmd, sizeof cmd, "wm density %s", arg);
    system(cmd);
    return 0;
}

// Reply the current density as "PHYS=<n> OVER=<n|->" parsed from `wm density` (one line).
static void get_density(int fd) {
    FILE *p = popen("wm density 2>/dev/null", "r");
    char phys[16] = "?", over[16] = "-", buf[160], out[48];
    if (p) {
        while (fgets(buf, sizeof buf, p)) {
            char *c;
            if ((c = strstr(buf, "Physical density:"))) sscanf(c + 17, "%15s", phys);
            else if ((c = strstr(buf, "Override density:"))) sscanf(c + 17, "%15s", over);
        }
        pclose(p);
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
    system(cmd);
    return 0;
}

// Capture the screen as PNG and stream the raw bytes to [fd]. Client half-closes then reads to EOF.
static void screencap_to(int fd) {
    FILE *p = popen("screencap -p", "r");
    if (!p) return;
    char buf[8192]; size_t n;
    while ((n = fread(buf, 1, sizeof buf, p)) > 0) (void)!write(fd, buf, n);
    pclose(p);
}

// --- perf snapshot (PERFDUMP) ---------------------------------------------------------------------
// A sandboxed app (untrusted_app) is SELinux-denied /proc/stat, /proc/loadavg, thermal, and other pids'
// stat, so it can't compute CPU/load/temp/top itself. Root here can. PERFDUMP streams one marker-
// delimited snapshot (client half-closes then reads to EOF, like SCREENCAP); PerfReader parses it.
// Pure file reads — no shell, no globbing exec.

// utime(field14)+stime(field15) from a /proc/<pid>[/task/<tid>]/stat buffer; comm (in the parens) via [comm].
static long stat_jiffies(const char *buf, char *comm, size_t commsz) {
    const char *lp = strchr(buf, '(');
    const char *rp = strrchr(buf, ')');
    if (!lp || !rp || rp < lp) return -1;
    if (comm && commsz) {
        size_t L = (size_t)(rp - lp - 1);
        if (L >= commsz) L = commsz - 1;
        memcpy(comm, lp + 1, L); comm[L] = '\0';
    }
    // Fields after ')': idx0 = state (a char), ... utime = idx11, stime = idx12. Walk tokens as strings
    // (state isn't numeric) and convert only 11 + 12.
    const char *p = rp + 1;
    long utime = -1, stime = -1;
    int idx = 0;
    while (*p) {
        while (*p == ' ') p++;
        if (!*p) break;
        if (idx == 11) utime = strtol(p, NULL, 10);
        else if (idx == 12) { stime = strtol(p, NULL, 10); break; }
        while (*p && *p != ' ') p++;
        idx++;
    }
    return (utime >= 0 && stime >= 0) ? utime + stime : -1;
}

static void cat_to(int out, const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return;
    char b[4096]; ssize_t n;
    while ((n = read(fd, b, sizeof b)) > 0) (void)!write(out, b, n);
    close(fd);
}

// First line of [path] into [dst] (NUL-terminated, newline stripped). dst[0]='\0' on failure.
static void first_line(const char *path, char *dst, size_t dstsz) {
    dst[0] = '\0';
    int fd = open(path, O_RDONLY);
    if (fd < 0) return;
    ssize_t n = read(fd, dst, dstsz - 1);
    close(fd);
    if (n <= 0) { dst[0] = '\0'; return; }
    dst[n] = '\0';
    char *nl = strchr(dst, '\n'); if (nl) *nl = '\0';
}

static void perfdump_to(int fd) {
    char out[320];
    reply(fd, "@STAT\n");
    cat_to(fd, "/proc/stat");

    char load[256]; first_line("/proc/loadavg", load, sizeof load);
    snprintf(out, sizeof out, "@LOAD %s\n", load[0] ? load : "-"); reply(fd, out);

    long maxt = -1;                              // max thermal_zone*/temp (millidegrees)
    DIR *dt = opendir("/sys/class/thermal");
    if (dt) {
        struct dirent *e; char path[256], b[32];
        while ((e = readdir(dt))) {
            if (strncmp(e->d_name, "thermal_zone", 12) != 0) continue;
            snprintf(path, sizeof path, "/sys/class/thermal/%s/temp", e->d_name);
            first_line(path, b, sizeof b);
            if (b[0]) { long t = strtol(b, NULL, 10); if (t > maxt) maxt = t; }
        }
        closedir(dt);
    }
    snprintf(out, sizeof out, "@TEMP %ld\n", maxt); reply(fd, out);

    char gpu[64] = "-";                          // first devfreq *gpu*/load ("<load>@<freq>Hz")
    DIR *dg = opendir("/sys/class/devfreq");
    if (dg) {
        struct dirent *e; char path[256];
        while ((e = readdir(dg))) {
            if (e->d_name[0] == '.' || !strstr(e->d_name, "gpu")) continue;
            snprintf(path, sizeof path, "/sys/class/devfreq/%s/load", e->d_name);
            first_line(path, gpu, sizeof gpu);
            if (gpu[0]) break; else snprintf(gpu, sizeof gpu, "-");
        }
        closedir(dg);
    }
    snprintf(out, sizeof out, "@GPU %s\n", gpu); reply(fd, out);

    reply(fd, "@PROC\n");                        // pid \t utime+stime \t comm; collect renderer pids
    int rend[32]; int rn = 0;
    DIR *dp = opendir("/proc");
    if (dp) {
        struct dirent *e;
        while ((e = readdir(dp))) {
            if (!valid_num(e->d_name)) continue;
            char path[64], b[1024], comm[64];
            snprintf(path, sizeof path, "/proc/%s/stat", e->d_name);
            int f = open(path, O_RDONLY); if (f < 0) continue;
            ssize_t n = read(f, b, sizeof b - 1); close(f);
            if (n <= 0) continue; b[n] = '\0';
            long j = stat_jiffies(b, comm, sizeof comm); if (j < 0) continue;
            // Full name from cmdline argv0 (comm is truncated to 15 chars, losing the head — "axlyth.hapaneld"
            // not "io.github.maxlyth.hapaneld"); comm is the fallback for kernel threads (empty cmdline) and
            // isolated renderers (cmdline unreadable in the su domain).
            char cl[160], name[160];
            snprintf(path, sizeof path, "/proc/%s/cmdline", e->d_name);
            int cf = open(path, O_RDONLY);
            ssize_t cn = (cf >= 0) ? read(cf, cl, sizeof cl - 1) : -1;
            if (cf >= 0) close(cf);
            if (cn > 0) { cl[cn] = '\0'; snprintf(name, sizeof name, "%s", cl); }  // "%s" stops at argv0's NUL
            else snprintf(name, sizeof name, "%s", comm);
            for (char *t = name; *t; t++) if (*t == '\t') *t = ' ';                // tabs would break parsing
            snprintf(out, sizeof out, "%s\t%ld\t%s\n", e->d_name, j, name); reply(fd, out);
            // Chromium renderer: main-thread comm is the truncated tail of "…SandboxedProcessService0:N"
            // (e.g. "ocessService0:1") — match "cessService". (cmdline has the full name but the su domain
            // can't read an isolated process's cmdline; comm from stat is readable.)
            if (rn < 32 && strstr(comm, "cessService")) rend[rn++] = atoi(e->d_name);
        }
        closedir(dp);
    }

    reply(fd, "@REND\n");                        // CrRendererMain thread jiffies per renderer (pid \t jiffies)
    for (int i = 0; i < rn; i++) {
        char tdir[64]; snprintf(tdir, sizeof tdir, "/proc/%d/task", rend[i]);
        DIR *td = opendir(tdir); if (!td) continue;
        struct dirent *te;
        while ((te = readdir(td))) {
            if (!valid_num(te->d_name)) continue;
            char path[128], cb[32];
            snprintf(path, sizeof path, "/proc/%d/task/%s/comm", rend[i], te->d_name);
            first_line(path, cb, sizeof cb);
            if (strcmp(cb, "CrRendererMain") != 0) continue;
            snprintf(path, sizeof path, "/proc/%d/task/%s/stat", rend[i], te->d_name);
            char sb[1024]; int sf = open(path, O_RDONLY); if (sf < 0) continue;
            ssize_t sn = read(sf, sb, sizeof sb - 1); close(sf);
            if (sn <= 0) continue; sb[sn] = '\0';
            long j = stat_jiffies(sb, NULL, 0); if (j < 0) continue;
            snprintf(out, sizeof out, "%d\t%ld\n", rend[i], j); reply(fd, out);
            break;
        }
        closedir(td);
    }
    reply(fd, "@END\n");
}

// --- button instrumentation: subscriber registry + evdev reader threads ---------------------------
// SUBSCRIBEd client fds receive async "KEY <code> <value>\n" lines. A small fixed registry under a
// mutex; writes that fail (client gone) just drop — the conn thread removes the fd on disconnect.
#define MAX_SUBS 8
static int subs[MAX_SUBS];
static pthread_mutex_t subs_lock = PTHREAD_MUTEX_INITIALIZER;

static void sub_add(int fd) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < MAX_SUBS; i++) if (subs[i] == fd) { pthread_mutex_unlock(&subs_lock); return; }
    for (int i = 0; i < MAX_SUBS; i++) if (subs[i] < 0) { subs[i] = fd; break; }
    pthread_mutex_unlock(&subs_lock);
}
static void sub_del(int fd) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < MAX_SUBS; i++) if (subs[i] == fd) subs[i] = -1;
    pthread_mutex_unlock(&subs_lock);
}
static void sub_broadcast(const char *line) {
    pthread_mutex_lock(&subs_lock);
    for (int i = 0; i < MAX_SUBS; i++)
        if (subs[i] >= 0 && write(subs[i], line, strlen(line)) < 0) subs[i] = -1;  // drop dead fd
    pthread_mutex_unlock(&subs_lock);
}

// WATCHed nodes, deduped so a reconnecting app doesn't double-open (the second EVIOCGRAB would fail).
#define MAX_WATCH 8
static char watched[MAX_WATCH][128];
static int  watch_n = 0;
static pthread_mutex_t watch_lock = PTHREAD_MUTEX_INITIALIZER;

struct watch_arg { char path[128]; int grab; };

// Open an evdev node, optionally grab it exclusively, stream EV_KEY events to subscribers. Re-opens
// on error (node not ready at boot / device unplug) so it self-heals.
static void *evdev_thread(void *arg) {
    struct watch_arg *w = arg;
    for (;;) {
        int fd = open(w->path, O_RDONLY);
        if (fd < 0) { sleep(2); continue; }
        // EVIOCGRAB MUST succeed for grab-to-suppress to work: if another process already holds the
        // grab it fails with EBUSY and we'd read non-exclusively (events reach us AND Android — the
        // key still acts). Surface that instead of silently degrading.
        if (w->grab && ioctl(fd, EVIOCGRAB, (void *)1) < 0)
            fprintf(stderr, "hapaneld-ledd: EVIOCGRAB %s failed (%s) — NOT exclusive\n",
                    w->path, strerror(errno));
        struct input_event ev;
        char line[48];
        while (read(fd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
            // EV_KEY = momentary keys; EV_SW = latching switches (e.g. the TPA10 orange button reports
            // SW_MUTE_DEVICE on gpio-keys, not a key). Stream both; the app decides what each means.
            if (ev.type == EV_KEY || ev.type == EV_SW) {
                snprintf(line, sizeof line, "%s %d %d\n",
                         ev.type == EV_SW ? "SW" : "KEY", ev.code, ev.value);
                sub_broadcast(line);
            }
        }
        close(fd);   // device went away — reopen (grab is released by the close)
        sleep(1);
    }
    return NULL;
}

// Start watching a node once. grab=1 takes exclusive ownership (suppresses the default Android action).
static int watch_node(const char *path, int grab) {
    if (!*path) return -1;
    pthread_mutex_lock(&watch_lock);
    for (int i = 0; i < watch_n; i++)
        if (strcmp(watched[i], path) == 0) { pthread_mutex_unlock(&watch_lock); return 0; }  // already
    if (watch_n >= MAX_WATCH) { pthread_mutex_unlock(&watch_lock); return -1; }
    snprintf(watched[watch_n++], 128, "%s", path);
    pthread_mutex_unlock(&watch_lock);
    struct watch_arg *a = calloc(1, sizeof *a);
    snprintf(a->path, sizeof a->path, "%s", path);
    a->grab = grab;
    pthread_t t;
    if (pthread_create(&t, NULL, evdev_thread, a) != 0) { perror("pthread_create"); free(a); return -1; }
    pthread_detach(t);
    fprintf(stderr, "hapaneld-ledd: %s %s\n", grab ? "grab" : "watch", path);
    return 0;
}

// Handle one command line on connection [fd]. Writes a reply. Sets *subscribed when the connection
// joins the async KEY stream, so serve() can exempt it from the idle timeout (subscribers are meant
// to sit idle, only reading).
static void handle(int fd, char *line, int *subscribed) {
    int r, g, b;
    if (sscanf(line, "RGB %d %d %d", &r, &g, &b) == 3) {
        reply(fd, set_rgb(r, g, b) == 0 ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "OFF", 3) == 0) {
        reply(fd, set_off() == 0 ? "OK\n" : "ERR\n");
    } else if (sscanf(line, "BTN %d", &r) == 1) {
        reply(fd, set_btn(r) == 0 ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "SCREENCAP", 9) == 0) {
        screencap_to(fd);   // raw PNG bytes; serve() closes on the client half-close → client gets EOF
    } else if (strncmp(line, "SCREEN", 6) == 0) {
        char w[8] = "";
        sscanf(line, "SCREEN %7s", w);
        int on = strcasecmp(w, "OFF") != 0;  // anything but OFF -> on
        reply(fd, set_screen(on) == 0 ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "RELOAD", 6) == 0) {
        char pkg[128] = "";
        sscanf(line, "RELOAD %127s", pkg);
        reply(fd, reload_pkg(pkg) == 0 ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "START", 5) == 0) {
        char comp[160] = "";
        sscanf(line, "START %159s", comp);
        reply(fd, start_component(comp) == 0 ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "WATCH", 5) == 0) {
        char path[128] = ""; int grab = 0;
        sscanf(line, "WATCH %127s %d", path, &grab);
        // only absolute /dev/input/ paths — defends against opening arbitrary files
        int ok = strncmp(path, "/dev/input/", 11) == 0 && watch_node(path, grab ? 1 : 0) == 0;
        reply(fd, ok ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "SUBSCRIBE", 9) == 0) {
        sub_add(fd);
        if (subscribed) *subscribed = 1;   // exempt from the idle timeout from here on
        reply(fd, "OK\n");   // KEY lines now stream on this connection until it closes
    } else if (strncmp(line, "REBOOT", 6) == 0) {
        reply(fd, "OK\n");   // reply before we go down
        system("svc power reboot 2>/dev/null || reboot");
    } else if (strncmp(line, "DENSITY", 7) == 0) {
        char arg[16] = ""; sscanf(line, "DENSITY %15s", arg);
        if (arg[0] == '\0') get_density(fd);                                 // get -> "PHYS=.. OVER=.."
        else reply(fd, set_density(arg) == 0 ? "OK\n" : "ERR\n");            // set <n>|reset
    } else if (strncmp(line, "GOV", 3) == 0) {
        char gov[32] = ""; sscanf(line, "GOV %31s", gov);
        reply(fd, set_governor(gov) == 0 ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "PERFDUMP", 8) == 0) {
        perfdump_to(fd);    // marker-delimited snapshot; serve() closes on client half-close → EOF
    } else if (strncmp(line, "LEDPROBE", 8) == 0) {
        // Which RGB-LED backend this panel actually has — lets the app gate the LED entity on a
        // *reachable* node, not merely "a daemon is up" (an old daemon doesn't know this verb and
        // replies "ERR", which the app reads as "assume present", preserving its prior behaviour).
        reply(fd, led_backend == LED_LEDJNI ? "ledjni\n" : led_backend == LED_SYSFS ? "sysfs\n" : "none\n");
    } else if (strncmp(line, "PING", 4) == 0) {
        reply(fd, "OK\n");
    } else {
        reply(fd, "ERR\n");
    }
}

static void serve(int cfd) {
    // Idle timeout: a connection that sends nothing for IDLE_SEC is dropped — unless it SUBSCRIBEs,
    // where sitting idle (only reading the KEY stream) is the whole point. SO_RCVTIMEO affects reads
    // only; the evdev thread's writes to a subscriber keep flowing regardless.
    struct timeval tv = { .tv_sec = IDLE_SEC, .tv_usec = 0 };
    setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);

    char line[MAX_LINE + 1];
    size_t len = 0;
    int overlong = 0;        // current line exceeded MAX_LINE — drop bytes through to the next newline
    int subscribed = 0;
    char buf[256];
    for (;;) {
        ssize_t n = read(cfd, buf, sizeof buf);
        if (n == 0) break;                                    // client (half-)closed — done
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {    // idle timeout fired
                if (subscribed) continue;                     // subscribers are meant to idle — keep
                break;                                        // drop an idle non-subscriber
            }
            break;                                            // other read error
        }
        // Accumulate into a bounded line buffer so a command split across reads still parses, and an
        // overlong (malicious) line is dropped instead of overflowing or being mis-split.
        for (ssize_t i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '\n' || c == '\r') {
                if (!overlong && len > 0) { line[len] = '\0'; handle(cfd, line, &subscribed); }
                len = 0; overlong = 0;
            } else if (len < MAX_LINE) {
                line[len++] = c;
            } else {
                overlong = 1;
            }
        }
    }
}

// --- peer authentication --------------------------------------------------------------------------
// ha-paneld's uid changes on every (re)install, so resolve it live by stat'ing its data dir rather
// than hardcoding it. Accept that uid, plus root (0) and shell (2000) so adb debugging still works.
// Any other local app is rejected — this is the whole point of the UNIX-socket switch.
static uid_t app_uid = (uid_t)-1;   // last resolved ha-paneld uid (cached across stat failures)

static int uid_allowed(uid_t uid) {
    if (uid == 0 || uid == 2000) return 1;                 // root, shell
    struct stat st;
    if (stat(APP_DATA, &st) == 0) app_uid = st.st_uid;     // refresh (data dir may be absent pre-install)
    return app_uid != (uid_t)-1 && uid == app_uid;
}

static volatile int conn_count = 0;   // live connection count, capped at MAX_CONN

// One thread per connection, so a long-lived SUBSCRIBE stream doesn't block LED/screen commands on
// other connections. Removes the fd from the subscriber registry on disconnect.
static void *conn_thread(void *arg) {
    int cfd = *(int *)arg;
    free(arg);
    serve(cfd);
    sub_del(cfd);
    close(cfd);
    __atomic_sub_fetch(&conn_count, 1, __ATOMIC_SEQ_CST);
    return NULL;
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);   // a dead subscriber's socket must not kill the daemon
    for (int i = 0; i < MAX_SUBS; i++) subs[i] = -1;
    find_backlight();
    find_led_backend();

    // Optional startup watches from args (per-device .rc may pass them): --grab/--watch <node>. The
    // app also sets these via the WATCH command, so args are not required.
    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], "--grab") == 0) watch_node(argv[++i], 1);
        else if (strcmp(argv[i], "--watch") == 0) watch_node(argv[++i], 0);
    }

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) { perror("socket"); return 1; }

    // Abstract-namespace UNIX socket: leading NUL in sun_path, name follows. No filesystem entry to
    // create, label (SELinux), permission, or clean up — and it's released automatically on close.
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof SOCK_NAME - 1);
    socklen_t alen = offsetof(struct sockaddr_un, sun_path) + 1 + (sizeof SOCK_NAME - 1);

    if (bind(sfd, (struct sockaddr *)&addr, alen) < 0) { perror("bind"); return 1; }
    if (listen(sfd, MAX_CONN) < 0) { perror("listen"); return 1; }
    fprintf(stderr, "hapaneld-ledd listening on abstract unix socket @%s\n", SOCK_NAME);

    for (;;) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) { if (errno == EINTR) continue; perror("accept"); break; }

        // Authenticate the peer by uid — only possible because this is a UNIX socket. Reject (and
        // close) anything that isn't ha-paneld / root / shell before it can issue a single command.
        struct ucred cred; socklen_t cl = sizeof cred;
        if (getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cred, &cl) < 0 || !uid_allowed(cred.uid)) {
            close(cfd); continue;
        }

        // Cap concurrent connections so a connection flood can't exhaust the thread-per-conn model.
        if (__atomic_add_fetch(&conn_count, 1, __ATOMIC_SEQ_CST) > MAX_CONN) {
            __atomic_sub_fetch(&conn_count, 1, __ATOMIC_SEQ_CST);
            close(cfd); continue;
        }

        int *p = malloc(sizeof(int));
        *p = cfd;
        pthread_t t;
        if (pthread_create(&t, NULL, conn_thread, p) != 0) {
            free(p); close(cfd);
            __atomic_sub_fetch(&conn_count, 1, __ATOMIC_SEQ_CST);
            continue;
        }
        pthread_detach(t);
    }
    close(sfd);
    return 0;
}
