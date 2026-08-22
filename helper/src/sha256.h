#ifndef HAPANELD_SHA256_H
#define HAPANELD_SHA256_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint32_t state[8];
    uint64_t bytes;
    unsigned char block[64];
    size_t used;
} hapaneld_sha256;

void hapaneld_sha256_init(hapaneld_sha256 *ctx);
void hapaneld_sha256_update(hapaneld_sha256 *ctx, const void *bytes, size_t size);
void hapaneld_sha256_final(hapaneld_sha256 *ctx, unsigned char digest[32]);
void hapaneld_sha256_hex(const unsigned char digest[32], char hex[65]);

/* Hash an already-open descriptor from offset zero without changing its final offset. */
int hapaneld_sha256_fd(int fd, uint64_t expected_size, char hex[65]);

#endif
