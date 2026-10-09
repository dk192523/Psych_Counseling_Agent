#!/usr/bin/env python3
"""Read-only, aggregate-only Compose mount inventory and PostgreSQL preflight.

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
# PostgreSQL DDL contract from UserRepository, ConversationHistoryService and
# ChatTurnService. owner_id is nullable in DDL; owner_null separately checks data.
# The vector-store dependency owns its column contract; its existing dimension
# check and exact id primary key remain mandatory here.
COLUMN_CONTRACT = {
    "psych_user": {
        "id": ("bigint", False, "serial"),
        "username": ("character varying(64)", False, None),
        "password_hash": ("character varying(100)", False, None),
        "role": ("character varying(16)", False, "'USER'::character varying"),
        "status": ("character varying(16)", False, "'ACTIVE'::character varying"),
        "created_at": ("timestamp with time zone", False, "CURRENT_TIMESTAMP"),
        "updated_at": ("timestamp with time zone", False, "CURRENT_TIMESTAMP"),
        "last_login_at": ("timestamp with time zone", True, None),
        "disabled_at": ("timestamp with time zone", True, None),
        "disabled_reason": ("character varying(200)", True, None),
    },
    "psych_conversation": {
        "id": ("character varying(64)", False, None),
        "title": ("character varying(120)", False, None),
        "owner_id": ("bigint", True, None),
        "created_at": ("timestamp with time zone", False, "CURRENT_TIMESTAMP"),
        "updated_at": ("timestamp with time zone", False, "CURRENT_TIMESTAMP"),
    },
    "psych_chat_message": {
        "id": ("bigint", False, "serial"),
        "conversation_id": ("character varying(64)", False, None),
        "role": ("character varying(16)", False, None),
        "content": ("text", False, None),
        "client_msg_id": ("character varying(64)", True, None),
        "created_at": ("timestamp with time zone", False, "CURRENT_TIMESTAMP"),
    },
    "psych_conversation_memory": {
        "conversation_id": ("character varying(64)", False, None),
        "digest": ("text", False, "''::text"),
        "covered_until_message_id": ("bigint", False, "0"),
        "covered_message_count": ("integer", False, "0"),
        "digest_chars": ("integer", False, "0"),
        "updated_at": ("timestamp with time zone", False, "CURRENT_TIMESTAMP"),
    },
    "psych_chat_turn": {
        "conversation_id": ("character varying(64)", False, None),
        "client_msg_id": ("character varying(64)", False, None),
        "request_hash": ("character varying(64)", False, None),
        "status": ("character varying(16)", False, None),
        "answer": ("text", False, "''::text"),
        "effective_mode": ("character varying(16)", False, "'standard'::character varying"),
        "fallback": ("boolean", False, "false"),
        "updated_at": ("timestamp with time zone", False, "CURRENT_TIMESTAMP"),
    },
    "psych_conversation_tombstone": {
        "conversation_id": ("character varying(64)", False, None),
        "owner_id": ("bigint", False, None),
        "deleted_at": ("timestamp with time zone", False, "CURRENT_TIMESTAMP"),
    },
}
# Presence alone blocks baseline classification; this preflight does not infer
# migration state from or disclose dependency-owned history rows.
MIGRATION_HISTORY_TABLES = ("psych_schema_history", "flyway_schema_history")
PRIMARY_KEYS = {table: ("id",) for table in ("psych_user", "psych_conversation", "psych_chat_message", "vector_store")}
PRIMARY_KEYS.update({"psych_conversation_memory": ("conversation_id",),
                     "psych_chat_turn": ("conversation_id", "client_msg_id"),
                     "psych_conversation_tombstone": ("conversation_id",)})
FOREIGN_KEYS = (
    ("psych_conversation", "owner_id", "psych_user", "id", "a"),
    ("psych_chat_message", "conversation_id", "psych_conversation", "id", "c"),
    ("psych_conversation_memory", "conversation_id", "psych_conversation", "id", "c"),
    ("psych_chat_turn", "conversation_id", "psych_conversation", "id", "c"),
)
# Index names are deliberately irrelevant; catalog shapes establish behavior.
UNIQUE_INDEXES = (
    ("psych_user", ("username",), None),
    ("psych_chat_message", ("conversation_id", "client_msg_id"), None),
    ("psych_chat_turn", ("conversation_id",), "((status)::text = 'RUNNING'::text)"),
)
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


KNOWN_MOUNTS = {
    ("postgres", "postgres_data", "/var/lib/postgresql/data"): "database",
    ("backend", "onnx_cache", "/tmp/spring-ai-onnx-generative"): "rebuildable_cache",
    ("backend", "djl_cache", "/home/app/.djl.ai"): "rebuildable_cache",
}
KNOWN_STORES = {"postgres_data": "database", "onnx_cache": "rebuildable_cache", "djl_cache": "rebuildable_cache"}


def mount_counts() -> dict[str, int]:
    return dict.fromkeys(("total", "database", "rebuildable_cache", "tmpfs", "unknown_volume", "bind", "unsupported"), 0)


def count_mount(counts: dict[str, int], kind: str) -> None:
    counts["total"] += 1
    counts[kind] += 1


def print_inventory(section: str, counts: dict[str, int]) -> None:
    # Keys are fixed by the program; never emit service names, targets or sources.
    for key in sorted(counts):
        print(f"mount_inventory.{section}.{key}={counts[key]}")


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
    services = config.get("services", {})
    volumes = config.get("volumes", {})
    declared, stores, observed_counts = mount_counts(), mount_counts(), mount_counts()
    expected_by_service = {}
    blockers = set()
    for source, definition in volumes.items():
        definition = definition or {}
        kind = KNOWN_STORES.get(source, "unknown_volume")
        if definition.get("driver", "local") != "local" or definition.get("driver_opts"):
            kind = "unsupported"
        count_mount(stores, kind)
        if kind in ("unknown_volume", "unsupported"):
            blockers.add("Compose 存在未分类的持久化卷或存储驱动配置，需先人工盘点。")
    for service, definition in sorted(services.items()):
        expected = {}
        mounts = list(definition.get("volumes", []))
        # Compose's service-level tmpfs shorthand is separate from volumes.
        tmpfs = definition.get("tmpfs", [])
        if isinstance(tmpfs, str):
            tmpfs = [tmpfs]
        for entry in tmpfs:
            target = entry.split(":", 1)[0] if isinstance(entry, str) else entry.get("target")
            mounts.append({"type": "tmpfs", "target": target})
        for mount in mounts:
            mount_type, target, source = mount.get("type"), mount.get("target"), mount.get("source")
            kind, actual_name = "unsupported", None
            if mount_type == "volume":
                kind = KNOWN_MOUNTS.get((service, source, target), "unknown_volume")
                if kind != "unknown_volume":
                    actual_name = safe_name((volumes.get(source) or {}).get("name"))
            elif mount_type in ("bind", "tmpfs"):
                kind = mount_type
            count_mount(declared, kind)
            if not isinstance(target, str) or not target.startswith("/") or target in expected:
                blockers.add("Compose 挂载目标无效或重复，无法完整核对。")
            else:
                expected[target] = (mount_type, actual_name, kind, source)
            if kind in ("unknown_volume", "bind", "unsupported"):
                blockers.add("Compose 存在未知持久化挂载、bind mount 或不支持的挂载，需先人工盘点；路径已隐藏。")
        # These can create mounts outside service.volumes; fail closed rather
        # than mistake a partial inventory for complete migration coverage.
        for field in ("configs", "secrets", "volumes_from"):
            for _ in definition.get(field, []):
                count_mount(declared, "unsupported")
                blockers.add("Compose 存在额外配置、密钥或继承挂载，当前预检不能证明其迁移覆盖。")
        expected_by_service[service] = expected
    print_inventory("declared", declared)
    print_inventory("stores", stores)
    if expected_by_service.get("postgres", {}).get("/var/lib/postgresql/data") != (
            "volume", (volumes.get("postgres_data") or {}).get("name"), "database", "postgres_data"):
        blockers.add("Compose 的 postgres_data 挂载不符合原地升级契约。")
    volume = safe_name((volumes.get("postgres_data") or {}).get("name"))
    # Compose ps is scoped to declared services and can omit running orphans.
    # Read only service-label metadata for this already-validated project;
    # unknown/missing labels cannot establish that a stale writer is stopped.
    project_services = run([
        "docker", "ps", "--filter", "label=com.docker.compose.project=" + project,
        "--filter", "status=running", "--format",
        '{{json (.Label "com.docker.compose.service")}}',
    ])
    if len(project_services) > 65536:
        raise Blocked("项目运行容器清单超出预检上限；原始元数据已隐藏。")
    service_rows = project_services.splitlines()
    if len(service_rows) > 256:
        raise Blocked("项目运行容器数量超出预检上限；需先人工盘点。")
    for row in service_rows:
        running_service = json.loads(row)
        if not isinstance(running_service, str) or running_service not in expected_by_service:
            raise Blocked("当前 Compose 项目存在未声明服务的运行容器；请先人工确认并停止旧实例，服务标签已隐藏。")
    template = '{{json .Config.Labels}}\n{{json .Mounts}}\n{{json .State.Running}}'
    container = None
    verified_volumes = set()
    running_containers = services_without_running = 0
    for service, expected in sorted(expected_by_service.items()):
        ids = run(command + ["ps", "--status", "running", "-q", service], env=env).split()
        if any(not re.fullmatch(r"[a-f0-9]{12,64}", item) for item in ids) or len(ids) != len(set(ids)):
            raise Blocked("运行容器清单格式异常；原始输出已隐藏。")
        if not ids:
            services_without_running += 1
        if service == "backend" and len(ids) > 1:
            blockers.add("当前 Compose 最多允许一个已运行的 backend；请先停止额外实例再执行预检。")
        if service == "postgres":
            if len(ids) != 1:
                blockers.add("当前 Compose 必须有且仅有一个已运行的 postgres；不会启动新容器。")
            else:
                container = ids[0]
        for item in sorted(ids):
            running_containers += 1
            observed = run(["docker", "inspect", "--format", template, item]).splitlines()
            if len(observed) != 3:
                raise Blocked("无法确认正在运行的容器元数据。")
            labels, actual_mounts, running = [json.loads(line) for line in observed]
            if running is not True or labels.get("com.docker.compose.project") != project or labels.get("com.docker.compose.service") != service:
                blockers.add("容器状态或 Compose 项目/服务标签不符，无法确认实际挂载。")
            seen_targets = set()
            for mount in actual_mounts:
                target, mount_type = mount.get("Destination"), mount.get("Type")
                declared_mount = expected.get(target)
                kind = "unknown_volume" if mount_type == "volume" else mount_type if mount_type in ("bind", "tmpfs") else "unsupported"
                if declared_mount and mount_type == "volume" and declared_mount[0] == "volume" and mount.get("Name") == declared_mount[1]:
                    kind = declared_mount[2]
                count_mount(observed_counts, kind)
                if target in seen_targets or not declared_mount or mount_type != declared_mount[0] or kind != declared_mount[2]:
                    blockers.add("运行容器挂载与 Compose 声明不符，或存在额外挂载；路径已隐藏。")
                seen_targets.add(target)
                if kind in ("unknown_volume", "bind", "unsupported"):
                    blockers.add("运行容器存在未分类的持久化卷或 bind mount，需先人工盘点；路径已隐藏。")
                if kind in ("database", "rebuildable_cache") and mount.get("Name") not in verified_volumes:
                    name, source = mount["Name"], declared_mount[3]
                    volume_labels = json.loads(run(["docker", "volume", "inspect", "--format", '{{json .Labels}}', name])) or {}
                    if volume_labels.get("com.docker.compose.project") != project or volume_labels.get("com.docker.compose.volume") != source:
                        blockers.add("named volume 的 Compose 项目/卷标签不符或缺失；不能创建替代卷。")
                    verified_volumes.add(name)
            if seen_targets != expected.keys():
                blockers.add("运行容器缺少 Compose 声明的挂载，无法确认数据或缓存位置。")
    print_inventory("observed", observed_counts)
    print_inventory("coverage", {"declared_services": len(services), "running_containers": running_containers,
                                 "services_without_running_container": services_without_running})
    if blockers:
        raise Blocked(" ".join(sorted(blockers)))
    if container is None:
        raise Blocked("没有可确认的运行 postgres；不会启动新容器。")
    return container, project, volume


def literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def table_exists(table: str) -> str:
    return f"EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname={literal(table)} AND c.relkind='r')"


def column_exists(table: str, column: str) -> str:
    return f"EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name={literal(table)} AND column_name={literal(column)})"


def text_array(values: tuple[str, ...]) -> str:
    return "ARRAY[" + ",".join(literal(value) for value in values) + "]::text[]"


def schema_contract_queries() -> dict[str, str]:
    # All identifiers below are source constants. Only counts leave PostgreSQL;
    # catalog names, default expressions and predicates never enter the report.
    rows = []
    for table, columns in COLUMN_CONTRACT.items():
        for column, (data_type, nullable, default) in columns.items():
            rows.append("(" + ",".join((literal(table), literal(column), literal(data_type),
                                       "true" if nullable else "false",
                                       "NULL" if default is None else literal(default))) + ")")
    columns_cte = """
WITH required(tab,col,typ,nullable,def) AS (VALUES %s), actual AS (
    SELECT r.*, a.attnum, a.attnotnull, a.attidentity, a.attgenerated,
           format_type(a.atttypid,a.atttypmod) AS actual_type,
           d.oid AS default_oid, pg_get_expr(d.adbin,d.adrelid) AS actual_default,
           to_regclass(pg_get_serial_sequence('public.'||r.tab,r.col)) AS owned_sequence
    FROM required r
    LEFT JOIN pg_attribute a ON a.attrelid=to_regclass('public.'||r.tab)
        AND a.attname=r.col AND a.attnum>0 AND NOT a.attisdropped
    LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
)
""" % ",".join(rows)
    # pg_get_serial_sequence throws on absent relations, so catalog checks run
    # behind the same existence guard as business queries in build_sql().
    queries = {
        "unexpected_columns": """
WITH required(tab,col) AS (VALUES %s)
SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
    JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN (%s)
    AND a.attnum>0 AND NOT a.attisdropped
    AND NOT EXISTS (SELECT 1 FROM required r WHERE r.tab=c.relname AND r.col=a.attname)
""" % (",".join("(" + literal(table) + "," + literal(column) + ")"
                  for table, columns in COLUMN_CONTRACT.items() for column in columns),
           ",".join(literal(table) for table in COLUMN_CONTRACT)),
        "column_type_or_length_mismatch": columns_cte + "SELECT count(*) FROM actual WHERE actual_type IS DISTINCT FROM typ",
        "column_nullability_mismatch": columns_cte + "SELECT count(*) FROM actual WHERE attnotnull IS DISTINCT FROM NOT nullable",
        "column_default_mismatch": columns_cte + """
SELECT count(*) FROM actual r WHERE attidentity IS DISTINCT FROM ''
    OR attgenerated IS DISTINCT FROM '' OR NOT (
        CASE WHEN def='serial' THEN
            owned_sequence IS NOT NULL AND actual_default IS NOT DISTINCT FROM
                ('nextval('||quote_literal(owned_sequence::text)||'::regclass)')
            AND EXISTS (SELECT 1 FROM pg_depend dep
                WHERE dep.classid='pg_attrdef'::regclass AND dep.objid=r.default_oid
                    AND dep.refclassid='pg_class'::regclass AND dep.refobjid=r.owned_sequence)
        ELSE actual_default IS NOT DISTINCT FROM def END)
""",
    }
    pk_rows = ",".join("(" + literal(table) + "," + text_array(keys) + ")"
                       for table, keys in PRIMARY_KEYS.items())
    queries["missing_primary_keys"] = """
WITH required(tab,cols) AS (VALUES %s)
SELECT count(*) FROM required r WHERE NOT EXISTS (
    SELECT 1 FROM pg_constraint k JOIN pg_index i ON i.indexrelid=k.conindid
    WHERE k.conrelid=to_regclass('public.'||r.tab) AND k.contype='p'
        AND k.convalidated AND NOT k.condeferrable AND NOT k.condeferred
        AND i.indisvalid AND i.indisready AND i.indimmediate AND i.indisunique AND i.indisprimary
        AND i.indexprs IS NULL AND i.indpred IS NULL AND i.indnatts=i.indnkeyatts
        AND ARRAY(SELECT a.attname::text FROM unnest(k.conkey) WITH ORDINALITY x(num,ord)
            JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=x.num
            ORDER BY x.ord)=r.cols)
""" % pk_rows
    fk_rows = ",".join("(" + ",".join(literal(value) for value in fk) + ")" for fk in FOREIGN_KEYS)
    fk_cte = """
WITH required(src,col,dst,dstcol,del) AS (VALUES %s), actual AS (
    SELECT k.*, ARRAY(SELECT a.attname::text FROM unnest(k.conkey) WITH ORDINALITY x(num,ord)
        JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=x.num ORDER BY x.ord) AS src_cols,
        ARRAY(SELECT a.attname::text FROM unnest(k.confkey) WITH ORDINALITY x(num,ord)
        JOIN pg_attribute a ON a.attrelid=k.confrelid AND a.attnum=x.num ORDER BY x.ord) AS dst_cols
    FROM pg_constraint k WHERE k.contype='f' AND k.conrelid IN (%s)
)
""" % (fk_rows, ",".join("to_regclass(" + literal("public." + table) + ")" for table in COLUMN_CONTRACT))
    fk_match = """k.conrelid=to_regclass('public.'||r.src) AND k.confrelid=to_regclass('public.'||r.dst)
        AND k.src_cols=ARRAY[r.col] AND k.dst_cols=ARRAY[r.dstcol]
        AND k.confdeltype::text=r.del AND k.confupdtype='a' AND k.confmatchtype='s'
        AND k.convalidated AND NOT k.condeferrable AND NOT k.condeferred"""
    queries["missing_validated_foreign_keys"] = fk_cte + "SELECT count(*) FROM required r WHERE (SELECT count(*) FROM actual k WHERE " + fk_match + ")<>1"
    # In particular, any FK on tombstones would violate their deletion semantics.
    queries["unexpected_foreign_keys"] = fk_cte + "SELECT count(*) FROM actual k WHERE NOT EXISTS (SELECT 1 FROM required r WHERE " + fk_match + ")"
    index_rows = ",".join("(" + literal(table) + "," + text_array(keys) + "," +
                          ("NULL" if predicate is None else literal(predicate)) + ")"
                          for table, keys, predicate in UNIQUE_INDEXES)
    queries["critical_unique_index_mismatch"] = """
WITH required(tab,cols,predicate) AS (VALUES %s)
SELECT count(*) FROM required r WHERE NOT EXISTS (
    SELECT 1 FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid
        JOIN pg_am am ON am.oid=idx.relam
    WHERE i.indrelid=to_regclass('public.'||r.tab) AND i.indisunique
        AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
        AND NOT i.indnullsnotdistinct AND am.amname='btree'
        AND i.indexprs IS NULL AND i.indnatts=i.indnkeyatts
        AND ARRAY(SELECT a.attname::text FROM unnest(i.indkey) WITH ORDINALITY x(num,ord)
            JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=x.num
            ORDER BY x.ord)=r.cols
        AND NOT EXISTS (SELECT 1 FROM unnest(i.indclass,i.indcollation,i.indoption)
            WITH ORDINALITY x(opclass,collation,options,ord)
            JOIN pg_opclass op ON op.oid=x.opclass
            JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attname=r.cols[x.ord::integer]
            WHERE NOT op.opcdefault OR x.collation<>a.attcollation OR x.options<>0)
        AND pg_get_expr(i.indpred,i.indrelid) IS NOT DISTINCT FROM r.predicate)
""" % index_rows
    return queries


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
    # Catalog-only, independent of core-table readiness. Even an empty history
    # table needs manual interpretation before claiming an unversioned baseline.
    guarded("blocker.preexisting_migration_history_tables",
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
            "WHERE n.nspname='public' AND c.relname IN (" +
            ",".join(literal(table) for table in MIGRATION_HISTORY_TABLES) + ")",
            "TRUE", "zero")
    for name, query in CHECKS.items():
        guarded("blocker." + name, query, ready, "zero")
    for name, query in schema_contract_queries().items():
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
        if report["blocker.preexisting_migration_history_tables"] == "0":
            print("schema_version=unversioned-initializers (no recognized migration version registry)")
        else:
            print("schema_version=unknown-preexisting-history (manual interpretation required)")
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
        print("schema_status=baseline_candidate")
        print("READY: 只读快照符合当前结构契约；baseline_candidate 不是执行 baseline 或启动许可，仍须加密备份、隔离恢复演练并停旧后启新。")
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
