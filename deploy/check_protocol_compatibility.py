#!/usr/bin/env python3
"""Check the version-1 release contract without importing or running product code.

This is a bounded source-layout guard, not a proof of wire compatibility. A
deliberate protocol upgrade must update this guard and its compatibility policy.
"""

import ast
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parent.parent
EXPECTED_VERSION = "1"
CLIENT = "desktop-client/src/main/java/com/dk/psychclient/LocalServerEndpoint.java"
BACKEND = "dk-ai-agent/src/main/resources/application.yml"
JAVA_WORKER = "dk-ai-agent/src/main/java/com/dk/dkaiagent/integration/aiworker/AiWorkerContracts.java"
PYTHON_WORKER = "ai-worker/src/dk_ai_worker/models.py"
BUILD = "deploy/desktop/build-desktop.ps1"
WORKER_MODELS = {
    "PlanRequest", "PlanResponse", "RefineRequest", "RefineResponse",
    "ConsolidateRequest", "ConsolidateResponse", "RecallRequest", "RecallResponse",
}


class CompatibilityError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CompatibilityError(message)


def read(relative_path: str) -> str:
    try:
        with (ROOT / relative_path).open("rb") as source:
            data = source.read(512 * 1024 + 1)
        require(len(data) <= 512 * 1024, f"{relative_path}: source exceeds validation size limit")
        return data.decode("utf-8-sig")
    except (OSError, UnicodeError):
        raise CompatibilityError(f"{relative_path}: cannot read source as UTF-8") from None


def single(pattern: str, source: str, label: str, flags: int = 0) -> str:
    matches = re.findall(pattern, source, flags)
    require(len(matches) == 1, f"{label}: expected one recognizable declaration; restore it or update this guard explicitly")
    return matches[0]


def java_version(source: str, declaration: str, label: str) -> None:
    # Ignore comments, while preserving string literals containing comment markers.
    tokens = r'"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*'
    source = re.sub(tokens, lambda match: match[0] if match[0].startswith('"') else " ", source)
    version = single(declaration, source, label)
    require(version == EXPECTED_VERSION, f"{label}: expected compatibility version 1; coordinate all participants before a protocol upgrade")


def backend_version(source: str) -> None:
    # Only parse the existing indentation-based mapping, never load arbitrary YAML.
    parents: list[tuple[int, str]] = []
    versions = []
    seen = set()
    for line in source.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        require("\t" not in line, f"{BACKEND}: tabs are unsupported; use space indentation")
        match = re.fullmatch(r"( *)([A-Za-z0-9_-]+):(?:\s+(.*))?", line)
        if not match:
            # The file also contains lists and block scalars outside app.system.
            require(tuple(key for _, key in parents)[:2] != ("app", "system"), f"{BACKEND}: unsupported app.system mapping syntax; update this guard explicitly")
            continue
        indent = len(match[1])
        while parents and parents[-1][0] >= indent:
            parents.pop()
        path = tuple(key for _, key in parents) + (match[2],)
        if path in {("app",), ("app", "system"), ("app", "system", "protocol-version")}:
            require(path not in seen, f"{BACKEND}: duplicate app.system protocol mapping")
            seen.add(path)
        if path == ("app", "system", "protocol-version"):
            versions.append(match[3])
        if not match[3] or match[3].startswith("#"):
            parents.append((indent, match[2]))
    require(len(versions) == 1, f"{BACKEND}: app.system.protocol-version is missing or ambiguous")
    require(versions[0] == "${APP_PROTOCOL_VERSION:1}", f"{BACKEND}: preserve APP_PROTOCOL_VERSION with compatibility default 1")


def python_versions(source: str) -> None:
    try:
        tree = ast.parse(source, filename=PYTHON_WORKER)
    except (SyntaxError, ValueError):
        raise CompatibilityError(f"{PYTHON_WORKER}: invalid Python syntax") from None
    found = set()
    for model in (node for node in tree.body if isinstance(node, ast.ClassDef)):
        fields = [node for node in model.body if isinstance(node, ast.AnnAssign)
                  and isinstance(node.target, ast.Name) and node.target.id == "contract_version"]
        if not fields:
            continue
        require(model.name not in found and len(fields) == 1, f"{PYTHON_WORKER}: duplicate contract_version in {model.name}")
        found.add(model.name)
        field = fields[0]
        annotation = field.annotation
        valid = (isinstance(annotation, ast.Subscript)
                 and isinstance(annotation.value, ast.Name)
                 and annotation.value.id == "Literal"
                 and isinstance(annotation.slice, ast.Constant)
                 and annotation.slice.value == EXPECTED_VERSION)
        require(valid, f"{PYTHON_WORKER}: {model.name}.contract_version must be Literal[\"1\"]")
        if field.value is not None:
            require(isinstance(field.value, ast.Constant) and field.value.value == EXPECTED_VERSION,
                    f"{PYTHON_WORKER}: {model.name}.contract_version default must be \"1\"")
        require(not model.name.endswith("Response") or field.value is not None,
                f"{PYTHON_WORKER}: {model.name} must retain its contract_version default")
    require(WORKER_MODELS <= found, f"{PYTHON_WORKER}: expected request/response contract_version declarations are missing")


def build_extraction(source: str, client_source: str) -> None:
    # Pin the small extraction chain. Do not execute PowerShell from the checker.
    source = "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("#"))
    source_path = single(r"(?im)^\$protocolSource\s*=\s*(.+)$", source, BUILD)
    require(source_path.strip() == "[IO.File]::ReadAllText((Join-Path $ClientRoot 'src/main/java/com/dk/psychclient/LocalServerEndpoint.java'), [Text.Encoding]::UTF8)",
            f"{BUILD}: protocol extraction must read the Client endpoint source as UTF-8")
    pattern = single(r"(?im)^\$protocolMatch\s*=\s*\[regex\]::Match\(\$protocolSource, '([^']+)'\)\s*$", source, BUILD)
    require(pattern == r"PROTOCOL_VERSION\s*=\s*(\d+)\s*;", f"{BUILD}: protocol extraction pattern changed; update this guard explicitly")
    extracted = single(pattern, client_source, f"{BUILD} Client protocol extraction")
    require(extracted == EXPECTED_VERSION, f"{BUILD}: extracted protocol must be compatibility version 1")
    assignment = single(r"(?im)^\$ProtocolVersion\s*=\s*(.+)$", source, BUILD)
    require(assignment.strip() == "[int]$protocolMatch.Groups[1].Value", f"{BUILD}: manifest protocol must use the extracted Client version")
    require(len(re.findall(r"\bprotocolVersion\s*=\s*\$ProtocolVersion\b", source, re.I)) == 1,
            f"{BUILD}: shared product manifest must retain protocolVersion = $ProtocolVersion")


def main() -> int:
    try:
        client_source = read(CLIENT)
        java_version(client_source, r"public\s+static\s+final\s+int\s+PROTOCOL_VERSION\s*=\s*(\d+)\s*;", CLIENT)
        backend_version(read(BACKEND))
        java_version(read(JAVA_WORKER), r'public\s+static\s+final\s+String\s+VERSION\s*=\s*"([^"\n]+)"\s*;', JAVA_WORKER)
        python_versions(read(PYTHON_WORKER))
        build_extraction(read(BUILD), client_source)
    except CompatibilityError as error:
        # Report only fixed paths and field names, never configuration contents.
        print(f"Protocol compatibility check failed: {error}", file=sys.stderr)
        return 1
    print("Protocol compatibility check passed: Client, Server default, Worker and desktop manifests use version 1.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
