#ifndef HAPANELD_SPINEL_H
#define HAPANELD_SPINEL_H

#include <stddef.h>
#include <stdint.h>

/* Open [dev] (e.g. "/dev/ttyS5") at 115200 baud, 8N1, raw; returns fd or -1. */
int  spinel_open(const char *dev);

/* Write the Active Operational Dataset (raw TLV bytes) to the OpenThread NCP on [fd] via
 * Spinel/HDLC-Lite, then bring up the Thread interface. Returns 0 on success, -1 on error.
 * Blocking: ~2 s NCP boot wait + up to 3 s for the three property-set round-trips. */
int  spinel_commission(int fd, const uint8_t *dataset, size_t dlen);

void spinel_close(int fd);

#endif
