// CHT8305 room temperature + humidity read verb (TPA10 and any panel exposing the same driver).
//
// The CHT8305 is an i2c temp/humidity chip whose vendor driver reports each reading through the input
// subsystem as an EV_ABS / ABS_THROTTLE axis (value = the reading × 100), on device nodes owned
// system:system — so an unprivileged app cannot read them and the value must come through the root
// daemon. cmd_cht8305 point-reads the *current* axis value via EVIOCGABS (no blocking wait for an event).
#ifndef HAPANELD_CHT8305_H
#define HAPANELD_CHT8305_H

#include "cmd.h"

// CHT8305 — reply "T=<centi> H=<centi>\n" (raw hundredths of a degree / %RH) or "ERR\n" when either
// input device is absent/unreadable. The app divides by 100 and applies the calibration offset.
void cmd_cht8305(conn_ctx *ctx, const char *args);

#endif
