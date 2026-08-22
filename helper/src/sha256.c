#include "sha256.h"

#include <errno.h>
#include <string.h>
#include <unistd.h>

static uint32_t rotr(uint32_t value, unsigned count) {
    return (value >> count) | (value << (32U - count));
}

static uint32_t load_be32(const unsigned char *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static void store_be32(unsigned char *p, uint32_t value) {
    p[0] = (unsigned char)(value >> 24);
    p[1] = (unsigned char)(value >> 16);
    p[2] = (unsigned char)(value >> 8);
    p[3] = (unsigned char)value;
}

static const uint32_t K[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

static void transform(hapaneld_sha256 *ctx, const unsigned char block[64]) {
    uint32_t w[64];
    for (unsigned i = 0; i < 16; i++) w[i] = load_be32(block + 4U * i);
    for (unsigned i = 16; i < 64; i++) {
        uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }

    uint32_t a = ctx->state[0], b = ctx->state[1], c = ctx->state[2], d = ctx->state[3];
    uint32_t e = ctx->state[4], f = ctx->state[5], g = ctx->state[6], h = ctx->state[7];
    for (unsigned i = 0; i < 64; i++) {
        uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t t1 = h + s1 + ch + K[i] + w[i];
        uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = s0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

void hapaneld_sha256_init(hapaneld_sha256 *ctx) {
    static const uint32_t initial[8] = {
        0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
        0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U,
    };
    memcpy(ctx->state, initial, sizeof initial);
    ctx->bytes = 0;
    ctx->used = 0;
}

void hapaneld_sha256_update(hapaneld_sha256 *ctx, const void *bytes, size_t size) {
    const unsigned char *p = bytes;
    ctx->bytes += size;
    while (size > 0) {
        size_t space = sizeof ctx->block - ctx->used;
        size_t take = size < space ? size : space;
        memcpy(ctx->block + ctx->used, p, take);
        ctx->used += take;
        p += take;
        size -= take;
        if (ctx->used == sizeof ctx->block) {
            transform(ctx, ctx->block);
            ctx->used = 0;
        }
    }
}

void hapaneld_sha256_final(hapaneld_sha256 *ctx, unsigned char digest[32]) {
    uint64_t bits = ctx->bytes * 8U;
    unsigned char padding[128] = {0x80};
    size_t pad = ctx->used < 56 ? 56 - ctx->used : 120 - ctx->used;
    hapaneld_sha256_update(ctx, padding, pad);
    unsigned char length[8];
    for (unsigned i = 0; i < 8; i++) length[7U - i] = (unsigned char)(bits >> (8U * i));
    hapaneld_sha256_update(ctx, length, sizeof length);
    for (unsigned i = 0; i < 8; i++) store_be32(digest + 4U * i, ctx->state[i]);
    memset(ctx, 0, sizeof *ctx);
}

void hapaneld_sha256_hex(const unsigned char digest[32], char hex[65]) {
    static const char digits[] = "0123456789abcdef";
    for (unsigned i = 0; i < 32; i++) {
        hex[2U * i] = digits[digest[i] >> 4];
        hex[2U * i + 1U] = digits[digest[i] & 15U];
    }
    hex[64] = '\0';
}

int hapaneld_sha256_fd(int fd, uint64_t expected_size, char hex[65]) {
    if (fd < 0 || lseek(fd, 0, SEEK_SET) < 0) return -1;
    hapaneld_sha256 ctx;
    hapaneld_sha256_init(&ctx);
    uint64_t total = 0;
    unsigned char buffer[65536];
    for (;;) {
        ssize_t count = read(fd, buffer, sizeof buffer);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return -1;
        if (count == 0) break;
        if (total > UINT64_MAX - (uint64_t)count) return -1;
        total += (uint64_t)count;
        hapaneld_sha256_update(&ctx, buffer, (size_t)count);
    }
    if (total != expected_size) return -1;
    unsigned char digest[32];
    hapaneld_sha256_final(&ctx, digest);
    hapaneld_sha256_hex(digest, hex);
    return lseek(fd, 0, SEEK_SET) >= 0 ? 0 : -1;
}
