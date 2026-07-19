// Generic GPIO input streaming for root-only proximity and contact sensors. The app supplies only a
// bounded GPIO number from its DeviceProfile; the helper derives the legacy sysfs paths, holds the
// value descriptor, and streams normalized binary GPIO records on a GPIO-only subscription domain.
#ifndef HAPANELD_GPIO_H
#define HAPANELD_GPIO_H

#include "cmd.h"

#define GPIO_MAX_SUBSCRIBERS 8
#define GPIO_MAX_WATCHES 8
#define GPIO_MAX_NUMBER 65535U

void gpio_init(void);                 // reset GPIO subscribers and watches (startup/test only)
void gpio_unsubscribe(int fd);        // drop one connection from the GPIO subscriber registry
int  gpio_reset_watches(void);        // clear GPIO watches when no GPIO subscriber owns delivery
int  gpio_watch(unsigned gpio);       // open and start watching one already-exported GPIO

void cmd_gpiov1(conn_ctx *ctx, const char *args);        // GPIOV1
void cmd_gpiowatch(conn_ctx *ctx, const char *args);     // GPIOWATCH <n>
void cmd_gpiosubscribe(conn_ctx *ctx, const char *args); // GPIOSUBSCRIBE
void cmd_gpioreset(conn_ctx *ctx, const char *args);     // GPIORESET

#ifdef HAPANELD_TEST
void gpio_test_broadcast_invalid_generation(unsigned gpio);
#endif

#endif
