import importlib.util
import json
from pathlib import Path
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
        path.write_text(json.dumps(value, ensure_ascii=False) + "\n", encoding="utf-8")

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


if __name__ == "__main__":
    unittest.main()
