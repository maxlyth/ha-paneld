import os
import pathlib
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

import shelly_firmware as shelly


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

    def run_probe(self, manifests):
        def fetch(url):
            track = next(track for track, manifest_url in shelly.MANIFESTS.items() if url == manifest_url)
            return manifests.get(track, self.manifest(track=track))

        with mock.patch.object(shelly, "fetch_manifest", side_effect=fetch), \
                mock.patch.object(shelly, "head_size", return_value=123) as head, \
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

    def test_github_output_rejects_newlines(self):
        with self.assertRaises(ValueError):
            shelly.gha_output("new_versions", "safe\nforged=true")

    def test_workflow_does_not_interpolate_manifest_output_into_shell_source(self):
        workflow = pathlib.Path(__file__).parents[2] / ".github/workflows/shelly-firmware-monitor.yml"
        text = workflow.read_text()
        self.assertIn("NEW_VERSIONS: ${{ steps.probe.outputs.new_versions }}", text)
        run_script = text.split("- name: Commit updated firmware index", 1)[1]
        self.assertNotIn('${{ steps.probe.outputs.new_versions }}', run_script.split("run: |", 1)[1])
        self.assertIn('if [ -n "$NEW_VERSIONS" ]', run_script)


if __name__ == "__main__":
    unittest.main()
