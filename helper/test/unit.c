// Host-native unit tests for the hapaneld-helper daemon's pure logic. Fuzzing proves the parser doesn't
// CRASH on hostile input; these assert it produces the CORRECT result — the validators reject what
// they should, the byte clamp and /proc parser compute the right values, dispatch routes verbs by
// EXACT match (so SCREEN/SCREENCAP and OFF/OFFOFF can't collide), and the line accumulator reassembles
// split reads and drops overlong lines. Linked against the real src/*.c modules + the sysexec stub.
//
// A clean run prints "UNIT OK" and exits 0; any failed assertion prints the case and exits 1.
#define _GNU_SOURCE
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
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
#include "sysexec_stub.h"
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

typedef struct {
    const char *line;
    char out[64];
} dispatch_job;

static void *dispatch_worker(void *arg) {
    dispatch_job *job = arg;
    int sv[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    char tmp[MAX_LINE + 1];
    snprintf(tmp, sizeof tmp, "%s", job->line);
    conn_ctx ctx = { .fd = sv[0], .subscribed = 0 };
    dispatch(&ctx, tmp);
    fcntl(sv[1], F_SETFL, O_NONBLOCK);
    ssize_t n = read(sv[1], job->out, sizeof job->out - 1);
    job->out[n > 0 ? n : 0] = '\0';
    close(sv[0]); close(sv[1]);
    return NULL;
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

    // is_critical_pkg: the never-stop/disable backstop.
    CHECK(is_critical_pkg("android"), "is_critical_pkg flags the framework\n");
    CHECK(is_critical_pkg("com.android.systemui"), "is_critical_pkg flags systemui\n");
    CHECK(is_critical_pkg("io.github.maxlyth.hapaneld"), "is_critical_pkg flags ourselves\n");
    CHECK(!is_critical_pkg("com.eWeLinkControlPanel"), "is_critical_pkg allows a vendor app\n");
    CHECK(!is_critical_pkg("com.android.systemui.x"), "is_critical_pkg is exact, not prefix\n");
    CHECK(!is_critical_pkg(""), "is_critical_pkg allows empty (valid_pkg rejects it first)\n");

    // valid_gov: lowercase-alnum + underscore.
    CHECK(valid_gov("schedutil"), "valid_gov accepts schedutil\n");
    CHECK(valid_gov("performance"), "valid_gov accepts performance\n");
    CHECK(!valid_gov("PERF;reboot"), "valid_gov rejects uppercase + ';'\n");
    CHECK(!valid_gov(""), "valid_gov rejects empty\n");

    // valid_hex_dataset: even-length hex, max 508 chars (254 bytes), hex digits only.
    CHECK(valid_hex_dataset("0e080000000000010000"), "valid_hex_dataset accepts even hex\n");
    CHECK(valid_hex_dataset("aAbBcCdDeEfF0123456789"), "valid_hex_dataset accepts mixed case\n");
    CHECK(!valid_hex_dataset(""), "valid_hex_dataset rejects empty\n");
    CHECK(!valid_hex_dataset("abc"), "valid_hex_dataset rejects odd length\n");
    CHECK(!valid_hex_dataset("gg"), "valid_hex_dataset rejects non-hex char\n");
    CHECK(!valid_hex_dataset("0e 0f"), "valid_hex_dataset rejects whitespace\n");
    // 510-char string (255 bytes) exceeds the 508-char limit
    { char big[511]; memset(big, '0', 510); big[510] = '\0';
      CHECK(!valid_hex_dataset(big), "valid_hex_dataset rejects >508 chars\n"); }

    // valid_gbl_path: absolute, no '..', no single-quote, ends in ".gbl".
    CHECK(valid_gbl_path("/data/local/tmp/efr32.gbl"), "valid_gbl_path accepts a normal path\n");
    CHECK(valid_gbl_path("/data/user/0/io.github.maxlyth.hapaneld/cache/efr32.gbl"), "valid_gbl_path accepts app cache path\n");
    CHECK(!valid_gbl_path(""), "valid_gbl_path rejects empty\n");
    CHECK(!valid_gbl_path("relative/path.gbl"), "valid_gbl_path rejects non-absolute\n");
    CHECK(!valid_gbl_path("/data/../etc/passwd.gbl"), "valid_gbl_path rejects traversal\n");
    CHECK(!valid_gbl_path("/data/local/tmp/efr32.bin"), "valid_gbl_path rejects wrong extension\n");
    CHECK(!valid_gbl_path("/data/local/tmp/it'squoted.gbl"), "valid_gbl_path rejects single-quote\n");
    CHECK(!valid_gbl_path("/data/local/tmp/"), "valid_gbl_path rejects path ending in slash\n");
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

    // SETHOME routes to set-home-activity, bounded to a valid component (reuses valid_component);
    // the sysexec stub makes a well-formed component succeed, a metachar/empty arg is rejected.
    dispatch_reply("SETHOME io.homeassistant.companion.android/.Home", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "SETHOME valid component -> OK (got '%s')\n", out);
    dispatch_reply("SETHOME ;reboot", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "SETHOME metachar arg -> ERR (got '%s')\n", out);
    dispatch_reply("SETHOME", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "SETHOME no arg -> ERR (got '%s')\n", out);

    // APPSTATE: a missing probe is an execution failure, not proof that the process is dead.
    dispatch_reply("APPSTATE io.homeassistant.companion.android", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE missing pidof probe -> ERR (got '%s')\n", out);
    dispatch_reply("APPSTATE ;rm", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE metachar pkg -> ERR (got '%s')\n", out);
    dispatch_reply("APPSTATE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE no arg -> ERR (got '%s')\n", out);

    // A handler with a failing target replies ERR (no LED/backlight node on the host).
    dispatch_reply("RGB 1 2 3", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RGB with no LED node -> ERR (got '%s')\n", out);
    dispatch_reply("RGB 1 2", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RGB with too few args -> ERR (got '%s')\n", out);
}

static void test_sysctl_execution_results(void) {
    char out[128];
    const struct { const char *line; const char *exec_match; } cases[] = {
        { "START com.example/.Main", "am start" },
        { "DENSITY 240", "wm density 240" },
        { "DENSITY reset", "wm density reset" },
        { "FONTSCALE 1.15", "settings put system font_scale" },
        { "FONTSCALE reset", "settings delete system font_scale" },
        { "GOV performance", "scaling_governor" },
        { "STOP com.example.app", "am force-stop" },
        { "DISABLE com.example.app", "pm disable-user" },
        { "ENABLE com.example.app", "pm enable" },
        { "OVERLAY com.example.app deny", "appops set" },
        { "SETHOME com.example/.Home", "set-home-activity" },
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        sysexec_stub_reset();
        dispatch_reply(cases[i].line, out, sizeof out);
        CHECK(strcmp(out, "OK\n") == 0, "%s succeeds when command succeeds (got '%s')\n", cases[i].line, out);
        sysexec_stub_fail_run(cases[i].exec_match, 256);
        dispatch_reply(cases[i].line, out, sizeof out);
        CHECK(strcmp(out, "ERR\n") == 0, "%s reports command failure (got '%s')\n", cases[i].line, out);
    }

    // RELOAD is a two-command transaction: either force-stop or relaunch failure makes it fail.
    sysexec_stub_reset();
    dispatch_reply("RELOAD com.example.app", out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "RELOAD succeeds when both commands succeed (got '%s')\n", out);
    sysexec_stub_fail_run("am force-stop", 256);
    dispatch_reply("RELOAD com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RELOAD reports force-stop failure (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_fail_run("monkey -p", 256);
    dispatch_reply("RELOAD com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "RELOAD reports relaunch failure (got '%s')\n", out);

    // Read verbs distinguish valid state from a pipe/open/exit failure.
    sysexec_stub_reset();
    sysexec_stub_add_popen("wm density", "Physical density: 320\nOverride density: 240\n", 0);
    dispatch_reply("DENSITY", out, sizeof out);
    CHECK(strcmp(out, "PHYS=320 OVER=240\n") == 0, "DENSITY reports parsed values (got '%s')\n", out);
    sysexec_stub_reset();
    dispatch_reply("DENSITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "DENSITY reports pipe-open failure (got '%s')\n", out);
    sysexec_stub_add_popen("wm density", "Physical density: 320\n", 256);
    dispatch_reply("DENSITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "DENSITY reports command-exit failure (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("wm density", "unexpected output\n", 0);
    dispatch_reply("DENSITY", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "DENSITY reports unparseable output (got '%s')\n", out);

    sysexec_stub_reset();
    sysexec_stub_add_popen("settings get system font_scale", "1.25\n", 0);
    dispatch_reply("FONTSCALE", out, sizeof out);
    CHECK(strcmp(out, "SCALE=1.25\n") == 0, "FONTSCALE reports parsed value (got '%s')\n", out);
    sysexec_stub_reset();
    dispatch_reply("FONTSCALE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "FONTSCALE reports pipe-open failure (got '%s')\n", out);
    sysexec_stub_add_popen("settings get system font_scale", "1.25\n", 256);
    dispatch_reply("FONTSCALE", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "FONTSCALE reports command-exit failure (got '%s')\n", out);

    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "", 256);  // pidof exit 1 is the expected no-process result.
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "DEAD\n") == 0, "APPSTATE distinguishes pidof exit 1 as DEAD (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "123\n", 0);
    sysexec_stub_add_popen("dumpsys window", "mCurrentFocus=Window{ com.example.app/.Main }\n", 0);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "FG\n") == 0, "APPSTATE reports focused live process (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "123\n", 0);
    sysexec_stub_add_popen("dumpsys window", "mCurrentFocus=Window{ com.other/.Main }\n", 0);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "BG\n") == 0, "APPSTATE reports background live process (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "", 512);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE reports unexpected pidof failure (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "123\n", 0);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE reports dumpsys pipe-open failure (got '%s')\n", out);
    sysexec_stub_reset();
    sysexec_stub_add_popen("pidof", "123\n", 0);
    sysexec_stub_add_popen("dumpsys window", "mCurrentFocus=Window{ com.other/.Main }\n", 256);
    dispatch_reply("APPSTATE com.example.app", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "APPSTATE reports dumpsys command failure (got '%s')\n", out);

    // INSTALL already had a two-stage result boundary; pin it to the same injectable seam.
    const char *install = "INSTALL /data/user/0/io.github.maxlyth.hapaneld/cache/update.apk";
    sysexec_stub_reset();
    dispatch_reply(install, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "INSTALL succeeds when staging and package install succeed (got '%s')\n", out);
    CHECK(sysexec_stub_count_run("rm -f /data/local/tmp/hapaneld-install.apk") == 1,
          "INSTALL removes root staging after success\n");
    sysexec_stub_fail_run("cp '", 256);
    dispatch_reply(install, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "INSTALL reports staging failure (got '%s')\n", out);
    CHECK(sysexec_stub_count_run("rm -f /data/local/tmp/hapaneld-install.apk") == 2,
          "INSTALL removes partial root staging after copy failure\n");
    sysexec_stub_reset();
    sysexec_stub_fail_run("pm install", 256);
    dispatch_reply(install, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "INSTALL reports package-manager failure (got '%s')\n", out);
    CHECK(sysexec_stub_count_run("rm -f /data/local/tmp/hapaneld-install.apk") == 1,
          "INSTALL removes root staging after package-manager failure\n");
    sysexec_stub_reset();

    const char *install_gc = "INSTALLGC /data/user/0/io.github.maxlyth.hapaneld/files/helper-install-staging/retained.apk";
    dispatch_reply(install_gc, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "INSTALLGC authorises retained-input cleanup while install lane is idle (got '%s')\n", out);
    dispatch_reply(install_gc, out, sizeof out);
    CHECK(strcmp(out, "OK\n") == 0, "duplicate INSTALLGC remains idempotent (got '%s')\n", out);
    dispatch_reply("INSTALL /data/user/0/io.github.maxlyth.hapaneld/files/helper-install-staging/retained.apk", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "a late INSTALL is rejected after cleanup authorisation (got '%s')\n", out);
    CHECK(sysexec_stub_count_run("cp '") == 0, "cancelled late INSTALL never consumes the retained input\n");
    dispatch_reply("INSTALLGC /data/local/tmp/not-owned.apk", out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "INSTALLGC rejects paths outside app-private storage (got '%s')\n", out);

    // The daemon is thread-per-connection but uses one root staging path. A concurrent INSTALL must
    // fail immediately instead of replacing the first transaction's staged bytes or waiting behind it.
    sysexec_stub_block_run("cp '");
    dispatch_job first = { .line = install, .out = "" };
    pthread_t install_thread;
    pthread_create(&install_thread, NULL, dispatch_worker, &first);
    sysexec_stub_wait_blocked();
    dispatch_reply(install, out, sizeof out);
    CHECK(strcmp(out, "ERR\n") == 0, "overlapping INSTALL is rejected while one owns root staging (got '%s')\n", out);
    dispatch_reply(install_gc, out, sizeof out);
    CHECK(strcmp(out, "BUSY\n") == 0, "INSTALLGC retains input while an install owns the lane (got '%s')\n", out);
    sysexec_stub_release_run();
    pthread_join(install_thread, NULL);
    CHECK(strcmp(first.out, "OK\n") == 0, "first INSTALL completes after overlap rejection (got '%s')\n", first.out);
    sysexec_stub_reset();
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

// --- concurrent-connection cap (MAX_CONN) -------------------------------------------------------
// The accept loop admits at most MAX_CONN simultaneous connections (main.c), via server.c's
// conn_admit()/conn_release() gate. Fuzz/parsing tests can't reach this — the cap lives in the
// connection lifecycle, not the byte parser — so pin it three ways: (1) the exact boundary,
// deterministically; (2) concurrent rejection while MAX_CONN slots are genuinely held open by other
// threads; (3) that a hammer of racing admit/release never lets more than MAX_CONN through at once
// (the atomics are correct — a non-atomic gate would over-admit here).

// (2) holders: each admits, waits, then releases on a shared signal — so the cap is under real
// concurrent pressure when the main thread probes it.
static volatile int hold_admitted = 0;   // holders that have run conn_admit()
static volatile int hold_release  = 0;    // main thread sets this to let holders release
static volatile int hold_admit_ok = 1;   // cleared if any holder's admit was (wrongly) refused
static void *cap_holder(void *arg) {
    (void)arg;
    if (!conn_admit()) __atomic_store_n(&hold_admit_ok, 0, __ATOMIC_SEQ_CST);
    __atomic_add_fetch(&hold_admitted, 1, __ATOMIC_SEQ_CST);
    while (!__atomic_load_n(&hold_release, __ATOMIC_SEQ_CST)) sched_yield();
    conn_release();
    return NULL;
}

// (3) hammer: admit/release in a tight loop, tracking the high-water mark of simultaneously-admitted
// connections. A correct gate keeps that peak <= MAX_CONN under any interleaving.
#define CAP_THREADS 64
#define CAP_ITERS   4000
static volatile int cap_live = 0;         // connections currently past the gate (our own mirror)
static volatile int cap_peak = 0;         // high-water mark of cap_live
static void *cap_hammer(void *arg) {
    (void)arg;
    for (int i = 0; i < CAP_ITERS; i++) {
        if (conn_admit()) {
            int c = __atomic_add_fetch(&cap_live, 1, __ATOMIC_SEQ_CST);
            int peak;
            while ((peak = __atomic_load_n(&cap_peak, __ATOMIC_SEQ_CST)) < c &&
                   !__atomic_compare_exchange_n(&cap_peak, &peak, c, 0,
                                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) { }
            sched_yield();                 // widen the window so a racy gate would over-admit
            __atomic_sub_fetch(&cap_live, 1, __ATOMIC_SEQ_CST);
            conn_release();
        }
    }
    return NULL;
}

static void test_conn_cap(void) {
    CHECK(conn_active() == 0, "conn cap starts clean at 0 (got %d)\n", conn_active());

    // (1) Exact boundary, single-threaded: MAX_CONN admits succeed, the next is refused and leaks
    // nothing, one release frees exactly one slot, then it drains back to 0.
    for (int i = 0; i < MAX_CONN; i++)
        CHECK(conn_admit() == 1, "admit %d/%d within cap succeeds\n", i + 1, MAX_CONN);
    CHECK(conn_active() == MAX_CONN, "active == MAX_CONN when full (got %d)\n", conn_active());
    CHECK(conn_admit() == 0, "the (MAX_CONN+1)th admit is refused\n");
    CHECK(conn_active() == MAX_CONN, "a refused admit leaks no slot (got %d)\n", conn_active());
    conn_release();
    CHECK(conn_active() == MAX_CONN - 1, "release frees one slot (got %d)\n", conn_active());
    CHECK(conn_admit() == 1, "one admit is allowed again after a release\n");
    for (int i = 0; i < MAX_CONN; i++) conn_release();
    CHECK(conn_active() == 0, "drains back to 0 (got %d)\n", conn_active());

    // (2) Concurrent rejection: MAX_CONN threads hold slots open; a probe admit must be refused while
    // they hold, then everything releases cleanly.
    hold_admitted = 0; hold_release = 0; hold_admit_ok = 1;
    pthread_t holders[MAX_CONN];
    for (int i = 0; i < MAX_CONN; i++) pthread_create(&holders[i], NULL, cap_holder, NULL);
    while (__atomic_load_n(&hold_admitted, __ATOMIC_SEQ_CST) < MAX_CONN) sched_yield();
    CHECK(hold_admit_ok, "all MAX_CONN concurrent holders were admitted\n");
    CHECK(conn_active() == MAX_CONN, "MAX_CONN slots held concurrently (got %d)\n", conn_active());
    CHECK(conn_admit() == 0, "admit refused while MAX_CONN held by other threads\n");
    __atomic_store_n(&hold_release, 1, __ATOMIC_SEQ_CST);
    for (int i = 0; i < MAX_CONN; i++) pthread_join(holders[i], NULL);
    CHECK(conn_active() == 0, "all held slots released back to 0 (got %d)\n", conn_active());

    // (3) Race invariant: hammer admit/release from many threads; the gate must never let the live
    // count exceed MAX_CONN, and must not leak a slot afterwards.
    cap_live = 0; cap_peak = 0;
    pthread_t hammer[CAP_THREADS];
    for (int i = 0; i < CAP_THREADS; i++) pthread_create(&hammer[i], NULL, cap_hammer, NULL);
    for (int i = 0; i < CAP_THREADS; i++) pthread_join(hammer[i], NULL);
    CHECK(cap_peak <= MAX_CONN, "gate never admits more than MAX_CONN at once (peak %d)\n", cap_peak);
    CHECK(cap_peak >= 1, "the hammer actually admitted connections (peak %d)\n", cap_peak);
    CHECK(conn_active() == 0, "no slot leaked after the connection storm (got %d)\n", conn_active());
}

int main(void) {
    test_validators();
    test_clamp();
    test_stat_jiffies();
    test_dispatch_exact_match();
    test_sysctl_execution_results();
    test_line_accumulator();
    test_ledjni_ioctl();
    test_conn_cap();

    if (failures) { printf("UNIT FAILED: %d assertion(s)\n", failures); return 1; }
    printf("UNIT OK\n");
    return 0;
}
