import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("i18n_deepl_bootstrap", ROOT / "scripts/i18n_deepl_bootstrap.py")
BOOTSTRAP = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(BOOTSTRAP)


REVISION = "1" * 40


class FakeHttp:
    def __init__(self, translated):
        self.translated = iter(translated)
        self.requests = []
        self.usage = 0

    def __call__(self, request):
        self.requests.append(request)
        if "/v2/languages?type=target" in request.full_url:
            return json.dumps([
                {"language": target, "name": locale, "supports_formality": False}
                for locale, (target, _) in BOOTSTRAP.TARGETS.items()
            ]).encode()
        if request.full_url.endswith("/v2/usage"):
            return json.dumps({"character_count": self.usage, "character_limit": 500_000}).encode()
        body = json.loads(request.data)
        values = []
        for text in body["text"]:
            value = next(self.translated)
            values.append({"text": value, "billed_characters": len(text)})
            self.usage += len(text)
        return json.dumps({"translations": values}).encode()


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source_path = self.root / "en.json"
        strings = {}
        for index in range(51):
            text = f"Value {index}"
            strings[f"settings.value_{index:02}.label"] = {
                "text": text,
                "sourceHash": BOOTSTRAP.catalogue.source_hash(text),
                "surface": "settings",
                "context": "A setting label.",
                "risk": "ordinary",
                "siblings": [],
                "placeholders": [],
                "frozen": [],
                "softMaxChars": 40,
                "hardMaxChars": 80,
            }
        self.source = {
            "schema": 1, "locale": "en", "sourceRevision": "2" * 40, "strings": strings,
        }
        self.source_path.write_text(json.dumps(self.source), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_priority_and_provider_codes_are_fixed(self):
        self.assertEqual(list(BOOTSTRAP.TARGETS), ["nl", "pl", "uk", "cs", "pt-BR"])
        self.assertEqual(BOOTSTRAP.TARGETS["uk"][0], "UK")

    def test_plan_is_review_only_and_binds_source(self):
        plan = BOOTSTRAP.build_plan(self.source_path, ["nl", "pl", "uk"], REVISION)
        self.assertIs(plan["reviewOnly"], True)
        self.assertEqual(plan["selectedRecords"], 153)
        self.assertEqual(BOOTSTRAP.validate_plan(plan, self.source_path), self.source)
        self.source["strings"]["settings.value_00.label"]["text"] = "Changed"
        self.source_path.write_text(json.dumps(self.source), encoding="utf-8")
        with self.assertRaises(BOOTSTRAP.catalogue.CatalogueError):
            BOOTSTRAP.validate_plan(plan, self.source_path)

    def test_protected_literals_never_enter_provider_text(self):
        record = {
            "key": "settings.example.label",
            "english": "Use Home Assistant with {name}",
            "context": "Example setting.",
            "placeholders": ["{name}"],
            "frozen": ["Home Assistant"],
            "maximumBilledCharacters": len("Use  with "),
        }
        parts, texts = BOOTSTRAP._split_record(record)
        self.assertEqual(parts, [("text", 0), ("literal", "{name}")])
        self.assertEqual(texts, ["Use Home Assistant with "])
        fake = FakeHttp(["Gebruik Home Assistant met"])
        translated, _ = BOOTSTRAP._translate_batch("nl", [record], "key:fx", fake)
        self.assertEqual(translated, ["Gebruik Home Assistant met {name}"])
        body = json.loads(fake.requests[0].data)
        self.assertEqual(body["text"], ["Use Home Assistant with "])
        self.assertIn("Home Assistant", body["text"][0])
        self.assertNotIn("{name}", body["text"])
        with self.assertRaisesRegex(BOOTSTRAP.deepl.DeepLError, "changed frozen literal"):
            BOOTSTRAP._translate_batch(
                "nl", [record], "key:fx", FakeHttp(["Gebruik Thuisassistent met"]),
            )

    def test_generate_batches_and_emits_review_only_artifact(self):
        plan = BOOTSTRAP.build_plan(self.source_path, ["uk"], REVISION)
        plan_path = self.root / "plan.json"
        BOOTSTRAP.deepl._write_json(plan_path, plan)
        translated = [f"Значення {index}" for index in range(51)]
        fake = FakeHttp(translated)
        output = self.root / "output"
        receipt = BOOTSTRAP.generate(plan_path, self.source_path, output, "key:fx", fake)
        candidate = json.loads((output / "uk.json").read_text())
        translate_requests = [request for request in fake.requests if request.full_url.endswith("/v2/translate")]
        self.assertEqual(len(translate_requests), 2)
        self.assertEqual([len(json.loads(request.data)["text"]) for request in translate_requests], [50, 1])
        self.assertIs(candidate["reviewOnly"], True)
        self.assertEqual(candidate["targetLocale"], "uk")
        self.assertEqual(len(candidate["translations"]), 51)
        self.assertEqual(receipt["candidateHashes"]["uk"], BOOTSTRAP._digest(output / "uk.json"))
        first_body = json.loads(translate_requests[0].data)
        self.assertEqual(first_body["model_type"], "prefer_quality_optimized")
        self.assertEqual(first_body["formality"], "prefer_less")
        self.assertNotIn("ignore_tags", first_body)
        self.assertNotIn("tag_handling", first_body)
        self.assertNotIn("custom_instructions", first_body)

    def test_quota_preflight_prevents_translation(self):
        plan = BOOTSTRAP.build_plan(self.source_path, ["nl"], REVISION)
        plan_path = self.root / "plan.json"
        BOOTSTRAP.deepl._write_json(plan_path, plan)
        fake = FakeHttp([])
        fake.usage = 499_999
        with self.assertRaisesRegex(BOOTSTRAP.deepl.DeepLError, "insufficient quota"):
            BOOTSTRAP.generate(plan_path, self.source_path, self.root / "output", "key:fx", fake)
        self.assertFalse(any(request.full_url.endswith("/v2/translate") for request in fake.requests))


if __name__ == "__main__":
    unittest.main()
