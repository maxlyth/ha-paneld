import hashlib
import io
import json
import os
import pathlib
import ssl
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

import shelly_firmware as shelly


class ShellyTlsTrustTest(unittest.TestCase):
    EXPECTED_CA_SHA256 = "5a7ee2faf82bd43beef6581f48bbd4a134456488a7904eec52a15c572f53093e"

    def test_vendor_ca_is_the_reviewed_allterco_trust_anchor(self):
        pem = shelly._SHELLY_CA.read_text()
        self.assertEqual(1, pem.count("-----BEGIN CERTIFICATE-----"))
        der = ssl.PEM_cert_to_DER_cert(pem)
        self.assertEqual(self.EXPECTED_CA_SHA256, hashlib.sha256(der).hexdigest())

        cert = ssl._ssl._test_decode_cert(str(shelly._SHELLY_CA))
        self.assertEqual(cert["subject"], cert["issuer"])
        self.assertEqual(((('organizationName', 'Allterco'),),), cert["subject"])
        self.assertEqual("Aug  2 12:03:41 2030 GMT", cert["notAfter"])

    def test_vendor_ca_extends_a_strict_default_context(self):
        self.assertIs(ssl.CERT_REQUIRED, shelly._SSL_CONTEXT.verify_mode)
        self.assertTrue(shelly._SSL_CONTEXT.check_hostname)
        trusted = {
            hashlib.sha256(cert).hexdigest()
            for cert in shelly._SSL_CONTEXT.get_ca_certs(binary_form=True)
        }
        self.assertIn(self.EXPECTED_CA_SHA256, trusted)


class ShellyFirmwareValidationTest(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.dat = pathlib.Path(self.tempdir.name) / "firmware.dat"
        self.output = pathlib.Path(self.tempdir.name) / "github-output"
        self.args = SimpleNamespace(dat=str(self.dat))

    @staticmethod
    def manifest(track="WallDisplay", version="2.8.0", build_id="20260716-120000/2.8.0-deadbeef"):
        return {
            "stable": {
                "version": version,
                "build_id": build_id,
                "url": f"https://fwcdn.shelly.cloud/gen2-ntest/{track}/" + "a" * 64,
            }
        }

    def run_probe(self, manifests, head_result=123):
        def fetch(url):
            track = next(track for track, manifest_url in shelly.MANIFESTS.items() if url == manifest_url)
            result = manifests.get(track, self.manifest(track=track))
            if isinstance(result, Exception):
                raise result
            return result

        with mock.patch.object(shelly, "fetch_manifest", side_effect=fetch), \
                mock.patch.object(shelly, "head_size", side_effect=(
                    head_result if isinstance(head_result, Exception) else None
                ), return_value=(head_result if not isinstance(head_result, Exception) else None)) as head, \
                mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(self.output)}):
            result = shelly.cmd_probe(self.args)
        return result, head

    def test_valid_manifest_is_persisted_and_outputs_are_single_line(self):
        result, head = self.run_probe({})
        self.assertEqual(0, result)
        self.assertEqual(2, head.call_count)
        rows = shelly.load_dat(self.dat)
        self.assertEqual({"WallDisplay", "WallDisplayV2"}, {row["track"] for row in rows})
        outputs = self.output.read_text().splitlines()
        self.assertIn("has_new=true", outputs)
        self.assertTrue(next(line for line in outputs if line.startswith("new_versions=")))

    def test_hostile_manifest_fields_fail_before_head_or_persistence(self):
        hostile = (
            self.manifest(version='2.8.0$(touch /tmp/pwned)'),
            self.manifest(version="2.8.0\nforged=true"),
            self.manifest(build_id="build|forged"),
            {"stable": {"version": "2.8.0", "build_id": "build", "url": "http://fwcdn.shelly.cloud/a"}},
            {"stable": {"version": "2.8.0", "build_id": "build", "url": "https://evil.example/firmware"}},
            {"stable": {"version": "2.8.0", "build_id": "build", "url": "https://fwcdn.shelly.cloud/gen2-ntest/WallDisplayV2/abc"}},
        )
        for manifest in hostile:
            with self.subTest(manifest=manifest):
                self.dat.unlink(missing_ok=True)
                self.output.unlink(missing_ok=True)
                result, head = self.run_probe({"WallDisplay": manifest})
                self.assertEqual(1, result)
                head.assert_not_called()
                self.assertFalse(self.dat.exists())

    def test_all_manifest_fetches_failing_returns_nonzero_without_persistence(self):
        failures = {
            track: OSError(f"{track} unavailable")
            for track in shelly.MANIFESTS
        }

        result, head = self.run_probe(failures)

        self.assertEqual(1, result)
        head.assert_not_called()
        self.assertFalse(self.dat.exists())
        self.assertFalse(self.output.exists())

    def test_new_version_cdn_failure_returns_nonzero_without_persistence(self):
        result, head = self.run_probe({}, OSError("CDN TLS failure"))

        self.assertEqual(1, result)
        self.assertEqual(1, head.call_count)
        self.assertFalse(self.dat.exists())
        self.assertFalse(self.output.exists())

    def test_read_only_verify_always_heads_every_current_cdn_object(self):
        manifests = {
            track: self.manifest(track=track, version="2.7.1")
            for track in shelly.MANIFESTS
        }

        def fetch(url):
            track = next(track for track, manifest_url in shelly.MANIFESTS.items() if url == manifest_url)
            return manifests[track]

        with mock.patch.object(shelly, "fetch_manifest", side_effect=fetch) as fetcher, \
                mock.patch.object(shelly, "head_size", return_value=123) as head:
            result = shelly.cmd_verify(SimpleNamespace())

        self.assertEqual(0, result)
        self.assertEqual(2, fetcher.call_count)
        self.assertEqual(2, head.call_count)

    def test_read_only_verify_fails_when_a_cdn_tls_check_fails(self):
        with mock.patch.object(shelly, "fetch_manifest", side_effect=(
                self.manifest(track="WallDisplay"),
                self.manifest(track="WallDisplayV2"),
            )), mock.patch.object(
                shelly, "head_size", side_effect=(OSError("CDN TLS failure"), 123),
            ) as head:
            result = shelly.cmd_verify(SimpleNamespace())

        self.assertEqual(1, result)
        self.assertEqual(2, head.call_count)

    def test_one_manifest_fetch_failing_does_not_persist_successful_track(self):
        manifests = {
            "WallDisplay": self.manifest(track="WallDisplay", version="2.8.1"),
            "WallDisplayV2": OSError("WallDisplayV2 unavailable"),
        }

        result, head = self.run_probe(manifests)

        self.assertEqual(1, result)
        head.assert_not_called()
        self.assertFalse(self.dat.exists())
        self.assertFalse(self.output.exists())

    def test_github_output_rejects_newlines(self):
        with self.assertRaises(ValueError):
            shelly.gha_output("new_versions", "safe\nforged=true")

    def test_shelly_network_calls_use_vendor_trust_context(self):
        manifest_response = mock.MagicMock()
        manifest_response.__enter__.return_value = io.StringIO(json.dumps(self.manifest()))
        with mock.patch.object(
                shelly.urllib.request, "urlopen", return_value=manifest_response) as open_url:
            shelly.fetch_manifest(shelly.MANIFESTS["WallDisplay"])
        self.assertIs(shelly._SSL_CONTEXT, open_url.call_args.kwargs["context"])

        cdn_url = self.manifest()["stable"]["url"]
        head_response = mock.MagicMock()
        head_response.__enter__.return_value.headers = {"Content-Length": "123"}
        head_response.__enter__.return_value.geturl.return_value = cdn_url
        with mock.patch.object(
                shelly.urllib.request, "urlopen", return_value=head_response) as open_url:
            self.assertEqual(123, shelly.head_size("WallDisplay", cdn_url))
        self.assertIs(shelly._SSL_CONTEXT, open_url.call_args.kwargs["context"])

    def test_workflow_does_not_interpolate_manifest_output_into_shell_source(self):
        workflow = pathlib.Path(__file__).parents[2] / ".github/workflows/shelly-firmware-monitor.yml"
        text = workflow.read_text()
        self.assertIn("NEW_VERSIONS: ${{ steps.probe.outputs.new_versions }}", text)
        run_script = text.split("- name: Commit updated firmware index", 1)[1]
        self.assertNotIn('${{ steps.probe.outputs.new_versions }}', run_script.split("run: |", 1)[1])
        self.assertIn('if [ -n "$NEW_VERSIONS" ]', run_script)

    def test_manual_verification_job_cannot_publish(self):
        workflow = pathlib.Path(__file__).parents[2] / ".github/workflows/shelly-firmware-monitor.yml"
        text = workflow.read_text()
        self.assertIn("default: verify", text)
        verify_job = text.split("  verify:\n", 1)[1].split("  monitor:\n", 1)[0]
        self.assertIn("contents: read", verify_job)
        self.assertIn("persist-credentials: false", verify_job)
        self.assertIn("Verify Shelly manifests and TLS trust", verify_job)
        self.assertIn("shelly_firmware.py verify", verify_job)
        self.assertNotIn("secrets.", verify_job)
        self.assertNotIn("gh api", verify_job)
        self.assertNotIn("git push", verify_job)

        monitor = text.split("  monitor:\n", 1)[1]
        self.assertIn("if: github.event_name == 'schedule' || inputs.mode == 'update'", monitor)


if __name__ == "__main__":
    unittest.main()
