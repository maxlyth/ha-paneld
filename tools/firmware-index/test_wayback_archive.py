#!/usr/bin/env python3

import unittest
import urllib.error
import urllib.request

import wayback_archive
from secure_urlopen import SameOriginRedirectHandler


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


class CredentialRedirectTest(unittest.TestCase):
    def redirect(self, destination):
        request = urllib.request.Request(
            "https://web.archive.org/save",
            headers={"Authorization": "LOW secret"},
        )
        return SameOriginRedirectHandler().redirect_request(
            request, None, 302, "Found", {}, destination
        )

    def test_preserves_authorization_for_same_origin_https_redirect(self):
        redirected = self.redirect("https://web.archive.org/save/status")
        self.assertEqual("LOW secret", redirected.get_header("Authorization"))

    def test_strips_authorization_for_cross_origin_redirect(self):
        redirected = self.redirect("https://attacker.example/capture")
        self.assertIsNone(redirected.get_header("Authorization"))

    def test_refuses_https_downgrade(self):
        with self.assertRaises(urllib.error.HTTPError):
            self.redirect("http://web.archive.org/save/status")


if __name__ == "__main__":
    unittest.main()
