#define _GNU_SOURCE

#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

static int fake_stat_result;
static uid_t fake_app_uid;

static int peer_auth_test_stat(const char *path, struct stat *st) {
    if (fake_stat_result != 0) return fake_stat_result;
    memset(st, 0, sizeof *st);
    st->st_uid = fake_app_uid;
    (void)path;
    return 0;
}

/* White-box the accept-loop policy while replacing only stat(2). Keeping the production function in
 * this translation unit ensures the regression test cannot drift into a second implementation. */
#define stat(path, st) peer_auth_test_stat((path), (st))
#define main hapaneld_helper_main_for_peer_auth_test
#include "../src/main.c"
#undef main
#undef stat

/* main.c's unused daemon entry point references these modules; the auth test never calls them. */
void input_init(void) {}
void gpio_init(void) {}
void screen_init(void) {}
void led_init(void) {}
int input_watch(const char *path, int grab) { (void)path; (void)grab; return 0; }
void server_serve(int fd) { (void)fd; }
void input_unsubscribe(int fd) { (void)fd; }
void gpio_unsubscribe(int fd) { (void)fd; }
void conn_release(void) {}
int conn_admit(void) { return 0; }
int guard_maintenance_init(void) { return 0; }
int guard_maintenance_replacement_safe(void) { return 0; }
int guard_maintenance_replacement_parent_grant(char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_export_lease(void) { return -1; }
int guard_maintenance_replacement_parent_abort(const char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_stage_app(const char nonce[65]) { (void)nonce; return -1; }
int guard_maintenance_replacement_supervisor_adopt_app(const char nonce[65]) {
    (void)nonce; return -1;
}
int guard_maintenance_replacement_worker_commit_app(const char nonce[65]) {
    (void)nonce; return -1;
}
int guard_maintenance_replacement_startup_reconcile_app(char nonce[65]) {
    (void)nonce; return 0;
}
void guard_maintenance_set_supervised(int supervised) { (void)supervised; }
void guard_maintenance_set_supervisor_owner(void) {}
int guard_maintenance_supervisor_tick(void) { return 0; }
int guard_maintenance_supervisor_work_deadline(enum guard_supervisor_work work,
        uint64_t *deadline_ms) { (void)work; (void)deadline_ms; return -1; }
int guard_maintenance_supervisor_start_work(enum guard_supervisor_work work, pid_t *pid) {
    (void)work; (void)pid; return -1;
}
int guard_maintenance_supervisor_complete(enum guard_supervisor_work work,
        enum guard_execution_result result, int status) {
    (void)work; (void)result; (void)status; return -1;
}
int sysexec_poll_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }
int sysexec_terminate_argv(pid_t pid, int *status) { (void)pid; (void)status; return -1; }

#define CHECK(condition, message) do { \
    if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); return 1; } \
} while (0)

int main(void) {
    fake_app_uid = 12345;
    fake_stat_result = 0;

    CHECK(uid_allowed(0), "root must remain allowed");
    CHECK(uid_allowed(fake_app_uid), "the currently resolved ha-paneld uid must be allowed");
    CHECK(!uid_allowed(2000), "generic Android shell uid must not inherit root helper verbs");
    CHECK(!uid_allowed(12346), "an unrelated app uid must be rejected");

    /* Prove a successful lookup is not cached across uninstall/data-dir loss. */
    CHECK(uid_allowed(fake_app_uid), "precondition: app uid is allowed while data dir exists");
    fake_stat_result = -1;
    CHECK(uid_allowed(0), "root must remain available while the app is absent");
    CHECK(!uid_allowed(fake_app_uid), "former app uid must fail closed after data dir disappears");

    fake_stat_result = 0;
    fake_app_uid = 23456;
    CHECK(!uid_allowed(12345), "old uid must stay rejected after reinstall with a new uid");
    CHECK(uid_allowed(fake_app_uid), "newly resolved app uid must be allowed after reinstall");
    CHECK(probe_command_allowed("PING"), "root probe mode must allow PING");
    CHECK(probe_command_allowed("COMPANIONCAPS"), "root probe mode must allow capability discovery");
    CHECK(probe_command_allowed("BUILDID"), "root probe mode must allow build identity");
    CHECK(probe_command_allowed("GUARDCAPS"), "root probe mode must allow Guard capability discovery");
    CHECK(probe_command_allowed("GUARDSTATUS"), "root probe mode must allow Guard status observation");
    const char *guard_mutation_verbs[] = {
        "GUARDPREPARE", "GUARDDEFINE", "GUARDSTREAM", "GUARDACTION", "GUARDHEALTH",
        "GUARDREFUSAL", "GUARDEVIDENCE", "GUARDCANCEL", "GUARDRETIRE",
    };
    for (size_t i = 0; i < sizeof guard_mutation_verbs / sizeof guard_mutation_verbs[0]; i++) {
        CHECK(!probe_command_allowed(guard_mutation_verbs[i]),
              "root probe mode must not expose Guard mutation verbs");
    }
    CHECK(!probe_command_allowed("REBOOT"), "root probe mode must not expose mutating verbs");
    CHECK(!probe_command_allowed("KEYEVENT SLEEP"),
          "root probe mode must not expose screen-power key injection");
    CHECK(!probe_command_allowed("PING extra"), "root probe mode must require an exact verb");
    CHECK(!probe_command_allowed("GUARDCAPS extra"),
          "root probe mode must require an exact Guard capability verb");
    CHECK(!probe_command_allowed("GUARDSTATUS extra"),
          "root probe mode must require an exact Guard status verb");

    puts("peer auth tests passed");
    return 0;
}
