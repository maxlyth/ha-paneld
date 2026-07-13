// System-control verbs that act via the Android shell tools (am / wm / svc / cpufreq sysfs /
// screencap). Each runs as root through this daemon's su domain — reaching what a sandboxed app
// can't (background-activity-launch limits, root-only cpufreq, full screen capture). Every argument
// passed into a shell string is validated/whitelisted (util.h) before it reaches sysexec_run().
#ifndef HAPANELD_SYSCTL_H
#define HAPANELD_SYSCTL_H

#include "cmd.h"

void cmd_reload(conn_ctx *ctx, const char *args);     // RELOAD <pkg>      force-stop + relaunch
void cmd_start(conn_ctx *ctx, const char *args);      // START <pkg/cls>   launch an activity
void cmd_sethome(conn_ctx *ctx, const char *args);    // SETHOME <pkg/cls> set default home (launcher)
void cmd_reboot(conn_ctx *ctx, const char *args);     // REBOOT
void cmd_density(conn_ctx *ctx, const char *args);    // DENSITY [<n>|reset]   get/set display density
void cmd_fontscale(conn_ctx *ctx, const char *args);  // FONTSCALE [<s>|reset] get/set system font scale
void cmd_gov(conn_ctx *ctx, const char *args);        // GOV <governor>    CPU scaling governor
void cmd_screencap(conn_ctx *ctx, const char *args);  // SCREENCAP         raw PNG stream
void cmd_appstate(conn_ctx *ctx, const char *args);   // APPSTATE <pkg>    FG|BG|DEAD (watchdog probe)
void cmd_stop(conn_ctx *ctx, const char *args);       // STOP <pkg>        force-stop (no relaunch)
void cmd_disable(conn_ctx *ctx, const char *args);    // DISABLE <pkg>     pm disable-user (boot block)
void cmd_enable(conn_ctx *ctx, const char *args);     // ENABLE <pkg>      pm enable (reverse DISABLE)
void cmd_overlay(conn_ctx *ctx, const char *args);    // OVERLAY <pkg> deny|allow  SYSTEM_ALERT_WINDOW
void cmd_install(conn_ctx *ctx, const char *args);    // INSTALL <apk-path>  root pm install of a staged, allowlisted APK
void cmd_installstream(conn_ctx *ctx, const char *args); // INSTALLSTREAM <bytes> two-phase socket upload + install
void cmd_installgc(conn_ctx *ctx, const char *args);  // INSTALLGC <apk-path> remove retained input under install lock

#endif
