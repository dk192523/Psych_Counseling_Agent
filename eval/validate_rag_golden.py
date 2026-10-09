"""Standard-library-only RAG annotation readiness gate, never a quality benchmark.

PENDING is a valid unfinished asset (exit 0); --require-ready makes it exit 2.
Malformed data or an unsupported READY declaration exits 1.
"""

import argparse
import hashlib
import json
import math
import re
import sys
import unicodedata
from datetime import datetime
from pathlib import Path

DEFAULT_GOLDEN = Path(__file__).with_name("golden") / "rag_golden_v1.json"
MINIMUM_REVIEWED = 60
ROUTE_DOMAINS = {
    "listen": set(), "professional": {"psych-knowledge", "psych-policy"},
    "cases": {"psych-cases"}, "mixed": {"psych-knowledge", "psych-policy", "psych-cases"},
    "exam": {"psych-exams"}, "irrelevant": set(),
}
BEHAVIORS = {"answer", "clarify", "insufficient_evidence", "refuse", "crisis_support", "listen"}
ANSWERABILITY = {"answerable", "partially_answerable", "unanswerable", "not_applicable"}


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _constant(value):
    raise ValueError(f"invalid JSON constant: {value}")


def _finite_float(value):
    number = float(value)
    if not math.isfinite(number):
        raise ValueError("JSON number must be finite")
    return number


def _read(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_object,
                      parse_constant=_constant, parse_float=_finite_float)


def canonical_sha256(value):
    """Hash UTF-8 JSON with sorted keys, compact separators, and no NaN/Infinity."""
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _text(value):
    return isinstance(value, str) and bool(value.strip()) and value == value.strip()


def _sha(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def _date(value):
    if not _text(value):
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return "T" in value and parsed.tzinfo is not None
    except ValueError:
        return False


def _strings(value, nonempty=False):
    return (isinstance(value, list) and (bool(value) or not nonempty)
            and all(_text(item) for item in value) and len(value) == len(set(value)))


def _query_key(value):
    return "".join(unicodedata.normalize("NFKC", value).casefold().split())


def _mapping(value):
    return value if isinstance(value, dict) else {}


def _snapshot(data, base, problems):
    snapshot = _mapping(data.get("snapshot"))
    corpus = _mapping(snapshot.get("corpus"))
    for field in ("id", "version"):
        if not _text(corpus.get(field)):
            problems.append(f"snapshot.corpus.{field} is not fixed")
    if corpus.get("production_eligible") is not True:
        problems.append("snapshot.corpus must be production_eligible; quarantine/reference/pending are not eligible")
    artifacts = {}
    for path_key, hash_key in (("path", "sha256"), ("manifest_path", "manifest_sha256")):
        relative, digest = corpus.get(path_key), corpus.get(hash_key)
        if not _text(relative) or not _sha(digest):
            problems.append(f"snapshot.corpus.{path_key}/{hash_key} is not fixed")
            continue
        relative_path = Path(relative)
        if relative_path.is_absolute() or relative_path.drive or ".." in relative_path.parts:
            problems.append(f"snapshot.corpus.{path_key} must be relative without parent traversal")
            continue
        try:
            candidate = (base / relative_path).resolve()
            candidate.relative_to(base.resolve())
            raw = candidate.read_bytes()
            if hashlib.sha256(raw).hexdigest() != digest:
                problems.append(f"snapshot.corpus.{hash_key} does not match artifact bytes")
            else:
                artifacts[path_key] = candidate
        except (OSError, ValueError) as error:
            problems.append(f"snapshot.corpus.{path_key} cannot be verified: {error}")
    docs, chunks = {}, {}
    if "manifest_path" in artifacts:
        try:
            manifest = _read(artifacts["manifest_path"])
            if not isinstance(manifest, dict):
                raise ValueError("manifest must be an object")
            if (manifest.get("corpus_id") != corpus.get("id") or
                    manifest.get("corpus_version") != corpus.get("version") or
                    manifest.get("corpus_sha256") != corpus.get("sha256") or
                    manifest.get("production_eligible") is not True):
                problems.append("manifest does not match the fixed production corpus")
            for label, target in (("documents", docs), ("chunks", chunks)):
                records = manifest.get(label)
                if not isinstance(records, list) or not records:
                    problems.append(f"manifest.{label} must be nonempty")
                    continue
                for record in records:
                    item = _mapping(record)
                    identifier = item.get("id")
                    if not _text(identifier) or not _sha(item.get("sha256")):
                        problems.append(f"manifest.{label} requires id and sha256")
                    elif identifier in target:
                        problems.append(f"manifest.{label} has duplicate id {identifier}")
                    else:
                        target[identifier] = item
            for chunk in chunks.values():
                if not _text(chunk.get("doc_id")) or chunk.get("doc_id") not in docs:
                    problems.append("manifest chunk references unknown document")
            for doc in docs.values():
                if not _text(doc.get("domain")) or doc["domain"] not in set().union(*ROUTE_DOMAINS.values()):
                    problems.append("manifest document has invalid retrieval domain")
        except (OSError, UnicodeError, ValueError) as error:
            problems.append(f"manifest cannot be read as strict UTF-8 JSON: {error}")
    for name, config_key in (("chunking", "config"), ("retrieval", "parameters")):
        item = _mapping(snapshot.get(name))
        if not all(_text(item.get(key)) for key in ("implementation", "revision")):
            problems.append(f"snapshot.{name} implementation/revision is not fixed")
        config = item.get(config_key)
        if not isinstance(config, dict) or not config:
            problems.append(f"snapshot.{name}.{config_key} is not fixed")
        elif item.get(config_key + "_sha256") != canonical_sha256(config):
            problems.append(f"snapshot.{name}.{config_key}_sha256 does not match")
        if name == "retrieval" and isinstance(config, dict):
            if type(config.get("top_k")) is not int or config["top_k"] < 1:
                problems.append("snapshot.retrieval.parameters.top_k must be positive integer")
            threshold = config.get("similarity_threshold")
            if type(threshold) not in (int, float) or not math.isfinite(threshold):
                problems.append("snapshot.retrieval.parameters.similarity_threshold must be finite")
            for key in ("distance_metric", "query_transform", "index_type", "index_revision"):
                if not _text(config.get(key)):
                    problems.append(f"snapshot.retrieval.parameters.{key} is not fixed")
    models = _mapping(snapshot.get("models"))
    for name in ("embedding", "tokenizer", "reranker", "answer"):
        item = _mapping(models.get(name))
        if name == "reranker" and item.get("enabled") is False:
            if any(item.get(key) is not None for key in ("id", "revision", "artifact_sha256")):
                problems.append("disabled reranker must have null model fields")
            continue
        if name == "reranker" and item.get("enabled") is not True:
            problems.append("snapshot.models.reranker.enabled is not fixed")
        if not _text(item.get("id")) or not _text(item.get("revision")) or not _sha(item.get("artifact_sha256")):
            problems.append(f"snapshot.models.{name} is not fixed")
    freeze = _mapping(data.get("freeze"))
    if not _strings(freeze.get("reviewed_by"), nonempty=True) or not _date(freeze.get("frozen_at")) or not _text(freeze.get("code_revision")):
        problems.append("freeze requires reviewers, timezone-aware frozen_at and code_revision")
    return docs, chunks


def _case(record, docs, chunks):
    problems = []
    route = _mapping(record.get("routing"))
    name, domains = route.get("expected_route"), route.get("allowed_domains")
    valid_route = isinstance(name, str) and name in ROUTE_DOMAINS
    if (not valid_route or not _strings(domains) or
            set(domains) != ROUTE_DOMAINS[name] or type(route.get("should_retrieve")) is not bool or
            route["should_retrieve"] != bool(domains)):
        problems.append("routing requires a valid route, exact allowed_domains and retrieval boolean")
    answerability = record.get("answerability")
    if not isinstance(answerability, str) or answerability not in ANSWERABILITY:
        problems.append("answerability label is missing or invalid")
    if record.get("split") != "test":
        problems.append("split must be test")
    scope = _mapping(record.get("judgment_scope"))
    if scope.get("method") not in ("exhaustive_corpus", "pooled_candidates", "no_retrieval") or not _text(scope.get("notes")):
        problems.append("judgment_scope requires method and scope/coverage notes")
    doc_ids, chunk_ids = scope.get("candidate_doc_ids"), scope.get("candidate_chunk_ids")
    if not _strings(doc_ids) or not _strings(chunk_ids):
        problems.append("judgment_scope candidate ids must be unique string lists")
        doc_ids, chunk_ids = [], []
    retrieve = route.get("should_retrieve") is True
    if retrieve and (scope.get("method") == "no_retrieval" or not doc_ids or not chunk_ids):
        problems.append("retrieval queries require nonempty judged document/chunk pool")
    if not retrieve and (scope.get("method") != "no_retrieval" or doc_ids or chunk_ids):
        problems.append("non-retrieval queries require explicit no_retrieval with empty pool")
    if scope.get("method") == "exhaustive_corpus" and (set(doc_ids) != set(docs) or set(chunk_ids) != set(chunks)):
        problems.append("exhaustive_corpus must cover the complete manifest")
    grades = {}
    for label, identifiers, catalog, key in (("document_relevance", doc_ids, docs, "doc_id"),
                                             ("chunk_relevance", chunk_ids, chunks, "chunk_id")):
        judgments = record.get(label)
        seen = set()
        if not isinstance(judgments, list):
            problems.append(f"{label} labels are missing")
            continue
        for judgment in judgments:
            item = _mapping(judgment)
            identifier = item.get(key)
            if not _text(identifier):
                problems.append(f"{label} requires identifier")
                continue
            if identifier in seen or identifier not in catalog:
                problems.append(f"{label} duplicate or unknown identifier {identifier}")
            seen.add(identifier)
            if type(item.get("grade")) is not int or not 0 <= item["grade"] <= 3 or not _text(item.get("rationale")):
                problems.append(f"{label} requires relevance grade 0..3 and rationale; unjudged is not zero")
            else:
                grades[(key, identifier)] = item["grade"]
            if key == "chunk_id" and identifier in chunks and item.get("doc_id") != chunks[identifier].get("doc_id"):
                problems.append("chunk_relevance doc_id does not match manifest")
            doc_id = identifier if key == "doc_id" else item.get("doc_id")
            if not _text(doc_id) or doc_id not in docs or not isinstance(domains, list) or docs[doc_id].get("domain") not in domains:
                problems.append(f"{label} document is outside allowed_domains")
        if seen != set(identifiers):
            problems.append(f"{label} must label every pooled candidate exactly once")
    for chunk_id in chunk_ids:
        if chunk_id not in chunks or chunks[chunk_id].get("doc_id") not in doc_ids:
            problems.append("candidate chunk must belong to a pooled document")
    response = _mapping(record.get("expected_response"))
    behavior = response.get("behavior")
    if not isinstance(behavior, str) or behavior not in BEHAVIORS:
        problems.append("expected_response.behavior is missing or invalid")
    if not _strings(response.get("required_elements"), nonempty=True) or not _strings(response.get("prohibited_elements")) or not _text(response.get("notes")):
        problems.append("expected_response requires required/prohibited elements and notes")
    if behavior == "answer" and answerability not in ("answerable", "partially_answerable"):
        problems.append("answer behavior requires an answerable label")
    if behavior == "insufficient_evidence" and answerability not in ("unanswerable", "partially_answerable"):
        problems.append("insufficient_evidence conflicts with answerability")
    if not retrieve and answerability != "not_applicable":
        problems.append("non-retrieval query answerability must be not_applicable")
    facts = record.get("supporting_facts")
    if not isinstance(facts, list):
        problems.append("supporting_facts label is missing")
        facts = []
    if retrieve and behavior == "answer" and not facts:
        problems.append("answer behavior requires supporting facts and citations")
    fact_ids = set()
    for fact in facts:
        item = _mapping(fact)
        identifier = item.get("id")
        if not _text(identifier) or identifier in fact_ids or not _text(item.get("fact")):
            problems.append("supporting fact requires unique id and fact text")
        if _text(identifier):
            fact_ids.add(identifier)
        citations = item.get("citations")
        if not isinstance(citations, list) or not citations:
            problems.append("supporting fact requires citations")
            continue
        for citation in citations:
            cite = _mapping(citation)
            doc_id, chunk_id = cite.get("doc_id"), cite.get("chunk_id")
            if (not _text(doc_id) or not _text(chunk_id) or chunk_id not in chunks or
                    chunks[chunk_id].get("doc_id") != doc_id or not _text(cite.get("evidence_quote")) or
                    grades.get(("doc_id", doc_id), -1) < 2 or grades.get(("chunk_id", chunk_id), -1) < 2):
                problems.append("citation requires matching relevant doc/chunk and evidence_quote")
    review = _mapping(record.get("review"))
    if (not _text(review.get("annotator")) or not _strings(review.get("reviewers"), nonempty=True) or
            review.get("annotator") in review.get("reviewers", []) or not _date(review.get("reviewed_at"))):
        problems.append("review requires annotator, independent reviewers and timezone-aware reviewed_at")
    adjudication = review.get("adjudication")
    if record.get("review_status") == "adjudicated":
        adjudication = _mapping(adjudication)
        if (not _text(adjudication.get("reviewer")) or not _date(adjudication.get("reviewed_at")) or
                not _text(adjudication.get("rationale")) or not _text(adjudication.get("outcome")) or
                adjudication.get("resolved") is not True):
            problems.append("adjudicated requires resolved adjudication with reviewer/date/rationale/outcome")
    elif adjudication is not None:
        problems.append("reviewed case must have null adjudication; use adjudicated for resolved disputes")
    return problems


def validate_rag_golden(path=DEFAULT_GOLDEN):
    """Return computed status, errors and blockers; no model or metric is run."""
    path = Path(path)
    result = {"status": "PENDING", "fully_reviewed_cases": 0, "total_cases": 0, "errors": [], "pending_reasons": []}
    errors, blockers = result["errors"], result["pending_reasons"]
    try:
        data = _read(path)
    except (OSError, UnicodeError, ValueError) as error:
        errors.append(f"cannot read strict UTF-8 JSON: {error}")
        return result
    if not isinstance(data, dict):
        errors.append("dataset must be an object")
        return result
    if type(data.get("schema_version")) is not int or data["schema_version"] != 1:
        errors.append("schema_version must be 1")
    if not _text(data.get("dataset_id")):
        errors.append("dataset_id is required")
    if data.get("dataset_status") not in ("PENDING", "READY"):
        errors.append("dataset_status must be PENDING or READY")
    if type(data.get("minimum_fully_reviewed_cases")) is not int or data["minimum_fully_reviewed_cases"] != MINIMUM_REVIEWED:
        errors.append("minimum_fully_reviewed_cases must be 60; cannot lower the gate")
    docs, chunks = _snapshot(data, path.parent, blockers)
    records = data.get("cases")
    if not isinstance(records, list):
        errors.append("cases must be an array")
        records = []
    result["total_cases"] = len(records)
    seen_ids, seen_queries = set(), set()
    for position, record in enumerate(records, start=1):
        prefix = f"cases[{position}]"
        if not isinstance(record, dict):
            errors.append(f"{prefix}: case must be an object")
            continue
        identifier, query = record.get("id"), record.get("query")
        for field, value, seen in (("id", identifier, seen_ids), ("query", query, seen_queries)):
            if not _text(value):
                errors.append(f"{prefix}: {field} must be nonempty string")
                continue
            key = _query_key(value) if field == "query" else value
            if key in seen:
                errors.append(f"{prefix}: duplicate {field}")
            seen.add(key)
        status = record.get("review_status")
        if status not in ("pending", "reviewed", "adjudicated"):
            errors.append(f"{prefix}: invalid review_status")
        elif status == "pending":
            blockers.append(f"{prefix}: pending independent human review")
        else:
            case_errors = _case(record, docs, chunks)
            errors.extend(f"{prefix}: {message}" for message in case_errors)
            if not case_errors:
                result["fully_reviewed_cases"] += 1
    if result["fully_reviewed_cases"] < MINIMUM_REVIEWED:
        blockers.append(f"requires at least 60 fully reviewed cases; found {result['fully_reviewed_cases']}")
    if not errors and not blockers:
        result["status"] = "READY"
    elif data.get("dataset_status") == "READY":
        errors.append("declared READY but readiness requirements are not satisfied")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="人工 RAG golden 就绪门禁；READY 不代表质量指标合格")
    parser.add_argument("path", nargs="?", type=Path, default=DEFAULT_GOLDEN)
    parser.add_argument("--require-ready", action="store_true")
    args = parser.parse_args(argv)
    result = validate_rag_golden(args.path)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["errors"]:
        return 1
    if args.require_ready and result["status"] != "READY":
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
