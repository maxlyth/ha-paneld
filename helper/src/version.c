#include "version.h"

#include "util.h"

#define STRINGIFY_INNER(value) #value
#define STRINGIFY(value) STRINGIFY_INNER(value)

#ifndef HAPANELD_BUILD_ID
#define HAPANELD_BUILD_ID "development"
#endif

#define BUILD_ID_RECORD_PREFIX "BUILDID "

static const char IDENTITY[] =
    "HELPER version=" HAPANELD_HELPER_VERSION
    " proto=" STRINGIFY(HAPANELD_HELPER_PROTOCOL_MAJOR)
    "." STRINGIFY(HAPANELD_HELPER_PROTOCOL_MINOR);
static const char BUILD_ID_RECORD[] = BUILD_ID_RECORD_PREFIX HAPANELD_BUILD_ID;

const char *helper_identity(void) {
    return IDENTITY;
}

const char *helper_build_id(void) {
    return BUILD_ID_RECORD + sizeof(BUILD_ID_RECORD_PREFIX) - 1;
}

const char *helper_build_id_record(void) {
    return BUILD_ID_RECORD;
}

void cmd_version(conn_ctx *ctx, const char *args) {
    if (*args != '\0') {
        reply(ctx->fd, "ERR\n");
        return;
    }
    reply(ctx->fd, IDENTITY);
    reply(ctx->fd, "\n");
}
