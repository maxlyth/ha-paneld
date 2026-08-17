// Production sysexec: real exec / pipe / thread / reboot. See sysexec.h for why this is isolated.
#include "sysexec.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

int sysexec_run_constant(const char *program) {
    if (!program) return -1;
    const char *const argv[] = { "sh", "-c", program, NULL };
    return sysexec_run_argv("/system/bin/sh", argv, 0);
}

static char *const clean_env[] = {
    "PATH=/system/bin:/vendor/bin",
    "LANG=C",
    "LC_ALL=C",
    NULL
};

static void close_inherited_fds(int keep_a, int keep_b) {
    long limit = sysconf(_SC_OPEN_MAX);
    if (limit < 0 || limit > 65536) limit = 65536;
    for (int fd = STDERR_FILENO + 1; fd < limit; fd++)
        if (fd != keep_a && fd != keep_b) close(fd);
}

static void child_exec(const char *path, const char *const argv[], int input, int output, int error) {
    if (dup2(input, STDIN_FILENO) < 0 || dup2(output, STDOUT_FILENO) < 0 ||
        dup2(error, STDERR_FILENO) < 0) _exit(127);
    close_inherited_fds(-1, -1);
    execve(path, (char *const *)argv, clean_env);
    _exit(127);
}

static int wait_child(pid_t pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    return status;
}

static pid_t spawn_argv(const char *path, const char *const argv[], int quiet) {
    if (!path || path[0] != '/' || !argv || !argv[0]) return -1;

    int null_fd = -1;
    if (quiet) {
        null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd < 0) return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        if (null_fd >= 0) close(null_fd);
        return -1;
    }
    if (pid == 0) {
        // Own process group, so that killing an expired actuator kills everything it forked: several
        // /system/bin actuators are wrappers that spawn their real worker, and signalling only the
        // direct child would leave that worker running unowned past its deadline.
        (void)setpgid(0, 0);
        if (quiet) child_exec(path, argv, null_fd, null_fd, null_fd);
        close_inherited_fds(-1, -1);
        execve(path, (char *const *)argv, clean_env);
        _exit(127);
    }

    // Both sides create the group so neither can observe a window where it does not exist yet: this
    // one fails harmlessly (EACCES) once the child has passed exec, by which point the child's own
    // setpgid has long since succeeded.
    (void)setpgid(pid, pid);
    if (null_fd >= 0) close(null_fd);
    return pid;
}

int sysexec_run_argv(const char *path, const char *const argv[], int quiet) {
    pid_t pid = spawn_argv(path, argv, quiet);
    if (pid < 0) return -1;
    return wait_child(pid);
}

// Poll rather than install a SIGCHLD handler: this daemon is thread-per-connection, and a process-wide
// handler to bound one actuator would reach every other thread's children.
#define WAIT_POLL_MS 20u
// After SIGKILL the child normally becomes reapable within milliseconds, so a short inline grace
// completes the common case without spawning anything. A child wedged in uninterruptible sleep can
// outlast any grace, and it must neither become a second unbounded wait on the connection thread nor
// be abandoned as a permanent zombie — past the grace it is handed to a detached reaper thread whose
// blocking waitpid finishes the reap whenever the kernel finally releases the child.
#define REAP_GRACE_MS 500u

static void *reap_child_thread(void *arg) {
    pid_t pid = (pid_t)(intptr_t)arg;
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) continue;
    return NULL;
}

// Wait up to [deadline_ms] for [pid]. Returns its wait status, or -1 if the deadline passed first.
// [waited_out] always receives the elapsed portion of the deadline, including on the -1 paths.
static int wait_child_deadline(pid_t pid, unsigned deadline_ms, unsigned *waited_out) {
    unsigned waited = 0;
    for (;;) {
        int status;
        pid_t done = waitpid(pid, &status, WNOHANG);
        if (done == pid) {
            *waited_out = waited;
            return status;
        }
        if (done < 0 && errno != EINTR) {
            *waited_out = waited;
            return -1;
        }
        if (waited >= deadline_ms) {
            *waited_out = waited;
            return -1;
        }
        unsigned remaining = deadline_ms - waited;
        unsigned slice = remaining < WAIT_POLL_MS ? remaining : WAIT_POLL_MS;
        sysexec_sleep_ms(slice);
        waited += slice;
    }
}

int sysexec_run_argv_deadline(const char *path, const char *const argv[], int quiet,
                              unsigned deadline_ms, unsigned *elapsed_ms) {
    unsigned elapsed = 0;
    pid_t pid = spawn_argv(path, argv, quiet);
    if (pid < 0) {
        if (elapsed_ms) *elapsed_ms = 0;
        return -1;
    }
    int status = wait_child_deadline(pid, deadline_ms, &elapsed);
    if (status < 0 && elapsed >= deadline_ms) {
        // Kill the whole process group, not just the direct child: a wrapper's forked worker would
        // otherwise keep acting past the deadline, unowned. The direct kill is the fallback for the
        // (should-be-impossible) case where the group was never created.
        if (kill(-pid, SIGKILL) != 0) kill(pid, SIGKILL);
        unsigned reaped = 0;
        if (wait_child_deadline(pid, REAP_GRACE_MS, &reaped) < 0 && reaped >= REAP_GRACE_MS) {
            // Still unreaped: hand the wait to a detached thread so the reap is eventually completed
            // without pinning this connection thread. If even the thread cannot be created the child
            // stays a zombie for this daemon's lifetime and init reaps it when the daemon exits —
            // best effort at that point, because nothing further can be guaranteed without memory.
            (void)sysexec_spawn(reap_child_thread, (void *)(intptr_t)pid);
        }
        elapsed += reaped;
    }
    if (elapsed_ms) *elapsed_ms = elapsed;
    return status;
}

static int pipe_argv(const char *path, const char *const argv[], int output_fd,
                     char *capture, size_t capacity) {
    if (!path || path[0] != '/' || !argv || !argv[0] || (capture && capacity == 0)) return -1;
    int pipefd[2];
    if (pipe(pipefd) != 0) return -1;
    (void)fcntl(pipefd[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(pipefd[1], F_SETFD, FD_CLOEXEC);
    int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null_fd < 0) { close(pipefd[0]); close(pipefd[1]); return -1; }
    pid_t pid = fork();
    if (pid < 0) { close(null_fd); close(pipefd[0]); close(pipefd[1]); return -1; }
    if (pid == 0) {
        close(pipefd[0]);
        child_exec(path, argv, null_fd, pipefd[1], null_fd);
    }
    close(null_fd);
    close(pipefd[1]);
    size_t used = 0;
    int io_ok = 1;
    char buf[8192];
    for (;;) {
        ssize_t n = read(pipefd[0], buf, sizeof buf);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { io_ok = 0; break; }
        if (n == 0) break;
        if (capture) {
            size_t available = capacity - 1 - used;
            size_t take = (size_t)n < available ? (size_t)n : available;
            if (take) memcpy(capture + used, buf, take);
            used += take;
            if (take != (size_t)n) io_ok = 0;
        } else {
            size_t offset = 0;
            while (offset < (size_t)n) {
                ssize_t written = write(output_fd, buf + offset, (size_t)n - offset);
                if (written < 0 && errno == EINTR) continue;
                if (written <= 0) { io_ok = 0; break; }
                offset += (size_t)written;
            }
        }
        if (!io_ok) break;
    }
    close(pipefd[0]);
    if (capture) capture[used] = '\0';
    if (!io_ok) {
        kill(pid, SIGKILL);
        (void)wait_child(pid);
        return -1;
    }
    return wait_child(pid);
}

int sysexec_capture_argv(const char *path, const char *const argv[], char *output, size_t capacity) {
    if (!output || capacity == 0) return -1;
    output[0] = '\0';
    return pipe_argv(path, argv, -1, output, capacity);
}

int sysexec_stream_argv(const char *path, const char *const argv[], int output_fd) {
    if (output_fd < 0) return -1;
    return pipe_argv(path, argv, output_fd, NULL, 0);
}

int sysexec_spawn(void *(*fn)(void *), void *arg) {
    pthread_t t;
    if (pthread_create(&t, NULL, fn, arg) != 0) return -1;
    pthread_detach(t);
    return 0;
}

void sysexec_sleep_ms(unsigned ms) {
    struct timespec remaining = {
        .tv_sec = (time_t)(ms / 1000u),
        .tv_nsec = (long)(ms % 1000u) * 1000000L,
    };
    // A signal must not shorten a bounded wait: nanosleep reports the unslept remainder, so resume
    // from it rather than returning early with the interval only partly elapsed.
    while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) continue;
}
