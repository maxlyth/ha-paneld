// Production sysexec: real exec / pipe / thread / reboot. See sysexec.h for why this is isolated.
#include "sysexec.h"

#include <pthread.h>
#include <stdlib.h>

int   sysexec_run(const char *cmd)        { return system(cmd); }
FILE *sysexec_popen_r(const char *cmd)    { return popen(cmd, "r"); }
int   sysexec_pclose(FILE *p)             { return pclose(p); }

int sysexec_spawn(void *(*fn)(void *), void *arg) {
    pthread_t t;
    if (pthread_create(&t, NULL, fn, arg) != 0) return -1;
    pthread_detach(t);
    return 0;
}

void sysexec_reboot(void) {
    if (system("svc power reboot 2>/dev/null || reboot")) { /* best-effort: we go down regardless */ }
}
