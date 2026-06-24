// System-control verbs that act via the Android shell tools (am / wm / svc / cpufreq sysfs /
// screencap). Each runs as root through this daemon's su domain — reaching what a sandboxed app
// can't (background-activity-launch limits, root-only cpufreq, full screen capture). Every argument
// passed into a shell string is validated/whitelisted (util.h) before it reaches sysexec_run().
#ifndef HAPANELD_SYSCTL_H
#define HAPANELD_SYSCTL_H

#include "cmd.h"

void cmd_reload(conn_ctx *ctx, const char *args);     // RELOAD <pkg>      force-stop + relaunch
void cmd_start(conn_ctx *ctx, const char *args);      // START <pkg/cls>   launch an activity
void cmd_reboot(conn_ctx *ctx, const char *args);     // REBOOT
void cmd_density(conn_ctx *ctx, const char *args);    // DENSITY [<n>|reset]   get/set display density
void cmd_gov(conn_ctx *ctx, const char *args);        // GOV <governor>    CPU scaling governor
void cmd_screencap(conn_ctx *ctx, const char *args);  // SCREENCAP         raw PNG stream

#endif
