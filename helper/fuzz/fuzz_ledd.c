// Fuzz harness for the hapaneld-ledd command parser (serve() / handle()).
//
// It compiles the REAL ../ledd.c (so it tracks the daemon, not a copy) and neutralises only the
// calls with host side effects, leaving every byte of the parsing path live under the sanitizers:
//   system / popen          -> no-op  (a valid REBOOT/RELOAD/START/GOV/DENSITY can't exec on the host)
//   pthread_create / detach  -> no-op  (a valid WATCH /dev/input/... can't spawn looping threads)
//
// What gets exercised for real: the bounded line accumulator in serve(), the prefix dispatch in
// handle(), every argument sscanf, the snprintf shell-command builders, and the valid_pkg / valid_num
// / valid_gov validators. The goal is memory safety against hostile/malformed input from an ALLOWED
// peer (the SO_PEERCRED uid gate is a separate, kernel-enforced control — not what this fuzzes).
//
// Build + run:  ./helper/fuzz/run.sh [iterations]
// A clean run exits 0 with no ASan/UBSan report. See README.md for the one expected LSan note.

#define _GNU_SOURCE
// Real libc declarations first (with their include guards), so the macro overrides below rewrite
// only ledd.c's *calls*, never the headers' declarations.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <pthread.h>
#include <sys/socket.h>
#include <time.h>

#define main            ledd_main_disabled
#define system(x)       (0)
#define popen(a, b)     ((FILE *)0)
#define pthread_create(a, b, c, d) (0)
#define pthread_detach(t)          (0)

#include "ledd.c"

#undef main
#undef system
#undef popen
#undef pthread_create
#undef pthread_detach

static int devnull;

// handle()/SUBSCRIBE can register an fd, and WATCH records a path — reset the globals between runs.
static void reset_state(void) { for (int i = 0; i < MAX_SUBS; i++) subs[i] = -1; watch_n = 0; }

// Drive serve() exactly as a real connection would: write the bytes, then half-close so it sees EOF.
static void run_serve(const uint8_t *data, size_t n) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return;
    if (n) { ssize_t w = write(sv[1], data, n); (void)w; }
    shutdown(sv[1], SHUT_WR);
    reset_state();
    serve(sv[0]);
    close(sv[0]); close(sv[1]);
}

// Drive handle() directly with one NUL-terminated line (the fast path — millions of iterations).
static void run_handle(const uint8_t *data, size_t n) {
    static char line[8192];
    if (n > sizeof line - 1) n = sizeof line - 1;
    memcpy(line, data, n); line[n] = '\0';
    int sub = 0;
    reset_state();
    handle(devnull, line, &sub);
}

int main(int argc, char **argv) {
    long iters = (argc > 1) ? strtol(argv[1], NULL, 10) : 1000000;
    signal(SIGPIPE, SIG_IGN);
    devnull = open("/dev/null", O_WRONLY);

    // 1) hand-crafted adversarial corpus (each fed to BOTH serve() and handle()).
    const char *corpus[] = {
        "", "\n", "\r", "\r\n", "\n\n\n\n", " ", "   \n   \n",
        "PING\n", "PING", "ping\n",
        "RGB 1 2 3\n", "RGB\n", "RGB   \n", "RGB 0 0 0\n",
        "RGB -1 -2 -3\n", "RGB 256 999 1000\n",
        "RGB 99999999999999999999 -99999999999999999999 2147483648\n",
        "RGB 2147483647 2147483647 2147483647\n",
        "OFF\n", "OFFOFFOFF\n", "OF\n",
        "BTN 5\n", "BTN -99999999999\n", "BTN 99999999999999999999\n", "BTN\n",
        "SCREEN ON\n", "SCREEN OFF\n", "SCREEN off\n", "SCREEN\n", "SCREEN xyzzyqqq\n",
        "SCREENCAP\n", "SCREENCAPEXTRA\n",
        "RELOAD com.foo.bar\n", "RELOAD \n", "RELOAD ;reboot\n", "RELOAD a|b`c$d(e)\n",
        "RELOAD ../../etc/passwd\n", "RELOAD \"$(rm -rf)\"\n",
        "START com.foo/.Bar\n", "START a/b/c/../d\n", "START ;reboot\n", "START \n",
        "WATCH /dev/input/event0 1\n", "WATCH /dev/input/event0 0\n",
        "WATCH /etc/passwd 1\n", "WATCH ../../dev/input/x 1\n",
        "WATCH /dev/input/event0 99999999999\n", "WATCH\n", "WATCH /dev/input/\n",
        "SUBSCRIBE\n", "SUBSCRIBE\nRGB 1 2 3\n",
        "REBOOT\n", "REBOOTNOW\n",
        "DENSITY\n", "DENSITY 240\n", "DENSITY reset\n", "DENSITY 99999999999999\n",
        "DENSITY ../../x\n", "DENSITY -1\n",
        "GOV schedutil\n", "GOV \n", "GOV PERF;reboot\n", "GOV performance\n",
        "PERFDUMP\n", "LEDPROBE\n",
        "RGB 1 2 3\r\nOFF\r\nBTN 9\r\n",
        "RGB 1 2 3\0OFF\n",                  // embedded NUL (fed with explicit length below)
        "no newline at all just bytes",
        "\xff\xfe\x00\x01\x02 garbage \x80\x90\n",
    };
    size_t ncorp = sizeof corpus / sizeof corpus[0];
    for (size_t i = 0; i < ncorp; i++) {
        size_t L = (i == ncorp - 3) ? 14 : strlen(corpus[i]);   // explicit len for the embedded-NUL case ("RGB 1 2 3\0OFF\n")
        run_serve((const uint8_t *)corpus[i], L);
        run_handle((const uint8_t *)corpus[i], L);
    }

    // 2) length-boundary lines around MAX_LINE for every verb.
    size_t lens[] = { MAX_LINE - 1, MAX_LINE, MAX_LINE + 1, 4096, 65536 };
    const char *verbs[] = { "RGB ", "BTN ", "RELOAD ", "START ", "WATCH /dev/input/",
                            "DENSITY ", "GOV ", "SCREEN ", "" };
    for (size_t li = 0; li < sizeof lens / sizeof lens[0]; li++)
        for (size_t vi = 0; vi < sizeof verbs / sizeof verbs[0]; vi++) {
            size_t L = lens[li], vp = strlen(verbs[vi]);
            uint8_t *b = malloc(L + 2);
            memcpy(b, verbs[vi], vp);
            memset(b + vp, 'A', L - vp);
            b[L] = '\n'; b[L + 1] = '\0';
            run_serve(b, L + 1);
            run_handle(b, L);
            free(b);
        }

    // 3) random fuzzing: serve() (multi-line) and handle() (single line), fixed seed = reproducible.
    srand(0xC0FFEE);
    uint8_t buf[3000];
    const char *kw[] = { "RGB", "OFF", "BTN", "SCREEN", "SCREENCAP", "RELOAD", "START", "WATCH",
                         "SUBSCRIBE", "REBOOT", "DENSITY", "GOV", "PERFDUMP", "LEDPROBE", "PING" };
    for (long it = 0; it < iters; it++) {
        size_t n = rand() % sizeof buf;
        for (size_t k = 0; k < n; k++) {
            int r = rand() % 100;
            if (r < 55)      buf[k] = (uint8_t)(rand() & 0xff);      // raw bytes
            else if (r < 75) buf[k] = " 0123456789"[rand() % 11];   // digits / spaces
            else if (r < 90) buf[k] = "\n\r /._:-"[rand() % 8];      // delimiters / path chars
            else             buf[k] = ";|`$()&<>*?\"'\\"[rand() % 14]; // shell metachars
        }
        if (n > 8 && (rand() % 3) == 0) {                            // bias toward real verbs
            const char *w = kw[rand() % (sizeof kw / sizeof kw[0])];
            size_t wl = strlen(w);
            memcpy(buf, w, wl);
            if (wl < n) buf[wl] = ' ';
        }
        if (it & 1) run_serve(buf, n);
        else        run_handle(buf, n);
    }

    return 0;
}
