#include "version.h"

#include "util.h"

#define STRINGIFY_INNER(value) #value
#define STRINGIFY(value) STRINGIFY_INNER(value)

#ifndef HAPANELD_BUILD_ID
#define HAPANELD_BUILD_ID "development"
#endif

static const char IDENTITY[] =
    "HELPER version=" HAPANELD_HELPER_VERSION
    " proto=" STRINGIFY(HAPANELD_HELPER_PROTOCOL_MAJOR)
    "." STRINGIFY(HAPANELD_HELPER_PROTOCOL_MINOR);

const char *helper_identity(void) {
    return IDENTITY;
}

const char *helper_build_id(void) {
    return HAPANELD_BUILD_ID;
}

void cmd_version(conn_ctx *ctx, const char *args) {
    if (*args != '\0') {
        reply(ctx->fd, "ERR\n");
        return;
    }
    reply(ctx->fd, IDENTITY);
    reply(ctx->fd, "\n");
}
