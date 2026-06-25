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

// Decimal number (e.g. font-scale arg "0.85"/"1.15") — digits with at most one dot, at least one
// digit. No sign/exponent/whitespace, so it's safe to interpolate into a sysexec_run() shell string.
int valid_decimal(const char *s) {
    if (!*s) return 0;
    int dot = 0, digit = 0;
    for (const char *p = s; *p; p++) {
        if (*p == '.') { if (dot++) return 0; }
        else if (*p >= '0' && *p <= '9') digit = 1;
        else return 0;
    }
    return digit;
}

// Packages this daemon will NEVER stop / disable / overlay-deny, even when asked — the privileged
// backstop against a buggy or hostile caller bricking the panel by tearing down the system UI,
// Settings, telephony, the framework, or ha-paneld's own controller. The app enforces its own block
// list too; this is defense-in-depth at the one place that actually holds root. (Re-enabling — ENABLE
// / overlay allow — is always permitted, so a panel can never get stuck disabled.)
int is_critical_pkg(const char *s) {
    static const char *const CRIT[] = {
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "io.github.maxlyth.hapaneld",
    };
    for (size_t i = 0; i < sizeof CRIT / sizeof CRIT[0]; i++)
        if (strcmp(s, CRIT[i]) == 0) return 1;
    return 0;
}

// Lowercase-alnum CPU governor names (schedutil/performance/powersave/interactive/ondemand…).
int valid_gov(const char *s) {
    if (!*s) return 0;
    for (const char *p = s; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9') || *p == '_')) return 0;
    return 1;
}

// Even-length hex string of at most 508 characters (= 254 raw dataset bytes, the Thread operational
// dataset max). Only [0-9a-fA-F] are accepted — safe to pass as-is to cmd_thread_commission.
int valid_hex_dataset(const char *s) {
    if (!s || !*s) return 0;
    size_t n = strlen(s);
    if (n % 2 != 0 || n > 508) return 0;
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
            return 0;
    }
    return 1;
}

// Absolute path to a GBL firmware image: must start with '/', contain no ".." component, contain no
// single-quote (the path is interpolated inside a single-quoted cp argument), and end with ".gbl".
int valid_gbl_path(const char *s) {
    if (!s || s[0] != '/') return 0;
    if (strstr(s, "..")) return 0;
    size_t n = strlen(s);
    if (n < 5 || strcmp(s + n - 4, ".gbl") != 0) return 0;
    for (size_t i = 0; i < n; i++) if (s[i] == '\'') return 0;
    return 1;
}
