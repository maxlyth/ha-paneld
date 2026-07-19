#!/usr/bin/env python3
"""Collect and compare bounded ha-paneld dashboard performance arms.

The collector runs on another machine and polls the service-owned API. It never
changes entity-filter configuration and deliberately omits entity ids, process
names, panel ids, URLs, and filter hashes from its output.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import secrets
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable


SCHEMA = 2
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_RESULT_BYTES = 8 * 1024 * 1024
STALE_BROWSER_MS = 15_000
HEALTH_RE = re.compile(
    r"^ha-paneld\s+(?P<version>\S+)\s+panel=(?P<panel>\S+)\s+build=(?P<build>\S+)\s+cfg=(?P<cfg>\S+)"
)
COMPARISON_ID_RE = re.compile(r"^[0-9a-f]{32}$")
FINGERPRINT_RE = re.compile(r"^[0-9a-f]{64}$")
DIRECT_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class MeasurementError(RuntimeError):
    pass


def finite_number(value: Any) -> float | int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def object_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def array_value(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def validate_panel_url(raw: str) -> str:
    parsed = urllib.parse.urlsplit(raw.strip())
    if parsed.scheme not in {"http", "https"}:
        raise MeasurementError("--panel must use http:// or https://")
    if not parsed.hostname or parsed.username or parsed.password:
        raise MeasurementError("--panel must be an origin without credentials")
    if parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise MeasurementError("--panel must not include a path, query, or fragment")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))


def read_bounded(response: Any, limit: int = MAX_RESPONSE_BYTES) -> bytes:
    declared = response.headers.get("Content-Length")
    if declared and declared.isdigit() and int(declared) > limit:
        raise MeasurementError("panel response exceeds the bounded size limit")
    body = response.read(limit + 1)
    if len(body) > limit:
        raise MeasurementError("panel response exceeds the bounded size limit")
    return body


def fetch_bytes(panel: str, path: str, timeout: float) -> bytes:
    request = urllib.request.Request(
        panel + path,
        headers={"Accept": "application/json", "User-Agent": "ha-paneld-perf-measure/1"},
        method="GET",
    )
    try:
        # LAN measurements must not inherit a workstation's HTTP proxy. HTTPS still uses the
        # platform CA store and hostname verification; the collector never weakens TLS.
        with DIRECT_OPENER.open(request, timeout=timeout) as response:
            if response.status != 200:
                raise MeasurementError(f"panel returned HTTP {response.status}")
            return read_bounded(response)
    except urllib.error.HTTPError as error:
        raise MeasurementError(f"panel returned HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise MeasurementError(f"panel request failed ({type(error.reason).__name__})") from error
    except TimeoutError as error:
        raise MeasurementError("panel request timed out") from error


def fetch_json(panel: str, path: str, timeout: float) -> dict[str, Any]:
    try:
        value = json.loads(fetch_bytes(panel, path, timeout))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise MeasurementError("panel returned malformed JSON") from error
    if not isinstance(value, dict):
        raise MeasurementError("panel returned a non-object JSON response")
    return value


def fetch_health(panel: str, timeout: float) -> dict[str, str]:
    try:
        text = fetch_bytes(panel, "/api/v1/health", timeout).decode("utf-8", "strict").strip()
    except UnicodeDecodeError as error:
        raise MeasurementError("panel returned malformed health text") from error
    match = HEALTH_RE.match(text)
    if not match:
        raise MeasurementError("target did not return the expected ha-paneld health response")
    values = match.groupdict()
    fingerprint = hashlib.sha256(
        f"{values['version']}\n{values['build']}\n{values['cfg']}".encode("utf-8")
    ).hexdigest()
    return {
        "version": values["version"],
        "panel": values["panel"],
        "build": values["build"],
        "fingerprint": fingerprint,
    }


def validate_comparison_id(value: Any) -> str:
    if not isinstance(value, str) or not COMPARISON_ID_RE.fullmatch(value):
        raise MeasurementError("paired result has an invalid comparison id")
    return value


def public_environment(
    health: dict[str, str],
    binding: dict[str, Any],
    comparison_id: str,
) -> dict[str, str]:
    if binding.get("comparison_id") != comparison_id:
        raise MeasurementError("panel returned a binding for another comparison")
    panel_fingerprint = binding.get("panel_fingerprint")
    workload_fingerprint = binding.get("workload_fingerprint")
    if (
        not isinstance(panel_fingerprint, str)
        or not FINGERPRINT_RE.fullmatch(panel_fingerprint)
        or not isinstance(workload_fingerprint, str)
        or not FINGERPRINT_RE.fullmatch(workload_fingerprint)
    ):
        raise MeasurementError("panel returned malformed measurement binding")
    return {
        "version": health["version"],
        "build": health["build"],
        "panel_fingerprint": panel_fingerprint,
        "workload_fingerprint": workload_fingerprint,
    }


def sanitized_mode(status: dict[str, Any]) -> dict[str, Any]:
    learning = object_value(status.get("learning"))
    runtime = object_value(status.get("runtime"))
    catalog_count = finite_number(learning.get("catalog_count"))
    stream_count = finite_number(learning.get("stream_entity_count"))
    stream_mode = learning.get("stream_mode") if isinstance(learning.get("stream_mode"), str) else "unknown"
    # An empty or unsynchronised catalog cannot truthfully quantify the unfiltered stream.
    if stream_mode == "unfiltered" and (catalog_count is None or catalog_count <= 0):
        stream_count = None
    return {
        "stream_mode": stream_mode,
        "catalogued_stream_entity_count": stream_count,
        "catalog_count": catalog_count if catalog_count and catalog_count > 0 else None,
        "runtime_filter_active": runtime.get("active") is True,
        "runtime_filter_entity_count": finite_number(runtime.get("entityCount")),
        "modified_subscriptions": finite_number(runtime.get("modifiedSubscriptions")),
        "failures": finite_number(runtime.get("failures")),
        "direct_fallbacks": finite_number(runtime.get("directFallbacks")),
    }


def mode_errors(mode: dict[str, Any], expected: str) -> list[str]:
    errors: list[str] = []
    if mode.get("stream_mode") != expected:
        errors.append(f"stream mode is {mode.get('stream_mode')!r}, expected {expected!r}")
    active = mode.get("runtime_filter_active") is True
    if expected == "filtered":
        if not active:
            errors.append("the filtered renderer is not active")
        if (mode.get("modified_subscriptions") or 0) <= 0:
            errors.append("no entity subscription has been rewritten yet")
    elif active:
        errors.append("runtime filtering is still active in the unfiltered arm")
    if mode.get("catalogued_stream_entity_count") is None:
        errors.append("catalog-backed stream entity count is unavailable")
    return errors


def compact_perf(payload: dict[str, Any], elapsed_ms: int, request_ms: int) -> dict[str, Any]:
    render = object_value(payload.get("render"))
    builtin = object_value(payload.get("builtin"))
    network = object_value(payload.get("network"))
    entity_filter = object_value(payload.get("entityFilter"))
    traffic = object_value(entity_filter.get("traffic"))
    dashboard = object_value(payload.get("dashboard"))
    dashboard_filter = object_value(dashboard.get("filter"))
    state = object_value(dashboard.get("stateStream"))
    blocking = object_value(dashboard.get("blocking"))
    interaction = object_value(dashboard.get("interaction"))
    probe_cpu = None
    for row in array_value(payload.get("top")):
        if isinstance(row, dict) and row.get("self") is True:
            probe_cpu = finite_number(row.get("cpu"))
            break
    raw_filter_hash = entity_filter.get("filterHash")
    filter_revision = (
        hashlib.sha256(raw_filter_hash.encode("utf-8")).hexdigest()
        if isinstance(raw_filter_hash, str) and raw_filter_hash else ""
    )
    return {
        "elapsed_ms": elapsed_ms,
        "request_ms": request_ms,
        "system_cpu_pct": finite_number(payload.get("cpu")),
        "gpu_pct": finite_number(payload.get("gpu")),
        "memory_used_mb": finite_number(payload.get("memUsedMb")),
        "memory_total_mb": finite_number(payload.get("memTotalMb")),
        "renderer_main_pct": finite_number(render.get("mainPct")),
        "renderer_jank_pct": finite_number(render.get("jankPct")),
        "renderer_p99_ms": finite_number(render.get("p99")),
        "measurement_probe_cpu_pct": probe_cpu,
        "network_rx_bytes": finite_number(network.get("uidRxBytes")),
        "network_tx_bytes": finite_number(network.get("uidTxBytes")),
        "reloads_24h": finite_number(builtin.get("reloads24h")),
        "filter_active": entity_filter.get("active") is True,
        "_filter_revision": filter_revision,
        "filter_entity_count": finite_number(entity_filter.get("entityCount")),
        "filter_modified_subscriptions": finite_number(entity_filter.get("modifiedSubscriptions")),
        "filter_failures": finite_number(entity_filter.get("failures")),
        "filter_direct_fallbacks": finite_number(entity_filter.get("directFallbacks")),
        "traffic_sample_ms": finite_number(traffic.get("sampleMs")),
        "traffic_payload_bytes": finite_number(traffic.get("payloadBytes")),
        "traffic_entity_updates": finite_number(traffic.get("entityUpdates")),
        "traffic_observer_micros": finite_number(traffic.get("observerMicros")),
        "traffic_dropped_frames": finite_number(traffic.get("droppedFrames")),
        "dashboard_mode": dashboard.get("mode") if isinstance(dashboard.get("mode"), str) else "unknown",
        "dashboard_generation": finite_number(dashboard.get("generation")),
        "dashboard_sample_count": finite_number(dashboard.get("sampleCount")),
        "dashboard_window_ms": finite_number(dashboard.get("windowMs")),
        "dashboard_latest_sample_age_ms": finite_number(dashboard.get("latestSampleAgeMs")),
        "dashboard_filter_active": dashboard_filter.get("active") is True,
        "dashboard_filter_entity_count": finite_number(dashboard_filter.get("entityCount")),
        "state_frames_per_sec": finite_number(state.get("framesPerSec")),
        "state_updates_per_sec": finite_number(state.get("updatesPerSec")),
        "state_payload_bytes_per_sec": finite_number(state.get("payloadBytesPerSec")),
        "state_main_thread_ms_per_sec": finite_number(state.get("mainThreadMsPerSec")),
        "blocked_ms_per_sec": finite_number(blocking.get("blockedMsPerSec")),
        "longest_frame_ms": finite_number(blocking.get("longestFrameMs")),
        "render_ms_per_sec": finite_number(blocking.get("renderMsPerSec")),
        "interaction_p95_ms": finite_number(interaction.get("p95Ms")),
    }


SERIES = (
    "request_ms",
    "system_cpu_pct",
    "gpu_pct",
    "renderer_main_pct",
    "renderer_jank_pct",
    "renderer_p99_ms",
    "measurement_probe_cpu_pct",
    "memory_used_mb",
    "state_frames_per_sec",
    "state_updates_per_sec",
    "state_payload_bytes_per_sec",
    "state_main_thread_ms_per_sec",
    "blocked_ms_per_sec",
    "longest_frame_ms",
    "render_ms_per_sec",
    "interaction_p95_ms",
)
BROWSER_SERIES = (
    "state_frames_per_sec",
    "state_updates_per_sec",
    "state_payload_bytes_per_sec",
    "state_main_thread_ms_per_sec",
    "blocked_ms_per_sec",
    "longest_frame_ms",
    "render_ms_per_sec",
    "interaction_p95_ms",
)


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * fraction) - 1)
    return ordered[index]


def series_summary(samples: list[dict[str, Any]], key: str) -> dict[str, Any]:
    values = [float(value) for sample in samples if (value := finite_number(sample.get(key))) is not None]
    if not values:
        return {"count": 0, "min": None, "median": None, "p95": None, "max": None}
    return {
        "count": len(values),
        "min": min(values),
        "median": statistics.median(values),
        "p95": percentile(values, 0.95),
        "max": max(values),
    }


def counter_delta(samples: list[dict[str, Any]], key: str) -> float | int | None:
    values = [value for sample in samples if (value := finite_number(sample.get(key))) is not None]
    if len(values) < 2 or values[-1] < values[0]:
        return None
    return values[-1] - values[0]


def counter_rate(
    samples: list[dict[str, Any]],
    key: str,
    clock_key: str,
    clock_units_per_second: float = 1_000.0,
) -> float | None:
    points = [
        (float(clock), float(value))
        for sample in samples
        if (clock := finite_number(sample.get(clock_key))) is not None
        and (value := finite_number(sample.get(key))) is not None
        and value >= 0
    ]
    if len(points) < 2:
        return None
    elapsed = points[-1][0] - points[0][0]
    delta = points[-1][1] - points[0][1]
    if elapsed <= 0 or delta < 0:
        return None
    return delta / (elapsed / clock_units_per_second)


def drift_errors(samples: list[dict[str, Any]], expected: str) -> list[str]:
    errors: list[str] = []
    expected_active = expected == "filtered"
    if any(sample.get("dashboard_mode") != "builtin_direct" for sample in samples):
        errors.append("built-in browser telemetry was not installed for every retained sample")
    if any(sample.get("filter_active") is not expected_active for sample in samples):
        errors.append("runtime filter mode changed during the arm")
    if any(sample.get("dashboard_filter_active") is not expected_active for sample in samples):
        errors.append("dashboard filter mode changed during the arm")
    generations = {
        value for sample in samples
        if (value := finite_number(sample.get("dashboard_generation"))) is not None
    }
    if len(generations) != 1:
        errors.append("dashboard renderer generation changed during the arm")
    counts = {
        value for sample in samples
        if (value := finite_number(sample.get("filter_entity_count"))) is not None
    }
    if len(counts) > 1:
        errors.append("configured filter entity count changed during the arm")
    revisions = {sample.get("_filter_revision") for sample in samples}
    if len(revisions) != 1 or (expected == "filtered" and revisions == {""}):
        errors.append("configured filter revision changed or was unavailable during the arm")
    if any((finite_number(sample.get("filter_failures")) or 0) > 0 for sample in samples):
        errors.append("entity-filter failures occurred during the arm")
    if any((finite_number(sample.get("filter_direct_fallbacks")) or 0) > 0 for sample in samples):
        errors.append("an entity-filter direct fallback occurred during the arm")
    return errors


def summarize_arm(
    samples: list[dict[str, Any]],
    expected: str,
    poll_errors: int,
) -> tuple[dict[str, Any], list[str]]:
    ages = [
        float(value) for sample in samples
        if (value := finite_number(sample.get("dashboard_latest_sample_age_ms"))) is not None
    ]
    fresh_samples = [
        sample for sample in samples
        if (age := finite_number(sample.get("dashboard_latest_sample_age_ms"))) is not None
        and 0 <= age <= STALE_BROWSER_MS
    ]
    summary = {
        key: series_summary(fresh_samples if key in BROWSER_SERIES else samples, key)
        for key in SERIES
    }
    stalled = len(samples) - len(fresh_samples)
    traffic_samples = samples if not stalled else []
    summary.update({
        "sample_count": len(samples),
        "poll_error_count": poll_errors,
        "browser_freshness_sample_count": len(ages),
        "browser_fresh_sample_count": len(fresh_samples),
        "browser_stalled_sample_count": stalled,
        "browser_stalled_fraction": stalled / len(samples) if samples else None,
        "network_rx_bytes_per_sec": counter_rate(samples, "network_rx_bytes", "elapsed_ms"),
        "network_tx_bytes_per_sec": counter_rate(samples, "network_tx_bytes", "elapsed_ms"),
        "traffic_sample_ms_delta": counter_delta(traffic_samples, "traffic_sample_ms"),
        "traffic_payload_bytes_per_sec": counter_rate(
            traffic_samples, "traffic_payload_bytes", "traffic_sample_ms",
        ),
        "traffic_entity_updates_per_sec": counter_rate(
            traffic_samples, "traffic_entity_updates", "traffic_sample_ms",
        ),
        "traffic_observer_micros_per_sec": counter_rate(
            traffic_samples, "traffic_observer_micros", "traffic_sample_ms",
        ),
        "traffic_dropped_frames_per_sec": counter_rate(
            traffic_samples, "traffic_dropped_frames", "traffic_sample_ms",
        ),
    })
    errors = drift_errors(samples, expected)
    if len(samples) < 3:
        errors.append("fewer than three retained performance samples")
    if summary["system_cpu_pct"]["count"] < 3:
        errors.append("fewer than three whole-panel CPU samples")
    if summary["renderer_main_pct"]["count"] < 3:
        errors.append("fewer than three native renderer-main CPU samples")
    if not ages:
        errors.append("dashboard freshness field is unavailable; use a build containing this measurement path")
    attempts = len(samples) + poll_errors
    if attempts and poll_errors / attempts > 0.2:
        errors.append("more than 20% of retained-window polls failed")
    return summary, errors


def invalid_arm(
    label: str,
    expected: str,
    comparison_id: str,
    settings: dict[str, Any],
    environment: dict[str, str] | None,
    mode: dict[str, Any] | None,
    errors: list[str],
) -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "kind": "ha-paneld-dashboard-performance-arm",
        "label": label,
        "expected_mode": expected,
        "comparison_id": comparison_id,
        "settings": settings,
        "environment": environment,
        "mode_start": mode,
        "mode_end": None,
        "samples": [],
        "summary": None,
        "valid": False,
        "validation_errors": errors,
    }


def collect_arm(
    panel: str,
    label: str,
    expected: str,
    comparison_id: str,
    seconds: int,
    warmup: int,
    interval: int,
    timeout: float,
    json_fetcher: Callable[[str, str, float], dict[str, Any]] = fetch_json,
    health_fetcher: Callable[[str, float], dict[str, str]] = fetch_health,
    clock: Callable[[], float] = time.monotonic,
    sleeper: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    settings = {
        "duration_seconds": seconds,
        "warmup_seconds": warmup,
        "interval_seconds": interval,
        "request_timeout_seconds": timeout,
        "browser_stale_after_ms": STALE_BROWSER_MS,
    }
    environment_start = None
    try:
        health_start = health_fetcher(panel, timeout)
        binding_start = json_fetcher(
            panel, f"/api/v1/perf/binding?comparison_id={comparison_id}", timeout
        )
        environment_start = public_environment(health_start, binding_start, comparison_id)
        mode_start = sanitized_mode(json_fetcher(panel, "/api/v1/dashboard/entity-filter", timeout))
    except MeasurementError as error:
        return invalid_arm(label, expected, comparison_id, settings, environment_start, None, [str(error)])
    initial_errors = mode_errors(mode_start, expected)
    if initial_errors:
        return invalid_arm(
            label, expected, comparison_id, settings, environment_start, mode_start, initial_errors
        )

    started = clock()
    next_poll = started
    deadline = started + seconds
    samples: list[dict[str, Any]] = []
    poll_errors = 0
    while clock() < deadline:
        now = clock()
        if now < next_poll:
            sleeper(next_poll - now)
        if clock() >= deadline:
            break
        requested = clock()
        try:
            payload = json_fetcher(panel, "/api/v1/perf", timeout)
            received = clock()
            elapsed_ms = max(0, round((received - started) * 1000))
            if received - started >= warmup:
                samples.append(compact_perf(
                    payload,
                    elapsed_ms=elapsed_ms,
                    request_ms=max(0, round((received - requested) * 1000)),
                ))
        except MeasurementError:
            if clock() - started >= warmup:
                poll_errors += 1
        next_poll += interval

    terminal_errors: list[str] = []
    try:
        mode_end = sanitized_mode(json_fetcher(panel, "/api/v1/dashboard/entity-filter", timeout))
        binding_end = json_fetcher(
            panel, f"/api/v1/perf/binding?comparison_id={comparison_id}", timeout
        )
        health_end = health_fetcher(panel, timeout)
        environment_end = public_environment(health_end, binding_end, comparison_id)
    except MeasurementError as error:
        mode_end = None
        health_end = None
        environment_end = None
        terminal_errors.append(str(error))
    if mode_end is not None:
        terminal_errors.extend(mode_errors(mode_end, expected))
    if health_end is not None:
        if health_start["version"] != health_end["version"] or health_start["build"] != health_end["build"]:
            terminal_errors.append("ha-paneld build changed during the arm")
        if health_start["fingerprint"] != health_end["fingerprint"]:
            terminal_errors.append("panel configuration changed during the arm")
        if health_start["panel"] != health_end["panel"]:
            terminal_errors.append("panel identity changed during the arm")
    if environment_end is not None and environment_start != environment_end:
        terminal_errors.append("measurement workload changed during the arm")
    summary, sample_errors = summarize_arm(samples, expected, poll_errors)
    errors = terminal_errors + sample_errors
    for sample in samples:
        sample.pop("_filter_revision", None)
    return {
        "schema": SCHEMA,
        "kind": "ha-paneld-dashboard-performance-arm",
        "label": label,
        "expected_mode": expected,
        "comparison_id": comparison_id,
        "settings": settings,
        "environment": environment_start,
        "mode_start": mode_start,
        "mode_end": mode_end,
        "samples": samples,
        "summary": summary,
        "valid": not errors,
        "validation_errors": errors,
    }


COMPARISON_METRICS = (
    "system_cpu_pct",
    "renderer_main_pct",
    "renderer_jank_pct",
    "renderer_p99_ms",
    "measurement_probe_cpu_pct",
    "memory_used_mb",
    "state_updates_per_sec",
    "state_payload_bytes_per_sec",
    "state_main_thread_ms_per_sec",
    "blocked_ms_per_sec",
    "render_ms_per_sec",
    "interaction_p95_ms",
    "request_ms",
)


def compare_arms(first: dict[str, Any], second: dict[str, Any]) -> dict[str, Any]:
    arms = {first.get("expected_mode"): first, second.get("expected_mode"): second}
    errors: list[str] = []
    if set(arms) != {"filtered", "unfiltered"}:
        errors.append("comparison requires one filtered arm and one unfiltered arm")
    filtered = arms.get("filtered", {})
    unfiltered = arms.get("unfiltered", {})
    if not filtered.get("valid"):
        errors.append("filtered arm is invalid")
    if not unfiltered.get("valid"):
        errors.append("unfiltered arm is invalid")
    binding_errors: list[str] = []
    for label, arm in (("filtered", filtered), ("unfiltered", unfiltered)):
        if arm.get("kind") != "ha-paneld-dashboard-performance-arm":
            binding_errors.append(f"{label} input is not a performance arm result")
        try:
            validate_comparison_id(arm.get("comparison_id"))
        except MeasurementError:
            binding_errors.append(f"{label} arm lacks a valid comparison binding")
        environment = object_value(arm.get("environment"))
        if not isinstance(environment.get("version"), str) or not environment.get("version"):
            binding_errors.append(f"{label} arm lacks a version identity")
        if not isinstance(environment.get("build"), str) or not environment.get("build"):
            binding_errors.append(f"{label} arm lacks a build identity")
        for key in ("panel_fingerprint", "workload_fingerprint"):
            value = environment.get(key)
            if not isinstance(value, str) or not FINGERPRINT_RE.fullmatch(value):
                binding_errors.append(f"{label} arm lacks a valid {key}")
    errors.extend(binding_errors)
    comparison_id = filtered.get("comparison_id")
    if not binding_errors and comparison_id != unfiltered.get("comparison_id"):
        errors.append("arms are not linked; collect the second arm with --pair-with")
    elif not binding_errors:
        filtered_environment = object_value(filtered.get("environment"))
        unfiltered_environment = object_value(unfiltered.get("environment"))
        if (
            filtered_environment.get("version"), filtered_environment.get("build")
        ) != (
            unfiltered_environment.get("version"), unfiltered_environment.get("build")
        ):
            errors.append("arms were not collected from the same ha-paneld build")
        if filtered_environment.get("panel_fingerprint") != unfiltered_environment.get("panel_fingerprint"):
            errors.append("arms were not collected from the same physical panel")
        if filtered_environment.get("workload_fingerprint") != unfiltered_environment.get("workload_fingerprint"):
            errors.append("arms used different measurement-relevant panel configuration")
    filtered_settings = object_value(filtered.get("settings"))
    unfiltered_settings = object_value(unfiltered.get("settings"))
    for key in ("duration_seconds", "warmup_seconds", "interval_seconds"):
        if filtered_settings.get(key) != unfiltered_settings.get(key):
            errors.append(f"arms used different {key}")
    metrics: dict[str, Any] = {}
    filtered_summary = object_value(filtered.get("summary"))
    unfiltered_summary = object_value(unfiltered.get("summary"))
    for key in COMPARISON_METRICS:
        left = finite_number(object_value(filtered_summary.get(key)).get("median"))
        right = finite_number(object_value(unfiltered_summary.get(key)).get("median"))
        metrics[key] = {
            "filtered_median": left,
            "unfiltered_median": right,
            "delta_unfiltered_minus_filtered": right - left if left is not None and right is not None else None,
        }
    for key in (
        "network_rx_bytes_per_sec",
        "network_tx_bytes_per_sec",
        "traffic_payload_bytes_per_sec",
        "traffic_entity_updates_per_sec",
        "traffic_observer_micros_per_sec",
        "traffic_dropped_frames_per_sec",
        "browser_stalled_fraction",
    ):
        left = finite_number(filtered_summary.get(key))
        right = finite_number(unfiltered_summary.get(key))
        metrics[key] = {
            "filtered": left,
            "unfiltered": right,
            "delta_unfiltered_minus_filtered": right - left if left is not None and right is not None else None,
        }
    return {
        "schema": SCHEMA,
        "kind": "ha-paneld-dashboard-performance-comparison",
        "filtered_label": filtered.get("label"),
        "unfiltered_label": unfiltered.get("label"),
        "comparison_id": comparison_id,
        "filtered_catalogued_stream_entity_count": object_value(filtered.get("mode_end")).get(
            "catalogued_stream_entity_count"
        ),
        "unfiltered_catalogued_stream_entity_count": object_value(unfiltered.get("mode_end")).get(
            "catalogued_stream_entity_count"
        ),
        "metrics": metrics,
        "valid": not errors,
        "validation_errors": errors,
    }


def load_result(path: str) -> dict[str, Any]:
    file_path = Path(path)
    if not file_path.is_file() or file_path.stat().st_size > MAX_RESULT_BYTES:
        raise MeasurementError(f"invalid or oversized result file: {path}")
    try:
        value = json.loads(file_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise MeasurementError(f"could not read result file: {path}") from error
    if not isinstance(value, dict) or value.get("schema") != SCHEMA:
        raise MeasurementError(f"unsupported result file: {path}")
    return value


def comparison_id_for_collection(pair_with: str | None, expected: str) -> str:
    if pair_with is None:
        return secrets.token_hex(16)
    result = load_result(pair_with)
    if result.get("kind") != "ha-paneld-dashboard-performance-arm":
        raise MeasurementError("--pair-with must reference a collected arm result")
    if result.get("valid") is not True:
        raise MeasurementError("--pair-with must reference a valid collected arm")
    if result.get("expected_mode") == expected:
        raise MeasurementError("--pair-with must reference the opposite filter mode")
    return validate_comparison_id(result.get("comparison_id"))


def write_result(result: dict[str, Any], output: str) -> None:
    body = json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + "\n"
    if len(body.encode("utf-8")) > MAX_RESULT_BYTES:
        raise MeasurementError("measurement result exceeds the bounded size limit")
    if output == "-":
        sys.stdout.write(body)
        return
    path = Path(output)
    try:
        with path.open("x", encoding="utf-8") as handle:
            handle.write(body)
    except FileExistsError as error:
        raise MeasurementError(f"refusing to overwrite existing output: {output}") from error
    except OSError as error:
        raise MeasurementError(f"could not write output: {output}") from error


def bounded_int(name: str, value: int, minimum: int, maximum: int) -> int:
    if value < minimum or value > maximum:
        raise MeasurementError(f"{name} must be between {minimum} and {maximum}")
    return value


def validate_label(value: str) -> str:
    if not 1 <= len(value) <= 80 or any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise MeasurementError("--label must be 1-80 printable characters")
    return value


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    collect = sub.add_parser("collect", help="collect one manually selected filter arm")
    collect.add_argument("--panel", required=True, help="panel origin, for example http://192.168.1.50:8888")
    collect.add_argument("--expect", required=True, choices=("filtered", "unfiltered"))
    collect.add_argument("--label", required=True, help="non-sensitive label for this arm")
    collect.add_argument("--seconds", type=int, default=180)
    collect.add_argument("--warmup", type=int, default=30)
    collect.add_argument("--interval", type=int, default=10)
    collect.add_argument("--timeout", type=float, default=5.0)
    collect.add_argument("--pair-with", help="first arm JSON; links this arm to the same panel/workload")
    collect.add_argument("--output", default="-", help="new JSON file, or - for stdout")
    compare = sub.add_parser("compare", help="compare one filtered and one unfiltered result")
    compare.add_argument("filtered_or_unfiltered")
    compare.add_argument("other_arm")
    compare.add_argument("--output", default="-", help="new JSON file, or - for stdout")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "collect":
            panel = validate_panel_url(args.panel)
            seconds = bounded_int("--seconds", args.seconds, 30, 900)
            warmup = bounded_int("--warmup", args.warmup, 0, seconds - 10)
            interval = bounded_int("--interval", args.interval, 5, 30)
            if not 1.0 <= args.timeout <= 30.0:
                raise MeasurementError("--timeout must be between 1 and 30 seconds")
            result = collect_arm(
                panel=panel,
                label=validate_label(args.label),
                expected=args.expect,
                comparison_id=comparison_id_for_collection(args.pair_with, args.expect),
                seconds=seconds,
                warmup=warmup,
                interval=interval,
                timeout=args.timeout,
            )
        else:
            result = compare_arms(load_result(args.filtered_or_unfiltered), load_result(args.other_arm))
        write_result(result, args.output)
        return 0 if result.get("valid") else 2
    except MeasurementError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
