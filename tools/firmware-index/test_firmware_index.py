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

# A version is crowned whenever superlative wording is attached to a release. The
# generated tables legitimately carry versions, so these patterns key off the wording
# rather than off the version, and are applied to the whole document — an earlier guard
# stripped every line starting with "|" and so could never see the crowning sentence
# that actually shipped, which sat inside a table cell.
CROWNING = (
    # superlative attached to a release noun. The article is optional: a table cell
    # opening "Newest release on the CDN" carries the same claim without a leading "the".
    r"\b(?:newest|latest)\s+(?:release|version|firmware|build|full[ -]ROM)\b",
    # "current" is narrower — "the current firmware" usually means the build installed on
    # the panel, not a claim about which release is newest, so only crown-shaped uses count.
    r"\bthe\s+current\s+(?:release|full[ -]ROM)\b",
    # superlative sitting next to a version: "OTA latest | **4.0.12**"
    r"\b(?:newest|latest|current)\b[^.\n]{0,60}?(?:\*\*|`)?\d+\.\d+\.\d+",
    # the same claim written backwards: "4.7.0 is the newest release found on the CDN"
    r"\d+\.\d+\.\d+[^.\n]{0,40}?\b(?:is|was|remains)\s+(?:the\s+)?(?:newest|latest|current)\b",
)

# Third-party wording we quote verbatim is reported, not asserted, so it is not ours to
# police. Two deliberate narrowings: it is an explicit allowlist rather than a blanket
# "ignore quoted text" rule, and each entry is exempt ONLY where its attribution appears
# just before it. The same words written in our own voice elsewhere are still a defect.
ATTRIBUTED_QUOTATIONS = (
    # Sonoff's own wording about F-Droid app builds, quoted on the 86P/120P page.
    ("Sonoff states", "may differ slightly from the latest release"),
)
ATTRIBUTION_WINDOW = 250

# Nothing past this has been flashed on real hardware by this project.
FLASH_VERIFIED_CEILING = (4, 4, 0)


def strip_attributed_quotations(text):
    """Remove allowlisted quotations only where their attribution precedes them."""
    for attribution, quotation in ATTRIBUTED_QUOTATIONS:
        out, cursor = [], 0
        for match in re.finditer(re.escape(quotation), text):
            window = text[max(0, match.start() - ATTRIBUTION_WINDOW):match.start()]
            out.append(text[cursor:match.start()])
            if attribution not in window:
                out.append(match.group(0))  # unattributed: keep it, so it can be caught
            cursor = match.end()
        out.append(text[cursor:])
        text = "".join(out)
    return text

# Naming one version as *the* verified/extension target reintroduces the staleness this
# task removed: it is wrong the moment the next release is indexed.
PINNED_TARGET = (
    r"\b(?:extension|extensions)\s+to\s+\d+\.\d+\.\d+",
    r"\bthe\s+\d+\.\d+\.\d+\s+extension\b",
    r"\bthe\s+documented\s+\d+\.\d+\.\d+\s+diff\b",
    # naming a specific successor dates the sentence the next time anything ships
    r"\bsuperseded\s+by\s+v?\d+\.\d+\.\d+",
)


class PublishedProseTest(unittest.TestCase):
    """Contracts over every surface this task publishes, not just the half it edited."""

    def test_render_states_the_flash_verification_boundary(self):
        body = self._body()

        self.assertIn("hardware-verified the flashing procedure only through **4.4.0**", body)
        for overstatement in ("official *Stable*", "official **Stable**", "current Stable"):
            self.assertNotIn(overstatement, body)

    def test_no_document_crowns_a_version(self):
        """Neither generated nor static prose may call a release newest/latest/current."""
        for name, text in self._documents().items():
            with self.subTest(document=name):
                ours = strip_attributed_quotations(text)
                offenders = [
                    match.group(0)
                    for pattern in CROWNING + PINNED_TARGET
                    for match in re.finditer(pattern, ours, flags=re.I)
                ]
                self.assertEqual(offenders, [], f"{name} crowns a version: {offenders}")

    def test_no_document_calls_the_feedback_thread_an_announcement(self):
        """Thread 208789 is a user discussion thread, not a release announcement.

        Every document that names 4.7.0 must actually carry the link, otherwise this
        contract would be satisfied by deleting the citation — it passed with zero links
        before, which is the same defect as having no contract at all.
        """
        for name, text in self._documents().items():
            with self.subTest(document=name):
                links = list(re.finditer(r"\[([^\]]*)\]\([^)]*208789[^)]*\)", text))
                if "4.7.0" in text:
                    self.assertGreaterEqual(
                        len(links), 1, f"{name} discusses 4.7.0 but cites no source for it",
                    )
                for link in links:
                    self.assertIn(
                        "feedback",
                        link.group(1).lower(),
                        f"{name} links thread 208789 as {link.group(1)!r}",
                    )
                for mislabel in ("release thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0",
                                 "announced in the [eWeLink"):
                    self.assertNotIn(mislabel, text)

    def test_no_document_asserts_absence_rather_than_a_failed_search(self):
        """A 403 rules out one filename at one index; a search rules out only itself."""
        for name, text in self._documents().items():
            with self.subTest(document=name):
                for absolute in (
                    "release notes exist",
                    "does not exist on the CDN",
                    "4.6.1 does not exist",
                    "neither a vendor changelog entry nor a community thread.",
                ):
                    self.assertNotIn(absolute, text)
        self.assertIn("absence here is not proof of non-existence", self._body())

    def test_upgrade_guidance_matches_the_indexed_data(self):
        """Releases past the checkpoint are diffs OR APK-only, from several sources.

        The singular phrasing is checked against the data rather than by taste: the index
        records releases reachable by several different inbound diffs, so guidance saying
        a release arrives as "a diff against one specific earlier version" is false.
        """
        widest = self._widest_inbound_diff_count()
        self.assertGreater(widest, 1, "fixture has no multi-source release to guard against")
        for name, text in self._documents().items():
            with self.subTest(document=name):
                self.assertNotIn("everything past it is diff-based", text)
                self.assertNotIn("everything past `3.x` is distributed as diffs", text)
                self.assertNotIn("a diff against one specific earlier version", text)
                self.assertIn("APK-only", text)

    def test_static_quirks_table_order_matches_its_caption(self):
        """The quirks table said newest-first while listing oldest-first."""
        page = (DOCS / "nspanel-pro.md").read_text(encoding="utf-8")
        section = page.split("### Firmware quirks by version")[1].split("\n## ")[0]
        versions = []
        for line in section.split("\n"):
            match = re.match(r"\|\s*\*\*v?(\d+)\.(\d+)(?:\.(\d+))?", line)
            if match:
                versions.append(tuple(int(g or 0) for g in match.groups()))
        self.assertGreater(len(versions), 4, "quirks table not found")

        caption = section.split("\n\n")[1] if "\n\n" in section else section
        if re.search(r"\bnewest\b", caption, flags=re.I):
            self.assertEqual(versions, sorted(versions, reverse=True),
                             "caption says newest first but rows ascend")
        else:
            self.assertEqual(versions, sorted(versions),
                             "caption says oldest first but rows descend")

    @staticmethod
    def _widest_inbound_diff_count():
        widest = 0
        for name in ("fw-120p.dat", "fw-86p.dat"):
            for line in (pathlib.Path(__file__).parent / name).read_text().splitlines():
                if line.startswith("diff|"):
                    widest = max(widest, len(line.split("|")) - 3)
        return widest

    def test_no_document_attributes_community_releases_to_the_vendor(self):
        """Sonoff's changelog stops at 4.6.0 — every surface must say so, not just one."""
        self.assertIn("No vendor changelog entry found", self._body())
        self.assertIn("No release notes found", self._body())
        for name, text in self._documents().items():
            with self.subTest(document=name):
                for claim in (
                    "4.7.0 | [SONOFF",
                    "4.6.2 | [SONOFF",
                    "official release notes for 4.7.0",
                    "vendor changelog entry for 4.7.0",
                    "Sonoff announced 4.7.0",
                ):
                    self.assertNotIn(claim, text)

    def test_no_document_quantifies_unreproduced_community_reports(self):
        """We link the thread and mark it unverified, on every surface that mentions it."""
        for name, text in self._documents().items():
            with self.subTest(document=name):
                self.assertIn("unverified user reports rather than a known regression", text)
                for overstatement in (
                    "widespread Zigbee", "Zigbee regression", "three users", "Hue",
                ):
                    self.assertNotIn(overstatement, text)

    def test_every_document_states_the_flash_verification_boundary(self):
        """4.4.0 is the hardware-verified boundary; no surface may widen or drop it.

        Stating the boundary is necessary but not sufficient — a document could say it and
        then claim verification of something later in the next sentence, so every
        verification claim is checked against the ceiling too.
        """
        # Matched in both directions and without pinning the connecting verb — "4.7.0 has
        # since been flash-verified" must be caught just as "flash-verified through 4.7.0"
        # is, otherwise the contract only rejects the phrasings its author happened to try.
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

    def test_no_document_calls_the_discussion_current_while_admitting_it_lags(self):
        """The Discussion is regenerated from the .dat files and can trail them."""
        for name, text in self._documents().items():
            with self.subTest(document=name):
                for overclaim in (
                    "the Discussion is the current list",
                    "live, community-maintained",
                ):
                    self.assertNotIn(overclaim, text)
                if "discussions/7" in text:
                    self.assertIn("can lag", text)

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


if __name__ == "__main__":
    unittest.main()
