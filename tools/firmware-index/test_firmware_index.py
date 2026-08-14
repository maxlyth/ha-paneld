#!/usr/bin/env python3

import io
import pathlib
import re
import unittest
from types import SimpleNamespace
from unittest import mock

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

    def test_device_render_uses_one_utc_window_anchor_for_every_row(self):
        device = {
            "channel": "test-channel",
            "suffix": "",
            "apkfmt": "app",
            "fulls": [("1.0.0", "1", "full.zip", 10)],
            "diffs": [("1.0.0", "0.9.0", "2", 5)],
            "apks": [("1.0.0", "3", 2)],
        }
        render_time = 20000 * 86400 + 23 * 3600 + 59 * 60 + 59

        with mock.patch.object(
            firmware_index,
            "sparkline",
            return_value="⬜⬜⬜⬜⬜⬜⬜",
        ) as sparkline:
            firmware_index.device_block(
                "Test",
                "fixture",
                device,
                {"samples": []},
                render_time,
            )

        self.assertEqual(sparkline.call_count, 3)
        self.assertTrue(
            all(call.args[2] == render_time for call in sparkline.call_args_list),
        )


DOCS = pathlib.Path(__file__).resolve().parents[2] / "docs" / "hardware"

# Nothing past this has been flashed on real hardware by this project.
FLASH_VERIFIED_CEILING = (4, 4, 0)


class PublishedDataContractTest(unittest.TestCase):
    """Contracts the published surfaces must keep with the indexed data.

    Only claims checkable against the data or a recorded hardware fact live here.
    Editorial wording is not tested; a wording regression is fixed by editing the
    sentence, the same as in any docs repo.
    """

    def test_every_document_states_the_flash_verification_boundary(self):
        """4.4.0 is the hardware-verified boundary; no surface may widen or drop it.

        Stating the boundary is necessary but not sufficient — a document could say it and
        then claim verification of something later in the next sentence, so every
        verification claim is checked against the ceiling too, in both directions and
        without pinning the connecting verb ("4.7.0 has since been flash-verified" is as
        wrong as "flash-verified through 4.7.0").
        """
        claim = re.compile(
            r"(?:hardware|flash|live-flash)[- ]verified[^.\n]{0,80}?(\d+\.\d+\.\d+)"
            r"|(\d+\.\d+\.\d+)[^.\n]{0,60}?(?:hardware|flash|live-flash)[- ]verified",
            flags=re.I,
        )
        for name, text in self._documents().items():
            with self.subTest(document=name):
                self.assertRegex(text, r"(?:hardware-verified|flash-verified)[^.\n]{0,60}4\.4\.0")
                for match in claim.finditer(text):
                    raw = match.group(1) or match.group(2)
                    version = tuple(int(part) for part in raw.split("."))
                    self.assertLessEqual(
                        version,
                        FLASH_VERIFIED_CEILING,
                        f"{name} claims verification of {raw}, past the "
                        f"{'.'.join(map(str, FLASH_VERIFIED_CEILING))} ceiling: "
                        f"{match.group(0)!r}",
                    )

    def test_upgrade_guidance_matches_the_indexed_data(self):
        """The index records releases with several inbound diffs, and APK-only releases
        with no ROM at all, so guidance must not describe a single-diff-only model."""
        widest = self._widest_inbound_diff_count()
        self.assertGreater(widest, 1, "index has no multi-source release to guard against")
        for name, text in self._documents().items():
            with self.subTest(document=name):
                self.assertNotIn("a diff against one specific earlier version", text)
                self.assertIn("APK-only", text)

    @staticmethod
    def _widest_inbound_diff_count():
        widest = 0
        for name in ("fw-120p.dat", "fw-86p.dat"):
            for line in (pathlib.Path(__file__).parent / name).read_text().splitlines():
                if line.startswith("diff|"):
                    widest = max(widest, len(line.split("|")) - 3)
        return widest

    def test_curated_release_rows_are_in_ascending_version_order(self):
        """A release appended to the curated table must not land out of sequence."""
        versions = self._curated_versions()
        self.assertGreater(len(versions), 5, "curated table not found")
        self.assertEqual(
            versions,
            sorted(versions),
            f"curated release rows out of order: {['.'.join(map(str, v)) for v in versions]}",
        )

    @classmethod
    def _curated_versions(cls):
        body = cls._body()
        section = body.split("## Which version should an HA panel run?")[1].split("\n## ")[0]
        found = []
        for line in section.split("\n"):
            match = re.match(r"\|\s*\[?\*\*(\d+)\.(\d+)\.(\d+)\*\*", line)
            if match:
                found.append(tuple(int(g) for g in match.groups()))
        return found

    @classmethod
    def _documents(cls):
        return {
            "generated discussion body": cls._body(),
            "docs/hardware/nspanel-pro.md": (DOCS / "nspanel-pro.md").read_text(encoding="utf-8"),
            "docs/hardware/nspanel-pro-firmware.md": (
                DOCS / "nspanel-pro-firmware.md"
            ).read_text(encoding="utf-8"),
        }

    @staticmethod
    def _body():
        output = io.StringIO()
        with mock.patch("sys.stdout", output):
            firmware_index.cmd_render(SimpleNamespace(history=None, out=None))
        return output.getvalue()


class DiscoveryTest(unittest.TestCase):
    """Contracts for `discover`, checked against the real index data.

    Discovery guesses forward into a bucket that cannot be listed, so its
    failure mode is a silent false negative. Each contract below pins a way
    that has already been observed to happen or would hide a real release.
    """

    def setUp(self):
        self.devices = firmware_index.load_devices()

    def test_every_rom_target_can_be_an_upgrade_source(self):
        # Regression: the from-set was built only from versions already used
        # as a diff SOURCE, so the newest release — which has only ever been
        # a TARGET — was excluded, and the likeliest upgrade path of all
        # (latest → next) could never be discovered.
        for d in self.devices:
            targets = {to for to, _f, _i, _s in d["diffs"]}
            states = firmware_index.rom_states(d)
            missing = sorted(targets - states, key=firmware_index.vkey)
            self.assertEqual(
                missing, [], f"{d['channel']}: ROM targets absent from the upgrade-source set"
            )

    def test_candidate_versions_span_the_largest_historical_minor_jump(self):
        # Sonoff shipped 4.0.12 and then 4.4.0 with nothing between, so the
        # candidate window must reach at least that far or a real release
        # would sit undiscovered forever.
        jumps = []
        for d in self.devices:
            versions = sorted({v for v, _i, _s in d["apks"]}, key=firmware_index.vkey)
            for earlier, later in zip(versions, versions[1:]):
                a, b = firmware_index.vkey(earlier), firmware_index.vkey(later)
                if b[0] == a[0]:
                    jumps.append(b[1] - a[1])
        widest = max(jumps)
        self.assertGreater(widest, 0, "index data has no minor-version jump to calibrate against")

        newest = firmware_index.newest_version(self.devices[0]["apks"])
        major, minor, _patch = firmware_index.vkey(newest)
        candidates = firmware_index.candidate_versions(newest)
        self.assertIn(
            f"{major}.{minor + widest}.0",
            candidates,
            f"candidate versions do not reach a {widest}-minor jump, which has happened before",
        )

    def test_candidate_versions_exclude_the_known_release(self):
        newest = firmware_index.newest_version(self.devices[0]["apks"])
        self.assertNotIn(newest, firmware_index.candidate_versions(newest))

    def test_discover_one_requires_zip_magic(self):
        # A 206 alone is not proof: the CDN could serve an error body that
        # still satisfies a range request.
        response = mock.MagicMock()
        response.status = 206
        response.headers = {"Content-Range": "bytes 0-3/137890388"}
        response.read.return_value = b"<htm"
        response.__enter__.return_value = response

        with mock.patch("urllib.request.urlopen", return_value=response):
            self.assertIsNone(firmware_index.discover_one("https://example.invalid/x.apk"))

    def test_discover_one_accepts_a_real_zip(self):
        response = mock.MagicMock()
        response.status = 206
        response.headers = {"Content-Range": "bytes 0-3/137890388"}
        response.read.return_value = b"PK\x03\x04"
        response.__enter__.return_value = response

        with mock.patch("urllib.request.urlopen", return_value=response):
            self.assertEqual(
                firmware_index.discover_one("https://example.invalid/x.apk"), 137890388
            )

    def test_a_blind_prober_aborts_instead_of_reporting_nothing_new(self):
        # The whole point of the daily job is that "nothing found" is
        # trustworthy. If the prober cannot see objects that are known to
        # exist, it must fail loudly rather than emit a clean negative.
        output = io.StringIO()
        with mock.patch.object(firmware_index, "discover_one", return_value=None), \
                mock.patch("sys.stdout", output):
            code = firmware_index.cmd_discover(
                SimpleNamespace(index_window=2, minor_window=1, json=None, apply=False)
            )

        self.assertEqual(code, 2)
        self.assertIn("harness FAILED", output.getvalue())
        self.assertNotIn("no unindexed firmware found", output.getvalue())

    def test_searched_window_is_reported_even_when_nothing_is_found(self):
        # A fixed window can miss a release when the CDN skips indices, so a
        # negative result must say what it actually covered.
        real = firmware_index.discover_one
        devices = self.devices

        def only_known_objects(url):
            known = firmware_index.all_url_sizes(devices)
            return known.get(url)

        output = io.StringIO()
        with mock.patch.object(firmware_index, "discover_one", side_effect=only_known_objects), \
                mock.patch("sys.stdout", output):
            code = firmware_index.cmd_discover(
                SimpleNamespace(index_window=2, minor_window=1, json=None, apply=False)
            )

        self.assertIsNot(real, None)
        self.assertEqual(code, 0)
        text = output.getvalue()
        self.assertIn("no unindexed firmware found", text)
        for d in devices:
            self.assertIn(f"searched {d['channel']}: apk indices", text)


if __name__ == "__main__":
    unittest.main()
