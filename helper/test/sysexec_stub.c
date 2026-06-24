// Stub sysexec for the fuzz + unit-test builds: the real parser/dispatch/validator code links and
// runs unmodified, but nothing here execs a command, opens a pipe, spawns a thread, or reboots the
// host. This is what lets a valid REBOOT/RELOAD/WATCH/GOV run through the real handlers on the build
// machine with no effect — replacing the per-call macro stubbing the fuzz harness used to need.
#include "sysexec.h"

int   sysexec_run(const char *cmd)        { (void)cmd; return 0; }
FILE *sysexec_popen_r(const char *cmd)    { (void)cmd; return (FILE *)0; }  // callers handle NULL
int   sysexec_pclose(FILE *p)             { (void)p; return 0; }

// Return failure so input_watch() frees its per-node arg itself (the real daemon hands it to a
// lifetime evdev thread). No thread is spawned here, and nothing leaks — so the fuzz build can run
// with LeakSanitizer ON.
int   sysexec_spawn(void *(*fn)(void *), void *arg) { (void)fn; (void)arg; return -1; }

void  sysexec_reboot(void)                { }
