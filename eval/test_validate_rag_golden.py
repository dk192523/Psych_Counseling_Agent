"""Synthetic gate-contract fixtures only; these are not human golden annotations.

All synthetic cases and corpus artifacts live in a temporary directory. No model,
network, benchmark, or writes to the shipped dataset are involved.
"""

import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

SCRIPT = Path(__file__).with_name("validate_rag_golden.py")
SPEC = importlib.util.spec_from_file_location("validate_rag_golden", SCRIPT)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def complete_fixture(base, count=60):
    """Fabricated review identities are test data, never shipped or scored."""
    digest = write_json(base / "corpus.json", {"test_fixture_only": True, "content": "synthetic evidence"})
    manifest = {
        "corpus_id": "test-only", "corpus_version": "fixture-v1", "corpus_sha256": digest,
        "production_eligible": True,
        "documents": [{"id": "doc-1", "domain": "psych-knowledge", "sha256": "a" * 64}],
        "chunks": [{"id": "chunk-1", "doc_id": "doc-1", "sha256": "b" * 64}],
    }
    manifest_digest = write_json(base / "manifest.json", manifest)
    chunk_config = {"size": 256, "overlap": 32, "unit": "tokens", "separators": ["\n\n", "。"]}
    retrieval = {"top_k": 4, "similarity_threshold": 0.87, "distance_metric": "cosine",
                 "query_transform": "identity", "index_type": "exact", "index_revision": "fixture-v1"}
    record = {
        "id": "test-0", "query": "合成测试查询 0", "split": "test", "review_status": "reviewed",
        "routing": {"expected_route": "professional", "allowed_domains": ["psych-knowledge", "psych-policy"], "should_retrieve": True},
        "answerability": "answerable",
        "judgment_scope": {"method": "pooled_candidates", "candidate_doc_ids": ["doc-1"], "candidate_chunk_ids": ["chunk-1"], "notes": "Synthetic single-candidate pool; not corpus recall."},
        "document_relevance": [{"doc_id": "doc-1", "grade": 3, "rationale": "Synthetic fixture label."}],
        "chunk_relevance": [{"chunk_id": "chunk-1", "doc_id": "doc-1", "grade": 3, "rationale": "Synthetic fixture label."}],
        "supporting_facts": [{"id": "fact-1", "fact": "Synthetic fact.", "citations": [{"doc_id": "doc-1", "chunk_id": "chunk-1", "evidence_quote": "synthetic evidence"}]}],
        "expected_response": {"behavior": "answer", "required_elements": ["Cite the synthetic evidence."], "prohibited_elements": ["Invent evidence."], "notes": "Test only."},
        "review": {"annotator": "test-annotator", "reviewers": ["test-reviewer"], "reviewed_at": "2026-10-09T00:00:00+08:00", "adjudication": None},
    }
    cases = []
    for index in range(count):
        case = copy.deepcopy(record)
        case.update(id=f"test-{index}", query=f"合成测试查询 {index}")
        cases.append(case)
    return {
        "schema_version": 1, "dataset_id": "synthetic-unit-test", "dataset_status": "READY",
        "minimum_fully_reviewed_cases": 60,
        "snapshot": {
            "corpus": {"id": "test-only", "version": "fixture-v1", "path": "corpus.json", "sha256": digest,
                       "manifest_path": "manifest.json", "manifest_sha256": manifest_digest, "production_eligible": True},
            "chunking": {"implementation": "test-splitter", "revision": "fixed-test-revision", "config": chunk_config, "config_sha256": validator.canonical_sha256(chunk_config)},
            "models": {**{name: {"id": "test-only-model", "revision": "fixed-test-revision", "artifact_sha256": "c" * 64}
                          for name in ("embedding", "tokenizer", "answer")},
                       "reranker": {"enabled": False, "id": None, "revision": None, "artifact_sha256": None}},
            "retrieval": {"implementation": "test-retriever", "revision": "fixed-test-revision", "parameters": retrieval, "parameters_sha256": validator.canonical_sha256(retrieval)},
        },
        "freeze": {"reviewed_by": ["test-freezer"], "frozen_at": "2026-10-09T00:00:00+08:00", "code_revision": "fixed-test-revision"},
        "cases": cases,
    }


class RagGoldenGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.path = self.base / "golden.json"

    def validate(self, data):
        write_json(self.path, data)
        return validator.validate_rag_golden(self.path)

    def test_shipped_empty_template_is_pending_and_require_ready_fails(self):
        result = validator.validate_rag_golden()
        self.assertEqual("PENDING", result["status"])
        self.assertEqual(0, result["total_cases"])
        self.assertEqual(0, result["fully_reviewed_cases"])
        self.assertEqual([], result["errors"])
        self.assertTrue(result["pending_reasons"])
        with redirect_stdout(StringIO()) as output:
            self.assertEqual(0, validator.main([]))
            self.assertEqual(2, validator.main(["--require-ready"]))
        self.assertIn('"status": "PENDING"', output.getvalue())

    def test_complete_sixty_reviewed_synthetic_cases_are_ready(self):
        result = self.validate(complete_fixture(self.base))
        self.assertEqual("READY", result["status"])
        self.assertEqual(60, result["fully_reviewed_cases"])
        self.assertEqual([], result["errors"])
        self.assertEqual([], result["pending_reasons"])
        with redirect_stdout(StringIO()):
            self.assertEqual(0, validator.main([str(self.path), "--require-ready"]))

    def test_fifty_nine_reviewed_cannot_claim_ready(self):
        result = self.validate(complete_fixture(self.base, 59))
        self.assertEqual("PENDING", result["status"])
        self.assertTrue(any("at least 60" in reason for reason in result["pending_reasons"]))
        self.assertTrue(any("declared READY" in error for error in result["errors"]))

    def test_pending_case_is_not_counted_even_with_labels(self):
        data = complete_fixture(self.base)
        data["cases"][0]["review_status"] = "pending"
        result = self.validate(data)
        self.assertEqual(59, result["fully_reviewed_cases"])
        self.assertEqual("PENDING", result["status"])

    def test_sixty_reviewed_plus_one_pending_stays_pending(self):
        data = complete_fixture(self.base, 61)
        data["cases"][-1]["review_status"] = "pending"
        result = self.validate(data)
        self.assertEqual(60, result["fully_reviewed_cases"])
        self.assertEqual("PENDING", result["status"])

    def test_missing_labels_and_review_metadata_are_rejected(self):
        for field in ("routing", "answerability", "judgment_scope", "document_relevance", "chunk_relevance",
                      "supporting_facts", "expected_response", "review"):
            with self.subTest(field=field):
                data = complete_fixture(self.base)
                del data["cases"][0][field]
                result = self.validate(data)
                self.assertEqual("PENDING", result["status"])
                self.assertTrue(result["errors"])
        data = complete_fixture(self.base)
        data["cases"][0]["review"]["reviewers"] = []
        self.assertTrue(any("independent reviewers" in error for error in self.validate(data)["errors"]))

    def test_duplicate_queries_are_rejected_after_nfkc_and_whitespace(self):
        data = complete_fixture(self.base)
        data["cases"][1]["query"] = "合成测试 查询 ０"
        self.assertTrue(any("duplicate query" in error for error in self.validate(data)["errors"]))

    def test_missing_snapshots_cannot_be_ready(self):
        for section in ("corpus", "chunking", "models", "retrieval"):
            with self.subTest(section=section):
                data = complete_fixture(self.base)
                del data["snapshot"][section]
                result = self.validate(data)
                self.assertEqual("PENDING", result["status"])
                self.assertTrue(result["pending_reasons"])
        data = complete_fixture(self.base)
        data["snapshot"]["corpus"]["production_eligible"] = False
        self.assertEqual("PENDING", self.validate(data)["status"])

    def test_tampered_corpus_manifest_and_parameter_hashes_are_rejected(self):
        for field in ("sha256", "manifest_sha256"):
            with self.subTest(field=field):
                data = complete_fixture(self.base)
                data["snapshot"]["corpus"][field] = "0" * 64
                self.assertEqual("PENDING", self.validate(data)["status"])
        data = complete_fixture(self.base)
        data["snapshot"]["retrieval"]["parameters"]["top_k"] = 20
        self.assertTrue(any("parameters_sha256 does not match" in reason for reason in self.validate(data)["pending_reasons"]))

    def test_missing_corpus_and_escaping_paths_are_rejected(self):
        for path in ("not-there.json", "../corpus.json"):
            with self.subTest(path=path):
                data = complete_fixture(self.base)
                data["snapshot"]["corpus"]["path"] = path
                self.assertEqual("PENDING", self.validate(data)["status"])

    def test_unjudged_and_unknown_citations_cannot_claim_ready(self):
        data = complete_fixture(self.base)
        data["cases"][0]["chunk_relevance"][0]["grade"] = None
        self.assertTrue(self.validate(data)["errors"])
        data = complete_fixture(self.base)
        data["cases"][0]["supporting_facts"][0]["citations"][0]["chunk_id"] = "unknown"
        self.assertTrue(any("citation requires" in error for error in self.validate(data)["errors"]))

    def test_adjudication_requires_resolved_record(self):
        data = complete_fixture(self.base)
        case = data["cases"][0]
        case["review_status"] = "adjudicated"
        self.assertTrue(any("resolved adjudication" in error for error in self.validate(data)["errors"]))
        case["review"]["adjudication"] = {"reviewer": "test-adjudicator", "reviewed_at": "2026-10-09T01:00:00+08:00",
                                             "rationale": "Synthetic dispute.", "outcome": "Final fixture labels accepted.", "resolved": True}
        self.assertEqual("READY", self.validate(data)["status"])

    def test_lowering_minimum_and_json_parser_shortcuts_are_rejected(self):
        data = complete_fixture(self.base)
        data["minimum_fully_reviewed_cases"] = 1
        self.assertTrue(self.validate(data)["errors"])
        for raw in ('{"schema_version":1,"schema_version":1}', '{"x":NaN}', '{"x":1e999}', '[]'):
            with self.subTest(raw=raw):
                self.path.write_text(raw, encoding="utf-8")
                self.assertTrue(validator.validate_rag_golden(self.path)["errors"])
        self.path.write_bytes(b"\xff")
        self.assertTrue(validator.validate_rag_golden(self.path)["errors"])


if __name__ == "__main__":
    unittest.main()
