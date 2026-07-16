#include "version.h"

#include "util.h"

#define STRINGIFY_INNER(value) #value
#define STRINGIFY(value) STRINGIFY_INNER(value)

static const char IDENTITY[] =
    "HELPER version=" HAPANELD_HELPER_VERSION
    " proto=" STRINGIFY(HAPANELD_HELPER_PROTOCOL_MAJOR)
    "." STRINGIFY(HAPANELD_HELPER_PROTOCOL_MINOR);

const char *helper_identity(void) {
    return IDENTITY;
}

void cmd_version(conn_ctx *ctx, const char *args) {
    if (*args != '\0') {
        reply(ctx->fd, "ERR\n");
        return;
    }
    reply(ctx->fd, IDENTITY);
    reply(ctx->fd, "\n");
}
