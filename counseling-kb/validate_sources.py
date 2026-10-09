"""Validate source metadata and admission gates offline using only the stdlib.

This implements the committed manifest contract, not a general JSON Schema engine.
It does not fetch sources, scan PII, or grant rights. Production admission at
runtime is enforced by the Java loader using the same manifest contract.
"""
from __future__ import annotations

import argparse
from datetime import date
import json
from pathlib import Path, PurePosixPath
import re
import stat
import sys
from urllib.parse import urlsplit

KB_ROOT = Path(__file__).resolve().parent
SCHEMA_PATH = KB_ROOT / "manifests" / "manifest.schema.json"


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _resolve_existing(path: Path, link_depth: int = 0) -> Path:
    """Resolve existing components without Windows' strict realpath call.

    lstat/readlink remain explicit so a denied or unknown reparse point cannot
    silently become a lexical path and bypass the containment checks.
    """
    if link_depth > 40:
        raise RuntimeError("too many symbolic links")
    absolute = Path(path).absolute()
    current = Path(absolute.anchor)
    for component in absolute.parts[1:]:
        if component == "..":
            current = current.parent
            continue
        current = current / component
        metadata = current.lstat()
        is_reparse = bool(
            getattr(metadata, "st_file_attributes", 0)
            & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
        )
        if stat.S_ISLNK(metadata.st_mode) or is_reparse:
            target_text = str(current.readlink())
            # Windows junction targets can use the extended-length prefix.
            if target_text.startswith("\\\\?\\UNC\\"):
                target_text = "\\\\" + target_text[8:]
            elif target_text.startswith("\\\\?\\"):
                target_text = target_text[4:]
            target = Path(target_text)
            if not target.is_absolute():
                target = current.parent / target
            current = _resolve_existing(target, link_depth + 1)
    return current


def validate_sources(manifest: Path, root: Path = KB_ROOT) -> list[str]:
    """Return all validation errors; an empty list means metadata is valid."""
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    root = _resolve_existing(root)
    errors = []
    ids = set()
    locations = {}
    count = 0
    with Path(manifest).open(encoding="utf-8") as stream:
        for line_no, line in enumerate(stream, 1):
            if not line.strip():
                continue
            count += 1
            prefix = f"line {line_no}"
            try:
                entry = json.loads(line, object_pairs_hook=_unique_object)
            except ValueError as exc:
                errors.append(f"{prefix}: invalid JSON: {exc}")
                continue
            if not isinstance(entry, dict):
                errors.append(f"{prefix}: expected an object")
                continue
            local_errors = []
            for field in schema["required"]:
                if field not in entry:
                    local_errors.append(f"missing required field: {field}")
            for field, value in entry.items():
                rule = schema["properties"].get(field)
                if rule is None:
                    local_errors.append(f"unknown field: {field}")
                elif not isinstance(value, str) or not value.strip():
                    local_errors.append(f"{field} must be a non-empty string")
                elif "enum" in rule and value not in rule["enum"]:
                    local_errors.append(f"invalid {field}: {value}")
                elif "pattern" in rule and re.search(rule["pattern"], value) is None:
                    local_errors.append(f"invalid {field} format")
            if local_errors:
                errors.extend(f"{prefix}: {error}" for error in local_errors)
                continue
            source_id = entry["id"]
            if source_id in ids:
                errors.append(f"{prefix}: duplicate id: {source_id}")
            ids.add(source_id)
            if not any(entry.get(field) for field in ("path", "runtime_path", "source_url")):
                errors.append(f"{prefix}: path, runtime_path or source_url is required")
            resolved = None
            if "path" in entry:
                relative = PurePosixPath(entry["path"])
                if (relative.is_absolute() or ".." in relative.parts
                        or "\\" in entry["path"] or ":" in entry["path"]
                        or not relative.parts):
                    errors.append(f"{prefix}: path must be a safe KB-relative POSIX path")
                else:
                    try:
                        resolved = _resolve_existing(root / entry["path"])
                        resolved.relative_to(root)
                        if not (resolved.is_file() or resolved.is_dir()):
                            raise ValueError("not a regular file or directory")
                        if resolved.is_dir() and entry["use"] != "quarantine":
                            errors.append(f"{prefix}: directory entries are quarantine inventory only")
                    except (OSError, ValueError, RuntimeError):
                        errors.append(f"{prefix}: unknown path or path outside KB: {entry['path']}")
                        resolved = None
            runtime_resolved = None
            if "runtime_path" in entry:
                runtime_relative = PurePosixPath(entry["runtime_path"])
                runtime_prefix = PurePosixPath("dk-ai-agent/src/main/resources")
                if (runtime_relative.is_absolute() or ".." in runtime_relative.parts
                        or "\\" in entry["runtime_path"] or ":" in entry["runtime_path"]
                        or runtime_relative.parts[:len(runtime_prefix.parts)] != runtime_prefix.parts):
                    errors.append(f"{prefix}: runtime_path must be a safe repository-relative resources path")
                else:
                    try:
                        runtime_base = _resolve_existing(root.parent / runtime_prefix)
                        runtime_resolved = _resolve_existing(root.parent / entry["runtime_path"])
                        runtime_resolved.relative_to(runtime_base)
                        runtime_resolved.relative_to(root.parent)
                        if not runtime_resolved.is_file():
                            raise ValueError("runtime_path must identify a file")
                    except (OSError, ValueError, RuntimeError):
                        errors.append(f"{prefix}: unknown runtime_path or path outside resources: {entry['runtime_path']}")
                        runtime_resolved = None
            if "source_url" in entry:
                try:
                    url = urlsplit(entry["source_url"])
                    if (url.scheme not in {"http", "https"} or not url.hostname
                            or url.username or url.password
                            or any(ch.isspace() for ch in entry["source_url"])):
                        raise ValueError("invalid URL")
                    url.port  # Validate malformed ports too; never fetch the URL.
                except ValueError:
                    errors.append(f"{prefix}: source_url must be an HTTP(S) URL without credentials")
            if "reviewed_at" in entry:
                try:
                    date.fromisoformat(entry["reviewed_at"])
                except ValueError:
                    errors.append(f"{prefix}: reviewed_at must be a real YYYY-MM-DD date")
            if entry["use"] == "production":
                production = schema["allOf"][0]["then"]
                for field in production["required"]:
                    if field not in entry:
                        errors.append(f"{prefix}: production requires {field}")
                for field, rule in production["properties"].items():
                    if field not in entry:
                        continue
                    value = entry[field]
                    if ("const" in rule and value != rule["const"]
                            or "enum" in rule and value not in rule["enum"]
                            or field == "license" and value.strip().lower() == "unknown"):
                        errors.append(f"{prefix}: unsafe production {field}: {value}")
                    if "pattern" in rule and re.search(rule["pattern"], value) is None:
                        errors.append(
                            f"{prefix}: production runtime_path must identify a Markdown file "
                            "directly under document/, document/professional/ or document/policy/ "
                            "within dk-ai-agent/src/main/resources/"
                        )
                if runtime_resolved is not None:
                    document_base = (root.parent / "dk-ai-agent/src/main/resources/document").resolve()
                    try:
                        runtime_parts = runtime_resolved.relative_to(document_base).parts
                        if (runtime_resolved.suffix != ".md"
                                or not (len(runtime_parts) == 1
                                        or len(runtime_parts) == 2
                                        and runtime_parts[0] in {"professional", "policy"})):
                            raise ValueError("outside production document directories")
                    except ValueError:
                        errors.append(f"{prefix}: production runtime_path resolves outside allowed Markdown resources")
                if resolved is not None:
                    domain_dir = root / entry["domain"]
                    try:
                        resolved.relative_to(domain_dir.resolve())
                    except ValueError:
                        errors.append(f"{prefix}: production path must be inside its domain directory")
            # A single artifact cannot be relabelled into both production and eval.
            keys = ([str(resolved)] if resolved is not None else [])
            if runtime_resolved is not None:
                keys.append(str(runtime_resolved))
            if "source_url" in entry:
                keys.append(entry["source_url"])
            for key in keys:
                previous = locations.setdefault(key, set())
                previous.add(entry["use"])
                if {"production", "evaluation"} <= previous:
                    errors.append(f"{prefix}: source appears in both production and evaluation")
    if count == 0:
        errors.append("manifest must contain at least one source")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", nargs="?", type=Path,
                        default=KB_ROOT / "manifests" / "sources.jsonl")
    args = parser.parse_args()
    try:
        errors = validate_sources(args.manifest)
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"Cannot validate manifest: {exc}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("Source manifest valid (Python checks metadata and format; runtime admission is enforced by the Java loader).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
