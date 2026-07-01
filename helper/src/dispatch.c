#include "dispatch.h"

#include <string.h>

#include "led.h"
#include "screen.h"
#include "input.h"
#include "sysctl.h"
#include "perf.h"
#include "util.h"

static void cmd_ping(conn_ctx *ctx, const char *args) { (void)args; reply(ctx->fd, "OK\n"); }

// The command table — the single place a verb is wired to a handler. Handlers live in the capability
// module that owns the verb (led.c, screen.c, …); a new command is one row here plus its handler.
static const struct { const char *verb; cmd_fn fn; } COMMANDS[] = {
    { "RGB",       cmd_rgb },
    { "OFF",       cmd_off },
    { "BTN",       cmd_btn },
    { "LEDPROBE",  cmd_ledprobe },
    { "SCREEN",    cmd_screen },
    { "SCREENCAP", cmd_screencap },
    { "RELOAD",    cmd_reload },
    { "START",     cmd_start },
    { "SETHOME",   cmd_sethome },
    { "APPSTATE",  cmd_appstate },
    { "STOP",      cmd_stop },
    { "DISABLE",   cmd_disable },
    { "ENABLE",    cmd_enable },
    { "OVERLAY",   cmd_overlay },
    { "INSTALL",   cmd_install },
    { "WATCH",     cmd_watch },
    { "SUBSCRIBE", cmd_subscribe },
    { "REBOOT",    cmd_reboot },
    { "DENSITY",   cmd_density },
    { "FONTSCALE", cmd_fontscale },
    { "GOV",       cmd_gov },
    { "PERFDUMP",      cmd_perfdump },
    { "PING",          cmd_ping },
};

void dispatch(conn_ctx *ctx, char *line) {
    while (*line == ' ' || *line == '\t') line++;     // trim leading whitespace

    // Verb = the first whitespace-delimited token, copied out bounded (an overlong token matches
    // nothing → ERR, so it can't overflow or be mis-parsed).
    char verb[24];
    size_t v = 0;
    while (line[v] && line[v] != ' ' && line[v] != '\t') {
        if (v < sizeof verb - 1) verb[v] = line[v];
        v++;
    }
    verb[v < sizeof verb ? v : sizeof verb - 1] = '\0';

    const char *args = line + v;                      // remainder after the verb token…
    while (*args == ' ' || *args == '\t') args++;      // …with leading spaces trimmed

    for (size_t i = 0; i < sizeof COMMANDS / sizeof COMMANDS[0]; i++)
        if (v < sizeof verb && strcmp(verb, COMMANDS[i].verb) == 0) {
            COMMANDS[i].fn(ctx, args);
            return;
        }
    reply(ctx->fd, "ERR\n");
}
