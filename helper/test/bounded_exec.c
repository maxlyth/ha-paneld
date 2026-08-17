// Real-exec proof for the bounded actuator primitive. The unit suite links sysexec_stub.c and so can
// only assert the POLICY above sysexec (which deadline each mechanism is given, and that the
// remainder of the window is slept). Nothing there executes anything, so the property that actually
// matters on a panel — a wedged app_process wrapper does not pin the calling thread, and is not left
// behind as a zombie — has to be proved against the production sysexec.c with a real child.
#include "sysexec.h"

#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int failures;

#define CHECK(cond, msg)                                    \
    do {                                                    \
        if (!(cond)) {                                      \
            printf("FAIL: %s\n", msg);                      \
            failures++;                                     \
        }                                                   \
    } while (0)

static unsigned long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long)ts.tv_sec * 1000UL + (unsigned long)(ts.tv_nsec / 1000000L);
}

// The helper runs on Android, where these live in /system/bin; the host build needs whichever of the
// usual two prefixes exists, and a missing tool must fail the test rather than silently pass it.
static const char *resolve(const char *const candidates[]) {
    for (int i = 0; candidates[i]; i++)
        if (access(candidates[i], X_OK) == 0) return candidates[i];
    return NULL;
}

// The probe must OBSERVE, never reap: a plain waitpid(-1, WNOHANG) sweep consumes the very zombie
// whose absence it is supposed to prove, so a production path that abandoned reaping entirely would
// still pass — the probe would have done the reap itself on its first call. waitid with WNOWAIT
// reports without consuming: it succeeds while any child is pending (an unreaped zombie or a live
// child alike) and fails ECHILD only once production code has genuinely reaped everything.
static int child_pending(void) {
    siginfo_t info;
    return waitid(P_ALL, 0, &info, WEXITED | WNOHANG | WNOWAIT) == 0;
}

// Reaping past the inline grace is handed to a detached thread, so "reaped" is eventually true
// rather than immediately true. Poll for it with a bound far above any sane handoff latency.
static int eventually_reaped(void) {
    for (int waited_ms = 0; waited_ms < 3000; waited_ms += 20) {
        if (!child_pending()) return 1;
        sysexec_sleep_ms(20);
    }
    return !child_pending();
}

int main(void) {
    static const char *const sleep_candidates[] = { "/bin/sleep", "/usr/bin/sleep", NULL };
    static const char *const true_candidates[] = { "/bin/true", "/usr/bin/true", NULL };
    const char *sleep_path = resolve(sleep_candidates);
    const char *true_path = resolve(true_candidates);
    if (!sleep_path || !true_path) {
        printf("FAIL: bounded-exec test needs sleep and true on the build host\n");
        return 1;
    }

    // A child that outlives its deadline: the call must return on the deadline, not on the child.
    {
        const char *const argv[] = { "sleep", "30", NULL };
        unsigned elapsed = 0;
        unsigned long began = now_ms();
        int status = sysexec_run_argv_deadline(sleep_path, argv, 1, 300u, &elapsed);
        unsigned long took = now_ms() - began;
        CHECK(status < 0, "a child that outlives its deadline reports no status\n");
        CHECK(took < 5000UL, "a wedged actuator releases the calling thread at its deadline\n");
        CHECK(took >= 300UL, "the deadline is honoured in full before the child is killed\n");
        CHECK(elapsed >= 300u, "an expired deadline reports its whole window as consumed\n");
        CHECK(eventually_reaped(),
              "a child killed at its deadline is reaped, not left a zombie\n");
    }

    // A wrapper that forked its real worker before wedging: killing only the direct child would leave
    // that worker acting past the deadline, unowned. Every process in the actuator's group inherits
    // this test's stdout, so a pipe placed there reads EOF exactly when the LAST of them is gone —
    // the leader alone dying is not enough to release it.
    {
        static const char *const sh_candidates[] = { "/bin/sh", "/usr/bin/sh", NULL };
        const char *sh_path = resolve(sh_candidates);
        int pipe_fds[2] = { -1, -1 };
        if (!sh_path || pipe(pipe_fds) != 0) {
            printf("FAIL: descendant test needs sh and a pipe on the build host\n");
            failures++;
        } else {
            fflush(stdout);
            int saved_stdout = dup(STDOUT_FILENO);
            dup2(pipe_fds[1], STDOUT_FILENO);
            close(pipe_fds[1]);
            // Absolute paths: children run under sysexec's sanitized Android PATH, which resolves
            // nothing on a build host — the same property the whole issue is about.
            char script[256];
            snprintf(script, sizeof script, "%s 30 & exec %s 31", sleep_path, sleep_path);
            const char *const argv[] = { "sh", "-c", script, NULL };
            unsigned elapsed = 0;
            int status = sysexec_run_argv_deadline(sh_path, argv, 0, 300u, &elapsed);
            fflush(stdout);
            dup2(saved_stdout, STDOUT_FILENO);
            close(saved_stdout);
            struct pollfd probe = { .fd = pipe_fds[0], .events = POLLIN, .revents = 0 };
            int drained = poll(&probe, 1, 3000);
            close(pipe_fds[0]);
            CHECK(status < 0, "the wrapper itself is expired at its deadline\n");
            CHECK(drained == 1 && (probe.revents & POLLHUP),
                  "a timed-out actuator's forked descendants die with it, not just the direct child\n");
            CHECK(eventually_reaped(), "the killed group's leader is still reaped\n");
        }
    }

    // A child that exits inside its deadline: real status, and the unused remainder is the caller's.
    {
        const char *const argv[] = { "true", NULL };
        unsigned elapsed = 0;
        unsigned long began = now_ms();
        int status = sysexec_run_argv_deadline(true_path, argv, 1, 5000u, &elapsed);
        unsigned long took = now_ms() - began;
        CHECK(status == 0, "a fast actuator's real exit status survives the deadline wrapper\n");
        CHECK(took < 2000UL, "a fast actuator is not held until its deadline expires\n");
        CHECK(elapsed < 5000u, "a fast actuator reports an unconsumed remainder to its caller\n");
        CHECK(!child_pending(), "a child that exited inside its deadline is reaped\n");
    }

    // A child that fails inside its deadline is still distinguishable from one that timed out only by
    // its status, so the escalation above must not read "nonzero" as "the deadline expired".
    {
        const char *const argv[] = { "sleep", NULL };   // missing operand: exits nonzero, immediately
        unsigned elapsed = 0;
        int status = sysexec_run_argv_deadline(sleep_path, argv, 1, 5000u, &elapsed);
        CHECK(status != 0, "a failing actuator reports a nonzero status\n");
        CHECK(elapsed < 5000u, "a failing actuator returns without consuming its window\n");
        CHECK(!child_pending(), "a failing child is reaped\n");
    }

    // Spawn failure must not be reported as a consumed window, or the policy above would sleep out a
    // remainder for a mechanism that never ran.
    {
        const char *const argv[] = { "nope", NULL };
        unsigned elapsed = 99u;
        int status = sysexec_run_argv_deadline("/nonexistent/actuator", argv, 1, 5000u, &elapsed);
        CHECK(status != 0, "a missing actuator does not report success\n");
        CHECK(!child_pending(), "a missing actuator leaves no child behind\n");
    }

    printf(failures ? "bounded-exec FAILED (%d)\n" : "bounded-exec ok (%d)\n", failures);
    return failures ? 1 : 0;
}
