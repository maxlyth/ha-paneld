// hapaneld-ledd — tiny root helper that drives a sysfs-LED panel's RGB on behalf of ha-paneld.
//
// Why this exists: on some panels (e.g. Tuya TPA10) the RGB LED is a sysfs node labelled
// `sysfs_lights` (SELinux), writable only by the lights HAL / system / root. A normal Android app
// (`untrusted_app` domain) cannot write it — and cannot exec `su` to escalate. So this small
// daemon runs OUTSIDE the app sandbox, in a root domain that *can* write the node, and exposes a
// minimal whitelisted command surface on loopback TCP. ha-paneld (which has INTERNET) connects to
// 127.0.0.1 and asks it to set colours. The app stays a single uniform API; the privilege lives
// here.
//
// Security: binds 127.0.0.1 only; a fixed, tiny command set; writes ONLY the two safe nodes below.
// It NEVER writes avs-pwm-led/avsux_select or custom_animation — those are firmware-backed and
// reliably reboot the TPA10.
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
//   RGB <r> <g> <b>   set colour, each 0..255         -> "OK\n"
//   OFF               LED off                          -> "OK\n"
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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h>
#include <signal.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#define PORT       8889
#define NODE_ANIM  "/sys/class/leds/avs-pwm-led/avsux_animation"
#define NODE_BTN   "/sys/class/leds/button-backlight/brightness"
// avsux reverts to the idle animation after <duration_ms>; use ~24h so a set colour holds until
// the next command re-issues it.
#define HOLD_MS    86400000L

static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

// Write a NUL-terminated string to a sysfs node. Returns 0 on success.
static int write_node(const char *path, const char *val) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    ssize_t n = write(fd, val, strlen(val));
    close(fd);
    return n < 0 ? -1 : 0;
}

static int set_rgb(int r, int g, int b) {
    char buf[48];
    snprintf(buf, sizeof buf, "%ld:%02X%02X%02X\n", HOLD_MS, clamp(r), clamp(g), clamp(b));
    return write_node(NODE_ANIM, buf);
}

static int set_off(void) {
    char buf[48];
    snprintf(buf, sizeof buf, "%ld:000000\n", HOLD_MS);
    return write_node(NODE_ANIM, buf);
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

// Handle one command line on connection [fd]. Returns nothing; writes a reply.
static void handle(int fd, char *line) {
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
    } else if (strncmp(line, "PING", 4) == 0) {
        reply(fd, "OK\n");
    } else {
        reply(fd, "ERR\n");
    }
}

static void serve(int cfd) {
    char buf[256];
    ssize_t n;
    while ((n = read(cfd, buf, sizeof buf - 1)) > 0) {
        buf[n] = '\0';
        // Split on newlines; handle each non-empty line.
        char *save = NULL, *tok = strtok_r(buf, "\r\n", &save);
        while (tok) {
            handle(cfd, tok);
            tok = strtok_r(NULL, "\r\n", &save);
        }
    }
}

// One thread per connection, so a long-lived SUBSCRIBE stream doesn't block LED/screen commands on
// other connections. Removes the fd from the subscriber registry on disconnect.
static void *conn_thread(void *arg) {
    int cfd = *(int *)arg;
    free(arg);
    serve(cfd);
    sub_del(cfd);
    close(cfd);
    return NULL;
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);   // a dead subscriber's socket must not kill the daemon
    for (int i = 0; i < MAX_SUBS; i++) subs[i] = -1;
    find_backlight();

    // Optional startup watches from args (per-device .rc may pass them): --grab/--watch <node>. The
    // app also sets these via the WATCH command, so args are not required.
    for (int i = 1; i < argc - 1; i++) {
        if (strcmp(argv[i], "--grab") == 0) watch_node(argv[++i], 1);
        else if (strcmp(argv[i], "--watch") == 0) watch_node(argv[++i], 0);
    }

    int sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) { perror("socket"); return 1; }
    int one = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);  // 127.0.0.1 only
    addr.sin_port = htons(PORT);

    if (bind(sfd, (struct sockaddr *)&addr, sizeof addr) < 0) { perror("bind"); return 1; }
    if (listen(sfd, 4) < 0) { perror("listen"); return 1; }
    fprintf(stderr, "hapaneld-ledd listening on 127.0.0.1:%d\n", PORT);

    for (;;) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) { if (errno == EINTR) continue; perror("accept"); break; }
        int *p = malloc(sizeof(int));
        *p = cfd;
        pthread_t t;
        if (pthread_create(&t, NULL, conn_thread, p) != 0) { free(p); close(cfd); continue; }
        pthread_detach(t);
    }
    close(sfd);
    return 0;
}
