"""路由标注文件的离线契约测试，不运行模型或访问网络。"""

import importlib.util
import json
from pathlib import Path

import pytest


SCRIPT = Path(__file__).with_name("validate_golden.py")
spec = importlib.util.spec_from_file_location("validate_golden", SCRIPT)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def sample(**overrides):
    record = {
        "id": "route-001",
        "query": "咨询中的知情同意通常涉及什么？",
        "expected_route": "professional",
        "allowed_domains": ["psych-knowledge", "psych-policy"],
        "should_retrieve": True,
        "notes": "原创测试样本，检索专业知识和政策资料。",
        "review_status": "pending",
    }
    record.update(overrides)
    return record


def write_records(tmp_path, records):
    path = tmp_path / "golden.jsonl"
    path.write_text("\n".join(json.dumps(record, ensure_ascii=False) for record in records) + "\n", encoding="utf-8")
    return path


@pytest.mark.parametrize("route,domains", list(validator.ROUTE_DOMAINS.items()))
def test_each_valid_route_accepts_its_domain_set(tmp_path, route, domains):
    path = write_records(tmp_path, [sample(expected_route=route, allowed_domains=sorted(domains), should_retrieve=bool(domains))])
    assert validator.validate_golden(path) == []


def test_shipped_golden_has_required_coverage():
    assert validator.validate_golden(validator.DEFAULT_GOLDEN) == []
    records = [json.loads(line) for line in validator.DEFAULT_GOLDEN.read_text(encoding="utf-8").splitlines()]
    assert 24 <= len(records) <= 36
    assert {record["expected_route"] for record in records} == set(validator.ROUTE_DOMAINS)


@pytest.mark.parametrize("overrides,expected", [
    ({"id": " "}, "id must"),
    ({"id": " padded "}, "id must"),
    ({"query": "\t\n "}, "query must"),
    ({"query": 123}, "query must"),
    ({"expected_route": "medical"}, "invalid expected_route"),
    ({"expected_route": []}, "invalid expected_route"),
    ({"allowed_domains": ["psych-prompts"]}, "retrieval domains"),
    ({"allowed_domains": ["psych-other"]}, "retrieval domains"),
    ({"allowed_domains": [{"domain": "psych-policy"}]}, "retrieval domains"),
    ({"allowed_domains": "psych-policy"}, "retrieval domains"),
    ({"allowed_domains": ["psych-knowledge", "psych-policy", "psych-policy"]}, "duplicate allowed_domains"),
    ({"allowed_domains": ["psych-cases"]}, "does not match expected_route"),
    ({"allowed_domains": ["psych-knowledge"]}, "does not match expected_route"),
    ({"expected_route": "listen"}, "does not match expected_route"),
    ({"should_retrieve": False}, "should_retrieve must equal"),
    ({"expected_route": "listen", "allowed_domains": [], "should_retrieve": True}, "should_retrieve must equal"),
    ({"should_retrieve": "true"}, "must be boolean"),
    ({"should_retrieve": 1}, "must be boolean"),
    ({"notes": ""}, "notes must"),
    ({"review_status": "approved"}, "invalid review_status"),
])
def test_invalid_records_are_rejected(tmp_path, overrides, expected):
    errors = validator.validate_golden(write_records(tmp_path, [sample(**overrides)]))
    assert any(expected in error for error in errors)


@pytest.mark.parametrize("field", sorted(validator.REQUIRED_FIELDS))
def test_required_fields_cannot_be_omitted(tmp_path, field):
    record = sample()
    del record[field]
    assert any("missing required" in error for error in validator.validate_golden(write_records(tmp_path, [record])))


def test_duplicate_ids_report_the_original_line(tmp_path):
    path = write_records(tmp_path, [sample(), sample(query="解释另一项咨询伦理要求。")])
    assert any(":2: duplicate id (first at line 1)" in error for error in validator.validate_golden(path))


@pytest.mark.parametrize("query", ["咨询中的知情同意通常涉及什么？", " 咨询中的知情同意 通常涉及什么? "])
def test_duplicate_queries_include_whitespace_and_width_variants(tmp_path, query):
    path = write_records(tmp_path, [sample(), sample(id="route-002", query=query)])
    assert any("duplicate query" in error for error in validator.validate_golden(path))


@pytest.mark.parametrize("content,expected", [
    ("", "dataset must not be empty"),
    ("\n", "invalid JSON"),
    ("[]\n", "each line must be an object"),
    ("{broken}\n", "invalid JSON"),
    ('{"id":"one","id":"two"}\n', "duplicate JSON key"),
    ('{"id":NaN}\n', "invalid JSON constant"),
])
def test_bad_jsonl_is_reported_without_crashing(tmp_path, content, expected):
    path = tmp_path / "broken.jsonl"
    path.write_text(content, encoding="utf-8")
    assert any(expected in error for error in validator.validate_golden(path))


def test_file_errors_are_validation_failures(tmp_path):
    assert validator.validate_golden(tmp_path / "missing.jsonl")
    path = tmp_path / "invalid_encoding.jsonl"
    path.write_bytes(b"\xff")
    assert any("UTF-8" in error for error in validator.validate_golden(path))


def test_cli_returns_zero_or_one(tmp_path, capsys):
    path = write_records(tmp_path, [sample()])
    assert validator.main([str(path)]) == 0
    assert "结构校验通过" in capsys.readouterr().out
    assert validator.main([str(tmp_path / "missing.jsonl")]) == 1
    assert "cannot read" in capsys.readouterr().err
    path.write_text("not json\n", encoding="utf-8")
    assert validator.main([str(path)]) == 1
    assert "invalid JSON" in capsys.readouterr().err
    assert validator.main(["--unknown-option"]) == 1
