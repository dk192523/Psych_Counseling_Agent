"""Offline admission tests; no model, network, or existing corpus mutation."""
import importlib.util
import json
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "counseling-kb" / "validate_sources.py"
spec = importlib.util.spec_from_file_location("validate_sources", SCRIPT)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def source(**overrides):
    entry = {"id": "sample", "title": "示例", "domain": "knowledge",
             "use": "production", "path": "knowledge/sample.txt", "license": "CC0-1.0",
             "license_status": "approved", "license_evidence": "Fixture authorization",
             "pii_status": "no_pii", "review_status": "approved", "reviewed_by": "test",
             "reviewed_at": "2026-09-20", "notes": "Synthetic fixture only"}
    entry.update(overrides)
    return entry


def validate(tmp_path, entries):
    (tmp_path / "knowledge").mkdir(exist_ok=True)
    (tmp_path / "knowledge" / "sample.txt").write_text("fixture", encoding="utf-8")
    manifest = tmp_path / "sources.jsonl"
    manifest.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries), encoding="utf-8")
    return validator.validate_sources(manifest, tmp_path)


def test_valid_production_and_external_inventory(tmp_path):
    external = source(id="external", use="quarantine", source_url="https://example.com/source")
    del external["path"]
    assert not validate(tmp_path, [source(), external])


def test_duplicate_ids_and_missing_fields(tmp_path):
    assert any("duplicate id" in e for e in validate(tmp_path, [source(), source()]))
    entry = source()
    del entry["title"]
    assert any("missing required" in e for e in validate(tmp_path, [entry]))


@pytest.mark.parametrize("changes", [
    {"domain": "other"}, {"use": "train"}, {"path": "missing.txt"},
    {"path": "../escape.txt"}, {"path": "C:/escape.txt"},
    {"license_status": "unknown"}, {"license": "unknown"},
    {"review_status": "pending"}, {"pii_status": "contains_pii"},
    {"pii_status": "unknown"}, {"domain": "exam/practice"},
    {"reviewed_at": "2026-02-30"}, {"source_url": "https://"},
    {"path": "knowledge"}, {"unexpected": "typo"},
])
def test_invalid_entries_are_rejected(tmp_path, changes):
    assert validate(tmp_path, [source(**changes)])


@pytest.mark.parametrize("field", ["license_evidence", "reviewed_by", "reviewed_at"])
def test_production_requires_evidence(tmp_path, field):
    entry = source()
    del entry[field]
    assert validate(tmp_path, [entry])


def test_production_eval_overlap_rejected(tmp_path):
    errors = validate(tmp_path, [source(), source(id="eval-copy", use="evaluation")])
    assert any("both production and evaluation" in e for e in errors)


def test_current_manifest():
    assert not validator.validate_sources(SCRIPT.parent / "manifests" / "sources.jsonl")
