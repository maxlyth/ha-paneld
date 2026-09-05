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
LOCALES = ("de", "es", "fr", "it", "zh-Hans")


def named_step(workflow: str, name: str) -> str:
    match = re.search(
        rf"^      - name: {re.escape(name)}\n(?P<body>(?:^(?!      - name: ).*\n?)*)",
        workflow,
        flags=re.MULTILINE,
    )
    if not match:
        raise AssertionError(f"missing workflow step: {name}")
    return match.group(0)


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
        "npm run validate -- --repository ../.. --manifest ../../docs/i18n/manifest.json",
    )
    for value in required_validation:
        if value not in validation:
            raise AssertionError(f"documentation validation is missing {value}")
    if host.index(setup) > host.index(validation):
        raise AssertionError("documentation Node setup must precede validation")

    # This step validates already-committed files. It must never gain provider access, credentials,
    # repository mutation, or a network-side generation command.
    forbidden = (
        "${{ secrets.",
        "git push",
        "gh pr",
        "curl ",
        "wget ",
    )
    for value in forbidden:
        if value.lower() in validation.lower():
            raise AssertionError(f"documentation validation contains forbidden capability: {value}")


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


def source_manifest() -> dict:
    readme_outputs = {locale: f"docs/{locale}/README.md" for locale in LOCALES}
    provisioning_outputs = {
        locale: f"docs/{locale}/provisioning.md" for locale in LOCALES
    }
    return {
        "schema": 1,
        "sourceRevision": "0" * 40,
        "parser": {},
        "notice": {},
        "limits": {},
        "locales": list(LOCALES),
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

        host = workflow[
            workflow.index("  host-contracts:") : workflow.index("\n  dependency-integrity:")
        ]
        setup = named_step(host, "Set up Node.js for documentation localization")
        validation = named_step(host, "Validate multilingual documentation")
        mutations = (
            workflow.replace("          fetch-depth: 0\n", "", 1),
            workflow.replace(setup, setup.replace("node-version: '20.18.1'", "node-version: '22'")),
            workflow.replace(
                validation,
                validation.replace("npm ci --ignore-scripts --audit=false --fund=false", "npm install"),
            ),
            workflow.replace(validation, validation.replace("npm test", "npm run generate")),
            workflow.replace(
                validation,
                validation.replace(
                    "npm run validate -- --repository ../.. --manifest ../../docs/i18n/manifest.json",
                    "npm run validate",
                ),
            ),
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
                ["python3", "-", "docs/i18n/manifest.json"],
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
                ["python3", "-", "docs/i18n/manifest.json"],
                input=extractor,
                text=True,
                cwd=root,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("relative path", rejected.stderr)

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
                ["python3", "-", "docs/i18n/manifest.json"],
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
