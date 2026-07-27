#!/usr/bin/env python3
"""Capture and compare ha-paneld process-resource budgets across builds.

Companion to measure-dashboard-performance.py. That collector watches the
dashboard renderer's main-thread CPU over HTTP; this one watches the *process
budget* over adb — total PSS, live thread count, open file descriptors and
retained recent crash/ANR marker lines — the signals a lifecycle or concurrency refactor can
regress (a leaked thread, an unbounded queue, a descriptor leak) on a panel that
runs for months. The HTTP perf API exposes none of these, so this reads them via
adb: `pidof`, `dumpsys meminfo`, `/proc/<pid>/status`, `/proc/<pid>/fd` and the
crash log buffer.

Two modes:
  capture  <adb-target>            -> one JSON snapshot on stdout / --output
  compare  <baseline> <candidate>  -> pass/fail against tolerances; nonzero exit on regression

The parsers are pure and unit-tested (scripts/tests/resource_budget_snapshot_test.py);
only the thin adb layer needs a live panel, which is where on-panel validation
of the exact command output happens. Output deliberately omits panel id / URL,
matching the perf collector; a snapshot carries only the build's version code so
baseline and candidate can be checked to be the same build lineage.

Rooted panels return every field; on a non-root panel with restricted /proc the
thread/fd fields degrade to null rather than failing, and PSS (via dumpsys) and
crash markers still populate.
"""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
from typing import Any, Callable

PKG = "io.github.maxlyth.hapaneld"
SCHEMA = 1
# Bound the crash-log read: the whole `-b crash` buffer can be large and dumping it unbounded times
# out over a slow/remote (e.g. WireGuard) adb link. A rise within the retained tail is a hard signal;
# an unchanged tail is not complete proof that an older marker was never evicted during a long soak.
CRASH_LOG_TAIL = 500

# Default regression tolerances. A refactor is behaviour-preserving, so these are
# tight: small absolute headroom for sampling noise, no tolerance for new crashes.
DEFAULT_TOLERANCES = {
    "pss_kb_pct": 10.0,   # total PSS may drift +10% for GC/sampling timing
    "pss_kb_abs": 8192,   # ...but never more than +8 MiB regardless of percent
    "threads_abs": 2,     # live thread count may rise by at most 2
    "fds_abs": 16,        # open fds may rise by at most 16
    # crash_markers: any increase is a hard fail (tolerance is implicitly 0)
}


class SnapshotError(RuntimeError):
    pass


# ----------------------------------------------------------------------------- parsers (pure)

def parse_pid(pidof_output: str) -> int | None:
    """First pid from `pidof <pkg>` (space-separated; may be empty)."""
    for token in pidof_output.split():
        if token.isdigit():
            return int(token)
    return None


def parse_total_pss_kb(meminfo_text: str) -> int | None:
    """Total PSS in KiB from `dumpsys meminfo <pid>`.

    Handles both the modern footer (`TOTAL PSS:   123456   TOTAL RSS: ...`) and
    the older App Summary / table `TOTAL` row (`  TOTAL   123456   ...`).
    """
    footer = re.search(r"TOTAL\s+PSS:\s*(\d+)", meminfo_text)
    if footer:
        return int(footer.group(1))
    row = re.search(r"^\s*TOTAL\s+(\d+)", meminfo_text, re.MULTILINE)
    if row:
        return int(row.group(1))
    return None


def parse_threads(status_text: str) -> int | None:
    """Live thread count from a `/proc/<pid>/status` `Threads:` line."""
    match = re.search(r"^Threads:\s*(\d+)", status_text, re.MULTILINE)
    return int(match.group(1)) if match else None


def parse_fd_count(fd_listing: str) -> int | None:
    """Open fd count from `ls -1 /proc/<pid>/fd`. Blank / permission-denied -> null."""
    lines = [ln for ln in fd_listing.splitlines() if ln.strip()]
    if not lines or any("Permission denied" in ln or "No such file" in ln for ln in lines):
        return None
    return len(lines)


def parse_crash_markers(crash_log_text: str, pkg: str = PKG) -> int:
    """Count retained recent crash-buffer marker lines referencing the package.

    A rise against a baseline is a hard regression signal. Absence is not a
    complete long-soak crash guarantee because log rotation can evict older lines.
    """
    if not crash_log_text.strip():
        return 0
    return sum(1 for ln in crash_log_text.splitlines() if pkg in ln)


def parse_version_code(dumpsys_package_text: str) -> str | None:
    """versionCode from `dumpsys package <pkg>` so a snapshot is tied to a build."""
    match = re.search(r"versionCode=(\d+)", dumpsys_package_text)
    return match.group(1) if match else None


# ----------------------------------------------------------------------------- compare (pure)

def compare_snapshots(
    baseline: dict[str, Any],
    candidate: dict[str, Any],
    tolerances: dict[str, float] | None = None,
) -> dict[str, Any]:
    """Return {ok: bool, findings: [...], deltas: {...}}.

    A finding is a metric that regressed beyond tolerance. Missing (null) fields
    on either side are reported as 'skipped', never as a pass, so a degraded
    capture cannot silently green a real regression.
    """
    tol = {**DEFAULT_TOLERANCES, **(tolerances or {})}
    b, c = baseline.get("metrics", {}), candidate.get("metrics", {})
    findings: list[str] = []
    skipped: list[str] = []
    deltas: dict[str, Any] = {}

    if baseline.get("version_code") and candidate.get("version_code") and \
            baseline["version_code"] == candidate["version_code"]:
        findings.append(
            f"baseline and candidate are the same build ({candidate['version_code']}); "
            "compare a control build against the refactor build"
        )

    # PSS: percent OR absolute, whichever is tighter must hold.
    bp, cp = b.get("pss_kb"), c.get("pss_kb")
    if isinstance(bp, int) and isinstance(cp, int):
        d = cp - bp
        deltas["pss_kb"] = d
        pct = (d / bp * 100.0) if bp else 0.0
        if d > tol["pss_kb_abs"] and pct > tol["pss_kb_pct"]:
            findings.append(f"total PSS +{d} KiB (+{pct:.1f}%) exceeds +{tol['pss_kb_abs']} KiB and +{tol['pss_kb_pct']}%")
    else:
        skipped.append("pss_kb")

    for key, label, limit in (
        ("threads", "live threads", tol["threads_abs"]),
        ("fds", "open fds", tol["fds_abs"]),
    ):
        bv, cv = b.get(key), c.get(key)
        if isinstance(bv, int) and isinstance(cv, int):
            d = cv - bv
            deltas[key] = d
            if d > limit:
                findings.append(f"{label} +{d} exceeds +{limit}")
        else:
            skipped.append(key)

    bm, cm = b.get("crash_markers", 0), c.get("crash_markers", 0)
    deltas["crash_markers"] = cm - bm
    if cm > bm:
        findings.append(
            f"retained recent crash/ANR marker lines rose {bm} -> {cm}; "
            "inspect the candidate and full soak evidence"
        )

    return {"schema": SCHEMA, "ok": not findings, "findings": findings, "skipped": skipped, "deltas": deltas}


# ----------------------------------------------------------------------------- adb layer (thin)

def _adb(target: str, args: list[str], timeout: float = 20.0) -> str:
    cmd = ["adb", "-s", target, "shell", *args]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError as e:
        raise SnapshotError("adb not found on PATH") from e
    except subprocess.TimeoutExpired as e:
        raise SnapshotError(f"adb timed out: {shlex.join(cmd)}") from e
    return out.stdout


def _denied(text: str) -> bool:
    low = text.lower()
    return "denied" in low or "not permitted" in low or not text.strip()


def _read(
    runner: Callable[[str, list[str]], str],
    target: str,
    argv: list[str],
    su_prefix: str,
) -> str:
    """Run a `/proc` read directly; on a restricted panel retry via the operator-supplied
    root prefix (e.g. `su 0` / `su root`). No su-dialect auto-detection lives here — the
    operator names their panel's join-style form once with --su-prefix, which keeps this a
    diagnostic and avoids a fourth copy of the fleet su matrix."""
    out = runner(target, argv)
    if su_prefix and _denied(out):
        out = runner(target, [*su_prefix.split(), *argv])
    return out


def capture(
    target: str,
    runner: Callable[[str, list[str]], str] = _adb,
    pkg: str = PKG,
    su_prefix: str = "",
) -> dict[str, Any]:
    """One snapshot from a live panel. `runner` is injected in tests."""
    pid = parse_pid(runner(target, ["pidof", pkg]))
    if pid is None:
        raise SnapshotError(f"{pkg} is not running on {target} (pidof empty)")

    # Query meminfo by PID, not package: on some devices (e.g. TPA10/Android 11) `dumpsys meminfo <pkg>`
    # returns the global memory view with no per-app "TOTAL PSS:" footer, so by-pkg parses null there.
    pss = parse_total_pss_kb(runner(target, ["dumpsys", "meminfo", str(pid)]))
    threads = parse_threads(_read(runner, target, ["cat", f"/proc/{pid}/status"], su_prefix))
    fds = parse_fd_count(_read(runner, target, ["ls", "-1", f"/proc/{pid}/fd"], su_prefix))
    crashes = parse_crash_markers(runner(target, ["logcat", "-b", "crash", "-d", "-t", str(CRASH_LOG_TAIL)]), pkg)
    version = parse_version_code(runner(target, ["dumpsys", "package", pkg]))

    final_pid = parse_pid(runner(target, ["pidof", pkg]))
    if final_pid != pid:
        final_label = str(final_pid) if final_pid is not None else "not running"
        raise SnapshotError(
            f"{pkg} restarted during snapshot on {target}: pid {pid} -> {final_label}; retry capture"
        )

    return {
        "schema": SCHEMA,
        "version_code": version,
        "metrics": {"pss_kb": pss, "threads": threads, "fds": fds, "crash_markers": crashes},
    }


# ----------------------------------------------------------------------------- CLI

def _emit(obj: dict[str, Any], output: str) -> None:
    body = json.dumps(obj, indent=2, sort_keys=True, allow_nan=False) + "\n"
    if output == "-":
        sys.stdout.write(body)
    else:
        with open(output, "w", encoding="utf-8") as fh:
            fh.write(body)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    cap = sub.add_parser("capture", help="one resource snapshot from an adb target")
    cap.add_argument("target", help="adb target, e.g. 192.168.1.50:5555")
    cap.add_argument("--output", default="-", help="JSON file, or - for stdout")
    cap.add_argument(
        "--su-prefix",
        default="",
        help="join-style root prefix for /proc reads on a rooted panel, e.g. 'su 0' or 'su root'; "
        "omit on a non-root panel (thread/fd fields then degrade to null)",
    )

    cmp_ = sub.add_parser("compare", help="baseline vs candidate; nonzero exit on regression")
    cmp_.add_argument("baseline", help="baseline snapshot JSON (a trusted control build)")
    cmp_.add_argument("candidate", help="candidate snapshot JSON (the refactor build)")
    cmp_.add_argument("--output", default="-", help="verdict JSON file, or - for stdout")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "capture":
        try:
            _emit(capture(args.target, su_prefix=args.su_prefix), args.output)
        except SnapshotError as e:
            print(f"capture failed: {e}", file=sys.stderr)
            return 2
        return 0
    if args.command == "compare":
        with open(args.baseline, encoding="utf-8") as fh:
            baseline = json.load(fh)
        with open(args.candidate, encoding="utf-8") as fh:
            candidate = json.load(fh)
        verdict = compare_snapshots(baseline, candidate)
        _emit(verdict, args.output)
        return 0 if verdict["ok"] else 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
