// Hardware-button instrumentation. The Android input pipeline doesn't deliver some physical buttons
// to a sandboxed app (e.g. the WF1589T power key, the TPA10 orange EV_SW switch). A reader thread
// opens an evdev node, optionally EVIOCGRABs it (exclusive grab suppresses the default Android
// action), and streams "KEY <code> <value>" / "SW <code> <value>" lines to SUBSCRIBEd clients. The
// app picks the node + grab flag from its DeviceProfile — the daemon stays panel-blind.
#ifndef HAPANELD_INPUT_H
#define HAPANELD_INPUT_H

#include "cmd.h"

void input_init(void);                  // reset the subscriber registry (call once at startup)
int  input_watch(const char *path, int grab);  // start watching a node (also used for --grab/--watch argv)
void input_unsubscribe(int fd);         // drop a connection's fd from the registry (on disconnect)

void cmd_watch(conn_ctx *ctx, const char *args);      // WATCH <evdev> <0|1>
void cmd_subscribe(conn_ctx *ctx, const char *args);  // SUBSCRIBE

#endif
