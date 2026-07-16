#ifndef HAPANELD_CDPRELAY_POLICY_H
#define HAPANELD_CDPRELAY_POLICY_H

#include <arpa/inet.h>
#include <stdint.h>

/* Keep the privileged DevTools relay on the same IPv4 trust boundary as the HTTP control plane:
 * loopback, RFC1918, and IPv4 link-local sources only. The listener remains on all interfaces so it
 * follows DHCP/interface changes without restart, but globally routed peers are rejected at accept. */
static inline int cdprelay_peer_allowed(uint32_t network_address) {
    uint32_t address = ntohl(network_address);
    return (address & 0xff000000U) == 0x7f000000U || /* 127/8 */
           (address & 0xff000000U) == 0x0a000000U || /* 10/8 */
           (address & 0xfff00000U) == 0xac100000U || /* 172.16/12 */
           (address & 0xffff0000U) == 0xc0a80000U || /* 192.168/16 */
           (address & 0xffff0000U) == 0xa9fe0000U;   /* 169.254/16 */
}

#endif
