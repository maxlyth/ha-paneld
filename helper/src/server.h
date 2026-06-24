// Per-connection serving: the bounded line accumulator that turns a byte stream into command lines,
// dispatches each, and enforces the idle timeout. Connection setup, peer-auth, and the accept loop
// live in main.c; this is just "given a connected fd, serve it until it closes".
#ifndef HAPANELD_SERVER_H
#define HAPANELD_SERVER_H

#define MAX_LINE 512   // longest accepted command line; longer lines are dropped, not mis-split
#define IDLE_SEC 30    // drop a non-SUBSCRIBE connection that sends nothing for this long

// Serve a connected socket fd until the client (half-)closes or an idle non-subscriber times out.
void server_serve(int cfd);

#endif
