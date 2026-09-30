"""校验路由 golden JSONL 的结构与域边界；不调用模型、不证明标注正确。

仓库根目录：python eval/validate_golden.py [eval/golden/routing_golden_v1.jsonl]
成功返回 0；文件、JSON 或契约错误返回 1。仅依赖 Python 标准库。
"""

import argparse
import json
import sys
import unicodedata
from pathlib import Path


DEFAULT_GOLDEN = Path(__file__).with_name("golden") / "routing_golden_v1.jsonl"
ROUTE_DOMAINS = {
    "listen": frozenset(),
    "professional": frozenset({"psych-knowledge", "psych-policy"}),
    "cases": frozenset({"psych-cases"}),
    "mixed": frozenset({"psych-knowledge", "psych-policy", "psych-cases"}),
    "exam": frozenset({"psych-exams"}),
    "irrelevant": frozenset(),
}
RETRIEVAL_DOMAINS = frozenset().union(*ROUTE_DOMAINS.values())
REQUIRED_FIELDS = {"id", "query", "expected_route", "allowed_domains", "should_retrieve", "notes"}


def _unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError(f"invalid JSON constant: {value}")


def _query_key(query: str) -> str:
    """全半角、大小写及空白差异不应制造新的评测样本。"""
    return "".join(unicodedata.normalize("NFKC", query).casefold().split())


def validate_golden(path: str | Path) -> list[str]:
    """返回包含行号的错误列表；空列表表示结构有效，不代表人工审核通过。"""
    path = Path(path)
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        return [f"{path}: cannot read UTF-8 JSONL: {error}"]
    if not lines:
        return [f"{path}: dataset must not be empty"]

    errors = []
    seen_ids = {}
    seen_queries = {}
    for line_number, line in enumerate(lines, start=1):
        prefix = f"{path}:{line_number}"
        try:
            record = json.loads(line, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
        except ValueError as error:
            errors.append(f"{prefix}: invalid JSON: {error}")
            continue
        if not isinstance(record, dict):
            errors.append(f"{prefix}: each line must be an object")
            continue
        missing = REQUIRED_FIELDS - record.keys()
        if missing:
            errors.append(f"{prefix}: missing required fields: {', '.join(sorted(missing))}")

        identifier = record.get("id")
        if not isinstance(identifier, str) or not identifier.strip() or identifier != identifier.strip():
            errors.append(f"{prefix}: id must be a nonempty string without surrounding whitespace")
        elif identifier in seen_ids:
            errors.append(f"{prefix}: duplicate id (first at line {seen_ids[identifier]})")
        else:
            seen_ids[identifier] = line_number

        query = record.get("query")
        if not isinstance(query, str) or not query.strip():
            errors.append(f"{prefix}: query must be a nonempty string")
        else:
            query_key = _query_key(query)
            if query_key in seen_queries:
                errors.append(f"{prefix}: duplicate query (first at line {seen_queries[query_key]})")
            else:
                seen_queries[query_key] = line_number

        route = record.get("expected_route")
        valid_route = isinstance(route, str) and route in ROUTE_DOMAINS
        if not valid_route:
            errors.append(f"{prefix}: invalid expected_route")

        domains = record.get("allowed_domains")
        valid_domains = isinstance(domains, list) and all(
            isinstance(domain, str) and domain in RETRIEVAL_DOMAINS for domain in domains
        )
        if not valid_domains:
            errors.append(f"{prefix}: allowed_domains must be an array of retrieval domains; psych-prompts is forbidden")
        else:
            if len(domains) != len(set(domains)):
                errors.append(f"{prefix}: duplicate allowed_domains")
            if valid_route and set(domains) != ROUTE_DOMAINS[route]:
                errors.append(f"{prefix}: allowed_domains does not match expected_route")

        should_retrieve = record.get("should_retrieve")
        if type(should_retrieve) is not bool:
            errors.append(f"{prefix}: should_retrieve must be boolean")
        elif valid_domains and should_retrieve != bool(domains):
            errors.append(f"{prefix}: should_retrieve must equal whether allowed_domains is nonempty")

        notes = record.get("notes")
        if not isinstance(notes, str) or not notes.strip():
            errors.append(f"{prefix}: notes must be a nonempty string")
        if "review_status" in record and record["review_status"] not in ("pending", "reviewed", "adjudicated"):
            errors.append(f"{prefix}: invalid review_status")
    return errors


class _Parser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise ValueError(message)


def main(argv: list[str] | None = None) -> int:
    parser = _Parser(description="校验路由 golden JSONL；结构通过不代表质量合格或人工审核完成")
    parser.add_argument("path", nargs="?", type=Path, default=DEFAULT_GOLDEN)
    try:
        args = parser.parse_args(argv)
    except ValueError as error:
        print(f"参数错误：{error}", file=sys.stderr)
        return 1
    errors = validate_golden(args.path)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"结构校验通过：{args.path}；未评估标签正确性或检索质量。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
