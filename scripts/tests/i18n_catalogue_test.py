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

    def test_empty_frozen_literal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / "en.json"
            source = self.source()
            source["strings"]["settings.example.help"]["frozen"] = [""]
            self.write(source_path, source)
            with self.assertRaises(i18n.CatalogueError):
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
