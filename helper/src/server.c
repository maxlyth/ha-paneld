#include "server.h"
#include "dispatch.h"

#include <errno.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

void server_serve(int cfd) {
    // Idle timeout: a connection that sends nothing for IDLE_SEC is dropped — unless it SUBSCRIBEs,
    // where sitting idle (only reading the KEY stream) is the whole point. SO_RCVTIMEO affects reads
    // only; the evdev thread's writes to a subscriber keep flowing regardless.
    struct timeval tv = { .tv_sec = IDLE_SEC, .tv_usec = 0 };
    setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);

    conn_ctx ctx = { .fd = cfd, .subscribed = 0 };
    char line[MAX_LINE + 1];
    size_t len = 0;
    int overlong = 0;        // current line exceeded MAX_LINE — drop bytes through to the next newline
    char buf[256];
    for (;;) {
        ssize_t n = read(cfd, buf, sizeof buf);
        if (n == 0) break;                                    // client (half-)closed — done
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {    // idle timeout fired
                if (ctx.subscribed) continue;                 // subscribers are meant to idle — keep
                break;                                        // drop an idle non-subscriber
            }
            break;                                            // other read error
        }
        // Accumulate into a bounded line buffer so a command split across reads still parses, and an
        // overlong (malicious) line is dropped instead of overflowing or being mis-split.
        for (ssize_t i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '\n' || c == '\r') {
                if (!overlong && len > 0) { line[len] = '\0'; dispatch(&ctx, line); }
                len = 0; overlong = 0;
            } else if (len < MAX_LINE) {
                line[len++] = c;
            } else {
                overlong = 1;
            }
        }
    }
}
