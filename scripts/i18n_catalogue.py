#!/usr/bin/env python3
"""Deterministic, provider-neutral catalogue validation and translation-batch export."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Any

SCHEMA = 1
SOURCE_ROOT_KEYS = {"schema", "locale", "sourceRevision", "strings"}
SOURCE_RECORD_KEYS = {
    "text", "sourceHash", "surface", "context", "risk", "siblings",
    "placeholders", "frozen", "softMaxChars", "hardMaxChars",
}
TARGET_ROOT_KEYS = {"schema", "locale", "sourceRevision", "strings"}
TARGET_RECORD_KEYS = {"text", "sourceHash", "state"}
CANDIDATE_ROOT_KEYS = {
    "schema", "targetLocale", "sourceRevision", "sourceCatalogueHash", "translations",
}
CANDIDATE_RECORD_KEYS = {"key", "translation"}
LOCALES = {"de", "fr", "it", "es", "zh-Hans"}
STATES = {"english-fallback", "machine-draft", "machine-cross-checked", "community-corrected"}
KEY_RE = re.compile(r"[a-z0-9][a-z0-9._-]*\Z")
SHA_RE = re.compile(r"[0-9a-f]{64}\Z")
REV_RE = re.compile(r"[0-9a-f]{40}\Z")
PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?[a-zA-Z]|\{[a-zA-Z_][a-zA-Z0-9_]*\}")
LATIN_RE = re.compile(r"[A-Za-z\u00c0-\u024f]")
HAN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
ENGLISH_WORD_RE = re.compile(r"[A-Za-z][A-Za-z'-]{2,}")
REQUIRED_FROZEN_LITERALS = ("Home Assistant", "dB")
RENDERABLE_STATES = {"machine-cross-checked", "community-corrected"}


class CatalogueError(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CatalogueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CatalogueError(f"{path}: {error}") from error
    if not isinstance(value, dict):
        raise CatalogueError(f"{path}: root must be an object")
    return value


def exact_keys(value: dict[str, Any], expected: set[str], owner: str) -> None:
    if set(value) != expected:
        raise CatalogueError(f"{owner}: expected keys {sorted(expected)}, got {sorted(value)}")


def source_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def contains_literal(text: str, literal: str) -> bool:
    return re.search(
        rf"(?<![A-Za-z0-9_]){re.escape(literal)}(?![A-Za-z0-9_])",
        text,
    ) is not None


def unprotected_text(text: str, source_record: dict[str, Any]) -> str:
    result = text
    protected = list(source_record["placeholders"]) + list(source_record["frozen"])
    for token in sorted(protected, key=len, reverse=True):
        result = result.replace(token, " ")
    return result


def validate_target_language(key: str, text: str, locale: str, source_record: dict[str, Any]) -> None:
    source_visible = unprotected_text(source_record["text"], source_record)
    if not LATIN_RE.search(source_visible):
        return
    target_visible = unprotected_text(text, source_record)
    if locale == "zh-Hans":
        if not HAN_RE.search(target_visible):
            raise CatalogueError(f"{key}: zh-Hans target has no Han text")
        residual = sorted({word.casefold() for word in ENGLISH_WORD_RE.findall(target_visible)})
        if residual:
            raise CatalogueError(f"{key}: residual English words: {', '.join(residual)}")
        if any(character.isalpha() and not HAN_RE.fullmatch(character) for character in target_visible):
            raise CatalogueError(f"{key}: zh-Hans target has unexpected script")
    else:
        if not LATIN_RE.search(target_visible) or any(
            character.isalpha() and not LATIN_RE.fullmatch(character)
            for character in target_visible
        ):
            raise CatalogueError(f"{key}: {locale} target has unexpected script")
        if target_visible.strip().casefold() == source_visible.strip().casefold():
            raise CatalogueError(f"{key}: target is unchanged English")


def string_list(value: Any, owner: str, *, distinct: bool = True) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise CatalogueError(f"{owner}: expected an array of strings")
    if any(not item for item in value):
        raise CatalogueError(f"{owner}: empty value")
    if distinct and len(value) != len(set(value)):
        raise CatalogueError(f"{owner}: duplicate value")
    return value


def validate_source(path: Path) -> dict[str, Any]:
    root = read_json(path)
    exact_keys(root, SOURCE_ROOT_KEYS, "source root")
    if root["schema"] != SCHEMA or root["locale"] != "en":
        raise CatalogueError("source root: unsupported schema or locale")
    if not isinstance(root["sourceRevision"], str) or not REV_RE.fullmatch(root["sourceRevision"]):
        raise CatalogueError("source root: sourceRevision must be a 40-character lowercase Git SHA")
    strings = root["strings"]
    if not isinstance(strings, dict) or not strings:
        raise CatalogueError("source root: strings must be a non-empty object")
    if list(strings) != sorted(strings):
        raise CatalogueError("source root: strings must use canonical key order")
    for key, record in strings.items():
        if not KEY_RE.fullmatch(key) or not isinstance(record, dict):
            raise CatalogueError(f"invalid source record: {key}")
        exact_keys(record, SOURCE_RECORD_KEYS, key)
        text = record["text"]
        if not isinstance(text, str) or not text:
            raise CatalogueError(f"{key}: text must be non-empty")
        if record["sourceHash"] != source_hash(text):
            raise CatalogueError(f"{key}: sourceHash mismatch")
        if record["surface"] != "settings" or not isinstance(record["context"], str) or not record["context"].strip():
            raise CatalogueError(f"{key}: invalid surface or context")
        if record["risk"] not in {"ordinary", "setup", "consequential"}:
            raise CatalogueError(f"{key}: invalid risk")
        siblings = string_list(record["siblings"], f"{key}.siblings")
        if any(sibling not in strings for sibling in siblings):
            raise CatalogueError(f"{key}: unknown sibling")
        placeholders = string_list(record["placeholders"], f"{key}.placeholders", distinct=False)
        if placeholders != PLACEHOLDER_RE.findall(text):
            raise CatalogueError(f"{key}: placeholder metadata mismatch")
        frozen = string_list(record["frozen"], f"{key}.frozen")
        if any(token not in text for token in frozen):
            raise CatalogueError(f"{key}: frozen literal missing from English text")
        missing_required = [
            literal for literal in REQUIRED_FROZEN_LITERALS
            if contains_literal(text, literal) and literal not in frozen
        ]
        if missing_required:
            raise CatalogueError(
                f"{key}: required frozen literal missing: {', '.join(missing_required)}"
            )
        soft, hard = record["softMaxChars"], record["hardMaxChars"]
        if not isinstance(soft, int) or not isinstance(hard, int) or soft <= 0 or hard < soft or hard < len(text):
            raise CatalogueError(f"{key}: invalid layout budget")
    return root


def validate_target(
    path: Path,
    source: dict[str, Any],
    *,
    expected_locale: str | None = None,
) -> dict[str, Any]:
    root = read_json(path)
    exact_keys(root, TARGET_ROOT_KEYS, "target root")
    if root["schema"] != SCHEMA or root["locale"] not in LOCALES:
        raise CatalogueError("target root: unsupported schema or locale")
    if expected_locale is not None and root["locale"] != expected_locale:
        raise CatalogueError(f"target root: locale {root['locale']} does not match {expected_locale}")
    if not isinstance(root["sourceRevision"], str) or not REV_RE.fullmatch(root["sourceRevision"]):
        raise CatalogueError("target root: sourceRevision must be a 40-character lowercase Git SHA")
    records = root["strings"]
    if not isinstance(records, dict):
        raise CatalogueError("target root: strings must be an object")
    for key, record in records.items():
        if not KEY_RE.fullmatch(key) or not isinstance(record, dict):
            raise CatalogueError(f"target contains invalid record: {key}")
        exact_keys(record, TARGET_RECORD_KEYS, key)
        text = record["text"]
        if not isinstance(text, str) or not text:
            raise CatalogueError(f"{key}: target text must be non-empty")
        if len(text) > 16_384:
            raise CatalogueError(f"{key}: target text is unreasonably large")
        if not isinstance(record["sourceHash"], str) or not SHA_RE.fullmatch(record["sourceHash"]):
            raise CatalogueError(f"{key}: invalid source hash")
        if record["state"] not in STATES:
            raise CatalogueError(f"{key}: invalid state")
        source_record = source["strings"].get(key)
        if source_record is None or record["sourceHash"] != source_record["sourceHash"]:
            continue
        if record["state"] == "english-fallback" and text != source_record["text"]:
            raise CatalogueError(f"{key}: English fallback does not equal its source")
        if Counter(PLACEHOLDER_RE.findall(text)) != Counter(source_record["placeholders"]):
            raise CatalogueError(f"{key}: changed placeholders")
        if any(text.count(token) != source_record["text"].count(token) for token in source_record["frozen"]):
            raise CatalogueError(f"{key}: changed frozen literal")
        if len(text) > source_record["hardMaxChars"]:
            raise CatalogueError(f"{key}: hard length budget exceeded")
        if record["state"] != "english-fallback":
            validate_target_language(key, text, root["locale"], source_record)
    return root


def git_head(worktree: Path) -> str:
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=worktree, text=True, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout.strip()
    if not REV_RE.fullmatch(head):
        raise CatalogueError("could not resolve an exact Git HEAD")
    return head


def require_clean(worktree: Path) -> None:
    status = subprocess.run(
        ["git", "status", "--porcelain"], cwd=worktree, text=True, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout
    if status:
        raise CatalogueError("translation input must be exported from a clean committed worktree")


def write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(rendered)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def export_batch(source_path: Path, locale: str, output: Path, worktree: Path) -> None:
    if locale not in LOCALES:
        raise CatalogueError(f"unsupported target locale: {locale}")
    require_clean(worktree)
    canonical_source = worktree.resolve() / "app/src/main/assets/i18n/en.json"
    if source_path.resolve() != canonical_source:
        raise CatalogueError("source must be the canonical catalogue inside the selected worktree")
    source = validate_source(source_path)
    source_bytes = source_path.read_bytes()
    committed_source = subprocess.run(
        ["git", "show", "HEAD:app/src/main/assets/i18n/en.json"], cwd=worktree, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout
    if source_bytes != committed_source:
        raise CatalogueError("source catalogue bytes do not match the selected Git HEAD")
    messages = source["strings"]
    records: list[dict[str, Any]] = []
    for key in sorted(messages):
        record = messages[key]
        siblings = [
            {"key": sibling, "english": messages[sibling]["text"]}
            for sibling in record["siblings"]
        ]
        records.append({
            "key": key,
            "english": record["text"],
            "sourceHash": record["sourceHash"],
            "surface": record["surface"],
            "context": record["context"],
            "risk": record["risk"],
            "siblings": siblings,
            "placeholders": record["placeholders"],
            "frozen": record["frozen"],
            "softMaxChars": record["softMaxChars"],
            "hardMaxChars": record["hardMaxChars"],
        })
    batch = {
        "schema": SCHEMA,
        "publicRevision": git_head(worktree),
        "sourceRevision": source["sourceRevision"],
        "sourceCatalogueHash": hashlib.sha256(source_bytes).hexdigest(),
        "sourceLocale": "en",
        "targetLocale": locale,
        "records": records,
    }
    write_json_atomic(output, batch)


def candidate_to_target(source_path: Path, candidate_path: Path, output: Path) -> None:
    source = validate_source(source_path)
    candidate = read_json(candidate_path)
    exact_keys(candidate, CANDIDATE_ROOT_KEYS, "candidate root")
    locale = candidate["targetLocale"]
    if candidate["schema"] != SCHEMA or locale not in LOCALES:
        raise CatalogueError("candidate root: unsupported schema or target locale")
    if candidate["sourceRevision"] != source["sourceRevision"]:
        raise CatalogueError("candidate root: stale sourceRevision")
    catalogue_hash = hashlib.sha256(source_path.read_bytes()).hexdigest()
    if candidate["sourceCatalogueHash"] != catalogue_hash:
        raise CatalogueError("candidate root: stale sourceCatalogueHash")
    translations = candidate["translations"]
    if not isinstance(translations, list):
        raise CatalogueError("candidate translations must be an array")
    wanted = sorted(source["strings"])
    if len(translations) != len(wanted):
        raise CatalogueError("candidate does not have exact source coverage")
    target_strings: dict[str, Any] = {}
    for index, (expected_key, item) in enumerate(zip(wanted, translations)):
        if not isinstance(item, dict):
            raise CatalogueError(f"candidate record {index} must be an object")
        exact_keys(item, CANDIDATE_RECORD_KEYS, f"candidate record {index}")
        if item["key"] != expected_key:
            raise CatalogueError(f"candidate record {index}: expected {expected_key}, got {item['key']}")
        translation = item["translation"]
        if not isinstance(translation, str) or not translation:
            raise CatalogueError(f"{expected_key}: empty translation")
        source_record = source["strings"][expected_key]
        if Counter(PLACEHOLDER_RE.findall(translation)) != Counter(source_record["placeholders"]):
            raise CatalogueError(f"{expected_key}: changed placeholders")
        if any(translation.count(token) != source_record["text"].count(token) for token in source_record["frozen"]):
            raise CatalogueError(f"{expected_key}: changed frozen literal")
        if len(translation) > source_record["hardMaxChars"]:
            raise CatalogueError(f"{expected_key}: hard length budget exceeded")
        validate_target_language(expected_key, translation, locale, source_record)
        target_strings[expected_key] = {
            "text": translation,
            "sourceHash": source_record["sourceHash"],
            "state": "machine-draft",
        }
    target = {
        "schema": SCHEMA,
        "locale": locale,
        "sourceRevision": source["sourceRevision"],
        "strings": target_strings,
    }
    validate_target_value = output.parent / f".{output.name}.validation"
    write_json_atomic(validate_target_value, target)
    try:
        validate_target(validate_target_value, source)
        os.replace(validate_target_value, output)
    finally:
        validate_target_value.unlink(missing_ok=True)


def merge_candidate(
    source_path: Path,
    base_target_path: Path,
    candidate_path: Path,
    output: Path,
) -> None:
    source = validate_source(source_path)
    base = validate_target(base_target_path, source, expected_locale=base_target_path.stem)
    candidate = read_json(candidate_path)
    exact_keys(candidate, CANDIDATE_ROOT_KEYS, "candidate root")
    locale = candidate["targetLocale"]
    if candidate["schema"] != SCHEMA or locale not in LOCALES or locale != base["locale"]:
        raise CatalogueError("candidate root: unsupported or mismatched target locale")
    if candidate["sourceRevision"] != source["sourceRevision"]:
        raise CatalogueError("candidate root: stale sourceRevision")
    catalogue_hash = hashlib.sha256(source_path.read_bytes()).hexdigest()
    if candidate["sourceCatalogueHash"] != catalogue_hash:
        raise CatalogueError("candidate root: stale sourceCatalogueHash")
    translations = candidate["translations"]
    if not isinstance(translations, list) or not translations:
        raise CatalogueError("partial candidate translations must be a non-empty array")
    selected: dict[str, str] = {}
    previous_key: str | None = None
    for index, item in enumerate(translations):
        if not isinstance(item, dict):
            raise CatalogueError(f"candidate record {index} must be an object")
        exact_keys(item, CANDIDATE_RECORD_KEYS, f"candidate record {index}")
        key, translation = item["key"], item["translation"]
        if key not in source["strings"]:
            raise CatalogueError(f"candidate record {index}: unknown key {key}")
        if previous_key is not None and key <= previous_key:
            raise CatalogueError("partial candidate keys must be unique and in canonical order")
        previous_key = key
        if not isinstance(translation, str) or not translation:
            raise CatalogueError(f"{key}: empty translation")
        old = base["strings"].get(key)
        source_record = source["strings"][key]
        if old and old["state"] == "community-corrected" and old["sourceHash"] == source_record["sourceHash"]:
            raise CatalogueError(f"{key}: current community correction is protected")
        if Counter(PLACEHOLDER_RE.findall(translation)) != Counter(source_record["placeholders"]):
            raise CatalogueError(f"{key}: changed placeholders")
        if any(translation.count(token) != source_record["text"].count(token) for token in source_record["frozen"]):
            raise CatalogueError(f"{key}: changed frozen literal")
        if len(translation) > source_record["hardMaxChars"]:
            raise CatalogueError(f"{key}: hard length budget exceeded")
        validate_target_language(key, translation, locale, source_record)
        selected[key] = translation

    # Removed source keys are never renderable. Drop them from the public merged artifact; any
    # review value worth retaining belongs in the candidate plan/evidence, not an accumulating
    # runtime catalogue orphan.
    merged_strings = {
        key: value for key, value in base["strings"].items()
        if key in source["strings"]
    }
    for key, translation in selected.items():
        source_record = source["strings"][key]
        merged_strings[key] = {
            "text": translation,
            "sourceHash": source_record["sourceHash"],
            "state": "machine-draft",
        }
    merged = {
        "schema": SCHEMA,
        "locale": locale,
        "sourceRevision": source["sourceRevision"],
        "strings": merged_strings,
    }
    validation_path = output.parent / f".{output.name}.validation"
    write_json_atomic(validation_path, merged)
    try:
        validate_target(validation_path, source, expected_locale=locale)
        os.replace(validation_path, output)
    finally:
        validation_path.unlink(missing_ok=True)


def coverage(count: int, total: int) -> dict[str, int | float]:
    return {
        "count": count,
        "percent": round(count * 100 / total, 2),
    }


def catalogue_report(source_path: Path, target_paths: list[Path]) -> dict[str, Any]:
    """Return deterministic per-locale catalogue and runtime-fallback counts."""
    if not target_paths:
        raise CatalogueError("report requires at least one target catalogue")
    source = validate_source(source_path)
    source_strings = source["strings"]
    source_keys = set(source_strings)
    total = len(source_keys)
    locales: dict[str, Any] = {}

    for path in target_paths:
        target = validate_target(path, source, expected_locale=path.stem)
        locale = target["locale"]
        if locale in locales:
            raise CatalogueError(f"duplicate target locale: {locale}")

        records = target["strings"]
        record_keys = set(records)
        present_keys = source_keys & record_keys
        missing_keys = source_keys - record_keys
        stale_keys = {
            key for key in present_keys
            if records[key]["sourceHash"] != source_strings[key]["sourceHash"]
        }
        current_keys = present_keys - stale_keys
        translated_keys = {
            key for key in current_keys
            if records[key]["state"] in RENDERABLE_STATES
        }
        fallback_keys = source_keys - translated_keys
        state_counts = Counter(record["state"] for record in records.values())

        locales[locale] = {
            "catalogueRecords": len(records),
            "sourceRevision": target["sourceRevision"],
            "sourceRevisionMatches": target["sourceRevision"] == source["sourceRevision"],
            "stateCounts": {state: state_counts[state] for state in sorted(STATES)},
            "missing": coverage(len(missing_keys), total),
            "stale": coverage(len(stale_keys), total),
            "current": coverage(len(current_keys), total),
            "translated": coverage(len(translated_keys), total),
            "fallback": coverage(len(fallback_keys), total),
            "extra": len(record_keys - source_keys),
        }

    return {
        "schema": SCHEMA,
        "source": {
            "locale": source["locale"],
            "revision": source["sourceRevision"],
            "strings": total,
        },
        "locales": {locale: locales[locale] for locale in sorted(locales)},
    }


def selected_targets(
    source_path: Path,
    target_paths: list[Path],
    target_dir: Path | None,
    *,
    excluded: set[Path] | None = None,
) -> list[Path]:
    excluded = excluded or set()
    selected = list(target_paths)
    if target_dir:
        selected.extend(
            path for path in sorted(target_dir.glob("*.json"))
            if path.resolve() != source_path.resolve() and path.resolve() not in excluded
        )
    return selected


def report_targets(
    source_path: Path,
    target_paths: list[Path],
    target_dir: Path | None,
    output: Path | None,
) -> list[Path]:
    if output is None:
        return selected_targets(source_path, target_paths, target_dir)

    output_path = output.resolve()
    if output_path == source_path.resolve():
        raise CatalogueError("report output must not overwrite the source catalogue")
    if any(output_path == path.resolve() for path in target_paths):
        raise CatalogueError("report output must not overwrite a target catalogue")
    if (
        target_dir is not None
        and output_path.parent == target_dir.resolve()
        and output_path.stem in LOCALES
    ):
        raise CatalogueError("report output must not overwrite a target catalogue")

    selected = selected_targets(
        source_path,
        target_paths,
        target_dir,
        excluded={output_path},
    )
    if any(output_path == path.resolve() for path in selected):
        raise CatalogueError("report output must not overwrite a target catalogue")
    return selected


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    sub = result.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("--source", type=Path, required=True)
    validate.add_argument("--target", type=Path, action="append", default=[])
    validate.add_argument("--target-dir", type=Path)
    report = sub.add_parser(
        "report",
        help="report per-locale catalogue state, currency, and runtime fallback coverage as JSON",
    )
    report.add_argument("--source", type=Path, required=True)
    report.add_argument("--target", type=Path, action="append", default=[])
    report.add_argument("--target-dir", type=Path)
    report.add_argument("--output", type=Path)
    export = sub.add_parser("export")
    export.add_argument("--source", type=Path, required=True)
    export.add_argument("--locale", required=True)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--worktree", type=Path, default=Path.cwd())
    candidate = sub.add_parser("validate-candidate")
    candidate.add_argument("--source", type=Path, required=True)
    candidate.add_argument("--candidate", type=Path, required=True)
    candidate.add_argument("--output", type=Path, required=True)
    merge = sub.add_parser("merge-candidate")
    merge.add_argument("--source", type=Path, required=True)
    merge.add_argument("--base-target", type=Path, required=True)
    merge.add_argument("--candidate", type=Path, required=True)
    merge.add_argument("--output", type=Path, required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "validate":
            source = validate_source(args.source)
            for target in selected_targets(args.source, args.target, args.target_dir):
                validate_target(target, source, expected_locale=target.stem)
        elif args.command == "report":
            report = catalogue_report(
                args.source,
                report_targets(
                    args.source,
                    args.target,
                    args.target_dir,
                    args.output,
                ),
            )
            if args.output:
                write_json_atomic(args.output, report)
            else:
                print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
        elif args.command == "export":
            export_batch(args.source, args.locale, args.output, args.worktree)
        elif args.command == "validate-candidate":
            candidate_to_target(args.source, args.candidate, args.output)
        else:
            merge_candidate(args.source, args.base_target, args.candidate, args.output)
    except (CatalogueError, subprocess.CalledProcessError) as error:
        print(f"i18n catalogue error: {error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
