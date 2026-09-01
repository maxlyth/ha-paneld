import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "i18n_catalogue.py"
SPEC = importlib.util.spec_from_file_location("i18n_catalogue", SCRIPT)
assert SPEC and SPEC.loader
i18n = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(i18n)


class CatalogueTest(unittest.TestCase):
    def source(self):
        text = "Keep {name} on MQTT."
        return {
            "schema": 1,
            "locale": "en",
            "sourceRevision": "e" * 40,
            "strings": {
                "settings.example.help": {
                    "text": text,
                    "sourceHash": i18n.source_hash(text),
                    "surface": "settings",
                    "context": "Configure example help",
                    "risk": "ordinary",
                    "siblings": [],
                    "placeholders": ["{name}"],
                    "frozen": ["MQTT"],
                    "softMaxChars": 40,
                    "hardMaxChars": 80,
                }
            },
        }

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False) + "\n", encoding="utf-8")

    def report_source(self):
        source = self.source()
        strings = {}
        for index, suffix in enumerate(("a", "b", "c", "d", "e", "f")):
            text = f"English setting {suffix}."
            strings[f"settings.{suffix}.label"] = {
                "text": text,
                "sourceHash": i18n.source_hash(text),
                "surface": "settings",
                "context": f"Configure setting {suffix}",
                "risk": ("ordinary", "setup", "consequential")[index // 2],
                "siblings": [],
                "placeholders": [],
                "frozen": [],
                "softMaxChars": 40,
                "hardMaxChars": 80,
            }
        source["strings"] = strings
        return source

    def report_context(self):
        return {
            "schema": 1,
            "id": "test-terminology",
            "productContext": "Test product context.",
            "instruction": "Use the pinned term.",
            "license": "Apache-2.0",
            "notice": "Synthetic test fixture.",
            "sources": [{
                "id": "frontend",
                "repository": "https://github.com/home-assistant/frontend",
                "revision": "b" * 40,
                "artifact": "synthetic frontend artifact",
                "artifactSha256": "c" * 64,
                "license": "Apache-2.0",
            }],
            "terms": [{
                "id": "settings",
                "meaning": "Settings surface.",
                "english": "Settings",
                "source": "frontend",
                "sourceKey": "panel.config",
                "translations": {"de": "Einstellungen"},
            }],
        }

    def test_source_and_target_validate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, target_path = root / "en.json", root / "de.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": "{name} auf MQTT behalten.",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-draft",
                }},
            })
            parsed = i18n.validate_source(source_path)
            i18n.validate_target(target_path, parsed)

    def test_duplicate_key_and_changed_placeholder_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            duplicate = root / "duplicate.json"
            duplicate.write_text('{"schema":1,"schema":1}', encoding="utf-8")
            with self.assertRaises(i18n.CatalogueError):
                i18n.read_json(duplicate)

            source_path, target_path = root / "en.json", root / "de.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": "Ohne Namen auf MQTT behalten.",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-cross-checked",
                }},
            })
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

            target = json.loads(target_path.read_text(encoding="utf-8"))
            record = target["strings"]["settings.example.help"]
            record["text"] = "{name} auf MQTT MQTT behalten."
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

            record["text"] = "{name} auf MQTT behalten."
            record["state"] = "english-fallback"
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

    def test_candidate_requires_exact_order_and_becomes_draft(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, candidate_path, output = root / "en.json", root / "candidate.json", root / "de.json"
            self.write(source_path, self.source())
            source_hash = i18n.hashlib.sha256(source_path.read_bytes()).hexdigest()
            self.write(candidate_path, {
                "schema": 1,
                "targetLocale": "de",
                "sourceRevision": "e" * 40,
                "sourceCatalogueHash": source_hash,
                "translations": [{"key": "settings.example.help", "translation": "{name} auf MQTT behalten."}],
            })
            i18n.candidate_to_target(source_path, candidate_path, output)
            target = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("machine-draft", target["strings"]["settings.example.help"]["state"])

            candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
            candidate["sourceCatalogueHash"] = "0" * 64
            self.write(candidate_path, candidate)
            with self.assertRaises(i18n.CatalogueError):
                i18n.candidate_to_target(source_path, candidate_path, output)

    def test_target_filename_must_match_declared_locale(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, target_path = root / "en.json", root / "fr.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {},
            })
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path), expected_locale=target_path.stem)

    def test_stale_target_is_valid_and_partial_merge_preserves_unselected_records(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, base_path = root / "en.json", root / "de.json"
            candidate_path, output = root / "candidate.json", root / "merged" / "de.json"
            source = self.source()
            second_text = "Enable panel mode."
            source["strings"]["settings.second.help"] = {
                "text": second_text,
                "sourceHash": i18n.source_hash(second_text),
                "surface": "settings",
                "context": "Configure second help",
                "risk": "ordinary",
                "siblings": [],
                "placeholders": [],
                "frozen": [],
                "softMaxChars": 40,
                "hardMaxChars": 80,
            }
            source["strings"] = dict(sorted(source["strings"].items()))
            self.write(source_path, source)
            self.write(base_path, {
                "schema": 1, "locale": "de", "sourceRevision": "a" * 40,
                "strings": {
                    "settings.example.help": {
                        "text": "Alte Übersetzung.", "sourceHash": "0" * 64,
                        "state": "community-corrected",
                    },
                    "settings.second.help": {
                        "text": "Panelmodus aktivieren.",
                        "sourceHash": source["strings"]["settings.second.help"]["sourceHash"],
                        "state": "machine-cross-checked",
                    },
                    "settings.removed.help": {
                        "text": "Entfernter Text.", "sourceHash": "9" * 64,
                        "state": "machine-cross-checked",
                    },
                },
            })
            i18n.validate_target(base_path, i18n.validate_source(source_path), expected_locale="de")
            self.write(candidate_path, {
                "schema": 1, "targetLocale": "de", "sourceRevision": "e" * 40,
                "sourceCatalogueHash": i18n.hashlib.sha256(source_path.read_bytes()).hexdigest(),
                "translations": [{
                    "key": "settings.example.help", "translation": "{name} auf MQTT behalten.",
                }],
            })
            base_before = json.loads(base_path.read_text(encoding="utf-8"))
            i18n.merge_candidate(source_path, base_path, candidate_path, output)
            merged = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("machine-draft", merged["strings"]["settings.example.help"]["state"])
            self.assertEqual(
                base_before["strings"]["settings.second.help"],
                merged["strings"]["settings.second.help"],
            )
            self.assertNotIn("settings.removed.help", merged["strings"])

            protected = json.loads(base_path.read_text(encoding="utf-8"))
            protected["strings"]["settings.example.help"]["sourceHash"] = source["strings"]["settings.example.help"]["sourceHash"]
            protected["strings"]["settings.example.help"]["text"] = "{name} auf MQTT behalten."
            self.write(base_path, protected)
            with self.assertRaises(i18n.CatalogueError):
                i18n.merge_candidate(source_path, base_path, candidate_path, output)

    def test_empty_frozen_literal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / "en.json"
            source = self.source()
            source["strings"]["settings.example.help"]["frozen"] = [""]
            self.write(source_path, source)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_source(source_path)

    def test_product_name_and_exact_db_unit_require_frozen_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / "en.json"
            for literal, text in (
                ("Home Assistant", "Send audio to Home Assistant."),
                ("dB", "Microphone gain (dB)"),
            ):
                with self.subTest(literal=literal):
                    source = self.source()
                    record = source["strings"]["settings.example.help"]
                    record["text"] = text
                    record["sourceHash"] = i18n.source_hash(text)
                    record["placeholders"] = []
                    record["frozen"] = []
                    record["hardMaxChars"] = max(80, len(text))
                    self.write(source_path, source)
                    with self.assertRaisesRegex(
                        i18n.CatalogueError,
                        f"required frozen literal missing: {literal}",
                    ):
                        i18n.validate_source(source_path)
                    record["frozen"] = [literal]
                    self.write(source_path, source)
                    i18n.validate_source(source_path)

            source = self.source()
            record = source["strings"]["settings.example.help"]
            record["text"] = "Wi-Fi signal in dBm."
            record["sourceHash"] = i18n.source_hash(record["text"])
            record["placeholders"] = []
            record["frozen"] = ["dBm"]
            self.write(source_path, source)
            i18n.validate_source(source_path)

    def test_zh_hans_requires_han_text_and_rejects_residual_source_words(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, target_path = root / "en.json", root / "zh-Hans.json"
            source = self.source()
            self.write(source_path, source)
            target = {
                "schema": 1, "locale": "zh-Hans", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": "在 MQTT 上 keep {name}。",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-draft",
                }},
            }
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))
            target["strings"]["settings.example.help"]["text"] = "在 MQTT 上保留 {name}。"
            self.write(target_path, target)
            i18n.validate_target(target_path, i18n.validate_source(source_path))
            target["strings"]["settings.example.help"]["text"] = "在 MQTT 上自动 This {name}。"
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))
            target["strings"]["settings.example.help"]["text"] = "{name} on MQTT."
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

    def test_latin_locale_rejects_any_unexpected_alphabetic_script(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, target_path = root / "en.json", root / "de.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": "{name} auf MQTT behalten. Ελληνικά",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-draft",
                }},
            })
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

    def test_report_counts_current_translation_and_effective_fallback_per_locale(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "catalogues" / "en.json"
            target_path = root / "catalogues" / "de.json"
            output_path = root / "catalogues" / "report.json"
            context_path = source_path.parent / "context" / "home-assistant-terminology.json"
            source = self.report_source()
            self.write(source_path, source)
            self.write(context_path, self.report_context())
            hashes = {key: record["sourceHash"] for key, record in source["strings"].items()}
            self.write(target_path, {
                "schema": 1,
                "locale": "de",
                "sourceRevision": "a" * 40,
                "strings": {
                    "settings.a.label": {
                        "text": "Deutsche Einstellung A.",
                        "sourceHash": hashes["settings.a.label"],
                        "state": "machine-cross-checked",
                    },
                    "settings.b.label": {
                        "text": "Deutsche Einstellung B.",
                        "sourceHash": hashes["settings.b.label"],
                        "state": "community-corrected",
                    },
                    "settings.c.label": {
                        "text": "Deutsche Einstellung C.",
                        "sourceHash": hashes["settings.c.label"],
                        "state": "machine-draft",
                    },
                    "settings.d.label": {
                        "text": "Veraltete Einstellung D.",
                        "sourceHash": "0" * 64,
                        "state": "machine-cross-checked",
                    },
                    "settings.e.label": {
                        "text": "English setting e.",
                        "sourceHash": hashes["settings.e.label"],
                        "state": "english-fallback",
                    },
                    "settings.removed.label": {
                        "text": "Entfernte Einstellung.",
                        "sourceHash": "9" * 64,
                        "state": "machine-draft",
                    },
                },
            })

            expected = {
                "schema": 1,
                "source": {
                    "locale": "en",
                    "revision": "e" * 40,
                    "fileSha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
                    "strings": 6,
                    "surfaceCounts": {"settings": 6},
                    "riskCounts": {"consequential": 2, "ordinary": 2, "setup": 2},
                },
                "context": {
                    "id": "test-terminology",
                    "fileSha256": hashlib.sha256(context_path.read_bytes()).hexdigest(),
                    "terms": 1,
                    "sourcePins": [{
                        "id": "frontend",
                        "revision": "b" * 40,
                        "artifactSha256": "c" * 64,
                    }],
                },
                "locales": {"de": {
                    "catalogueRecords": 6,
                    "fileSha256": hashlib.sha256(target_path.read_bytes()).hexdigest(),
                    "sourceRevision": "a" * 40,
                    "sourceRevisionMatches": False,
                    "stateCounts": {
                        "community-corrected": 1,
                        "english-fallback": 1,
                        "machine-cross-checked": 2,
                        "machine-draft": 2,
                    },
                    "surfaces": {"settings": {
                        "source": 6,
                        "stateCounts": {
                            "community-corrected": 1,
                            "english-fallback": 1,
                            "machine-cross-checked": 2,
                            "machine-draft": 1,
                        },
                        "missing": 1,
                        "stale": 1,
                        "current": 4,
                        "translated": 2,
                        "fallback": 4,
                    }},
                    "risks": {
                        "consequential": {
                            "source": 2,
                            "stateCounts": {
                                "community-corrected": 0,
                                "english-fallback": 1,
                                "machine-cross-checked": 0,
                                "machine-draft": 0,
                            },
                            "missing": 1, "stale": 0, "current": 1,
                            "translated": 0, "fallback": 2,
                        },
                        "ordinary": {
                            "source": 2,
                            "stateCounts": {
                                "community-corrected": 1,
                                "english-fallback": 0,
                                "machine-cross-checked": 1,
                                "machine-draft": 0,
                            },
                            "missing": 0, "stale": 0, "current": 2,
                            "translated": 2, "fallback": 0,
                        },
                        "setup": {
                            "source": 2,
                            "stateCounts": {
                                "community-corrected": 0,
                                "english-fallback": 0,
                                "machine-cross-checked": 1,
                                "machine-draft": 1,
                            },
                            "missing": 0, "stale": 1, "current": 1,
                            "translated": 0, "fallback": 2,
                        },
                    },
                    "missing": {
                        "count": 1, "percent": 16.67,
                        "keys": ["settings.f.label"],
                    },
                    "stale": {
                        "count": 1, "percent": 16.67,
                        "keys": ["settings.d.label"],
                    },
                    "current": {"count": 4, "percent": 66.67},
                    "translated": {"count": 2, "percent": 33.33},
                    "fallback": {"count": 4, "percent": 66.67},
                    "extra": 1,
                }},
            }
            self.assertEqual(expected, i18n.catalogue_report(source_path, [target_path]))

            command = [
                sys.executable, str(SCRIPT), "report",
                "--source", str(source_path),
                "--target-dir", str(source_path.parent),
                "--output", str(output_path),
            ]
            subprocess.run(command, check=True)
            first_bytes = output_path.read_bytes()
            self.assertEqual(expected, json.loads(first_bytes))
            subprocess.run(command, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())

            directory_alias = root / "catalogue-alias"
            directory_alias.symlink_to(source_path.parent, target_is_directory=True)
            aliased_command = list(command)
            aliased_command[aliased_command.index("--target-dir") + 1] = str(directory_alias)
            subprocess.run(aliased_command, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())
            subprocess.run(aliased_command, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())

            explicit_context_path = source_path.parent / "terminology.json"
            self.write(explicit_context_path, self.report_context())
            explicit_command = command[:-2] + [
                "--context", str(explicit_context_path), "--output", str(output_path),
            ]
            subprocess.run(explicit_command, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())
            explicit_aliased_dir = list(explicit_command)
            explicit_aliased_dir[explicit_aliased_dir.index("--target-dir") + 1] = str(directory_alias)
            subprocess.run(explicit_aliased_dir, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())
            explicit_context_alias = list(explicit_command)
            explicit_context_alias[explicit_context_alias.index("--context") + 1] = str(
                directory_alias / explicit_context_path.name
            )
            subprocess.run(explicit_context_alias, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())
            explicit_context_path.unlink()

            source_before = source_path.read_bytes()
            source_collision = command[:-1] + [str(source_path)]
            failed = subprocess.run(source_collision, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite the source catalogue", failed.stderr)
            self.assertEqual(source_before, source_path.read_bytes())

            target_before = target_path.read_bytes()
            target_collision = command[:-1] + [str(target_path)]
            failed = subprocess.run(target_collision, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite a target catalogue", failed.stderr)
            self.assertEqual(target_before, target_path.read_bytes())

            context_before = context_path.read_bytes()
            context_collision = command[:-1] + [str(context_path)]
            failed = subprocess.run(context_collision, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite the context artifact", failed.stderr)
            self.assertEqual(context_before, context_path.read_bytes())

            context_alias = root / "context-output.json"
            context_alias.symlink_to(context_path)
            context_alias_collision = command[:-1] + [str(context_alias)]
            failed = subprocess.run(context_alias_collision, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite the context artifact", failed.stderr)
            self.assertEqual(context_before, context_path.read_bytes())

            alias_path = source_path.parent / "fr.json"
            alias_path.symlink_to(output_path.name)
            output_before = output_path.read_bytes()
            failed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite a target catalogue", failed.stderr)
            self.assertEqual(output_before, output_path.read_bytes())

            with self.assertRaisesRegex(
                i18n.CatalogueError,
                "report requires at least one target catalogue",
            ):
                i18n.catalogue_report(source_path, [])

    def test_report_rejects_malformed_public_context_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "en.json"
            target_path = root / "de.json"
            context_path = root / "context.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1,
                "locale": "de",
                "sourceRevision": "e" * 40,
                "strings": {},
            })
            malformed = []
            context = self.report_context()
            context["sources"][0]["artifactSha256"] = "not-a-pin"
            malformed.append(context)
            context = self.report_context()
            context["sources"][0]["repository"] = 7
            malformed.append(context)
            context = self.report_context()
            context["sources"][0]["license"] = "Proprietary"
            malformed.append(context)
            context = self.report_context()
            context["terms"] = [None]
            malformed.append(context)
            context = self.report_context()
            context["terms"][0]["source"] = "missing-source"
            malformed.append(context)
            for index, context in enumerate(malformed):
                with self.subTest(index=index):
                    self.write(context_path, context)
                    with self.assertRaises(i18n.CatalogueError):
                        i18n.catalogue_report(source_path, [target_path], context_path)


if __name__ == "__main__":
    unittest.main()
