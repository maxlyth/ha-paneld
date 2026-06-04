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
// Protocol (newline-terminated ASCII; one or more commands per connection):
//   RGB <r> <g> <b>   set colour, each 0..255         -> "OK\n"
//   OFF               LED off                          -> "OK\n"
//   BTN <0..255>      button-backlight brightness      -> "OK\n"
//   SCREEN ON|OFF     backlight power (bl_power 0|4)    -> "OK\n"
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
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

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

static void reply(int fd, const char *s) { (void)!write(fd, s, strlen(s)); }

// Handle one command line. Returns nothing; writes a reply.
static void handle(int fd, char *line) {
    int r, g, b;
    if (sscanf(line, "RGB %d %d %d", &r, &g, &b) == 3) {
        reply(fd, set_rgb(r, g, b) == 0 ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "OFF", 3) == 0) {
        reply(fd, set_off() == 0 ? "OK\n" : "ERR\n");
    } else if (sscanf(line, "BTN %d", &r) == 1) {
        reply(fd, set_btn(r) == 0 ? "OK\n" : "ERR\n");
    } else if (strncmp(line, "SCREEN", 6) == 0) {
        char w[8] = "";
        sscanf(line, "SCREEN %7s", w);
        int on = strcasecmp(w, "OFF") != 0;  // anything but OFF -> on
        reply(fd, set_screen(on) == 0 ? "OK\n" : "ERR\n");
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

int main(void) {
    find_backlight();
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
        serve(cfd);
        close(cfd);
    }
    close(sfd);
    return 0;
}
