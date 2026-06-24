// Shared command-handler contract for the hapaneld-helper daemon.
//
// dispatch() (dispatch.c) splits a command line into a verb + argument string and calls the matching
// handler. Each capability module (led.c, screen.c, …) defines its own handlers with this signature;
// the verb→handler table lives in dispatch.c. A handler parses `args` (the text AFTER the verb, with
// no leading spaces), does its work, and writes a reply on ctx->fd.
#ifndef HAPANELD_CMD_H
#define HAPANELD_CMD_H

// Per-connection state threaded through a command. `subscribed` is set by the SUBSCRIBE handler so
// the server's idle-timeout exempts a connection that has joined the async event stream (those are
// meant to sit idle, only reading).
typedef struct {
    int fd;
    int subscribed;
} conn_ctx;

typedef void (*cmd_fn)(conn_ctx *ctx, const char *args);

#endif
