#include "util.h"

#include <fcntl.h>
#include <string.h>
#include <unistd.h>

int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

int write_node(const char *path, const char *val) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    ssize_t n = write(fd, val, strlen(val));
    close(fd);
    return n < 0 ? -1 : 0;
}

void reply(int fd, const char *s) { (void)!write(fd, s, strlen(s)); }

void first_line(const char *path, char *dst, size_t dstsz) {
    dst[0] = '\0';
    int fd = open(path, O_RDONLY);
    if (fd < 0) return;
    ssize_t n = read(fd, dst, dstsz - 1);
    close(fd);
    if (n <= 0) { dst[0] = '\0'; return; }
    dst[n] = '\0';
    char *nl = strchr(dst, '\n'); if (nl) *nl = '\0';
}

void cat_to(int out, const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return;
    char b[4096]; ssize_t n;
    while ((n = read(fd, b, sizeof b)) > 0) (void)!write(out, b, n);
    close(fd);
}

// Android package name chars only — defends the sysexec_run() command strings against injection.
int valid_pkg(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_'))
            return 0;
    return 1;
}

// Component (pkg/class) — package chars plus the '/' separator.
int valid_component(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_' || *p == '/'))
            return 0;
    return 1;
}

// Numeric (e.g. display-density arg) — decimal digits only.
int valid_num(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++) if (!(*p >= '0' && *p <= '9')) return 0;
    return 1;
}

// Lowercase-alnum CPU governor names (schedutil/performance/powersave/interactive/ondemand…).
int valid_gov(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9') || *p == '_')) return 0;
    return 1;
}
