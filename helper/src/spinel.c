/*
 * Minimal Spinel/HDLC-Lite client for commissioning an OpenThread NCP.
 *
 * Protocol reference: OpenThread lib/hdlc/hdlc.cpp (HDLC-Lite framing) and
 * lib/spinel/spinel.h (property IDs and packed-uint encoding).
 *
 * Only the operations needed to provision a fresh NCP are implemented:
 *   CMD_RESET → wait for boot → SET PROP_THREAD_ACTIVE_DATASET → SET PROP_NET_IF_UP
 *   → SET PROP_NET_STACK_UP.
 * Responses are read and discarded (we verify a frame arrives, not its content) to
 * keep the code self-contained; a future revision can parse error TLVs if needed.
 */

#include "spinel.h"

#include <fcntl.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>

/* HDLC-Lite framing constants */
#define HDLC_FLAG    0x7E
#define HDLC_ESCAPE  0x7D
#define HDLC_XOR     0x20

/* Spinel header: frame-type=normal(00), IID=0, TID=0 */
#define SPINEL_HEADER          0x80
#define SPINEL_CMD_RESET       0x01
#define SPINEL_CMD_PROP_VALUE_SET 0x03

/* Property IDs (packed uint / varint, little-endian 7-bit groups).
 * PROP_NET_IF_UP (0x51) and PROP_NET_STACK_UP (0x52) fit in a single byte.
 * PROP_THREAD_ACTIVE_DATASET = SPINEL_PROP_THREAD_EXT__BEGIN (0x1500) + 0x20 = 0x1520.
 *   0x1520 as Spinel varint: low 7 bits = 0x20 | continuation(0x80) = 0xA0; high = 0x2A. */
#define PROP_NET_IF_UP    0x51
#define PROP_NET_STACK_UP 0x52
#define PROP_DATASET_B0   0xA0  /* packed-uint low byte for 0x1520 */
#define PROP_DATASET_B1   0x2A  /* packed-uint high byte for 0x1520 */

/* CRC-16/KERMIT — polynomial 0x8408 (reflected 0x1021), init 0xFFFF.
 * Nibble-table implementation matching OpenThread's hdlc.cpp crc16Ccitt(). */
static uint16_t crc16_step(uint16_t crc, uint8_t byte) {
    static const uint16_t t[16] = {
        0x0000, 0x1081, 0x2102, 0x3183, 0x4204, 0x5285, 0x6306, 0x7387,
        0x8408, 0x9489, 0xa50a, 0xb58b, 0xc60c, 0xd68d, 0xe70e, 0xf78f
    };
    crc = (crc >> 4) ^ t[(crc ^ byte) & 0xf];
    crc = (crc >> 4) ^ t[(crc ^ (byte >> 4)) & 0xf];
    return crc;
}

/* Byte-stuff and write one byte to fd (flag and escape bytes are escaped). */
static int stuff_byte(int fd, uint8_t b) {
    if (b == HDLC_FLAG || b == HDLC_ESCAPE) {
        uint8_t esc[2] = {HDLC_ESCAPE, (uint8_t)(b ^ HDLC_XOR)};
        return write(fd, esc, 2) == 2 ? 0 : -1;
    }
    return write(fd, &b, 1) == 1 ? 0 : -1;
}

/* Encode [payload, len] as an HDLC-Lite frame and write it to fd. */
static int hdlc_send(int fd, const uint8_t *payload, size_t len) {
    uint8_t flag = HDLC_FLAG;
    if (write(fd, &flag, 1) != 1) return -1;

    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc = crc16_step(crc, payload[i]);
        if (stuff_byte(fd, payload[i]) < 0) return -1;
    }
    /* CRC appended little-endian, also stuffed */
    if (stuff_byte(fd, (uint8_t)(crc & 0xFF)) < 0) return -1;
    if (stuff_byte(fd, (uint8_t)(crc >> 8)) < 0)  return -1;

    if (write(fd, &flag, 1) != 1) return -1;
    return 0;
}

/* Read bytes from fd until a complete HDLC-Lite frame is received and decoded into [out].
 * Returns payload length (CRC stripped) on success, -1 on timeout or buffer overflow.
 * VTIME is set to 10 deciseconds (1 s per byte); up to 10 consecutive empty reads before giving up. */
static int hdlc_recv(int fd, uint8_t *out, size_t maxlen) {
    uint8_t raw[512];
    size_t n = 0;
    int in_frame = 0, escaped = 0, empty = 0;

    while (empty < 10) {
        uint8_t b;
        if (read(fd, &b, 1) <= 0) { empty++; continue; }
        empty = 0;

        if (b == HDLC_FLAG) {
            if (in_frame && n >= 2) {           /* frame complete — strip 2-byte CRC */
                size_t plen = n - 2;
                if (plen > maxlen) return -1;
                memcpy(out, raw, plen);
                return (int)plen;
            }
            n = 0; in_frame = 1; escaped = 0;
        } else if (in_frame) {
            if (b == HDLC_ESCAPE) { escaped = 1; continue; }
            if (escaped) { b = (uint8_t)(b ^ HDLC_XOR); escaped = 0; }
            if (n < sizeof raw) raw[n++] = b;
        }
    }
    return -1;
}

int spinel_open(const char *dev) {
    int fd = open(dev, O_RDWR | O_NOCTTY);
    if (fd < 0) return -1;

    struct termios t;
    if (tcgetattr(fd, &t) < 0) { close(fd); return -1; }
    cfmakeraw(&t);
    cfsetispeed(&t, B115200);
    cfsetospeed(&t, B115200);
    t.c_cflag &= ~(unsigned)CRTSCTS;
    t.c_cc[VMIN]  = 0;
    t.c_cc[VTIME] = 10;    /* 1 s read timeout (in deciseconds) */
    if (tcsetattr(fd, TCSANOW, &t) < 0) { close(fd); return -1; }
    tcflush(fd, TCIOFLUSH);
    return fd;
}

void spinel_close(int fd) { close(fd); }

int spinel_commission(int fd, const uint8_t *dataset, size_t dlen) {
    uint8_t frame[512];
    uint8_t resp[256];

    /* 1. Reset NCP and wait for it to boot */
    frame[0] = SPINEL_HEADER;
    frame[1] = SPINEL_CMD_RESET;
    hdlc_send(fd, frame, 2);
    sleep(2);
    tcflush(fd, TCIFLUSH);   /* discard any unsolicited boot notifications */

    /* 2. Set Active Operational Dataset (PROP_THREAD_ACTIVE_DATASET = 0x1520) */
    if (4 + dlen > sizeof frame) return -1;
    frame[0] = SPINEL_HEADER;
    frame[1] = SPINEL_CMD_PROP_VALUE_SET;
    frame[2] = PROP_DATASET_B0;
    frame[3] = PROP_DATASET_B1;
    memcpy(frame + 4, dataset, dlen);
    if (hdlc_send(fd, frame, 4 + dlen) < 0) return -1;
    if (hdlc_recv(fd, resp, sizeof resp) < 0) return -1;

    /* 3. Bring up the network interface (PROP_NET_IF_UP = 0x51, bool true) */
    frame[0] = SPINEL_HEADER;
    frame[1] = SPINEL_CMD_PROP_VALUE_SET;
    frame[2] = PROP_NET_IF_UP;
    frame[3] = 0x01;
    if (hdlc_send(fd, frame, 4) < 0) return -1;
    if (hdlc_recv(fd, resp, sizeof resp) < 0) return -1;

    /* 4. Start the Thread stack (PROP_NET_STACK_UP = 0x52, bool true) */
    frame[0] = SPINEL_HEADER;
    frame[1] = SPINEL_CMD_PROP_VALUE_SET;
    frame[2] = PROP_NET_STACK_UP;
    frame[3] = 0x01;
    if (hdlc_send(fd, frame, 4) < 0) return -1;
    if (hdlc_recv(fd, resp, sizeof resp) < 0) return -1;

    return 0;
}
