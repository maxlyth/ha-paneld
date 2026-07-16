#!/usr/bin/env python3

import unittest

import wayback_archive


class ArchiveProgressTest(unittest.TestCase):
    def test_ignores_stale_state_entries(self):
        urls = ["https://example.test/current-a", "https://example.test/current-b"]
        files = {
            "https://example.test/current-a": {"wb": "20260701000000"},
            "https://example.test/removed": {"wb": "20260601000000"},
        }

        self.assertEqual(wayback_archive.archive_progress(urls, files), (1, 1))

    def test_reports_complete_only_when_every_current_url_is_archived(self):
        urls = ["https://example.test/a", "https://example.test/b"]
        files = {url: {"wb": "20260701000000"} for url in urls}

        self.assertEqual(wayback_archive.archive_progress(urls, files), (2, 0))


if __name__ == "__main__":
    unittest.main()
