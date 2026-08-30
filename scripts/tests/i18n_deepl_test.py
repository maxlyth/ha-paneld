import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("i18n_deepl", ROOT / "scripts/i18n_deepl.py")
DEEPL = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEEPL)


REVISION = "1" * 40
SOURCE_REVISION = "2" * 40


def source_record(text, *, frozen=None, placeholders=None):
    frozen = frozen or []
    placeholders = placeholders or []
    return {
        "text": text,
        "sourceHash": DEEPL.catalogue.source_hash(text),
        "surface": "settings",
        "context": "A concise setting label.",
        "risk": "ordinary",
        "siblings": [],
        "placeholders": placeholders,
        "frozen": frozen,
        "softMaxChars": max(20, len(text)),
        "hardMaxChars": max(40, len(text)),
    }


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


class FakeHttp:
    def __init__(self, translations, usages=((100, 500_000), (1_000, 500_000)), languages=None):
        self.translations = iter(translations)
        self.usages = iter(usages)
        self.languages = languages or [
            {"language": "DE", "name": "German", "supports_formality": True},
            {"language": "FR", "name": "French", "supports_formality": True},
            {"language": "IT", "name": "Italian", "supports_formality": True},
            {"language": "ES", "name": "Spanish", "supports_formality": True},
            {"language": "ZH-HANS", "name": "Chinese (simplified)", "supports_formality": False},
        ]
        self.requests = []

    def __call__(self, request):
        self.requests.append(request)
        if "/v2/languages?type=target" in request.full_url:
            return json.dumps(self.languages).encode()
        if request.full_url.endswith("/v2/usage"):
            count, limit = next(self.usages)
            return json.dumps({"character_count": count, "character_limit": limit}).encode()
        request_body = json.loads(request.data)
        return json.dumps({
            "translations": [{
                "text": next(self.translations),
                "billed_characters": len(request_body["text"][0]),
            }],
        }).encode()


class DeepLAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source_path = self.root / "en.json"
        self.target_dir = self.root / "targets"
        self.source = {
            "schema": 1,
            "locale": "en",
            "sourceRevision": SOURCE_REVISION,
            "strings": {
                "settings.alpha.label": source_record("Alpha"),
                "settings.beta.help": source_record("Keep {name} on MQTT", frozen=["MQTT"], placeholders=["{name}"]),
                "settings.gamma.label": source_record("Gamma"),
            },
        }
        write_json(self.source_path, self.source)

    def tearDown(self):
        self.temp.cleanup()

    def target(self, locale, records, source_revision=SOURCE_REVISION):
        write_json(self.target_dir / f"{locale}.json", {
            "schema": 1,
            "locale": locale,
            "sourceRevision": source_revision,
            "strings": records,
        })

    def test_plan_selects_only_missing_stale_and_fallback_records(self):
        self.target("de", {
            "settings.alpha.label": {
                "text": "Alpha DE",
                "sourceHash": self.source["strings"]["settings.alpha.label"]["sourceHash"],
                "state": "machine-draft",
            },
            "settings.beta.help": {
                "text": "old",
                "sourceHash": "0" * 64,
                "state": "machine-cross-checked",
            },
        }, source_revision="3" * 40)
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["de"], REVISION, set())
        records = plan["batches"][0]["records"]
        self.assertEqual([item["key"] for item in records], ["settings.beta.help", "settings.gamma.label"])
        self.assertEqual(records[0]["sourceHash"], self.source["strings"]["settings.beta.help"]["sourceHash"])
        self.assertEqual(records[0]["context"], "A concise setting label.")
        self.assertEqual(records[0]["priorTarget"], {
            "text": "old",
            "sourceHash": "0" * 64,
            "state": "machine-cross-checked",
        })
        self.assertIsNone(records[1]["priorTarget"])
        self.assertEqual(plan["requestedCharacters"], len("Keep {name} on MQTT") + len("Gamma"))
        self.assertGreaterEqual(plan["maximumBilledCharacters"], plan["requestedCharacters"])

    def test_plan_refuses_to_replace_current_community_correction(self):
        record = self.source["strings"]["settings.alpha.label"]
        self.target("fr", {
            "settings.alpha.label": {
                "text": "Alpha corrigé",
                "sourceHash": record["sourceHash"],
                "state": "community-corrected",
            },
        })
        with self.assertRaisesRegex(DEEPL.DeepLError, "community correction"):
            DEEPL.build_plan(
                self.source_path, self.target_dir, ["fr"], REVISION, {"settings.alpha.label"},
            )

    def test_protected_xml_round_trip_requires_exact_tokens(self):
        record = DEEPL._selected_record(
            "settings.beta.help", self.source["strings"]["settings.beta.help"], self.source,
        )
        protected, tokens = DEEPL._protected_xml(record)
        self.assertIn('<x id="0">{name}</x>', protected)
        self.assertIn("MQTT</x>", protected)
        translated = protected.replace("Keep ", "Behalte ").replace(" on ", " auf ")
        self.assertEqual(DEEPL._restore_xml(translated, tokens, record["key"]), "Behalte {name} auf MQTT")
        with self.assertRaisesRegex(DEEPL.DeepLError, "changed protected token"):
            DEEPL._restore_xml(translated.replace("MQTT", "Mqtt"), tokens, record["key"])

    def test_protected_xml_accepts_overlapping_frozen_tokens(self):
        record = {
            "key": "settings.url.help",
            "english": "Use HTTPS, not HTTP.",
            "placeholders": [],
            "frozen": ["HTTPS", "HTTP"],
        }
        protected, tokens = DEEPL._protected_xml(record)
        self.assertEqual(protected.count("<x "), 2)
        self.assertEqual(DEEPL._restore_xml(protected, tokens, record["key"]), record["english"])

    def test_default_http_rejects_oversized_response(self):
        class Response:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, limit):
                self.asserted_limit = limit
                return b"x" * limit

        response = Response()
        request = DEEPL.urllib.request.Request(DEEPL.API_ORIGIN + "/v2/usage")
        opener = mock.Mock()
        opener.open.return_value = response
        with mock.patch.object(DEEPL.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(DEEPL.DeepLError, "too large"):
                DEEPL._default_http(request)
        self.assertEqual(response.asserted_limit, DEEPL.MAX_RESPONSE_BYTES + 1)

    def test_authenticated_requests_never_follow_redirects(self):
        request = DEEPL.urllib.request.Request(
            DEEPL.API_ORIGIN + "/v2/usage",
            headers={"Authorization": "DeepL-Auth-Key test-key"},
        )
        redirected = DEEPL._NoRedirect().redirect_request(
            request, None, 302, "Found", {}, "https://example.invalid/collect",
        )
        self.assertIsNone(redirected)

    def test_plan_ignores_removed_stale_target_keys(self):
        self.target("de", {
            "settings.removed.label": {
                "text": "Entfernt",
                "sourceHash": "0" * 64,
                "state": "machine-draft",
            },
        })
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["de"], REVISION, set())
        self.assertEqual(len(plan["batches"][0]["records"]), 3)

    def test_generate_preflights_translates_once_and_postflights(self):
        self.target("de", {
            "settings.alpha.label": {
                "text": "previous private review context",
                "sourceHash": "0" * 64,
                "state": "community-corrected",
            },
        })
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["de"], REVISION, set())
        self.assertEqual(
            plan["batches"][0]["records"][0]["priorTarget"]["text"],
            "previous private review context",
        )
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp(["Alfa", 'Behalte <x id="0">{name}</x> auf <x id="1">MQTT</x>', "Gamma DE"])
        output = self.root / "output"

        result = DEEPL.generate(plan_path, output, "secret-value:fx", fake)

        self.assertEqual(result["status"], "generated")
        self.assertEqual([request.full_url for request in fake.requests], [
            DEEPL.API_ORIGIN + "/v2/languages?type=target",
            DEEPL.API_ORIGIN + "/v2/usage",
            DEEPL.API_ORIGIN + "/v2/translate",
            DEEPL.API_ORIGIN + "/v2/translate",
            DEEPL.API_ORIGIN + "/v2/translate",
            DEEPL.API_ORIGIN + "/v2/usage",
        ])
        request_body = json.loads(fake.requests[3].data)
        self.assertEqual(request_body["target_lang"], "DE")
        self.assertEqual(request_body["formality"], "less")
        self.assertEqual(request_body["tag_handling"], "xml")
        self.assertEqual(request_body["tag_handling_version"], "v2")
        self.assertIs(request_body["show_billed_characters"], True)
        self.assertIn("software settings UI", request_body["custom_instructions"][0])
        self.assertIn("A concise setting label.", request_body["context"])
        self.assertNotIn("previous private review context", json.dumps([
            json.loads(request.data)
            for request in fake.requests
            if request.data is not None
        ]))
        self.assertNotIn("secret-value", json.dumps(result))
        self.assertEqual(result["billedCharacters"], result["maximumBilledCharacters"])
        candidate = json.loads((output / "candidates/de.json").read_text())
        self.assertEqual(candidate["sourceRevision"], SOURCE_REVISION)
        self.assertEqual(candidate["sourceCatalogueHash"], DEEPL._source_digest(self.source_path))
        self.assertEqual(candidate["translations"][1]["translation"], "Behalte {name} auf MQTT")
        self.assertEqual(result["resultHashes"]["de"], DEEPL._source_digest(output / "candidates/de.json"))

    def test_insufficient_quota_is_a_no_op_with_reserve(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["fr"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        requested = plan["requestedCharacters"]
        fake = FakeHttp([], usages=((450_000 - requested + 1, 500_000),))
        output = self.root / "output"

        result = DEEPL.generate(plan_path, output, "key:fx", fake)

        self.assertEqual(result["status"], "skipped-quota")
        self.assertFalse((output / "candidates").exists())
        self.assertEqual(len(fake.requests), 2)

    def test_no_changes_needs_no_credential_or_api_call(self):
        current = {
            key: {
                "text": "translated " + key,
                "sourceHash": record["sourceHash"],
                "state": "machine-draft",
            }
            for key, record in self.source["strings"].items()
        }
        self.target("fr", current)
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["fr"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        output = self.root / "output"
        result = DEEPL.generate(plan_path, output, "")
        self.assertEqual(result["status"], "no-changes")

    def test_ambiguous_translation_failure_is_not_retried_or_published(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["es"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        calls = []

        def failing(request):
            calls.append(request)
            if "/v2/languages?type=target" in request.full_url:
                return json.dumps(FakeHttp([]).languages).encode()
            if request.full_url.endswith("/usage"):
                return b'{"character_count":0,"character_limit":500000}'
            raise DEEPL.DeepLError("translation request outcome is ambiguous; not retrying")

        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "ambiguous; not retrying"):
            DEEPL.generate(plan_path, output, "key:fx", failing)
        self.assertEqual(len(calls), 3)
        self.assertFalse(output.exists())

    def test_malformed_postflight_discards_all_candidates(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["it"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp(["Alfa", 'Tieni <x id="0">{name}</x> su <x id="1">MQTT</x>', "Gamma IT"], usages=((0, 500_000), (50, 400_000)))
        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "inconsistent"):
            DEEPL.generate(plan_path, output, "key:fx", fake)
        self.assertFalse(output.exists())

    def test_decreasing_postflight_usage_discards_all_candidates(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["de"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp(
            ["Alfa", 'Behalte <x id="0">{name}</x> auf <x id="1">MQTT</x>', "Gamma DE"],
            usages=((100, 500_000), (99, 500_000)),
        )
        output = self.root / "output"

        with self.assertRaisesRegex(DEEPL.DeepLError, "inconsistent"):
            DEEPL.generate(plan_path, output, "key:fx", fake)
        self.assertFalse(output.exists())

    def test_postflight_usage_must_corroborate_reported_billing(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["fr"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        billed = plan["maximumBilledCharacters"]
        fake = FakeHttp(
            ["Alpha FR", 'Garder <x id="0">{name}</x> sur <x id="1">MQTT</x>', "Gamma FR"],
            usages=((100, 500_000), (100 + billed - 1, 500_000)),
        )
        output = self.root / "output"

        with self.assertRaisesRegex(DEEPL.DeepLError, "inconsistent"):
            DEEPL.generate(plan_path, output, "key:fx", fake)
        self.assertFalse(output.exists())

    def test_run_validation_rejects_uncorroborated_generated_billing(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["it"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp(
            ["Alfa", 'Tieni <x id="0">{name}</x> su <x id="1">MQTT</x>', "Gamma IT"],
        )
        result = DEEPL.generate(plan_path, self.root / "output", "key:fx", fake)
        result["accountUsageAfter"] = result["accountUsageBefore"] + result["billedCharacters"] - 1

        with self.assertRaisesRegex(DEEPL.DeepLError, "inconsistent"):
            DEEPL._validate_run(result, plan)

    def test_public_summary_omits_private_account_quota_telemetry(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["de"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        output = self.root / "output"
        DEEPL.generate(
            plan_path,
            output,
            "key:fx",
            FakeHttp(["Alfa", 'Behalte <x id="0">{name}</x> auf <x id="1">MQTT</x>', "Gamma DE"]),
        )

        rendered = DEEPL.summary(plan_path, output / "run.json")
        self.assertIn("Run billed characters", rendered)
        self.assertNotIn("Account usage", rendered)
        self.assertNotIn("500000", rendered)

    def test_missing_target_capability_fails_before_usage_or_translation(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["de"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp([], languages=[
            {"language": "FR", "name": "French", "supports_formality": True},
        ])
        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "target language is unavailable"):
            DEEPL.generate(plan_path, output, "key:fx", fake)
        self.assertEqual(len(fake.requests), 1)
        self.assertFalse(output.exists())

    def test_unsupported_configured_formality_fails_before_billing(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["fr"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp([], languages=[
            {"language": "FR", "name": "French", "supports_formality": False},
        ])
        with self.assertRaisesRegex(DEEPL.DeepLError, "formality is unsupported"):
            DEEPL.generate(plan_path, self.root / "output", "key:fx", fake)
        self.assertEqual(len(fake.requests), 1)

    def test_missing_billed_characters_discards_candidate_without_retry(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["es"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        calls = []

        def missing_billing(request):
            calls.append(request)
            if "/v2/languages?type=target" in request.full_url:
                return json.dumps(FakeHttp([]).languages).encode()
            if request.full_url.endswith("/usage"):
                return b'{"character_count":0,"character_limit":500000}'
            return b'{"translations":[{"text":"Alfa"}]}'

        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "billed_characters"):
            DEEPL.generate(plan_path, output, "key:fx", missing_billing)
        self.assertEqual(len(calls), 3)
        self.assertFalse(output.exists())

    def test_excessive_reported_billing_discards_candidate(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, ["de"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)

        def excessive_billing(request):
            if "/v2/languages?type=target" in request.full_url:
                return json.dumps(FakeHttp([]).languages).encode()
            if request.full_url.endswith("/usage"):
                return b'{"character_count":0,"character_limit":500000}'
            return b'{"translations":[{"text":"Alfa","billed_characters":999999}]}'

        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "excessive billed_characters"):
            DEEPL.generate(plan_path, output, "key:fx", excessive_billing)
        self.assertFalse(output.exists())

    def test_usage_rejects_unparseable_limit(self):
        fake = FakeHttp([], usages=((0, "500000"),))
        with self.assertRaisesRegex(DEEPL.DeepLError, "invalid character"):
            DEEPL._usage("key:fx", fake)

    def test_check_base_rejects_moved_main(self):
        completed = mock.Mock(stdout="3" * 40 + "\n")
        with mock.patch.object(DEEPL.subprocess, "run", return_value=completed) as run:
            with self.assertRaisesRegex(DEEPL.DeepLError, "stale base"):
                DEEPL.check_base(REVISION, self.root, "origin/main")
        run.assert_called_once()


if __name__ == "__main__":
    unittest.main()
