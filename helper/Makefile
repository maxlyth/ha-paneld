# Host-native build, unit tests, and fuzz wiring for the hapaneld-helper daemon.
#
# The shipped Android binary is cross-compiled by build.sh (Docker NDK) — it compiles the same
# src/*.c set. This Makefile is the host-side dev/CI surface: a -Werror compile check, the unit
# tests, and the source list the fuzz harness reuses (so neither test nor fuzz can silently drop a
# module). Only a host C compiler is needed.
#
#   make            # -Werror host build of the daemon (compile sanity check)
#   make test       # build + run the unit tests
#   make fuzz       # ASan+UBSan parser fuzz (delegates to fuzz/run.sh)
#   make clean

CC      ?= cc
# -Wno-format-truncation: the PERFDUMP /proc walk intentionally builds short paths/lines into fixed
# buffers (pids and comms are small in practice) and snprintf truncates harmlessly; gcc can't prove
# the bound, and the shipped NDK clang build doesn't flag it. Everything else is -Werror-clean.
CFLAGS  ?= -O2 -g -Wall -Wextra -Werror -Wno-format-truncation
LDLIBS  ?= -lpthread

SRC_DIR := src
BUILD   := build

# Capability + transport modules — the SINGLE source list shared by the daemon, the fuzz harness, and
# the unit tests, so coverage can never silently drop a module. main.c (accept loop) and sysexec.c
# (real exec/pipe/thread/reboot) are deliberately NOT here: the fuzz and test builds swap in a stub
# sysexec and never run main, so a valid REBOOT/RELOAD/WATCH can't exec or spawn on the build host.
CORE_SRCS := $(addprefix $(SRC_DIR)/, util.c led.c screen.c input.c sysctl.c perf.c thread.c server.c dispatch.c)
DAEMON_SRCS := $(CORE_SRCS) $(SRC_DIR)/main.c $(SRC_DIR)/sysexec.c
STUB := test/sysexec_stub.c

.PHONY: all daemon test fuzz clean print-core
all: daemon

# Host build of the real daemon — a compile sanity check (-Werror); the shipped binary is build.sh.
daemon: $(BUILD)/hapaneld-helper
$(BUILD)/hapaneld-helper: $(DAEMON_SRCS) | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(DAEMON_SRCS) $(LDLIBS) -o $@

# Unit tests: CORE modules + the sysexec stub (no real exec) + the test driver.
test: $(BUILD)/unit
	$(BUILD)/unit
# --wrap=ioctl lets the tests capture the ledjni per-channel ioctls (the SMT1019 LED path) without a
# real /dev/ledjni; only test code emits ioctls in this build, so the wrap is unambiguous.
$(BUILD)/unit: $(CORE_SRCS) $(STUB) test/unit.c | $(BUILD)
	$(CC) $(CFLAGS) -I$(SRC_DIR) $(CORE_SRCS) $(STUB) test/unit.c $(LDLIBS) -Wl,--wrap=ioctl -o $@

# Fuzz: ASan+UBSan over the same CORE_SRCS (the script asks this Makefile for the list).
fuzz:
	./fuzz/run.sh

# Used by fuzz/run.sh to reuse the one source list.
print-core:
	@echo $(CORE_SRCS)

$(BUILD):
	mkdir -p $(BUILD)

clean:
	rm -rf $(BUILD) fuzz/build
