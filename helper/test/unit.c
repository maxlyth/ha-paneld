// Host-native unit tests for the hapaneld-helper daemon's pure logic. Fuzzing proves the parser doesn't
// CRASH on hostile input; these assert it produces the CORRECT result — the validators reject what
// they should, the byte clamp and /proc parser compute the right values, dispatch routes verbs by
// EXACT match (so SCREEN/SCREENCAP and OFF/OFFOFF can't collide), and the line accumulator reassembles
// split reads and drops overlong lines. Linked against the real src/*.c modules + the sysexec stub.
//
// A clean run prints "UNIT OK" and exits 0; any failed assertion prints the case and exits 1.
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "cmd.h"
#include "dispatch.h"
#include "server.h"
#include "input.h"
#include "led.h"
#include "perf.h"
#include "util.h"

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { \
    printf("FAIL: " __VA_ARGS__); printf("  (%s:%d)\n", __FILE__, __LINE__); failures++; } } while (0)

// --- ioctl interception (linked with -Wl,--wrap=ioctl) -------------------------------------------
// Captures the ledjni per-channel ioctls so we can assert the command numbers + scaled values without
// a real /dev/ledjni (the SMT1019 isn't hardware we own). Returns 0 = success so the handler proceeds.
static unsigned long cap_cmd[8];
static int cap_val[8], cap_n;
int __wrap_ioctl(int fd, unsigned long req, ...) {
    (void)fd;
    va_list ap; va_start(ap, req); int arg = va_arg(ap, int); va_end(ap);
    if (cap_n < 8) { cap_cmd[cap_n] = req; cap_val[cap_n] = arg; cap_n++; }
    return 0;
}

// --- helpers: capture what a handler / the server writes back ------------------------------------
// Run one line through dispatch() and return its reply bytes (NUL-terminated, into `out`).
static void dispatch_reply(const char *line, char *out, size_t outsz) {
    int sv[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    char tmp[MAX_LINE + 1];
    snprintf(tmp, sizeof tmp, "%s", line);
    conn_ctx ctx = { .fd = sv[0], .subscribed = 0 };
    input_init();
    dispatch(&ctx, tmp);
    fcntl(sv[1], F_SETFL, O_NONBLOCK);
    ssize_t n = read(sv[1], out, outsz - 1);
    out[n > 0 ? n : 0] = '\0';
    close(sv[0]); close(sv[1]);
}

// Feed a raw byte stream to server_serve() (half-closed) and return all reply bytes.
static void serve_reply(const char *bytes, size_t len, char *out, size_t outsz) {
    int sv[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    (void)!write(sv[1], bytes, len);
    shutdown(sv[1], SHUT_WR);
    input_init();
    server_serve(sv[0]);
    fcntl(sv[1], F_SETFL, O_NONBLOCK);
    ssize_t n = read(sv[1], out, outsz - 1);
    out[n > 0 ? n : 0] = '\0';
    close(sv[0]); close(sv[1]);
}

static void test_validators(void) {
    // valid_pkg: package chars only; rejects shell metacharacters and traversal.
    CHECK(valid_pkg("com.foo.bar"), "valid_pkg accepts a package\n");
    CHECK(valid_pkg("io.github.maxlyth.hapaneld"), "valid_pkg accepts the app id\n");
    CHECK(!valid_pkg(""), "valid_pkg rejects empty\n");
    CHECK(!valid_pkg(";reboot"), "valid_pkg rejects ;reboot\n");
    CHECK(!valid_pkg("a|b`c$d(e)"), "valid_pkg rejects metachars\n");
    CHECK(!valid_pkg("../../etc/passwd"), "valid_pkg rejects traversal (slash)\n");
    CHECK(!valid_pkg("com.foo bar"), "valid_pkg rejects space\n");

    // valid_component: like valid_pkg but '/' is allowed (pkg/class).
    CHECK(valid_component("com.foo/.Bar"), "valid_component accepts pkg/class\n");
    CHECK(!valid_component("com.foo/.Bar;rm"), "valid_component rejects ';'\n");
    CHECK(!valid_component(""), "valid_component rejects empty\n");

    // valid_num: decimal digits only.
    CHECK(valid_num("240"), "valid_num accepts digits\n");
    CHECK(!valid_num(""), "valid_num rejects empty\n");
    CHECK(!valid_num("-1"), "valid_num rejects sign\n");
    CHECK(!valid_num("12x"), "valid_num rejects trailing junk\n");

    // valid_decimal: digits with at most one dot (font-scale arg).
    CHECK(valid_decimal("1.15"), "valid_decimal accepts 1.15\n");
    CHECK(valid_decimal("1"), "valid_decimal accepts a bare integer\n");
    CHECK(valid_decimal(".5"), "valid_decimal accepts a leading dot\n");
    CHECK(!valid_decimal(""), "valid_decimal rejects empty\n");
    CHECK(!valid_decimal("."), "valid_decimal rejects a lone dot\n");
    CHECK(!valid_decimal("1.2.3"), "valid_decimal rejects two dots\n");
    CHECK(!valid_decimal("-1.0"), "valid_decimal rejects sign\n");
    CHECK(!valid_decimal("1.0;reboot"), "valid_decimal rejects trailing junk\n");

    // valid_gov: lowercase-alnum + underscore.
    CHECK(valid_gov("schedutil"), "valid_gov accepts schedutil\n");
    CHECK(valid_gov("performance"), "valid_gov accepts performance\n");
    CHECK(!valid_gov("PERF;reboot"), "valid_gov rejects uppercase + ';'\n");
    CHECK(!valid_gov(""), "valid_gov rejects empty\n");
}

static void test_clamp(void) {
    CHECK(clamp(-5) == 0, "clamp(-5)==0\n");
    CHECK(clamp(0) == 0, "clamp(0)==0\n");
    CHECK(clamp(128) == 128, "clamp(128)==128\n");
    CHECK(clamp(255) == 255, "clamp(255)==255\n");
    CHECK(clamp(300) == 255, "clamp(300)==255\n");
}

static void test_stat_jiffies(void) {
    char comm[64];
    // utime=idx11, stime=idx12 of the fields after ')'. comm contains a space (tests paren handling).
    const char *s = "1 (proc name) R 0 0 0 0 0 0 0 0 0 0 100 200 99 0 0\n";
    long j = stat_jiffies(s, comm, sizeof comm);
    CHECK(j == 300, "stat_jiffies sums utime+stime (got %ld, want 300)\n", j);
    CHECK(strcmp(comm, "proc name") == 0, "stat_jiffies extracts comm (got '%s')\n", comm);

    // comm with embedded ')' — strrchr finds the LAST ')', so the real end is used.
    const char *s2 = "7 (weird) name) S 0 0 0 0 0 0 0 0 0 0 5 6 0\n";
    CHECK(stat_jiffies(s2, NULL, 0) == 11, "stat_jiffies handles ')' in comm\n");

    // malformed: no parens -> -1.
    CHECK(stat_jiffies("garbage with no parens", NULL, 0) == -1, "stat_jiffies rejects no-paren\n");
}

static void test_dispatch_exact_match(void) {
    char out[64];
    dispatch_reply("PING", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "PING -> OK (got '%s')\n", out);

    // Case-sensitive, exact verb: lowercase and unknown verbs are ERR.
    dispatch_reply("ping", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "ping (lowercase) -> ERR (got '%s')\n", out);
    dispatch_reply("NOPE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "unknown verb -> ERR (got '%s')\n", out);

    // The headline win of exact-match: a verb that is a PREFIX of input no longer matches.
    dispatch_reply("OFFOFF", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "OFFOFF -> ERR, not OFF (got '%s')\n", out);
    dispatch_reply("PINGEXTRA", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "PINGEXTRA -> ERR (got '%s')\n", out);

    // Leading whitespace is trimmed before the verb.
    dispatch_reply("   PING", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "leading spaces trimmed (got '%s')\n", out);

    // LEDPROBE reports the backend; on a host with no LED node it is "none".
    dispatch_reply("LEDPROBE", out, sizeof out);
    CHECK(strcmp(out, "none\n") == 0, "LEDPROBE -> none on host (got '%s')\n", out);

    // A handler with a failing target replies ERR (no LED/backlight node on the host).
    dispatch_reply("RGB 1 2 3", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RGB with no LED node -> ERR (got '%s')\n", out);
    dispatch_reply("RGB 1 2", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RGB with too few args -> ERR (got '%s')\n", out);
}

static void test_line_accumulator(void) {
    char out[256];

    // Two complete lines in one stream -> two replies, in order.
    serve_reply("PING\nPING\n", 10, out, sizeof out);
    CHECK(strcmp(out, "OK\nOK\n") == 0, "two PINGs -> OK\\nOK (got '%s')\n", out);

    // CRLF terminators are accepted the same as LF.
    serve_reply("PING\r\nPING\r\n", 12, out, sizeof out);
    CHECK(strcmp(out, "OK\nOK\n") == 0, "CRLF PINGs -> OK\\nOK (got '%s')\n", out);

    // Blank lines are ignored (len==0 lines aren't dispatched).
    serve_reply("\n\nPING\n", 7, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "blank lines skipped (got '%s')\n", out);

    // An overlong line (> MAX_LINE) is dropped, not mis-split; the following PING still parses.
    char big[MAX_LINE + 64];
    memset(big, 'A', MAX_LINE + 10);
    memcpy(big + MAX_LINE + 10, "\nPING\n", 6);
    serve_reply(big, MAX_LINE + 16, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "overlong line dropped, next line parses (got '%s')\n", out);
}

// The SMT1019 LED path: per-channel ioctl on /dev/ledjni. Untestable on owned hardware, so pin the
// wire protocol here — the exact command numbers and the 0..255 -> 0..15 scaling. The expected values
// are HARDCODED (not pulled from led.c's #defines) so an accidental change to either fails this test.
static void test_ledjni_ioctl(void) {
    CHECK(led15(0) == 0,    "led15(0)==0\n");
    CHECK(led15(255) == 15, "led15(255)==15\n");
    CHECK(led15(128) == 7,  "led15(128)==7 (got %d)\n", led15(128));
    CHECK(led15(17) == 1,   "led15(17)==1 (got %d)\n", led15(17));
    CHECK(led15(-9) == 0,   "led15 clamps negative to 0\n");
    CHECK(led15(999) == 15, "led15 clamps over-range to 15\n");

    int fd = open("/dev/null", O_WRONLY);   // any fd; ioctl is wrapped, never reaches it

    cap_n = 0;
    CHECK(led_ledjni_rgb(fd, 255, 128, 0) == 0, "led_ledjni_rgb returns ok\n");
    CHECK(cap_n == 3, "rgb emits exactly 3 ioctls (got %d)\n", cap_n);
    CHECK(cap_cmd[0] == 0xa1 && cap_val[0] == 15, "R: cmd 0xa1, 255->15 (got 0x%lx/%d)\n", cap_cmd[0], cap_val[0]);
    CHECK(cap_cmd[1] == 0xa2 && cap_val[1] == 7,  "G: cmd 0xa2, 128->7 (got 0x%lx/%d)\n", cap_cmd[1], cap_val[1]);
    CHECK(cap_cmd[2] == 0xa3 && cap_val[2] == 0,  "B: cmd 0xa3, 0->0 (got 0x%lx/%d)\n", cap_cmd[2], cap_val[2]);

    cap_n = 0;
    CHECK(led_ledjni_off(fd) == 0, "led_ledjni_off returns ok\n");
    CHECK(cap_n == 1 && cap_cmd[0] == 0x99 && cap_val[0] == 0, "off: cmd 0x99, val 0 (got 0x%lx/%d)\n", cap_cmd[0], cap_val[0]);

    if (fd >= 0) close(fd);
}

int main(void) {
    test_validators();
    test_clamp();
    test_stat_jiffies();
    test_dispatch_exact_match();
    test_line_accumulator();
    test_ledjni_ioctl();

    if (failures) { printf("UNIT FAILED: %d assertion(s)\n", failures); return 1; }
    printf("UNIT OK\n");
    return 0;
}
