import hashlib
import io
import json
import os
import pathlib
import ssl
import subprocess
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

    @staticmethod
    def entry(track="WallDisplay", version="2.8.0", wayback_ts=""):
        return {
            "track": track,
            "version": version,
            "build_id": f"20260716-120000/{version}-deadbeef",
            "bytes": 123,
            "discovered": "2026-07-30",
            "cdn_url": f"https://fwcdn.shelly.cloud/gen2-ntest/{track}/" + "a" * 64,
            "wayback_ts": wayback_ts,
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

    def test_saved_and_rendered_track_descriptions_match_current_model_facts(self):
        entries = [
            {
                "track": track,
                "version": "2.7.1",
                "build_id": "20260609-205046/2.7.1-857d7175",
                "bytes": 123,
                "discovered": "2026-06-26",
                "cdn_url": f"https://fwcdn.shelly.cloud/gen2-ntest/{track}/" + "a" * 64,
                "wayback_ts": "",
            }
            for track in shelly.MANIFESTS
        ]
        shelly.save_dat(self.dat, entries)

        saved = self.dat.read_text()
        output = io.StringIO()
        with mock.patch("sys.stdout", output):
            shelly.cmd_render(SimpleNamespace(dat=str(self.dat), out=None))
        body = output.getvalue()

        for text in ("SC7731E", "RK3326-S", "RK3566", "U1/D1 hardware not established"):
            self.assertIn(text, saved)
            self.assertIn(text, body)
        self.assertNotIn("Atlantis", saved)
        self.assertNotIn("Atlantis", body)
        self.assertNotIn("Legacy", body)
        self.assertNotIn("legacy devices (MT6580: Stargate, Pegasus)", body)

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

    def test_merge_dat_preserves_main_rows_and_pending_archive_state(self):
        base = [
            self.entry(track="WallDisplay", version="2.7.3", wayback_ts=""),
            self.entry(track="WallDisplayV2", version="2.8.0", wayback_ts=""),
        ]
        pending = [
            self.entry(track="WallDisplay", version="2.7.3", wayback_ts="20260730114129"),
            self.entry(track="WallDisplayV2", version="2.7.3", wayback_ts=""),
        ]

        merged = shelly.merge_dat(base, pending)

        self.assertEqual(
            [("WallDisplay", "2.7.3"), ("WallDisplayV2", "2.8.0"),
             ("WallDisplayV2", "2.7.3")],
            [(entry["track"], entry["version"]) for entry in merged])
        self.assertEqual("20260730114129", merged[0]["wayback_ts"])

    def test_merge_dat_rejects_conflicting_release_identity(self):
        base = [self.entry(track="WallDisplay", version="2.7.3", wayback_ts="")]
        pending_entry = self.entry(track="WallDisplay", version="2.7.3", wayback_ts="")
        pending_entry["cdn_url"] = pending_entry["cdn_url"] + "a"

        with self.assertRaisesRegex(ValueError, "conflicting data.*cdn_url"):
            shelly.merge_dat(base, [pending_entry])

    def test_merge_dat_rejects_conflicting_archive_timestamps(self):
        base = [self.entry(
            track="WallDisplay", version="2.7.3", wayback_ts="20260730114129")]
        pending = [self.entry(
            track="WallDisplay", version="2.7.3", wayback_ts="20260730114130")]

        with self.assertRaisesRegex(ValueError, "conflicting Wayback timestamps"):
            shelly.merge_dat(base, pending)

    def test_merge_command_rejects_malformed_pending_rows_without_rewriting_base(self):
        base = [self.entry(track="WallDisplay", version="2.7.3")]
        shelly.save_dat(self.dat, base)
        original = self.dat.read_bytes()
        pending = pathlib.Path(self.tempdir.name) / "pending.dat"
        pending.write_text("WallDisplay|truncated|row\n")

        with self.assertRaisesRegex(ValueError, "expected 7 fields"):
            shelly.cmd_merge(SimpleNamespace(dat=str(self.dat), pending=str(pending)))

        self.assertEqual(original, self.dat.read_bytes())

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
        run_script = text.split("- name: Open/update pull request with the firmware index", 1)[1]
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

    def test_workflow_reconciles_open_pr_branch_without_force_push(self):
        workflow = pathlib.Path(__file__).parents[2] / ".github/workflows/shelly-firmware-monitor.yml"
        text = workflow.read_text()
        monitor = text.split("  monitor:\n", 1)[1]
        update = monitor.split("- name: Open/update pull request with the firmware index", 1)[1]
        reconcile = pathlib.Path(__file__).with_name("reconcile_shelly_branch.sh").read_text()

        self.assertIn("fetch-depth: 0", monitor)
        self.assertIn("gh pr list", monitor)
        self.assertIn("shelly_firmware.py merge", monitor)
        self.assertIn("+refs/heads/main:refs/remotes/shelly/main", monitor)
        self.assertIn("reconcile_shelly_branch.sh", update)
        self.assertLess(reconcile.index('git restore --source=HEAD -- "$DAT"'),
                        reconcile.index('git checkout -q -B "$BRANCH"'))
        self.assertIn("git merge --no-commit --no-ff refs/remotes/shelly/main", reconcile)
        self.assertIn("chore: sync Shelly firmware update branch with main [skip ci]", reconcile)
        self.assertIn("+refs/heads/main:refs/remotes/shelly/main", reconcile)
        self.assertIn('git diff --name-only --diff-filter=U', reconcile)
        self.assertIn("shelly_firmware.py\" merge", reconcile)
        self.assertNotIn("--force", update)
        self.assertNotIn("--force", reconcile)

    def test_branch_reconciliation_survives_squash_merge_and_preserves_both_sides(self):
        root = pathlib.Path(self.tempdir.name)
        remote = root / "remote.git"
        seed = root / "seed"
        runner = root / "runner"
        dat_rel = pathlib.Path("tools/firmware-index/fw-shelly-walldisplay.dat")

        def git(repo, *args, check=True):
            return subprocess.run(
                ["git", "-C", str(repo), *args], check=check,
                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        git(root, "init", "--bare", str(remote))
        git(root, "init", "-b", "main", str(seed))
        git(seed, "config", "user.name", "test")
        git(seed, "config", "user.email", "test@example.invalid")
        (seed / dat_rel).parent.mkdir(parents=True)
        initial = [self.entry(track="WallDisplay", version="2.7.2", wayback_ts="20260721113036")]
        shelly.save_dat(seed / dat_rel, initial)
        git(seed, "add", str(dat_rel))
        git(seed, "commit", "-m", "initial")
        git(seed, "remote", "add", "origin", str(remote))
        git(seed, "push", "-u", "origin", "main")
        git(remote, "symbolic-ref", "HEAD", "refs/heads/main")

        git(seed, "checkout", "-b", "automation/shelly-firmware-index")
        discovered = initial + [
            self.entry(track="WallDisplay", version="2.7.3"),
            self.entry(track="WallDisplayV2", version="2.7.3"),
        ]
        shelly.save_dat(seed / dat_rel, discovered)
        git(seed, "add", str(dat_rel))
        git(seed, "commit", "-m", "discovery")
        git(seed, "push", "-u", "origin", "automation/shelly-firmware-index")

        git(seed, "checkout", "main")
        shelly.save_dat(seed / dat_rel, discovered)
        git(seed, "add", str(dat_rel))
        git(seed, "commit", "-m", "squash discovery")
        main_only = discovered + [self.entry(track="WallDisplay", version="2.8.0")]
        shelly.save_dat(seed / dat_rel, main_only)
        git(seed, "add", str(dat_rel))
        git(seed, "commit", "-m", "main-only discovery")
        git(seed, "push", "origin", "main")

        git(seed, "checkout", "automation/shelly-firmware-index")
        pending = discovered.copy()
        pending[-2] = pending[-2].copy()
        pending[-2]["wayback_ts"] = "20260730114129"
        shelly.save_dat(seed / dat_rel, pending)
        git(seed, "add", str(dat_rel))
        git(seed, "commit", "-m", "archive legacy")
        git(seed, "push", "origin", "automation/shelly-firmware-index")

        git(root, "clone", str(remote), str(runner))
        git(runner, "config", "user.name", "test")
        git(runner, "config", "user.email", "test@example.invalid")
        pending_text = git(
            runner, "show", "origin/automation/shelly-firmware-index:" + str(dat_rel)).stdout
        pending_path = root / "pending.dat"
        pending_path.write_text(pending_text)
        prepared = shelly.merge_dat(shelly.load_dat(runner / dat_rel), shelly.load_dat(pending_path))
        for entry in prepared:
            if entry["track"] == "WallDisplayV2" and entry["version"] == "2.7.3":
                entry["wayback_ts"] = "20260731115639"
        shelly.save_dat(runner / dat_rel, prepared)

        env = os.environ.copy()
        env.update({
            "DAT": str(dat_rel),
            "BRANCH": "automation/shelly-firmware-index",
            "REPO_URL": str(remote),
            "COMMIT_MESSAGE": "data: simulated update [skip ci]",
            "RUNNER_TEMP": str(root),
        })
        script = pathlib.Path(__file__).with_name("reconcile_shelly_branch.sh")
        subprocess.run(["bash", str(script)], cwd=runner, env=env, check=True)

        git(runner, "fetch", "origin")
        self.assertEqual(
            0, git(runner, "merge-base", "--is-ancestor", "origin/main",
                   "origin/automation/shelly-firmware-index", check=False).returncode)
        changed = git(
            runner, "diff", "--name-only", "origin/main...origin/automation/shelly-firmware-index"
        ).stdout.splitlines()
        self.assertEqual([str(dat_rel)], changed)
        final_text = git(
            runner, "show", "origin/automation/shelly-firmware-index:" + str(dat_rel)).stdout
        final_path = root / "final.dat"
        final_path.write_text(final_text)
        final = shelly.load_dat(final_path)
        self.assertIn(("WallDisplay", "2.8.0"), {
            (entry["track"], entry["version"]) for entry in final})
        timestamps = {
            (entry["track"], entry["version"]): entry["wayback_ts"] for entry in final}
        self.assertEqual("20260730114129", timestamps[("WallDisplay", "2.7.3")])
        self.assertEqual("20260731115639", timestamps[("WallDisplayV2", "2.7.3")])

        git(seed, "fetch", "origin")
        git(seed, "checkout", "main")
        (seed / "collision.txt").write_text("main\n")
        git(seed, "add", "collision.txt")
        git(seed, "commit", "-m", "main collision")
        git(seed, "push", "origin", "main")
        git(seed, "checkout", "-B", "automation/shelly-firmware-index",
            "origin/automation/shelly-firmware-index")
        (seed / "collision.txt").write_text("automation\n")
        git(seed, "add", "collision.txt")
        git(seed, "commit", "-m", "automation collision")
        git(seed, "push", "origin", "automation/shelly-firmware-index")
        branch_before = git(
            seed, "rev-parse", "automation/shelly-firmware-index").stdout.strip()

        git(runner, "fetch", "origin")
        git(runner, "checkout", "-B", "main", "origin/main")
        failed = subprocess.run(
            ["bash", str(script)], cwd=runner, env=env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(0, failed.returncode)
        self.assertIn("conflicts outside", failed.stderr)
        git(runner, "fetch", "origin")
        self.assertEqual(
            branch_before,
            git(runner, "rev-parse", "origin/automation/shelly-firmware-index").stdout.strip())


if __name__ == "__main__":
    unittest.main()
