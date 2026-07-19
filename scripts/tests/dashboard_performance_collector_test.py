import importlib.util
import json
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "measure-dashboard-performance.py"
SPEC = importlib.util.spec_from_file_location("dashboard_perf_measure", SCRIPT)
assert SPEC and SPEC.loader
measure = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(measure)


def status(mode="filtered", count=42, catalog=100, modified=1):
    return {
        "learning": {
            "stream_mode": mode,
            "stream_entity_count": count if mode == "filtered" else catalog,
            "catalog_count": catalog,
        },
        "runtime": {
            "active": mode == "filtered",
            "entityCount": count if mode == "filtered" else 0,
            "modifiedSubscriptions": modified if mode == "filtered" else 0,
            "failures": 0,
            "directFallbacks": 0,
        },
    }


def binding(comparison_id="0123456789abcdef0123456789abcdef"):
    return {
        "comparison_id": comparison_id,
        "panel_fingerprint": "a" * 64,
        "workload_fingerprint": "b" * 64,
    }


def perf(active=True, generation=7, age=1_000, counter=1):
    return {
        "cpu": 80 + counter,
        "gpu": 30,
        "memUsedMb": 500,
        "memTotalMb": 1000,
        "render": {"mainPct": 90 + counter, "jankPct": 5, "p99": 32},
        "top": [
            {"name": "secret.process.name", "cpu": 2.5},
            {"name": "sampling probes (su/dumpsys)", "cpu": 0.4, "self": True},
        ],
        "builtin": {"reloads24h": 0},
        "network": {"uidRxBytes": 1000 + counter * 100, "uidTxBytes": 500 + counter * 10},
        "entityFilter": {
            "active": active,
            "entityCount": 42 if active else 0,
            "filterHash": "private-filter-hash",
            "modifiedSubscriptions": 1 if active else 0,
            "failures": 0,
            "directFallbacks": 0,
            "traffic": {
                "sampleMs": counter * 5000,
                "payloadBytes": counter * 1000,
                "entityUpdates": counter * 10,
                "observerMicros": counter * 5,
                "droppedFrames": 0,
            },
        },
        "dashboard": {
            "mode": "builtin_direct",
            "generation": generation,
            "sampleCount": counter,
            "windowMs": counter * 5000,
            "latestSampleAgeMs": age,
            "filter": {"active": active, "entityCount": 42 if active else 0},
            "stateStream": {
                "framesPerSec": 2,
                "updatesPerSec": 10,
                "payloadBytesPerSec": 1000,
                "mainThreadMsPerSec": 20,
            },
            "blocking": {"blockedMsPerSec": 3, "longestFrameMs": 20, "renderMsPerSec": 2},
            "interaction": {"p95Ms": 50},
            "topEntities": [{"entityId": "sensor.private"}],
        },
    }


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class DashboardPerformanceCollectorTest(unittest.TestCase):
    def test_panel_url_requires_a_bare_http_origin(self):
        self.assertEqual(
            "http://192.0.2.10:8888",
            measure.validate_panel_url("http://192.0.2.10:8888/"),
        )
        for invalid in (
            "ftp://192.0.2.10",
            "http://user:pass@192.0.2.10:8888",
            "http://192.0.2.10:8888/private",
            "http://192.0.2.10:8888/?token=secret",
        ):
            with self.assertRaises(measure.MeasurementError):
                measure.validate_panel_url(invalid)

    def test_label_is_short_printable_and_explicitly_user_chosen(self):
        self.assertEqual("filtered-control", measure.validate_label("filtered-control"))
        for invalid in ("", "line\nbreak", "x" * 81):
            with self.assertRaises(measure.MeasurementError):
                measure.validate_label(invalid)

    def test_compact_sample_omits_entity_ids_process_names_and_filter_hash(self):
        sample = measure.compact_perf(perf(), elapsed_ms=1000, request_ms=20)
        serialized = json.dumps(sample)

        self.assertNotIn("sensor.private", serialized)
        self.assertNotIn("secret.process.name", serialized)
        self.assertNotIn("private-filter-hash", serialized)
        self.assertEqual(0.4, sample["measurement_probe_cpu_pct"])
        self.assertEqual(1_000, sample["dashboard_latest_sample_age_ms"])

    def test_summary_keeps_native_metrics_when_browser_batches_stall(self):
        samples = [
            measure.compact_perf(perf(age=1_000, counter=1), 10_000, 10),
            measure.compact_perf(perf(age=20_000, counter=2), 20_000, 11),
            measure.compact_perf(perf(age=-1, counter=3), 30_000, 12),
        ]

        summary, errors = measure.summarize_arm(samples, "filtered", poll_errors=0)

        self.assertEqual([], errors)
        self.assertEqual(3, summary["renderer_main_pct"]["count"])
        self.assertEqual(1, summary["state_updates_per_sec"]["count"])
        self.assertEqual(2, summary["browser_stalled_sample_count"])
        self.assertAlmostEqual(2 / 3, summary["browser_stalled_fraction"])
        self.assertIsNone(summary["traffic_payload_bytes_per_sec"])

    def test_fresh_browser_batches_normalize_traffic_to_browser_observation_time(self):
        samples = [
            measure.compact_perf(perf(age=1_000, counter=counter), counter * 10_000, 10)
            for counter in (1, 2, 3)
        ]

        summary, errors = measure.summarize_arm(samples, "filtered", poll_errors=0)

        self.assertEqual([], errors)
        self.assertEqual(3, summary["state_updates_per_sec"]["count"])
        self.assertEqual(200, summary["traffic_payload_bytes_per_sec"])

    def test_network_rates_use_actual_retained_interval_when_polls_are_missed(self):
        samples = [
            measure.compact_perf(perf(age=1_000, counter=1), 10_000, 10),
            measure.compact_perf(perf(age=1_000, counter=2), 20_000, 10),
            measure.compact_perf(perf(age=1_000, counter=4), 40_000, 10),
            measure.compact_perf(perf(age=1_000, counter=5), 50_000, 10),
        ]

        summary, errors = measure.summarize_arm(samples, "filtered", poll_errors=1)

        self.assertEqual([], errors)
        self.assertEqual(10, summary["network_rx_bytes_per_sec"])

    def test_unsupported_android_network_counters_are_not_reported_as_zero(self):
        samples = [
            measure.compact_perf(perf(age=1_000, counter=counter), counter * 10_000, 10)
            for counter in (1, 2, 3)
        ]
        for sample in samples:
            sample["network_rx_bytes"] = -1
            sample["network_tx_bytes"] = -1

        summary, errors = measure.summarize_arm(samples, "filtered", poll_errors=0)

        self.assertEqual([], errors)
        self.assertIsNone(summary["network_rx_bytes_per_sec"])
        self.assertIsNone(summary["network_tx_bytes_per_sec"])

    def test_generation_or_mode_drift_invalidates_an_arm(self):
        samples = [
            measure.compact_perf(perf(generation=1, counter=1), 10_000, 10),
            measure.compact_perf(perf(active=False, generation=2, counter=2), 20_000, 10),
            measure.compact_perf(perf(generation=2, counter=3), 30_000, 10),
        ]

        _, errors = measure.summarize_arm(samples, "filtered", poll_errors=0)

        self.assertTrue(any("generation changed" in error for error in errors))
        self.assertTrue(any("filter mode changed" in error for error in errors))

    def test_unsynchronised_unfiltered_catalog_is_not_reported_as_zero_entities(self):
        mode = measure.sanitized_mode(status(mode="unfiltered", count=0, catalog=0))

        self.assertIsNone(mode["catalogued_stream_entity_count"])
        self.assertTrue(any("unavailable" in error for error in measure.mode_errors(mode, "unfiltered")))

    def test_collect_validates_mode_and_excludes_panel_origin(self):
        clock = FakeClock()
        perf_counter = 0

        def json_fetcher(_panel, path, _timeout):
            nonlocal perf_counter
            if "/perf/binding?" in path:
                return binding()
            if path.endswith("entity-filter"):
                return status()
            perf_counter += 1
            return perf(counter=perf_counter)

        def health_fetcher(_panel, _timeout):
            return {
                "version": "0.9.5",
                "panel": "private-panel-id",
                "build": "test",
                "fingerprint": "same",
            }

        result = measure.collect_arm(
            panel="http://192.0.2.10:8888",
            label="filtered-control",
            expected="filtered",
            comparison_id="0123456789abcdef0123456789abcdef",
            seconds=40,
            warmup=0,
            interval=10,
            timeout=5,
            json_fetcher=json_fetcher,
            health_fetcher=health_fetcher,
            clock=clock,
            sleeper=clock.sleep,
        )

        self.assertTrue(result["valid"], result["validation_errors"])
        self.assertEqual(4, len(result["samples"]))
        self.assertNotIn("192.0.2.10", json.dumps(result))
        self.assertNotIn("private-panel-id", json.dumps(result))
        self.assertNotIn("homeassistant.local", json.dumps(result))

    def test_compare_orders_arms_and_reports_unfiltered_minus_filtered(self):
        def arm(mode, cpu, panel_fingerprint="a" * 64, workload_fingerprint="b" * 64):
            return {
                "kind": "ha-paneld-dashboard-performance-arm",
                "expected_mode": mode,
                "label": mode,
                "valid": True,
                "comparison_id": "0123456789abcdef0123456789abcdef",
                "environment": {
                    "version": "0.9.5",
                    "build": "same",
                    "panel_fingerprint": panel_fingerprint,
                    "workload_fingerprint": workload_fingerprint,
                },
                "settings": {"duration_seconds": 180, "warmup_seconds": 30, "interval_seconds": 10},
                "mode_end": {"catalogued_stream_entity_count": 42 if mode == "filtered" else 100},
                "summary": {
                    **{key: {"median": None} for key in measure.COMPARISON_METRICS},
                    "system_cpu_pct": {"median": cpu},
                    "network_rx_bytes_per_sec": 10,
                    "network_tx_bytes_per_sec": 5,
                    "traffic_payload_bytes_per_sec": 10,
                    "traffic_entity_updates_per_sec": 2,
                    "traffic_observer_micros_per_sec": 1,
                    "traffic_dropped_frames_per_sec": 0,
                    "browser_stalled_fraction": 0,
                },
            }

        comparison = measure.compare_arms(arm("unfiltered", 90), arm("filtered", 30))

        self.assertTrue(comparison["valid"])
        self.assertEqual(
            60,
            comparison["metrics"]["system_cpu_pct"]["delta_unfiltered_minus_filtered"],
        )

        wrong_panel = measure.compare_arms(
            arm("filtered", 30),
            arm("unfiltered", 90, panel_fingerprint="c" * 64),
        )
        self.assertFalse(wrong_panel["valid"])
        self.assertTrue(any("same physical panel" in error for error in wrong_panel["validation_errors"]))

        wrong_workload = measure.compare_arms(
            arm("filtered", 30),
            arm("unfiltered", 90, workload_fingerprint="d" * 64),
        )
        self.assertFalse(wrong_workload["valid"])
        self.assertTrue(
            any("measurement-relevant" in error for error in wrong_workload["validation_errors"])
        )

    def test_compare_rejects_legacy_or_malformed_unbound_arms(self):
        def unbound(mode):
            return {
                "expected_mode": mode,
                "label": mode,
                "valid": True,
                "settings": {"duration_seconds": 180, "warmup_seconds": 30, "interval_seconds": 10},
                "summary": {},
            }

        comparison = measure.compare_arms(unbound("filtered"), unbound("unfiltered"))

        self.assertFalse(comparison["valid"])
        self.assertTrue(any("valid comparison binding" in error for error in comparison["validation_errors"]))
        self.assertTrue(any("not a performance arm" in error for error in comparison["validation_errors"]))


if __name__ == "__main__":
    unittest.main()
