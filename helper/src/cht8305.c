#include "cht8305.h"
#include "util.h"

#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

// The CHT8305 driver exposes temperature and humidity as two separate input devices (names
// "temperature" / "humidity"), each a single EV_ABS / ABS_THROTTLE axis whose current value is the
// reading × 100. Read the live value with EVIOCGABS — that returns the driver's last measurement
// immediately, so there's no blocking wait for the next event.
//
// The /dev/input/eventN number is NOT stable across boots (it depends on probe order), so match by the
// device name via EVIOCGNAME rather than a hardcoded node. Returns the raw centi value, or -1 when no
// input device with that name is found / is unreadable.
// found: set to 1 and *out written when the named device's axis was read (kept separate from the value
// so a genuinely negative reading isn't confused with "no such device"). Returns 1 on success, 0 if not.
static int read_abs_by_name(const char *name, long *out) {
    DIR *d = opendir("/dev/input");
    if (!d) return 0;
    int found = 0;
    struct dirent *e;
    char path[64], nm[80];
    while ((e = readdir(d)) && !found) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        nm[0] = '\0';
        if (ioctl(fd, EVIOCGNAME(sizeof nm), nm) >= 0 && strcmp(nm, name) == 0) {
            struct input_absinfo abs;
            if (ioctl(fd, EVIOCGABS(ABS_THROTTLE), &abs) >= 0) { *out = abs.value; found = 1; }
        }
        close(fd);
    }
    closedir(d);
    return found;
}

void cmd_cht8305(conn_ctx *ctx, const char *args) {
    (void)args;
    long t = 0, h = 0;
    if (!read_abs_by_name("temperature", &t) || !read_abs_by_name("humidity", &h)) {
        reply(ctx->fd, "ERR\n");
        return;
    }
    char out[48];
    snprintf(out, sizeof out, "T=%ld H=%ld\n", t, h);
    reply(ctx->fd, out);
}
