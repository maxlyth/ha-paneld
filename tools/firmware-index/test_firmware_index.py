#!/usr/bin/env python3

import unittest
from types import SimpleNamespace

import firmware_index


class ResponseTotalSizeTest(unittest.TestCase):
    def test_reads_total_from_partial_response(self):
        response = SimpleNamespace(
            status=206,
            headers={"Content-Range": "bytes 0-0/309567578", "Content-Length": "1"},
        )

        self.assertEqual(firmware_index.response_total_size(response), 309567578)

    def test_rejects_partial_response_without_total(self):
        response = SimpleNamespace(status=206, headers={"Content-Range": "bytes 0-0/*"})

        self.assertIsNone(firmware_index.response_total_size(response))

    def test_reads_full_response_content_length(self):
        response = SimpleNamespace(status=200, headers={"Content-Length": "136037762"})

        self.assertEqual(firmware_index.response_total_size(response), 136037762)


class HistoryTrimTest(unittest.TestCase):
    def test_retry_replaces_the_same_utc_days_initial_sample(self):
        day = 20000 * 86400
        first = {"t": day + 8 * 3600, "r": {"firmware": 0}}
        retry = {"t": day + 9 * 3600, "r": {"firmware": 1}}
        history = {"samples": [first, retry]}

        firmware_index.trim(history, day + 10 * 3600)

        self.assertEqual(history["samples"], [retry])

    def test_retains_the_latest_sample_for_each_of_seven_days(self):
        base_day = 20000
        samples = []
        for offset in range(8):
            day = (base_day + offset) * 86400
            samples.append({"t": day + 8 * 3600, "r": {"day": offset, "attempt": 1}})
            samples.append({"t": day + 9 * 3600, "r": {"day": offset, "attempt": 2}})
        history = {"samples": list(reversed(samples))}
        now = (base_day + 7) * 86400 + 10 * 3600

        firmware_index.trim(history, now)

        self.assertEqual(len(history["samples"]), 7)
        self.assertEqual(
            [(sample["r"]["day"], sample["r"]["attempt"]) for sample in history["samples"]],
            [(offset, 2) for offset in range(1, 8)],
        )


class SparklineTest(unittest.TestCase):
    def test_places_missing_utc_day_in_its_calendar_position(self):
        base_day = 20000
        url = "https://example.invalid/firmware.zip"
        history = {
            "samples": [
                {"t": (base_day - 2) * 86400 + 8 * 3600, "r": {url: 1}},
                {"t": base_day * 86400 + 8 * 3600, "r": {url: 0}},
            ],
        }

        line = firmware_index.sparkline(
            url,
            history,
            now=base_day * 86400 + 10 * 3600,
        )

        self.assertEqual(line, "⬜⬜⬜⬜🟩⬜🟥")

    def test_uses_latest_sample_when_a_utc_day_has_a_retry(self):
        day = 20000
        url = "https://example.invalid/firmware.zip"
        history = {
            "samples": [
                {"t": day * 86400 + 8 * 3600, "r": {url: 0}},
                {"t": day * 86400 + 9 * 3600, "r": {url: 1}},
            ],
        }

        line = firmware_index.sparkline(url, history, now=day * 86400 + 10 * 3600)

        self.assertEqual(line, "⬜⬜⬜⬜⬜⬜🟩")


if __name__ == "__main__":
    unittest.main()
