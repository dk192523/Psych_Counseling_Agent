#!/usr/bin/env python3
"""CI-only real PostgreSQL coverage; intentionally excluded from unittest discovery.

The workflow passes its own disposable pgvector service container ID. It has
exactly one disposable tmpfs database mount and no bind or named project-data
mounts. There are no Compose calls, host database connections, or access to
.env, cloud, or project data. Fixture writes only target that labeled synthetic
service container.
Docker/psql output is captured, and failures expose fixed summaries only.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys


HERE = Path(__file__).resolve().parent
LABEL = "psych.migration-preflight-sql-ci"
DB_USER = "preflight_ci"
DB_NAME = "preflight_ci"


class TestFailure(Exception):
    """Messages must contain only fixed labels and aggregate metric names."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TestFailure(message)


def command(args: list[str], *, stdin: str | None = None, timeout: int = 180) -> str:
    try:
        result = subprocess.run(
            args, input=stdin, text=True, encoding="utf-8", errors="strict",
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout,
            check=False,
        )
    except (OSError, UnicodeError, subprocess.TimeoutExpired):
        raise TestFailure("Container command unavailable or timed out; raw diagnostics suppressed.") from None
    require(result.returncode == 0, "Container command failed; raw diagnostics suppressed.")
    return result.stdout


def psql(container: str, sql: str, *, readonly: bool = False) -> str:
    options = "-c statement_timeout=30000 -c lock_timeout=5000"
    if readonly:
        options += " -c default_transaction_read_only=on"
    return command([
        "docker", "exec", "-i", "--env", "PGOPTIONS=" + options,
        "--env", "PGCONNECT_TIMEOUT=5", container,
        "psql", "-X", "-w", "-qAt", "-v", "ON_ERROR_STOP=1",
        "-U", DB_USER, "-d", DB_NAME,
    ], stdin=sql)


def require_disposable_container(container: str) -> None:
    try:
        metadata = json.loads(command([
            "docker", "inspect", "--type", "container", "--format", "{{json .}}", container,
        ]))
    except (ValueError, TypeError):
        raise TestFailure("Container metadata is invalid; raw diagnostics suppressed.") from None
    require(isinstance(metadata, dict), "Container metadata is invalid; raw diagnostics suppressed.")
    config = metadata.get("Config")
    state = metadata.get("State")
    host_config = metadata.get("HostConfig")
    network = metadata.get("NetworkSettings")
    require(all(isinstance(value, dict) for value in (config, state, host_config, network)),
            "Container metadata is incomplete; raw diagnostics suppressed.")
    require(config.get("Image") == "pgvector/pgvector:pg16",
            "The service container must use the expected CI image.")
    require(state.get("Running") is True and state.get("Status") == "running",
            "The disposable CI service container must be running.")
    labels = config.get("Labels")
    require(isinstance(labels, dict) and labels.get(LABEL) == "true",
            "The service container is missing the synthetic CI fixture label.")
    mounts = metadata.get("Mounts")
    require(isinstance(mounts, list) and len(mounts) == 1
            and isinstance(mounts[0], dict)
            and mounts[0].get("Type") == "tmpfs"
            and mounts[0].get("Destination") == "/var/lib/postgresql/data"
            and mounts[0].get("RW") is True
            and host_config.get("Tmpfs") == {"/var/lib/postgresql/data": "rw"}
            and "Binds" in host_config and host_config["Binds"] in (None, []),
            "The service container must use only the expected disposable CI storage.")
    ports = network.get("Ports")
    require("PortBindings" in host_config and host_config["PortBindings"] in (None, {})
            and host_config.get("PublishAllPorts") is False
            and isinstance(ports, dict) and all(value in (None, []) for value in ports.values()),
            "The service container must not publish host ports.")


def main() -> int:
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("CI") == "true",
            "This fixture runner is restricted to GitHub Actions CI.")
    container = os.environ.get("MIGRATION_SQL_CI_CONTAINER", "")
    require(re.fullmatch(r"[a-f0-9]{64}", container) is not None,
            "A full disposable CI service container ID is required.")
    require_disposable_container(container)

    spec = importlib.util.spec_from_file_location("migration_preflight_sql_ci_target", HERE.parent / "migration-preflight.py")
    require(spec is not None and spec.loader is not None, "Cannot load the SQL generator.")
    preflight = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(preflight)
    sql, expected = preflight.build_sql()
    require("\\gexec" in sql, "The integration must exercise psql gexec.")
    require(sql.startswith("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;") and sql.endswith("ROLLBACK;\n"),
            "The generated SQL must retain its read-only snapshot transaction.")
    fixture = (HERE / "fixtures" / "migration_preflight_pg16.sql").read_text(encoding="utf-8")
    row_counts = {
        "rows.users": "1", "rows.conversations": "1", "rows.messages": "1",
        "rows.memories": "1", "rows.turns": "1", "rows.tombstones": "1",
        "rows.knowledge_vectors": "1", "info.running_turns": "1",
        "info.tombstone_owner_absent": "1",
    }

    def scenario(name: str, mutation: str = "", blockers: dict[str, str] | None = None) -> None:
        # Each scenario starts from the independent Java-DDL fixture. All writes
        # finish before build_sql() runs through the real psql client unchanged.
        psql(container, fixture + "\n" + mutation)
        report = preflight.parse_report(psql(container, sql, readonly=True), expected)
        require(int(report["postgres_version_num"]) // 10000 == 16, "PostgreSQL 16 is required.")
        require(report["pgvector_version"] != "missing", "pgvector extension must be installed.")
        require(report["read_only"] == "on" and report["snapshot_complete"] == "1",
                "Read-only snapshot did not complete.")
        require(all(report[key] == value for key, value in row_counts.items()),
                "Synthetic fixture row or informational counts changed.")
        wanted = blockers or {}
        for key, kind in expected.items():
            if kind == "zero":
                require(report[key] == wanted.get(key, "0"), name + ": unexpected aggregate for " + key)
        require(all(key in expected and expected[key] == "zero" for key in wanted),
                "Scenario refers to an unknown blocker metric.")
        print("PASS: " + name)

    scenario("current Java DDL contract")
    scenario("unexpected business column", "ALTER TABLE psych_user ADD COLUMN ci_extra TEXT;",
             {"blocker.unexpected_columns": "1"})
    scenario("varchar length drift", "ALTER TABLE psych_user ALTER COLUMN username TYPE VARCHAR(65);",
             {"blocker.column_type_or_length_mismatch": "1"})
    scenario("column type drift", "ALTER TABLE psych_chat_message ALTER COLUMN content TYPE VARCHAR(200);",
             {"blocker.column_type_or_length_mismatch": "1"})
    scenario("nullability drift", "ALTER TABLE psych_user ALTER COLUMN password_hash DROP NOT NULL;",
             {"blocker.column_nullability_mismatch": "1"})
    scenario("changed literal default", "ALTER TABLE psych_user ALTER COLUMN status SET DEFAULT 'DISABLED';",
             {"blocker.column_default_mismatch": "1"})
    scenario("missing timestamp default", "ALTER TABLE psych_user ALTER COLUMN created_at DROP DEFAULT;",
             {"blocker.column_default_mismatch": "1"})
    scenario("missing serial default", "ALTER TABLE psych_user ALTER COLUMN id DROP DEFAULT;",
             {"blocker.column_default_mismatch": "1"})
    scenario("unowned serial sequence", "ALTER SEQUENCE psych_user_id_seq OWNED BY NONE;",
             {"blocker.column_default_mismatch": "1"})
    # Retain the fixture's rows and column nullability while changing only the
    # catalog contract. No CASCADE drops: required relationships stay intact.
    for name, mutation in (
        ("missing primary key", """
ALTER TABLE psych_chat_turn DROP CONSTRAINT psych_chat_turn_pkey;
"""),
        ("primary key wrong columns", """
ALTER TABLE psych_chat_turn DROP CONSTRAINT psych_chat_turn_pkey;
ALTER TABLE psych_chat_turn ADD PRIMARY KEY (conversation_id, request_hash);
"""),
        ("primary key reversed column order", """
ALTER TABLE psych_chat_turn DROP CONSTRAINT psych_chat_turn_pkey;
ALTER TABLE psych_chat_turn ADD PRIMARY KEY (client_msg_id, conversation_id);
"""),
        ("primary key extra column", """
ALTER TABLE psych_chat_turn DROP CONSTRAINT psych_chat_turn_pkey;
ALTER TABLE psych_chat_turn ADD PRIMARY KEY (conversation_id, client_msg_id, request_hash);
"""),
        ("primary key included column", """
ALTER TABLE psych_chat_turn DROP CONSTRAINT psych_chat_turn_pkey;
ALTER TABLE psych_chat_turn ADD PRIMARY KEY (conversation_id, client_msg_id) INCLUDE (answer);
"""),
        ("deferrable primary key", """
ALTER TABLE psych_chat_turn DROP CONSTRAINT psych_chat_turn_pkey;
ALTER TABLE psych_chat_turn ADD PRIMARY KEY (conversation_id, client_msg_id) DEFERRABLE INITIALLY DEFERRED;
"""),
        ("vector primary key replaced by unique constraint", """
ALTER TABLE vector_store DROP CONSTRAINT vector_store_pkey;
ALTER TABLE vector_store ADD UNIQUE (id);
"""),
    ):
        scenario(name, mutation, {"blocker.missing_primary_keys": "1"})

    # Independently exercise all four required relationships. A wrong or
    # unvalidated FK is both absent from the required contract and unexpected.
    foreign_keys = (
        ("conversation owner", "psych_conversation", "owner_id", "psych_user", "id", "NO ACTION", "CASCADE"),
        ("message conversation", "psych_chat_message", "conversation_id", "psych_conversation", "id", "CASCADE", "NO ACTION"),
        ("memory conversation", "psych_conversation_memory", "conversation_id", "psych_conversation", "id", "CASCADE", "RESTRICT"),
        ("turn conversation", "psych_chat_turn", "conversation_id", "psych_conversation", "id", "CASCADE", "SET NULL"),
    )
    for label, table, column, target, target_column, delete_action, wrong_delete_action in foreign_keys:
        drop = f"ALTER TABLE {table} DROP CONSTRAINT {table}_{column}_fkey;\n"
        add = (f"ALTER TABLE {table} ADD CONSTRAINT ci_required_fk "
               f"FOREIGN KEY ({column}) REFERENCES {target} ({target_column}) ON DELETE ")
        scenario(label + " missing foreign key", drop,
                 {"blocker.missing_validated_foreign_keys": "1"})
        scenario(label + " wrong foreign key delete action", drop + add + wrong_delete_action + ";",
                 {"blocker.missing_validated_foreign_keys": "1", "blocker.unexpected_foreign_keys": "1"})
        scenario(label + " unvalidated foreign key", drop + add + delete_action + " NOT VALID;",
                 {"blocker.missing_validated_foreign_keys": "1", "blocker.unexpected_foreign_keys": "1"})
        scenario(label + " explicitly validated renamed foreign key",
                 drop + add + delete_action + " NOT VALID;\n"
                 + f"ALTER TABLE {table} VALIDATE CONSTRAINT ci_required_fk;")
    scenario("deferrable required foreign key", """
ALTER TABLE psych_conversation DROP CONSTRAINT psych_conversation_owner_id_fkey;
ALTER TABLE psych_conversation ADD FOREIGN KEY (owner_id) REFERENCES psych_user (id)
    DEFERRABLE INITIALLY DEFERRED;
""", {"blocker.missing_validated_foreign_keys": "1", "blocker.unexpected_foreign_keys": "1"})
    # NOT VALID preserves the deliberately orphaned tombstone fixture. Even a
    # fully validated self-reference is forbidden on this table.
    scenario("forbidden tombstone owner foreign key", """
ALTER TABLE psych_conversation_tombstone ADD FOREIGN KEY (owner_id) REFERENCES psych_user (id) NOT VALID;
""", {"blocker.unexpected_foreign_keys": "1"})
    scenario("forbidden tombstone conversation foreign key", """
ALTER TABLE psych_conversation_tombstone ADD FOREIGN KEY (conversation_id)
    REFERENCES psych_conversation (id) ON DELETE CASCADE NOT VALID;
""", {"blocker.unexpected_foreign_keys": "1"})
    scenario("forbidden validated tombstone foreign key", """
ALTER TABLE psych_conversation_tombstone ADD FOREIGN KEY (conversation_id)
    REFERENCES psych_conversation_tombstone (conversation_id);
""", {"blocker.unexpected_foreign_keys": "1"})

    for name, mutation in (
        ("username unique constraint missing", """
ALTER TABLE psych_user DROP CONSTRAINT psych_user_username_key;
"""),
        ("username unique constraint wrong columns", """
ALTER TABLE psych_user DROP CONSTRAINT psych_user_username_key;
ALTER TABLE psych_user ADD UNIQUE (username, password_hash);
"""),
        ("username unique expression index", """
ALTER TABLE psych_user DROP CONSTRAINT psych_user_username_key;
CREATE UNIQUE INDEX ci_unique_expression ON psych_user (lower(username));
"""),
        ("username deferrable unique constraint", """
ALTER TABLE psych_user DROP CONSTRAINT psych_user_username_key;
ALTER TABLE psych_user ADD UNIQUE (username) DEFERRABLE INITIALLY DEFERRED;
"""),
        ("message index nonunique", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE INDEX ci_nonunique ON psych_chat_message (conversation_id, client_msg_id);
"""),
        ("message unique index reversed column order", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_reversed ON psych_chat_message (client_msg_id, conversation_id);
"""),
        ("message unique index extra key", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_extra_key ON psych_chat_message (conversation_id, client_msg_id, id);
"""),
        ("message unique index included column", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_include ON psych_chat_message (conversation_id, client_msg_id) INCLUDE (id);
"""),
        ("message unique index descending key", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_descending ON psych_chat_message (conversation_id DESC, client_msg_id);
"""),
        ("message unique index nulls first", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_nulls_first ON psych_chat_message (conversation_id, client_msg_id NULLS FIRST);
"""),
        ("message unique index nulls not distinct", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_nulls_not_distinct ON psych_chat_message (conversation_id, client_msg_id) NULLS NOT DISTINCT;
"""),
        ("message unique index nondefault operator class", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_operator_class ON psych_chat_message (conversation_id, client_msg_id varchar_pattern_ops);
"""),
        ("message unique index nondefault collation", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_collation ON psych_chat_message (conversation_id, client_msg_id COLLATE "C");
"""),
        ("message unique index unexpected predicate", """
DROP INDEX uk_psych_chat_message_client_msg;
CREATE UNIQUE INDEX ci_unique_partial ON psych_chat_message (conversation_id, client_msg_id) WHERE client_msg_id IS NOT NULL;
"""),
        ("running turn unique index wrong key", """
DROP INDEX uk_psych_running_turn;
CREATE UNIQUE INDEX ci_running_wrong_key ON psych_chat_turn (client_msg_id) WHERE status = 'RUNNING';
"""),
        ("running turn unique index missing predicate", """
DROP INDEX uk_psych_running_turn;
CREATE UNIQUE INDEX ci_running_no_predicate ON psych_chat_turn (conversation_id);
"""),
        ("running turn unique index wrong predicate", """
DROP INDEX uk_psych_running_turn;
CREATE UNIQUE INDEX ci_running_wrong_predicate ON psych_chat_turn (conversation_id) WHERE status = 'COMPLETED';
"""),
        ("running turn unique index broadened predicate", """
DROP INDEX uk_psych_running_turn;
CREATE UNIQUE INDEX ci_running_broad_predicate ON psych_chat_turn (conversation_id) WHERE status IN ('RUNNING', 'COMPLETED');
"""),
    ):
        scenario(name, mutation, {"blocker.critical_unique_index_mismatch": "1"})
    scenario("renamed primary and critical unique indexes", """
ALTER TABLE psych_chat_turn RENAME CONSTRAINT psych_chat_turn_pkey TO ci_turn_primary;
ALTER TABLE psych_user RENAME CONSTRAINT psych_user_username_key TO ci_username_unique;
ALTER INDEX uk_psych_chat_message_client_msg RENAME TO ci_message_unique;
ALTER INDEX uk_psych_running_turn RENAME TO ci_running_unique;
""")
    # History presence blocks even when empty, independent of its row format.
    scenario("empty application history table", "CREATE TABLE psych_schema_history (ci_payload TEXT);",
             {"blocker.preexisting_migration_history_tables": "1"})
    scenario("empty Flyway history table", "CREATE TABLE flyway_schema_history (ci_payload TEXT);",
             {"blocker.preexisting_migration_history_tables": "1"})
    scenario("both history tables with opaque synthetic rows", """
CREATE TABLE psych_schema_history (ci_payload TEXT);
CREATE TABLE flyway_schema_history (ci_payload TEXT);
INSERT INTO psych_schema_history VALUES ('ci_history_payload_must_stay_private');
INSERT INTO flyway_schema_history VALUES ('ci_history_payload_must_stay_private');
""", {"blocker.preexisting_migration_history_tables": "2"})
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except TestFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        # Neither tracebacks nor raw SQL/client diagnostics enter Actions logs.
        print("FAIL: SQL integration could not complete; raw diagnostics suppressed.", file=sys.stderr)
        sys.exit(1)
