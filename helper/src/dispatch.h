// Command dispatch: split a NUL-terminated command line into a verb (first whitespace-delimited
// token) and an argument string (the remainder, leading spaces trimmed), then call the handler the
// verb maps to. Matching is EXACT on the verb token — so SCREEN vs SCREENCAP, OFF vs OFFOFF, REBOOT
// vs REBOOTNOW can't collide (the old prefix-strncmp chain depended on handler ordering for that).
// The verb→handler table lives in dispatch.c; adding a command is one row + a handler in its module.
#ifndef HAPANELD_DISPATCH_H
#define HAPANELD_DISPATCH_H

#include "cmd.h"

void dispatch(conn_ctx *ctx, char *line);

#endif
