#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "sysexec.h"

static int child_check(void) {
    errno = 0;
    return fcntl(9, F_GETFD, 0) == -1 && errno == EBADF ? 0 : 7;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--child-check") == 0) return child_check();
    char path[PATH_MAX];
    if (!realpath(argv[0], path)) { perror("realpath"); return 1; }
    char lock_path[] = "/tmp/hapaneld-sysexec-fd-XXXXXX";
    int lock = mkstemp(lock_path);
    if (lock < 0 || fchmod(lock, 0600) != 0 || flock(lock, LOCK_EX | LOCK_NB) != 0 ||
        dup2(lock, 9) < 0) {
        perror("owner fd");
        return 1;
    }
    if (lock != 9) close(lock);
    const char *const child_argv[] = { path, "--child-check", NULL };
    pid_t child = -1;
    if (sysexec_start_argv(path, child_argv, 0, &child) != 0) {
        perror("sysexec_start_argv");
        return 1;
    }
    int status = 0, state = 0;
    for (int attempt = 0; attempt < 100 && state == 0; attempt++) {
        state = sysexec_poll_argv(child, &status);
        if (state == 0) {
            struct timespec pause = { .tv_sec = 0, .tv_nsec = 10000000L };
            nanosleep(&pause, NULL);
        }
    }
    int contender = open(lock_path, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    int held = contender >= 0 && flock(contender, LOCK_EX | LOCK_NB) != 0 &&
        (errno == EWOULDBLOCK || errno == EAGAIN);
    if (contender >= 0) close(contender);
    close(9);
    unlink(lock_path);
    if (state != 1 || !WIFEXITED(status) || WEXITSTATUS(status) != 0 || !held) {
        fprintf(stderr, "sysexec fd boundary failed: state=%d status=%d held=%d\n",
            state, status, held);
        return 1;
    }
    puts("sysexec fd boundary tests passed");
    return 0;
}
