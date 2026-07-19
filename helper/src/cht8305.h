// Room temperature + humidity read verb for the exact supported CHT8305-compatible input layouts.
//
// TPA10's CHT8305 and ZX-SMT156's GXHT30 vendor drivers report readings through the input subsystem as
// EV_ABS centi-units on root/input-owned nodes. The app cannot read those nodes, so the value comes through
// the helper. cmd_cht8305 point-reads the current values via EVIOCGABS (no wait for the next event).
#ifndef HAPANELD_CHT8305_H
#define HAPANELD_CHT8305_H

#include "cmd.h"

// CHT8305 — reply "T=<centi> H=<centi>\n" (raw hundredths of a degree / %RH) or "ERR\n" when either
// input is absent/unreadable or exact-name discovery is ambiguous. The app divides by 100 and applies
// the calibration offset.
void cmd_cht8305(conn_ctx *ctx, const char *args);

#ifdef HAPANELD_TEST
#include <stddef.h>
size_t cht8305_test_candidate_count(void);
int cht8305_test_event_name(const char *name);
int cht8305_test_candidate(size_t index, const char **temp_name, unsigned int *temp_axis,
                           const char **humidity_name, unsigned int *humidity_axis);

struct cht8305_test_input {
    const char *name;
    int readable;
    long value;
};

int cht8305_test_resolve(const struct cht8305_test_input *inputs, size_t count,
                         long *temp, long *humidity);
#endif

#endif
