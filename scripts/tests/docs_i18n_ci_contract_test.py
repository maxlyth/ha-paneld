#!/usr/bin/env python3
"""Static and behavioral guards for the documentation-localization CI boundary."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
DEPENDABOT = ROOT / ".github" / "dependabot.yml"
CONSUMER_TEST = ROOT / "scripts" / "tests" / "consumer_docs_test.sh"


def supported_locales() -> tuple[str, ...]:
    result = subprocess.run(
        [
            "node",
            "--input-type=module",
            "-e",
            'import { SUPPORTED_LOCALES } from "./tools/docs-i18n/lib/paths.mjs"; '
            "process.stdout.write(JSON.stringify(SUPPORTED_LOCALES));",
        ],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=True,
    )
    locales = json.loads(result.stdout)
    if (
        not isinstance(locales, list)
        or not locales
        or any(
            not isinstance(locale, str) or not locale or locale == "en"
            for locale in locales
        )
        or len(set(locales)) != len(locales)
    ):
        raise ValueError("invalid supported documentation locale set")
    return tuple(locales)


LOCALES = supported_locales()

VALIDATE_COMMAND = "npm run validate -- --repository ../.. --manifest ../../docs/i18n/manifest.json"

# Documentation steps validate already-committed files. They must never gain provider access,
# credentials, repository mutation, or a network-side generation command.
FORBIDDEN_CAPABILITIES = (
    "${{ secrets.",
    "git push",
    "gh pr",
    "curl ",
    "wget ",
)


def named_step(workflow: str, name: str) -> str:
    match = re.search(
        rf"^      - name: {re.escape(name)}\n(?P<body>(?:^(?!      - name: ).*\n?)*)",
        workflow,
        flags=re.MULTILINE,
    )
    if not match:
        raise AssertionError(f"missing workflow step: {name}")
    return match.group(0)


def run_block(step: str) -> str:
    match = re.search(r"^        run: \|\n(?P<body>(?:^          .*\n?)*)", step, flags=re.MULTILINE)
    if not match:
        raise AssertionError("workflow step has no run block")
    return "".join(line[10:] for line in match.group("body").splitlines(keepends=True))


def host_contracts(workflow: str) -> str:
    return workflow[workflow.index("  host-contracts:") : workflow.index("\n  dependency-integrity:")]


def assert_docs_workflow_contract(workflow: str) -> None:
    host_start = workflow.find("  host-contracts:")
    host_end = workflow.find("\n  dependency-integrity:", host_start)
    if host_start < 0 or host_end < 0:
        raise AssertionError("host-contracts job boundary is missing")
    host = workflow[host_start:host_end]

    checkout = (
        "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1\n"
        "        with:\n"
        "          persist-credentials: false\n"
        "          fetch-depth: 0\n"
    )
    if host.count(checkout) != 1:
        raise AssertionError("documentation validation checkout must retain complete Git history")

    setup = named_step(host, "Set up Node.js for documentation localization")
    required_setup = (
        "actions/setup-node@820762786026740c76f36085b0efc47a31fe5020",
        "node-version: '20.18.1'",
        "cache: npm",
        "cache-dependency-path: tools/docs-i18n/package-lock.json",
    )
    for value in required_setup:
        if value not in setup:
            raise AssertionError(f"documentation Node setup is missing {value}")

    validation = named_step(host, "Validate multilingual documentation")
    required_validation = (
        "working-directory: tools/docs-i18n",
        "npm ci --ignore-scripts --audit=false --fund=false",
        "npm test",
    )
    for value in required_validation:
        if value not in validation:
            raise AssertionError(f"documentation validation is missing {value}")
    # Localized documentation is not part of the built app, so drift must never fail this job.
    if "npm run validate" in validation:
        raise AssertionError("documentation drift must not gate the job; validate belongs in the drift report")

    report = named_step(host, "Report multilingual documentation drift")
    if "working-directory: tools/docs-i18n" not in report:
        raise AssertionError("documentation drift report is missing working-directory: tools/docs-i18n")
    script = run_block(report)
    if f"if {VALIDATE_COMMAND}; then" not in script:
        raise AssertionError("documentation drift report must run the exact validate command only as a condition")
    if "::warning" not in script:
        raise AssertionError("documentation drift report must emit a warning annotation")
    if re.search(r"\bexit\b", script):
        raise AssertionError("documentation drift report must not fail the job")
    if not host.index(setup) < host.index(validation) < host.index(report):
        raise AssertionError("documentation Node setup, validation and drift report must run in that order")

    for step_name, step in (("validation", validation), ("drift report", report)):
        for value in FORBIDDEN_CAPABILITIES:
            if value.lower() in step.lower():
                raise AssertionError(f"documentation {step_name} contains forbidden capability: {value}")


def assert_dependabot_contract(config: str) -> None:
    blocks = re.findall(
        r"^  - package-ecosystem: npm\n(?P<body>(?:^(?!  - package-ecosystem: ).*\n?)*)",
        config,
        flags=re.MULTILINE,
    )
    matching = [block for block in blocks if "directory: /tools/docs-i18n" in block]
    if len(matching) != 1:
        raise AssertionError("Dependabot must contain exactly one npm entry for /tools/docs-i18n")
    block = matching[0]
    for value in (
        "interval: weekly",
        "default-days: 7",
        "docs-i18n-dependencies:",
        '          - "*"',
        "open-pull-requests-limit: 5",
        "prefix: chore",
        "include: scope",
    ):
        if value not in block:
            raise AssertionError(f"docs-i18n Dependabot entry is missing {value}")


def manifest_extractor(shell: str) -> str:
    marker = '<<\'PY\'\n'
    start = shell.find(marker)
    if start < 0:
        raise AssertionError("consumer test has no manifest extractor")
    start += len(marker)
    end = shell.find("\nPY\n", start)
    if end < 0:
        raise AssertionError("consumer test manifest extractor is unterminated")
    return shell[start:end]


def source_manifest(locales: tuple[str, ...] = LOCALES) -> dict:
    readme_outputs = {locale: f"docs/{locale}/README.md" for locale in locales}
    provisioning_outputs = {
        locale: f"docs/{locale}/provisioning.md" for locale in locales
    }
    return {
        "schema": 1,
        "sourceRevision": "0" * 40,
        "parser": {},
        "notice": {},
        "limits": {},
        "locales": list(locales),
        "documents": [
            {
                "sourcePath": "README.md",
                "sourceSha256": "0" * 64,
                "structuralSha256": "0" * 64,
                "outputs": readme_outputs,
                "segments": [],
            },
            {
                "sourcePath": "docs/provisioning.md",
                "sourceSha256": "0" * 64,
                "structuralSha256": "0" * 64,
                "outputs": provisioning_outputs,
                "segments": [],
            },
        ],
        "packets": [],
    }


class DocsI18nCiContractTest(unittest.TestCase):
    def test_workflow_runs_pinned_read_only_validation(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        assert_docs_workflow_contract(workflow)

        host = host_contracts(workflow)
        setup = named_step(host, "Set up Node.js for documentation localization")
        validation = named_step(host, "Validate multilingual documentation")
        report = named_step(host, "Report multilingual documentation drift")
        mutations = (
            workflow.replace("          fetch-depth: 0\n", "", 1),
            workflow.replace(setup, setup.replace("node-version: '20.18.1'", "node-version: '22'")),
            workflow.replace(
                validation,
                validation.replace("npm ci --ignore-scripts --audit=false --fund=false", "npm install"),
            ),
            workflow.replace(validation, validation.replace("npm test", "npm run generate")),
            workflow.replace(report, report.replace(VALIDATE_COMMAND, "npm run validate")),
        )
        for mutated in mutations:
            with self.subTest(mutation=mutated):
                with self.assertRaises(AssertionError):
                    assert_docs_workflow_contract(mutated)

        secret_mutation = workflow.replace(
            "          npm test",
            "          npm test\n          echo ${{ secrets.EXTERNAL_SERVICE_KEY }}",
            1,
        )
        with self.assertRaisesRegex(AssertionError, "forbidden capability"):
            assert_docs_workflow_contract(secret_mutation)
        report_secret_mutation = workflow.replace(
            report, report.replace("          fi\n", "          fi\n          echo ${{ secrets.EXTERNAL_SERVICE_KEY }}\n")
        )
        with self.assertRaisesRegex(AssertionError, "forbidden capability"):
            assert_docs_workflow_contract(report_secret_mutation)

    def test_documentation_drift_never_gates_the_job(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        host = host_contracts(workflow)
        validation = named_step(host, "Validate multilingual documentation")
        report = named_step(host, "Report multilingual documentation drift")
        gating_mutations = {
            "validate moved into the gating step": workflow.replace(
                validation, validation.replace("          npm test\n", f"          npm test\n          {VALIDATE_COMMAND}\n")
            ),
            "validate run unconditionally": workflow.replace(
                report, report.replace(f"if {VALIDATE_COMMAND}; then", f"{VALIDATE_COMMAND}\n          if true; then")
            ),
            "explicit failure on drift": workflow.replace(report, report.replace("          fi\n", "            exit 1\n          fi\n")),
            "warning annotation dropped": workflow.replace(report, report.replace("::warning", "::notice")),
        }
        for name, mutated in gating_mutations.items():
            with self.subTest(mutation=name):
                self.assertNotEqual(mutated, workflow, name)
                with self.assertRaises(AssertionError):
                    assert_docs_workflow_contract(mutated)

    def test_drift_report_exits_zero_whatever_validate_returns(self) -> None:
        script = run_block(named_step(host_contracts(WORKFLOW.read_text(encoding="utf-8")), "Report multilingual documentation drift"))
        with tempfile.TemporaryDirectory() as temporary:
            fake_npm = Path(temporary) / "npm"
            env = {**os.environ, "PATH": f"{temporary}{os.pathsep}{os.environ['PATH']}"}
            for validate_exit, expect_warning in ((1, True), (0, False)):
                fake_npm.write_text(f"#!/bin/sh\nexit {validate_exit}\n", encoding="utf-8")
                fake_npm.chmod(0o755)
                # GitHub runs a bash `run` block as: bash --noprofile --norc -eo pipefail {0}
                result = subprocess.run(
                    ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", script],
                    env=env,
                    text=True,
                    capture_output=True,
                    check=False,
                )
                with self.subTest(validate_exit=validate_exit):
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual("::warning" in result.stdout, expect_warning, result.stdout)

    def test_dependabot_tracks_exact_docs_tool_directory(self) -> None:
        config = DEPENDABOT.read_text(encoding="utf-8")
        assert_dependabot_contract(config)
        docs_block = next(
            block
            for block in re.findall(
                r"^  - package-ecosystem: npm\n(?P<body>(?:^(?!  - package-ecosystem: ).*\n?)*)",
                config,
                flags=re.MULTILINE,
            )
            if "directory: /tools/docs-i18n" in block
        )
        for old, new in (
            ("directory: /tools/docs-i18n", "directory: /tools/docs_i18n"),
            ("docs-i18n-dependencies:", "documentation-dependencies:"),
            ("default-days: 7", "default-days: 0"),
        ):
            with self.subTest(old=old):
                with self.assertRaises(AssertionError):
                    assert_dependabot_contract(config.replace(docs_block, docs_block.replace(old, new, 1)))

    def test_consumer_manifest_extractor_accepts_only_confined_regular_outputs(self) -> None:
        shell = CONSUMER_TEST.read_text(encoding="utf-8")
        extractor = manifest_extractor(shell)
        self.assertIn('is_checkout_free_source "$source"', shell)
        self.assertIn('checkout_free_docs+=("$output")', shell)
        self.assertIn("SUPPORTED_LOCALES", shell)
        self.assertIn("validateLanguagePickerPolicy", shell)
        self.assertNotIn("EXPECTED_LOCALES", shell)
        self.assertNotIn(repr(LOCALES), shell)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = source_manifest()
            manifest_path = root / "docs" / "i18n" / "manifest.json"
            manifest_path.parent.mkdir(parents=True)
            for document in manifest["documents"]:
                for output in document["outputs"].values():
                    target = root / output
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text("translated\n", encoding="utf-8")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = subprocess.run(
                ["python3", "-", "docs/i18n/manifest.json", json.dumps(LOCALES)],
                input=extractor,
                text=True,
                cwd=root,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                result.stdout.splitlines(),
                [
                    *(f"README.md\tdocs/{locale}/README.md" for locale in LOCALES),
                    *(f"docs/provisioning.md\tdocs/{locale}/provisioning.md" for locale in LOCALES),
                ],
            )

            manifest["documents"][0]["outputs"]["de"] = "docs/de/../outside.md"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            rejected = subprocess.run(
                ["python3", "-", "docs/i18n/manifest.json", json.dumps(LOCALES)],
                input=extractor,
                text=True,
                cwd=root,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("relative path", rejected.stderr)

    def test_consumer_manifest_extractor_follows_a_complete_next_locale_policy(self) -> None:
        extractor = manifest_extractor(CONSUMER_TEST.read_text(encoding="utf-8"))
        next_locales = (*LOCALES, "nl")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = source_manifest(next_locales)
            manifest_path = root / "docs" / "i18n" / "manifest.json"
            manifest_path.parent.mkdir(parents=True)
            for document in manifest["documents"]:
                for output in document["outputs"].values():
                    target = root / output
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text("translated\n", encoding="utf-8")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            def run(candidate: dict, locales: tuple[str, ...]) -> subprocess.CompletedProcess[str]:
                manifest_path.write_text(json.dumps(candidate), encoding="utf-8")
                return subprocess.run(
                    ["python3", "-", "docs/i18n/manifest.json", json.dumps(locales)],
                    input=extractor,
                    text=True,
                    cwd=root,
                    capture_output=True,
                    check=False,
                )

            accepted = run(manifest, next_locales)
            self.assertEqual(accepted.returncode, 0, accepted.stderr)
            self.assertIn("README.md\tdocs/nl/README.md", accepted.stdout)

            mutations = []
            missing_output = json.loads(json.dumps(manifest))
            del missing_output["documents"][0]["outputs"]["nl"]
            mutations.append(("missing output", missing_output, next_locales))
            extra_output = json.loads(json.dumps(manifest))
            extra_output["documents"][0]["outputs"]["pt"] = "docs/pt/README.md"
            mutations.append(("extra output", extra_output, next_locales))
            missing_manifest_locale = json.loads(json.dumps(manifest))
            missing_manifest_locale["locales"].remove("nl")
            mutations.append(("missing manifest locale", missing_manifest_locale, next_locales))
            mutations.append(("extra manifest locale", manifest, LOCALES))
            mutations.append(("duplicate authority locale", manifest, (*next_locales, "nl")))
            for name, candidate, locales in mutations:
                self.assertNotEqual((candidate, locales), (manifest, next_locales), name)
                rejected = run(candidate, locales)
                self.assertNotEqual(rejected.returncode, 0, name)

    @unittest.skipUnless(hasattr(os, "symlink"), "symlinks are unavailable")
    def test_consumer_manifest_extractor_rejects_symlinked_output(self) -> None:
        extractor = manifest_extractor(CONSUMER_TEST.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = source_manifest()
            manifest_path = root / "docs" / "i18n" / "manifest.json"
            manifest_path.parent.mkdir(parents=True)
            for document_index, document in enumerate(manifest["documents"]):
                for locale, output in document["outputs"].items():
                    target = root / output
                    target.parent.mkdir(parents=True, exist_ok=True)
                    if document_index == 0 and locale == "de":
                        external = root / "outside.md"
                        external.write_text("outside\n", encoding="utf-8")
                        target.symlink_to(external)
                    else:
                        target.write_text("translated\n", encoding="utf-8")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            rejected = subprocess.run(
                ["python3", "-", "docs/i18n/manifest.json", json.dumps(LOCALES)],
                input=extractor,
                text=True,
                cwd=root,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("symlink", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
