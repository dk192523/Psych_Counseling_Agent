#!/usr/bin/env python3
"""Read-only, aggregate-only preflight for an existing Compose PostgreSQL volume.

Python never opens, parses or prints .env. Compose reads it to resolve config;
the resolved output stays in process memory and diagnostics are suppressed.
No container lifecycle calls, restore, adoption or schema mutation.
Subprocess diagnostics are deliberately suppressed: PostgreSQL/Docker errors can
contain credentials, SQL values or private filesystem paths.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys


class Blocked(Exception):
    pass


TABLES = {
    "psych_user": "id username password_hash role status created_at updated_at last_login_at disabled_at disabled_reason",
    "psych_conversation": "id title owner_id created_at updated_at",
    "psych_chat_message": "id conversation_id role content client_msg_id created_at",
    "psych_conversation_memory": "conversation_id digest covered_until_message_id covered_message_count digest_chars updated_at",
    "psych_chat_turn": "conversation_id client_msg_id request_hash status answer effective_mode fallback updated_at",
    "psych_conversation_tombstone": "conversation_id owner_id deleted_at",
    "vector_store": "id content metadata embedding",
}
ROW_NAMES = ["users", "conversations", "messages", "memories", "turns", "tombstones", "knowledge_vectors"]
CHECKS = {
    "owner_null": "SELECT count(*) FROM public.psych_conversation WHERE owner_id IS NULL",
    "owner_mismatch": "SELECT count(*) FROM public.psych_conversation c LEFT JOIN public.psych_user u ON c.owner_id=u.id WHERE c.owner_id IS NOT NULL AND u.id IS NULL",
    "orphan_messages": "SELECT count(*) FROM public.psych_chat_message x LEFT JOIN public.psych_conversation c ON c.id=x.conversation_id WHERE c.id IS NULL",
    "orphan_memories": "SELECT count(*) FROM public.psych_conversation_memory x LEFT JOIN public.psych_conversation c ON c.id=x.conversation_id WHERE c.id IS NULL",
    "orphan_turns": "SELECT count(*) FROM public.psych_chat_turn x LEFT JOIN public.psych_conversation c ON c.id=x.conversation_id WHERE c.id IS NULL",
    "tombstone_owner_null": "SELECT count(*) FROM public.psych_conversation_tombstone WHERE owner_id IS NULL",
    "tombstone_live_collision": "SELECT count(*) FROM public.psych_conversation_tombstone t JOIN public.psych_conversation c ON c.id=t.conversation_id",
    "duplicate_message_keys": "SELECT count(*) FROM (SELECT 1 FROM public.psych_chat_message WHERE client_msg_id IS NOT NULL GROUP BY conversation_id,client_msg_id HAVING count(*)>1) x",
    "duplicate_turn_keys": "SELECT count(*) FROM (SELECT 1 FROM public.psych_chat_turn GROUP BY conversation_id,client_msg_id HAVING count(*)>1) x",
    "invalid_turn_status": "SELECT count(*) FROM public.psych_chat_turn WHERE status IS NULL OR status NOT IN ('RUNNING','COMPLETED','FAILED','INTERRUPTED')",
    "multiple_running_turns": "SELECT count(*) FROM (SELECT 1 FROM public.psych_chat_turn WHERE status='RUNNING' GROUP BY conversation_id HAVING count(*)>1) x",
    # Message retention can legitimately remove the row identified by a digest's
    # watermark. Its absence alone is not corruption and must not block upgrades.
    "invalid_memory_watermark": "SELECT count(*) FROM public.psych_conversation_memory WHERE covered_until_message_id<0 OR covered_message_count<0 OR digest_chars<0",
    "vector_dimension_mismatch": "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.vector_store'::regclass AND attname='embedding' AND NOT attisdropped AND format_type(atttypid,atttypmod)='vector(384)') THEN 0 ELSE 1 END",
    "missing_validated_foreign_keys": "SELECT count(*) FROM (VALUES ('psych_conversation','owner_id','psych_user','id'),('psych_chat_message','conversation_id','psych_conversation','id'),('psych_conversation_memory','conversation_id','psych_conversation','id'),('psych_chat_turn','conversation_id','psych_conversation','id')) AS r(src,col,dst,dstcol) WHERE NOT EXISTS (SELECT 1 FROM pg_constraint k JOIN pg_attribute a ON a.attrelid=k.conrelid AND k.conkey=ARRAY[a.attnum] JOIN pg_attribute b ON b.attrelid=k.confrelid AND k.confkey=ARRAY[b.attnum] WHERE k.contype='f' AND k.convalidated AND k.conrelid=to_regclass('public.'||r.src) AND k.confrelid=to_regclass('public.'||r.dst) AND a.attname=r.col AND b.attname=r.dstcol)",
    "missing_primary_keys": "SELECT count(*) FROM (VALUES ('psych_user'),('psych_conversation'),('psych_chat_message'),('psych_conversation_memory'),('psych_chat_turn'),('psych_conversation_tombstone'),('vector_store')) r(tab) WHERE NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.contype='p' AND k.convalidated AND k.conrelid=to_regclass('public.'||r.tab))",
}
INFO = {
    # Deleted users legitimately leave tombstones; this count is informational.
    "tombstone_owner_absent": "SELECT count(*) FROM public.psych_conversation_tombstone t LEFT JOIN public.psych_user u ON u.id=t.owner_id WHERE t.owner_id IS NOT NULL AND u.id IS NULL",
    "running_turns": "SELECT count(*) FROM public.psych_chat_turn WHERE status='RUNNING'",
}


def run(args: list[str], *, stdin: str | None = None, env: dict | None = None) -> str:
    try:
        result = subprocess.run(args, input=stdin, text=True, encoding="utf-8", errors="strict",
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                timeout=180, check=False, env=env)
    except (OSError, subprocess.TimeoutExpired, UnicodeError):
        raise Blocked("命令不可用或超时；原始诊断已隐藏，请在受控环境排查。") from None
    if result.returncode:
        raise Blocked("Compose、容器或只读查询失败；未执行写入，原始诊断已隐藏。")
    return result.stdout


def safe_name(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}", value):
        raise Blocked("项目名或卷名无效，不能确认原数据卷。")
    return value


def current_container(root: Path) -> tuple[str, str, str]:
    compose_file = root / "dk-ai-agent/docker-compose.yml"
    # Match manage.sh: exported interpolation variables must not override .env.
    text = compose_file.read_text(encoding="utf-8")
    env = dict(os.environ)
    for key in re.findall(r"\$\{([A-Za-z_][A-Za-z0-9_]*)", text):
        env.pop(key, None)
    command = ["docker", "compose", "--env-file", str(root / "dk-ai-agent/.env"), "-f", str(compose_file)]
    # Python never opens, parses or prints .env; Compose reads it to resolve config.
    # The resolved output stays in process memory, is never printed or persisted,
    # and subprocess diagnostics are suppressed by run().
    config = json.loads(run(command + ["config", "--format", "json"], env=env))
    project = safe_name(config.get("name"))
    volume_config = config.get("volumes", {}).get("postgres_data", {})
    volume = safe_name(volume_config.get("name"))
    mounts = config.get("services", {}).get("postgres", {}).get("volumes", [])
    if not any(m.get("type") == "volume" and m.get("source") == "postgres_data"
               and m.get("target") == "/var/lib/postgresql/data" for m in mounts):
        raise Blocked("Compose 的 postgres_data 挂载不符合原地升级契约。")
    ids = run(command + ["ps", "--status", "running", "-q", "postgres"], env=env).split()
    if len(ids) != 1 or not re.fullmatch(r"[a-f0-9]{12,64}", ids[0]):
        raise Blocked("当前 Compose 必须有且仅有一个已运行的 postgres；不会启动新容器。")
    container = ids[0]
    template = '{{json .Config.Labels}}\n{{json .Mounts}}\n{{json .State.Running}}'
    observed = run(["docker", "inspect", "--format", template, container]).splitlines()
    if len(observed) != 3:
        raise Blocked("无法确认正在运行的容器元数据。")
    labels, actual_mounts, running = [json.loads(line) for line in observed]
    if running is not True or labels.get("com.docker.compose.project") != project or labels.get("com.docker.compose.service") != "postgres":
        raise Blocked("容器状态或 Compose 项目标签不符，禁止使用其他项目数据库。")
    data_mounts = [m for m in actual_mounts if m.get("Destination") == "/var/lib/postgresql/data"]
    if len(data_mounts) != 1 or data_mounts[0].get("Type") != "volume" or data_mounts[0].get("Name") != volume:
        raise Blocked("postgres 容器没有挂载当前项目预期的 postgres_data named volume。")
    volume_labels = json.loads(run(["docker", "volume", "inspect", "--format", '{{json .Labels}}', volume])) or {}
    if volume_labels.get("com.docker.compose.project") != project or volume_labels.get("com.docker.compose.volume") != "postgres_data":
        raise Blocked("named volume 的 Compose 项目标签不符或缺失；需人工核对既有卷，不能创建替代卷。")
    return container, project, volume


def literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def table_exists(table: str) -> str:
    return f"EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname={literal(table)} AND c.relkind='r')"


def column_exists(table: str, column: str) -> str:
    return f"EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name={literal(table)} AND column_name={literal(column)})"


def build_sql() -> tuple[str, dict[str, str]]:
    expected = {"postgres_version_num": "version", "pgvector_version": "extension", "schema_fingerprint": "hash", "read_only": "readonly", "snapshot_complete": "one"}
    statements = ["BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;",
                  "SET LOCAL statement_timeout='30s';", "SET LOCAL lock_timeout='5s';",
                  "SELECT 'read_only' || E'\\t' || current_setting('transaction_read_only');",
                  "SELECT 'postgres_version_num' || E'\\t' || current_setting('server_version_num');",
                  "SELECT 'pgvector_version' || E'\\t' || COALESCE((SELECT extversion FROM pg_extension WHERE extname='vector'),'missing');",
                  "SELECT 'schema_fingerprint' || E'\\t' || md5(COALESCE(string_agg(table_name||'.'||column_name||':'||udt_name||':'||is_nullable, ',' ORDER BY table_name,ordinal_position),'')) FROM information_schema.columns WHERE table_schema='public' AND table_name IN (" + ",".join(literal(t) for t in TABLES) + ");"]
    predicates = []
    for table, columns in TABLES.items():
        key = "missing_table." + table
        expected[key] = "zero"
        predicate = table_exists(table)
        predicates.append(predicate)
        statements.append(f"SELECT {literal(key)} || E'\\t' || CASE WHEN {predicate} THEN '0' ELSE '1' END;")
        for column in columns.split():
            key = f"missing_column.{table}.{column}"
            expected[key] = "zero"
            predicate = column_exists(table, column)
            predicates.append(predicate)
            statements.append(f"SELECT {literal(key)} || E'\\t' || CASE WHEN {predicate} THEN '0' ELSE '1' END;")
    ready = " AND ".join(predicates)

    def guarded(key: str, query: str, guard: str, kind: str) -> None:
        expected[key] = kind
        sql = f"SELECT {literal(key)} || E'\\t' || ({query})::text;"
        unavailable = f"SELECT {literal(key)} || E'\\t' || 'unavailable';"
        statements.append(f"SELECT CASE WHEN {guard} THEN {literal(sql)} ELSE {literal(unavailable)} END\n\\gexec")

    for table, name in zip(TABLES, ROW_NAMES):
        guarded("rows." + name, f"SELECT count(*) FROM public.{table}", table_exists(table), "count")
    for name, query in CHECKS.items():
        guarded("blocker." + name, query, ready, "zero")
    for name, query in INFO.items():
        guarded("info." + name, query, ready, "count")
    statements += ["SELECT 'snapshot_complete' || E'\\t' || '1';", "ROLLBACK;"]
    return "\n".join(statements) + "\n", expected


def parse_report(output: str, expected: dict[str, str]) -> dict[str, str]:
    report = {}
    for line in output.splitlines():
        parts = line.split("\t")
        if len(parts) != 2 or parts[0] not in expected or parts[0] in report:
            raise Blocked("只读报告格式异常；原始输出已隐藏。")
        key, value = parts
        kind = expected[key]
        valid = bool(re.fullmatch(r"[0-9]{1,20}|unavailable", value)) if kind in ("zero", "count") else {
            "version": bool(re.fullmatch(r"[0-9]{5,6}", value)),
            "extension": bool(re.fullmatch(r"[0-9]{1,3}(?:\.[0-9]{1,3}){1,3}|missing", value)),
            "hash": bool(re.fullmatch(r"[a-f0-9]{32}", value)),
            "readonly": value == "on", "one": value == "1",
        }.get(kind, False)
        if not valid:
            raise Blocked("只读报告值异常；原始输出已隐藏。")
        report[key] = value
    if report.keys() != expected.keys():
        raise Blocked("只读报告不完整；不能判定 READY。")
    return report


def main(root: Path | None = None) -> int:
    try:
        container, project, volume = current_container(root or Path(__file__).resolve().parent)
        print(f"compose_project={project}\npostgres_data_volume={volume}")
        sql, expected = build_sql()
        shell = ('test -s /var/lib/postgresql/data/PG_VERSION || exit 41; '
                 'export PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=30000 -c lock_timeout=5000"; '
                 'export PGCONNECT_TIMEOUT=5; '
                 'exec psql -X -w -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"')
        report = parse_report(run(["docker", "exec", "-i", container, "sh", "-c", shell], stdin=sql), expected)
        print("schema_version=unversioned-initializers (no migration version registry)")
        for key, value in report.items():
            print(f"{key}={value}")
        blockers = [k for k, kind in expected.items() if kind == "zero" and report[k] != "0"]
        if report["pgvector_version"] == "missing":
            blockers.append("pgvector_missing")
        if int(report["postgres_version_num"]) // 10000 != 16:
            blockers.append("postgres_major_requires_separate_upgrade")
        if report["rows.users"] in ("0", "unavailable"):
            blockers.append("existing_user_database_empty_or_unavailable")
        if blockers:
            print("BLOCKED: " + ", ".join(blockers))
            print("需人工确认 schema/数据归属映射或原数据卷；禁止自动领养无主会话。预检未修改数据库。")
            return 2
        print("READY: 只读快照未发现阻塞；仍须加密备份、隔离恢复演练并停旧后启新。")
        return 0
    except Blocked as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2
    except (ValueError, TypeError, KeyError, AttributeError, OSError):
        # Never interpolate unexpected exception text: it may include private data.
        print("BLOCKED: 无法安全完成当前 Compose 数据卷的只读预检；需人工核对项目、命名卷、schema 和归属映射。原始诊断已隐藏。", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
