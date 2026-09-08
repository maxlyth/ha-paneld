#!/usr/bin/env python3
"""Generate read-only DeepL bootstrap candidates for unreleased locales."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from typing import Any

import i18n_catalogue as catalogue
import i18n_deepl as deepl


SCHEMA = 1
MAX_TEXTS_PER_REQUEST = 50
TARGETS = {
    "nl": ("NL", "prefer_less"),
    "pl": ("PL", "prefer_less"),
    "uk": ("UK", "prefer_less"),
    "cs": ("CS", "prefer_less"),
    "pt-BR": ("PT-BR", "prefer_less"),
}


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _records(source: dict[str, Any]) -> list[dict[str, Any]]:
    records = []
    for key, value in source["strings"].items():
        record = deepl._selected_record(key, value, source)
        record["maximumBilledCharacters"] = sum(len(text) for text in _split_record(record)[1])
        records.append(record)
    return records


def _split_record(record: dict[str, Any]) -> tuple[list[tuple[str, str | int]], list[str]]:
    """Separate provider text from immutable literals for local-only reassembly."""
    tokens = list(record["placeholders"])
    if not tokens:
        return [("text", 0)], [record["english"]]
    if any(token not in record["english"] for token in set(tokens)):
        raise deepl.DeepLError(f"{record['key']}: protected-token metadata does not match English")
    alternatives = "|".join(re.escape(token) for token in sorted(set(tokens), key=len, reverse=True))
    parts: list[tuple[str, str | int]] = []
    texts: list[str] = []
    cursor = 0
    for match in re.finditer(alternatives, record["english"]):
        prefix = record["english"][cursor:match.start()]
        if prefix.strip():
            parts.append(("text", len(texts)))
            texts.append(prefix)
        elif prefix:
            parts.append(("literal", prefix))
        parts.append(("literal", match.group(0)))
        cursor = match.end()
    suffix = record["english"][cursor:]
    if suffix.strip():
        parts.append(("text", len(texts)))
        texts.append(suffix)
    elif suffix:
        parts.append(("literal", suffix))
    return parts, texts


def _restore_boundary_whitespace(source: str, translated: str) -> str:
    leading = source[:len(source) - len(source.lstrip())]
    trailing = source[len(source.rstrip()):]
    return leading + translated.strip() + trailing


def _record_batches(records: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    batches: list[list[dict[str, Any]]] = []
    batch: list[dict[str, Any]] = []
    text_count = 0
    for record in records:
        record_text_count = len(_split_record(record)[1])
        if record_text_count > MAX_TEXTS_PER_REQUEST:
            raise deepl.DeepLError(f"{record['key']}: too many independently translated segments")
        if batch and text_count + record_text_count > MAX_TEXTS_PER_REQUEST:
            batches.append(batch)
            batch = []
            text_count = 0
        batch.append(record)
        text_count += record_text_count
    if batch:
        batches.append(batch)
    return batches


def build_plan(source_path: Path, locales: list[str], base_revision: str) -> dict[str, Any]:
    if not catalogue.REV_RE.fullmatch(base_revision):
        raise deepl.DeepLError("base revision must be a full lowercase Git SHA")
    if (
        not locales
        or len(locales) != len(set(locales))
        or any(locale not in TARGETS for locale in locales)
    ):
        raise deepl.DeepLError("locales must be a non-empty, duplicate-free bootstrap locale list")
    source = catalogue.validate_source(source_path)
    records = _records(source)
    requested = sum(len(record["english"]) for record in records) * len(locales)
    maximum = sum(record["maximumBilledCharacters"] for record in records) * len(locales)
    return {
        "schema": SCHEMA,
        "reviewOnly": True,
        "baseRevision": base_revision,
        "sourceRevision": source["sourceRevision"],
        "sourceCatalogueHash": _digest(source_path),
        "locales": locales,
        "selectedRecords": len(records) * len(locales),
        "requestedCharacters": requested,
        "maximumBilledCharacters": maximum,
    }


def validate_plan(plan: dict[str, Any], source_path: Path) -> dict[str, Any]:
    catalogue.exact_keys(plan, {
        "schema", "reviewOnly", "baseRevision", "sourceRevision", "sourceCatalogueHash",
        "locales", "selectedRecords", "requestedCharacters", "maximumBilledCharacters",
    }, "bootstrap plan")
    if (
        plan["schema"] != SCHEMA
        or plan["reviewOnly"] is not True
        or not isinstance(plan["baseRevision"], str)
        or not catalogue.REV_RE.fullmatch(plan["baseRevision"])
        or not isinstance(plan["sourceRevision"], str)
        or not catalogue.REV_RE.fullmatch(plan["sourceRevision"])
        or not isinstance(plan["sourceCatalogueHash"], str)
        or not catalogue.SHA_RE.fullmatch(plan["sourceCatalogueHash"])
        or not isinstance(plan["locales"], list)
        or not plan["locales"]
        or plan["locales"] != list(dict.fromkeys(plan["locales"]))
        or any(locale not in TARGETS for locale in plan["locales"])
    ):
        raise deepl.DeepLError("malformed bootstrap plan")
    source = catalogue.validate_source(source_path)
    if (
        plan["sourceRevision"] != source["sourceRevision"]
        or plan["sourceCatalogueHash"] != _digest(source_path)
        or plan != build_plan(source_path, plan["locales"], plan["baseRevision"])
    ):
        raise deepl.DeepLError("bootstrap plan does not match the exact source input")
    return source


def _capabilities(locales: list[str], api_key: str, http: deepl.HTTP) -> None:
    value = deepl._request_json("/v2/languages?type=target", api_key, http)
    if not isinstance(value, list):
        raise deepl.DeepLError("target-language response root must be an array")
    available: set[str] = set()
    for item in value:
        if (
            not isinstance(item, dict)
            or set(item) != {"language", "name", "supports_formality"}
            or not isinstance(item["language"], str)
            or not isinstance(item["name"], str)
            or not isinstance(item["supports_formality"], bool)
            or item["language"] in available
        ):
            raise deepl.DeepLError("malformed or duplicate target-language capability")
        available.add(item["language"])
    for locale in locales:
        if TARGETS[locale][0] not in available:
            raise deepl.DeepLError(f"{locale}: configured target language is unavailable")


def _context(records: list[dict[str, Any]]) -> str:
    meanings = "\n".join(
        f"{record['key']}: {record['context']} English: {record['english']}"
        for record in records
    )
    return (
        "ha-paneld is a Home Assistant wall-panel application. Translate concise software UI "
        "text, preserving meaning, placeholders, product names, warnings, and technical terms. "
        "Do not add actions, promises, or guarantees. Each input is independent.\n"
        f"String meanings:\n{meanings}"
    )


def _request_body(locale: str, records: list[dict[str, Any]], texts: list[str]) -> dict[str, Any]:
    target, formality = TARGETS[locale]
    return {
        "text": texts,
        "source_lang": "EN",
        "target_lang": target,
        "context": _context(records),
        "show_billed_characters": True,
        "formality": formality,
        "model_type": "prefer_quality_optimized",
        "preserve_formatting": True,
    }


def _translate_batch(
    locale: str,
    records: list[dict[str, Any]],
    api_key: str,
    http: deepl.HTTP,
) -> tuple[list[str], int]:
    layouts: list[list[tuple[str, str | int]]] = []
    texts: list[str] = []
    for record in records:
        parts, record_texts = _split_record(record)
        offset = len(texts)
        layouts.append([
            (kind, value + offset if kind == "text" else value)
            for kind, value in parts
        ])
        texts.extend(record_texts)
    response = deepl._request_json(
        "/v2/translate", api_key, http,
        _request_body(locale, records, texts),
    )
    translations = response.get("translations") if isinstance(response, dict) else None
    if not isinstance(translations, list) or len(translations) != len(texts):
        raise deepl.DeepLError("translation response does not match bootstrap batch")
    translated_texts: list[str] = []
    billed_total = 0
    for source_text, translated in zip(texts, translations, strict=True):
        if not isinstance(translated, dict):
            raise deepl.DeepLError("malformed translation response")
        text, billed = translated.get("text"), translated.get("billed_characters")
        if (
            not isinstance(text, str)
            or not text
            or isinstance(billed, bool)
            or not isinstance(billed, int)
            or billed < 0
            or billed > len(source_text)
        ):
            raise deepl.DeepLError("malformed translation result")
        translated_texts.append(_restore_boundary_whitespace(source_text, text))
        billed_total += billed

    output: list[str] = []
    for record, layout in zip(records, layouts, strict=True):
        restored = "".join(
            translated_texts[value] if kind == "text" else value
            for kind, value in layout
        )
        source_record = record
        if len(restored) > catalogue.MAX_TARGET_TEXT_CHARS:
            raise deepl.DeepLError(f"{record['key']}: translated text is unreasonably large")
        if Counter(catalogue.PLACEHOLDER_RE.findall(restored)) != Counter(source_record["placeholders"]):
            raise deepl.DeepLError(f"{record['key']}: changed placeholders")
        if any(restored.count(token) != record["english"].count(token) for token in record["frozen"]):
            raise deepl.DeepLError(f"{record['key']}: changed frozen literal")
        catalogue.validate_target_text_hygiene(record["key"], restored)
        output.append(restored)
    return output, billed_total


def generate(
    plan_path: Path,
    source_path: Path,
    output_dir: Path,
    api_key: str,
    http: deepl.HTTP = deepl._default_http,
) -> dict[str, Any]:
    plan = catalogue.read_json(plan_path)
    source = validate_plan(plan, source_path)
    if output_dir.exists():
        raise deepl.DeepLError("output directory already exists")
    if not api_key or any(character.isspace() for character in api_key):
        raise deepl.DeepLError("DEEPL_API_KEY is missing or malformed")
    _capabilities(plan["locales"], api_key, http)
    before, limit = deepl._usage(api_key, http)
    if before + plan["maximumBilledCharacters"] > limit - deepl.QUOTA_RESERVE:
        raise deepl.DeepLError("insufficient quota for the complete bootstrap batch and reserve")

    records = _records(source)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.", dir=output_dir.parent))
    billed_total = 0
    hashes: dict[str, str] = {}
    try:
        for locale in plan["locales"]:
            translated: list[dict[str, str]] = []
            for batch in _record_batches(records):
                texts, billed = _translate_batch(locale, batch, api_key, http)
                billed_total += billed
                translated.extend(
                    {"key": record["key"], "translation": text}
                    for record, text in zip(batch, texts, strict=True)
                )
            candidate = {
                "schema": SCHEMA,
                "reviewOnly": True,
                "targetLocale": locale,
                "sourceRevision": plan["sourceRevision"],
                "sourceCatalogueHash": plan["sourceCatalogueHash"],
                "translations": translated,
            }
            path = temporary / f"{locale}.json"
            deepl._write_json(path, candidate)
            hashes[locale] = _digest(path)
        after, after_limit = deepl._usage(api_key, http)
        if after_limit != limit or after < before or after - before < billed_total:
            raise deepl.DeepLError("postflight usage response is inconsistent")
        receipt = {
            "schema": SCHEMA,
            "reviewOnly": True,
            "baseRevision": plan["baseRevision"],
            "planHash": _digest(plan_path),
            "sourceCatalogueHash": plan["sourceCatalogueHash"],
            "locales": plan["locales"],
            "selectedRecords": plan["selectedRecords"],
            "requestedCharacters": plan["requestedCharacters"],
            "maximumBilledCharacters": plan["maximumBilledCharacters"],
            "billedCharacters": billed_total,
            "candidateHashes": dict(sorted(hashes.items())),
        }
        deepl._write_json(temporary / "receipt.json", receipt)
        shutil.copyfile(plan_path, temporary / "plan.json")
        os.replace(temporary, output_dir)
        return receipt
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan")
    plan.add_argument("--source", type=Path, required=True)
    plan.add_argument("--locale", action="append", required=True)
    plan.add_argument("--base-revision", required=True)
    plan.add_argument("--output", type=Path, required=True)
    create = commands.add_parser("generate")
    create.add_argument("--plan", type=Path, required=True)
    create.add_argument("--source", type=Path, required=True)
    create.add_argument("--output-dir", type=Path, required=True)
    verify = commands.add_parser("validate-inputs")
    verify.add_argument("--plan", type=Path, required=True)
    verify.add_argument("--source", type=Path, required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "plan":
            deepl._write_json(args.output, build_plan(args.source, args.locale, args.base_revision))
        elif args.command == "generate":
            generate(args.plan, args.source, args.output_dir, os.environ.get("DEEPL_API_KEY", ""))
        else:
            validate_plan(catalogue.read_json(args.plan), args.source)
    except (deepl.DeepLError, catalogue.CatalogueError, OSError) as error:
        print(f"translation bootstrap error: {error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
