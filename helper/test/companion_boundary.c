#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include "cmd.h"
#include "companion.h"
#include "dispatch.h"
#include "server.h"
#include "sysexec_stub.h"

#define ROOT "/tmp/.hapaneld-companion-test/data/user/0"
#define PKG  "io.homeassistant.companion.android.minimal"
#define BASE ROOT "/" PKG
#define DB   BASE "/databases/HomeAssistantDB"
#define WAL  BASE "/databases/HomeAssistantDB-wal"
#define SHM  BASE "/databases/HomeAssistantDB-shm"
#define SESSION BASE "/shared_prefs/session_0.xml"
#define INTEGRATION BASE "/shared_prefs/integration_0.xml"
#define ROOT_STAGE "/tmp/.hapaneld-companion-stage-test"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { \
    printf("FAIL: " __VA_ARGS__); printf("  (%s:%d)\n", __FILE__, __LINE__); failures++; } } while (0)

static int rename_calls;
static int fail_rename_call;
static int fail_rollback_call;
int __real_renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath);
int __wrap_renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    if (strcmp(oldpath, ".companion-restore.marker.tmp") == 0 ||
        strcmp(oldpath, ".companion-restore.prepared") == 0)
        return __real_renameat(olddirfd, oldpath, newdirfd, newpath);
    rename_calls++;
    if (rename_calls == fail_rename_call || rename_calls == fail_rollback_call) {
        errno = EIO;
        return -1;
    }
    return __real_renameat(olddirfd, oldpath, newdirfd, newpath);
}

static int write_all(int fd, const void *bytes, size_t size) {
    const char *p = bytes;
    while (size > 0) {
        ssize_t n = write(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += (size_t)n;
        size -= (size_t)n;
    }
    return 0;
}

static void mkdir_one(const char *path) {
    if (mkdir(path, 0700) != 0 && errno != EEXIST) {
        perror(path);
        exit(2);
    }
}

static void write_file(const char *path, const char *bytes) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0 || write_all(fd, bytes, strlen(bytes)) != 0 || close(fd) != 0) {
        perror(path);
        exit(2);
    }
}

static void unlink_fixture_names(void) {
    const char *const names[] = {
        DB, WAL, SHM, SESSION, INTEGRATION,
        BASE "/databases/.HomeAssistantDB.hapaneld-restore",
        BASE "/databases/.HomeAssistantDB.hapaneld-rollback",
        BASE "/databases/.HomeAssistantDB-wal.hapaneld-rollback",
        BASE "/databases/.HomeAssistantDB-shm.hapaneld-rollback",
        BASE "/shared_prefs/.session_0.xml.hapaneld-restore",
        BASE "/shared_prefs/.session_0.xml.hapaneld-rollback",
        BASE "/shared_prefs/.integration_0.xml.hapaneld-restore",
        BASE "/shared_prefs/.integration_0.xml.hapaneld-rollback",
        ROOT_STAGE "/.companion-restore.prepared",
        ROOT_STAGE "/.companion-restore.committed",
        ROOT_STAGE "/.companion-restore.marker.tmp",
        "/tmp/.hapaneld-companion-sentinel",
    };
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++) (void)unlink(names[i]);
    const char *const stages[] = {
        ROOT_STAGE "/companion-db.payload",
        ROOT_STAGE "/companion-session.payload",
        ROOT_STAGE "/companion-integration.payload",
    };
    for (size_t i = 0; i < sizeof stages / sizeof stages[0]; i++) (void)unlink(stages[i]);
}

static void setup_fixture(void) {
    mkdir_one("/tmp/.hapaneld-companion-test");
    mkdir_one("/tmp/.hapaneld-companion-test/data");
    mkdir_one("/tmp/.hapaneld-companion-test/data/user");
    mkdir_one(ROOT);
    mkdir_one(BASE);
    mkdir_one(BASE "/databases");
    mkdir_one(BASE "/shared_prefs");
    unlink_fixture_names();
    struct stat stage_st;
    if (lstat(ROOT_STAGE, &stage_st) == 0 && S_ISLNK(stage_st.st_mode)) unlink(ROOT_STAGE);
    write_file(DB, "database");
    write_file(WAL, "wal");
    write_file(SESSION, "session");
    sysexec_stub_reset();
    companion_test_set_fault(COMPANION_TEST_FAULT_NONE);
    rename_calls = fail_rename_call = fail_rollback_call = 0;
}

typedef struct {
    int fd;
    const char *line;
} job;

static void *dispatch_worker(void *arg) {
    job *work = arg;
    char line[MAX_LINE + 1];
    snprintf(line, sizeof line, "%s", work->line);
    conn_ctx ctx = { .fd = work->fd, .subscribed = 0 };
    dispatch(&ctx, line);
    close(work->fd);
    return NULL;
}

static ssize_t read_line(int fd, char *out, size_t capacity) {
    size_t used = 0;
    while (used + 1 < capacity) {
        ssize_t n = read(fd, out + used, 1);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        if (out[used++] == '\n') break;
    }
    out[used] = '\0';
    return (ssize_t)used;
}

static ssize_t read_exact(int fd, char *out, size_t size) {
    size_t used = 0;
    while (used < size) {
        ssize_t n = read(fd, out + used, size - used);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        used += (size_t)n;
    }
    return (ssize_t)used;
}

static void start(job *work, pthread_t *thread, int peer[2], const char *line) {
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0, "create protocol socket\n");
    *work = (job){ .fd = peer[0], .line = line };
    CHECK(pthread_create(thread, NULL, dispatch_worker, work) == 0, "start protocol worker\n");
}

static void finish(pthread_t thread, int peer) {
    pthread_join(thread, NULL);
    close(peer);
}

static void dispatch_once(const char *line, char *out, size_t capacity) {
    int peer[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, peer) == 0, "create direct dispatch socket\n");
    char command[MAX_LINE + 1];
    snprintf(command, sizeof command, "%s", line);
    conn_ctx ctx = { .fd = peer[0], .subscribed = 0 };
    dispatch(&ctx, command);
    read_line(peer[1], out, capacity);
    close(peer[0]);
    close(peer[1]);
}

static char *slurp(const char *path, char *out, size_t capacity) {
    int fd = open(path, O_RDONLY);
    ssize_t n = fd >= 0 ? read(fd, out, capacity - 1) : -1;
    if (fd >= 0) close(fd);
    out[n > 0 ? n : 0] = '\0';
    return out;
}

static void test_capability_status_and_launch_guards(void) {
    setup_fixture();
    char line[128];
    dispatch_once("COMPANIONCAPS", line, sizeof line);
    CHECK(strcmp(line, "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL\n") == 0,
          "capability envelope is exact (got %s)\n", line);
    dispatch_once("COMPANIONCAPS extra", line, sizeof line);
    CHECK(strcmp(line, "ERR\n") == 0, "capability probe rejects arguments\n");
    dispatch_once("COMPANIONSTATUS", line, sizeof line);
    CHECK(strcmp(line, "IDLE\n") == 0, "status is IDLE without a transaction\n");

    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 - -");
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "READY\n") == 0, "restore holds transaction lock while receiving payload\n");
    dispatch_once("COMPANIONSTATUS", line, sizeof line);
    CHECK(strcmp(line, "BUSY\n") == 0, "status is BUSY while restore owns the lock\n");
    dispatch_once("START " PKG "/.Home", line, sizeof line);
    CHECK(strcmp(line, "BUSY\n") == 0, "supported Companion START is blocked by transaction\n");
    dispatch_once("RELOAD " PKG, line, sizeof line);
    CHECK(strcmp(line, "BUSY\n") == 0, "supported Companion RELOAD is blocked by transaction\n");
    CHECK(sysexec_stub_count_run("am start -n " PKG) == 0,
          "blocked Companion START executes no shell command\n");
    CHECK(sysexec_stub_count_run("am force-stop " PKG) == 0,
          "blocked Companion RELOAD executes no shell command\n");
    dispatch_once("START com.example/.Main", line, sizeof line);
    CHECK(strcmp(line, "OK\n") == 0, "unrelated START remains available while Companion is busy\n");
    dispatch_once("RELOAD com.example", line, sizeof line);
    CHECK(strcmp(line, "OK\n") == 0, "unrelated RELOAD remains available while Companion is busy\n");

    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "ERR\n") == 0, "empty restore payload releases transaction cleanly\n");
    finish(thread, peer[1]);
    dispatch_once("COMPANIONSTATUS", line, sizeof line);
    CHECK(strcmp(line, "IDLE\n") == 0, "status returns to IDLE after failed stream\n");
}

static void test_backup_frames_raw_fixed_files(void) {
    setup_fixture();
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONBACKUP " PKG);
    char line[256], bytes[32];
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "BACKUP 3 18\n") == 0, "backup declares three bounded files (got %s)\n", line);

    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "FILE databases/HomeAssistantDB 8\n") == 0, "DB frame is first (got %s)\n", line);
    CHECK(read_exact(peer[1], bytes, 8) == 8 && memcmp(bytes, "database", 8) == 0, "DB bytes exact\n");
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "FILE databases/HomeAssistantDB-wal 3\n") == 0, "WAL frame follows DB (got %s)\n", line);
    CHECK(read_exact(peer[1], bytes, 3) == 3 && memcmp(bytes, "wal", 3) == 0, "WAL bytes exact\n");
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "FILE shared_prefs/session_0.xml 7\n") == 0, "session frame follows DB trio (got %s)\n", line);
    CHECK(read_exact(peer[1], bytes, 7) == 7 && memcmp(bytes, "session", 7) == 0, "session bytes exact\n");
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "DONE\n") == 0, "backup reports successful relaunch (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(sysexec_stub_count_run("am force-stop " PKG) == 1, "backup force-stops once\n");
    CHECK(sysexec_stub_count_run("monkey -p " PKG) == 1, "backup always attempts relaunch\n");
}

static void test_backup_rejects_links_and_reports_relaunch_failure(void) {
    setup_fixture();
    write_file("/tmp/.hapaneld-companion-sentinel", "sentinel");
    unlink(DB);
    CHECK(symlink("/tmp/.hapaneld-companion-sentinel", DB) == 0, "preplant DB symlink\n");
    sysexec_stub_fail_run("monkey -p", 1);

    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONBACKUP " PKG);
    char line[64], sentinel[16];
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "ERR RELAUNCH_ERR\n") == 0, "unsafe DB and relaunch failure are explicit (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(strcmp(slurp("/tmp/.hapaneld-companion-sentinel", sentinel, sizeof sentinel), "sentinel") == 0,
          "backup never follows unsafe live symlink\n");
}

static void test_backup_rejects_hardlinked_live_file(void) {
    setup_fixture();
    CHECK(link(DB, "/tmp/.hapaneld-companion-sentinel") == 0, "hardlink live DB fixture\n");
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONBACKUP " PKG);
    char line[64];
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "ERR\n") == 0, "hardlinked DB rejected (got %s)\n", line);
    finish(thread, peer[1]);
}

static void test_restore_commits_exact_payload_and_drops_db_sidecars(void) {
    setup_fixture();
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 4 -");
    char line[64], content[32];
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "READY\n") == 0, "valid restore receives READY (got %s)\n", line);
    CHECK(write_all(peer[1], "newdbnews", 9) == 0, "write concatenated restore payload\n");
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "OK\n") == 0, "restore commits and relaunches (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(strcmp(slurp(DB, content, sizeof content), "newdb") == 0, "DB replaced\n");
    CHECK(strcmp(slurp(SESSION, content, sizeof content), "news") == 0, "session replaced\n");
    CHECK(access(WAL, F_OK) != 0, "stale WAL removed by DB transaction\n");
}

static void test_restore_stream_failure_never_mutates_target(void) {
    setup_fixture();
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 - -");
    char line[64], content[32];
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "READY\n") == 0, "partial restore receives READY\n");
    CHECK(write_all(peer[1], "new", 3) == 0, "write partial payload\n");
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "ERR\n") == 0, "partial payload fails before target mutation (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(strcmp(slurp(DB, content, sizeof content), "database") == 0, "partial payload retains DB\n");
    CHECK(sysexec_stub_count_run("am force-stop") == 0, "partial payload never stops target\n");
}

static void test_restore_replaces_preplanted_stage_symlink_without_following(void) {
    setup_fixture();
    write_file("/tmp/.hapaneld-companion-sentinel", "sentinel");
    CHECK(symlink("/tmp/.hapaneld-companion-sentinel",
                  BASE "/databases/.HomeAssistantDB.hapaneld-restore") == 0,
          "preplant target-stage symlink\n");
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 - -");
    char line[64], content[32];
    read_line(peer[1], line, sizeof line);
    write_all(peer[1], "newdb", 5);
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "OK\n") == 0, "safe stage replacement succeeds (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(strcmp(slurp("/tmp/.hapaneld-companion-sentinel", content, sizeof content), "sentinel") == 0,
          "target-stage symlink destination untouched\n");
}

static void test_restore_rejects_root_stage_directory_symlink(void) {
    setup_fixture();
    (void)rmdir(ROOT_STAGE);
    mkdir_one("/tmp/.hapaneld-companion-stage-target");
    CHECK(symlink("/tmp/.hapaneld-companion-stage-target", ROOT_STAGE) == 0,
          "preplant root-stage directory symlink\n");
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 - -");
    char line[64], content[32];
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "STREAMERR\n") == 0, "root-stage directory symlink rejected before READY\n");
    finish(thread, peer[1]);
    CHECK(strcmp(slurp(DB, content, sizeof content), "database") == 0,
          "root-stage rejection retains live DB\n");
    unlink(ROOT_STAGE);
    rmdir("/tmp/.hapaneld-companion-stage-target");
}

static void test_restore_distinguishes_committed_relaunch_failure(void) {
    setup_fixture();
    sysexec_stub_fail_run("monkey -p", 1);
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 - -");
    char line[64], content[32];
    read_line(peer[1], line, sizeof line);
    write_all(peer[1], "newdb", 5);
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "COMMITTED RELAUNCH_ERR\n") == 0,
          "committed data remains distinct from relaunch failure (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(strcmp(slurp(DB, content, sizeof content), "newdb") == 0,
          "committed data is present despite relaunch failure\n");
}

static void test_restore_reports_rollback_and_partial_failure_distinctly(void) {
    setup_fixture();
    write_file(SHM, "shm");
    write_file(INTEGRATION, "integration");
    // Moves DB, session, integration, WAL, SHM then installs DB. Fail installing session (call 7);
    // all rollback renames remain available, so the helper must prove the prior set is intact.
    fail_rename_call = 7;
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 4 3");
    char line[64], content[32];
    read_line(peer[1], line, sizeof line);
    write_all(peer[1], "newdbnewsint", 12);
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "ROLLED_BACK\n") == 0, "successful rollback is explicit (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(strcmp(slurp(DB, content, sizeof content), "database") == 0, "rollback restores old DB\n");
    CHECK(strcmp(slurp(SESSION, content, sizeof content), "session") == 0, "rollback restores old session\n");

    setup_fixture();
    write_file(SHM, "shm");
    write_file(INTEGRATION, "integration");
    fail_rename_call = 7;
    fail_rollback_call = 10; // fail one reverse restoration after install failure
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 4 3");
    read_line(peer[1], line, sizeof line);
    write_all(peer[1], "newdbnewsint", 12);
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "ROLLBACK_FAILED RELAUNCH_SUPPRESSED\n") == 0,
          "failed rollback reports partial state without launching it (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(sysexec_stub_count_run("monkey -p " PKG) == 0,
          "known partial state is never relaunched\n");
}

static void run_interrupted_restore(enum companion_test_fault fault) {
    int peer[2];
    job work;
    pthread_t thread;
    companion_test_set_fault(fault);
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 - -");
    char line[64];
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "READY\n") == 0, "faulted restore receives READY\n");
    CHECK(write_all(peer[1], "newdb", 5) == 0, "write faulted restore payload\n");
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "ROLLBACK_FAILED RELAUNCH_SUPPRESSED\n") == 0,
          "interrupted transaction suppresses relaunch (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(sysexec_stub_count_run("monkey -p " PKG) == 0,
          "interrupted transaction never launches Companion\n");
    companion_test_set_fault(COMPANION_TEST_FAULT_NONE);
}

static void test_restore_recovers_prepared_and_committed_markers(void) {
    char content[32];

    setup_fixture();
    run_interrupted_restore(COMPANION_TEST_FAULT_AFTER_MOVES);
    CHECK(access(ROOT_STAGE "/.companion-restore.prepared", F_OK) == 0,
          "move-phase interruption retains prepared marker\n");
    CHECK(access(BASE "/databases/.HomeAssistantDB.hapaneld-rollback", F_OK) == 0,
          "move-phase interruption retains old DB rollback\n");
    sysexec_stub_fail_run("am force-stop " PKG, 1);
    dispatch_once("COMPANIONSTATUS", content, sizeof content);
    CHECK(strcmp(content, "BUSY\n") == 0,
          "status stays BUSY when journal recovery cannot stop Companion\n");
    CHECK(access(ROOT_STAGE "/.companion-restore.prepared", F_OK) == 0,
          "failed status recovery retains prepared marker\n");
    sysexec_stub_fail_run("", 0);
    dispatch_once("COMPANIONSTATUS", content, sizeof content);
    CHECK(strcmp(content, "IDLE\n") == 0,
          "status repairs a durable prepared marker after worker exit\n");
    CHECK(strcmp(slurp(DB, content, sizeof content), "database") == 0,
          "status recovery restores old DB after move-phase interruption\n");
    CHECK(access(ROOT_STAGE "/.companion-restore.prepared", F_OK) != 0,
          "status recovery clears prepared marker\n");
    CHECK(access(BASE "/databases/.HomeAssistantDB.hapaneld-rollback", F_OK) != 0,
          "status recovery consumes prepared rollback\n");
    dispatch_once("START " PKG "/.Home", content, sizeof content);
    CHECK(strcmp(content, "OK\n") == 0,
          "Companion launch is admitted after status recovery\n");

    setup_fixture();
    run_interrupted_restore(COMPANION_TEST_FAULT_AFTER_INSTALLS);
    CHECK(strcmp(slurp(DB, content, sizeof content), "newdb") == 0,
          "install-phase interruption leaves new live DB before recovery\n");
    dispatch_once("COMPANIONSTATUS", content, sizeof content);
    CHECK(strcmp(content, "IDLE\n") == 0,
          "status repairs an install-phase prepared marker\n");
    CHECK(strcmp(slurp(DB, content, sizeof content), "database") == 0,
          "status recovery replaces new live DB with rollback\n");

    setup_fixture();
    run_interrupted_restore(COMPANION_TEST_FAULT_AFTER_COMMIT_MARKER);
    CHECK(access(ROOT_STAGE "/.companion-restore.committed", F_OK) == 0,
          "post-commit interruption retains committed marker\n");
    dispatch_once("COMPANIONSTATUS", content, sizeof content);
    CHECK(strcmp(content, "IDLE\n") == 0,
          "status completes committed-marker cleanup\n");
    CHECK(strcmp(slurp(DB, content, sizeof content), "newdb") == 0,
          "committed status recovery preserves new DB\n");
    CHECK(access(ROOT_STAGE "/.companion-restore.committed", F_OK) != 0,
          "committed recovery clears marker after rollback cleanup\n");
    CHECK(access(BASE "/databases/.HomeAssistantDB.hapaneld-rollback", F_OK) != 0,
          "committed recovery removes obsolete rollback\n");
}

static void test_unexplained_rollback_fails_closed(void) {
    setup_fixture();
    CHECK(rename(DB, BASE "/databases/.HomeAssistantDB.hapaneld-rollback") == 0,
          "preplant unexplained rollback as only prior DB\n");
    char line[64], content[32];
    dispatch_once("COMPANIONSTATUS", line, sizeof line);
    CHECK(strcmp(line, "BUSY\n") == 0,
          "unexplained rollback keeps status BUSY without a marker\n");
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 5 - -");
    read_line(peer[1], line, sizeof line);
    CHECK(write_all(peer[1], "newdb", 5) == 0, "write restore beside unexplained rollback\n");
    shutdown(peer[1], SHUT_WR);
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "ROLLBACK_FAILED RELAUNCH_SUPPRESSED\n") == 0,
          "unexplained rollback fails closed (got %s)\n", line);
    finish(thread, peer[1]);
    CHECK(strcmp(slurp(BASE "/databases/.HomeAssistantDB.hapaneld-rollback",
                       content, sizeof content), "database") == 0,
          "later restore never deletes unexplained only rollback\n");
    CHECK(access(DB, F_OK) != 0, "failed-closed restore does not install new DB\n");
    CHECK(sysexec_stub_count_run("monkey -p " PKG) == 0,
          "unresolved rollback state suppresses relaunch\n");
}

static void test_restore_rejects_nonallowlisted_packages_and_sizes(void) {
    setup_fixture();
    int peer[2];
    job work;
    pthread_t thread;
    start(&work, &thread, peer, "COMPANIONRESTORE com.attacker.fake 5 - -");
    char line[64];
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "STREAMERR\n") == 0, "foreign package rejected before READY\n");
    finish(thread, peer[1]);
    start(&work, &thread, peer, "COMPANIONRESTORE " PKG " 33554433 - -");
    read_line(peer[1], line, sizeof line);
    CHECK(strcmp(line, "STREAMERR\n") == 0, "oversized DB rejected before READY\n");
    finish(thread, peer[1]);
}

int main(void) {
    test_capability_status_and_launch_guards();
    test_backup_frames_raw_fixed_files();
    test_backup_rejects_links_and_reports_relaunch_failure();
    test_backup_rejects_hardlinked_live_file();
    test_restore_commits_exact_payload_and_drops_db_sidecars();
    test_restore_stream_failure_never_mutates_target();
    test_restore_replaces_preplanted_stage_symlink_without_following();
    test_restore_rejects_root_stage_directory_symlink();
    test_restore_distinguishes_committed_relaunch_failure();
    test_restore_reports_rollback_and_partial_failure_distinctly();
    test_restore_recovers_prepared_and_committed_markers();
    test_unexplained_rollback_fails_closed();
    test_restore_rejects_nonallowlisted_packages_and_sizes();
    unlink_fixture_names();
    if (failures) {
        printf("COMPANION BOUNDARY FAILED: %d assertion(s)\n", failures);
        return 1;
    }
    printf("COMPANION BOUNDARY OK\n");
    return 0;
}
