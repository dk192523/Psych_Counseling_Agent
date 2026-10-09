"""Synthetic-only tests. No Docker, .env, database or production data required.

Run explicitly when authorized:
    python -m unittest discover -s deploy/tencent-cloud/tests -p 'test_*.py'
"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "migration-preflight.py"
SPEC = importlib.util.spec_from_file_location("migration_preflight", SCRIPT)
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)
SECRET = "private-username private-password confidential-counseling-text"
PROJECT = "synthetic-existing-project"
VOLUME = PROJECT + "_postgres_data"
CONTAINER = "a" * 64
BACKEND_CONTAINER = "b" * 64
HOST_PATH = "/private/synthetic-user/confidential-files"


class FakeDocker:
    def __init__(self):
        self.calls = []
        self.config = {"name": PROJECT, "volumes": {"postgres_data": {"name": VOLUME}},
                       "services": {"postgres": {"volumes": [
                           {"type": "volume", "source": "postgres_data", "target": "/var/lib/postgresql/data"}]}}}
        self.labels = {"com.docker.compose.project": PROJECT, "com.docker.compose.service": "postgres"}
        self.mounts = [{"Type": "volume", "Name": VOLUME, "Destination": "/var/lib/postgresql/data"}]
        self.volume_labels = {"com.docker.compose.project": PROJECT, "com.docker.compose.volume": "postgres_data"}
        self.ps = CONTAINER
        self.other_containers = {}
        self.project_services_output = None
        self.exec_code = 0
        self.extra_output = ""
        self.omit = None
        _, expected = PREFLIGHT.build_sql()
        self.report = {key: {"version": "160006", "extension": "0.8.0", "hash": "a" * 32,
                             "readonly": "on", "one": "1", "zero": "0", "count": "3"}[kind]
                       for key, kind in expected.items()}

    def run(self, args, **kwargs):
        self.calls.append((args, kwargs))
        if args[:2] == ["docker", "compose"]:
            if args[-3:] == ["config", "--format", "json"]:
                output = json.dumps(self.config)
            elif args[-5:-1] == ["ps", "--status", "running", "-q"]:
                output = self.ps if args[-1] == "postgres" else "\n".join(
                    item for item, details in self.other_containers.items() if details[0] == args[-1])
            else:
                raise AssertionError("Lifecycle or unapproved Compose command")
        elif args[:2] == ["docker", "ps"]:
            if args != ["docker", "ps", "--filter", "label=com.docker.compose.project=" + PROJECT,
                        "--filter", "status=running", "--format",
                        '{{json (.Label "com.docker.compose.service")}}']:
                raise AssertionError("Unscoped or unapproved project metadata query")
            output = self.project_services_output
            if output is None:
                services = [self.labels.get("com.docker.compose.service") for _ in self.ps.split()
                            if self.labels.get("com.docker.compose.project") == PROJECT]
                services.extend(details[0] for details in self.other_containers.values())
                output = "\n".join(json.dumps(service) for service in services)
        elif args[:2] == ["docker", "inspect"]:
            if args[-1] in self.other_containers:
                service, mounts = self.other_containers[args[-1]]
                labels = {"com.docker.compose.project": PROJECT, "com.docker.compose.service": service}
                output = "\n".join(json.dumps(x) for x in (labels, mounts, True))
            else:
                output = "\n".join(json.dumps(x) for x in (self.labels, self.mounts, True))
        elif args[:3] == ["docker", "volume", "inspect"]:
            source = next((key for key, value in self.config["volumes"].items() if value.get("name") == args[-1]), None)
            output = json.dumps(self.volume_labels if source == "postgres_data" else {
                "com.docker.compose.project": PROJECT, "com.docker.compose.volume": source})
        elif args[:3] == ["docker", "exec", "-i"]:
            output = "\n".join(f"{key}\t{value}" for key, value in self.report.items() if key != self.omit)
            output += self.extra_output
            return subprocess.CompletedProcess(args, self.exec_code, output, SECRET)
        else:
            raise AssertionError("Unapproved Docker command")
        return subprocess.CompletedProcess(args, 0, output, SECRET)

    def add_current_services(self):
        self.config["services"].update({"backend": {"volumes": []}, "ai-worker": {}, "frontend": {}})
        mounts = []
        for source, target in [("onnx_cache", "/tmp/spring-ai-onnx-generative"), ("djl_cache", "/home/app/.djl.ai")]:
            name = PROJECT + "_" + source
            self.config["volumes"][source] = {"name": name}
            self.config["services"]["backend"]["volumes"].append({"type": "volume", "source": source, "target": target})
            mounts.append({"Type": "volume", "Name": name, "Destination": target, "Source": HOST_PATH + "/" + source})
        self.other_containers[BACKEND_CONTAINER] = ("backend", mounts)
        self.other_containers["c" * 64] = ("ai-worker", [])
        self.other_containers["d" * 64] = ("frontend", [])


class MigrationPreflightTests(unittest.TestCase):
    def invoke(self, docker):
        output = io.StringIO()
        # Python opens only the public Compose file. Real Compose reads .env to
        # resolve config; that subprocess is mocked here and its output is private.
        def public_compose(path, **kwargs):
            self.assertEqual(path.name, "docker-compose.yml")
            self.assertEqual(kwargs.get("encoding"), "utf-8")
            return "name: ${COMPOSE_PROJECT_NAME:-synthetic}\npassword: ${POSTGRES_PASSWORD}\n"

        with patch.object(PREFLIGHT.subprocess, "run", side_effect=docker.run), \
             patch.object(PREFLIGHT.Path, "read_text", public_compose), \
             contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = PREFLIGHT.main(Path("/synthetic/package"))
        self.assertNotIn(SECRET, output.getvalue())
        self.assertNotIn(HOST_PATH, output.getvalue())
        self.assertNotIn(CONTAINER, output.getvalue())
        self.assertNotIn(BACKEND_CONTAINER, output.getvalue())
        return code, output.getvalue()

    def test_all_current_mounts_are_classified_and_all_running_services_inspected(self):
        docker = FakeDocker()
        docker.add_current_services()
        code, output = self.invoke(docker)
        self.assertEqual(code, 0)
        for section in ("declared", "stores", "observed"):
            self.assertIn(f"mount_inventory.{section}.database=1", output)
            self.assertIn(f"mount_inventory.{section}.rebuildable_cache=2", output)
            self.assertIn(f"mount_inventory.{section}.total=3", output)
        self.assertIn("mount_inventory.coverage.declared_services=4", output)
        self.assertIn("mount_inventory.coverage.running_containers=4", output)
        inspected = {args[-1] for args, _ in docker.calls if args[:2] == ["docker", "inspect"]}
        self.assertEqual(inspected, {CONTAINER, *docker.other_containers})

    def test_inventory_is_deterministic_and_stopped_services_are_explicit(self):
        docker = FakeDocker()
        docker.add_current_services()
        docker.other_containers.clear()
        code, output = self.invoke(docker)
        self.assertEqual(code, 0)
        self.assertIn("mount_inventory.coverage.services_without_running_container=3", output)
        self.assertIn("mount_inventory.declared.rebuildable_cache=2", output)
        self.assertIn("mount_inventory.observed.rebuildable_cache=0", output)
        docker.config["services"] = dict(reversed(list(docker.config["services"].items())))
        docker.config["volumes"] = dict(reversed(list(docker.config["volumes"].items())))
        self.assertEqual(self.invoke(docker)[1], output)

    def test_unknown_named_and_anonymous_volumes_and_unused_stores_fail_closed(self):
        for location in ("declared", "observed", "unused_store", "anonymous"):
            with self.subTest(location=location):
                docker = FakeDocker()
                if location in ("declared", "anonymous"):
                    mount = {"type": "volume", "target": "/private-data"}
                    if location == "declared":
                        mount["source"] = "private_store"
                    docker.config["services"]["offline-service"] = {"volumes": [mount]}
                elif location == "observed":
                    docker.mounts.append({"Type": "volume", "Name": "private_store", "Destination": "/private-data", "Source": HOST_PATH})
                else:
                    docker.config["volumes"]["private_store"] = {"name": "private_store"}
                code, output = self.invoke(docker)
                self.assertEqual(code, 2)
                self.assertNotIn("READY:", output)
                self.assertNotIn("private_store", output)
                self.assertFalse(any(args[1] == "exec" for args, _ in docker.calls))

    def test_bind_mounts_fail_closed_without_source_or_target_leakage(self):
        for location in ("declared", "observed"):
            for read_only in (True, False):
                with self.subTest(location=location, read_only=read_only):
                    docker = FakeDocker()
                    if location == "declared":
                        docker.config["services"]["offline-service"] = {"volumes": [{
                            "type": "bind", "source": HOST_PATH, "target": "/sensitive-target", "read_only": read_only}]}
                    else:
                        docker.mounts.append({"Type": "bind", "Source": HOST_PATH, "Destination": "/sensitive-target", "RW": not read_only})
                    code, output = self.invoke(docker)
                    self.assertEqual(code, 2)
                    self.assertIn("bind mount", output)
                    self.assertNotIn("/sensitive-target", output)
                    self.assertNotIn("READY:", output)

    def test_missing_or_changed_cache_mount_is_blocked(self):
        for missing in (True, False):
            docker = FakeDocker()
            docker.add_current_services()
            mounts = docker.other_containers[BACKEND_CONTAINER][1]
            if missing:
                mounts.pop()
            else:
                mounts[0]["Name"] = "unexpected-cache"
            self.assertEqual(self.invoke(docker)[0], 2)

    def test_storage_driver_and_indirect_mounts_fail_closed(self):
        for field in ("driver_opts", "configs", "secrets", "volumes_from"):
            docker = FakeDocker()
            if field == "driver_opts":
                docker.config["volumes"]["postgres_data"][field] = {"device": HOST_PATH, "o": "bind"}
            else:
                docker.config["services"]["postgres"][field] = [HOST_PATH]
            code, output = self.invoke(docker)
            self.assertEqual(code, 2)
            self.assertNotIn("READY:", output)

    def test_ready_is_read_only_and_python_does_not_open_env_or_restart(self):
        docker = FakeDocker()
        code, output = self.invoke(docker)
        self.assertEqual(code, 0)
        self.assertIn("READY:", output)
        self.assertIn("schema_version=unversioned-initializers", output)
        self.assertIn("schema_status=baseline_candidate", output)
        self.assertIn("不是执行 baseline 或启动许可", output)
        self.assertIn("compose_project=" + PROJECT, output)
        exec_calls = [(args, kwargs) for args, kwargs in docker.calls if args[1] == "exec"]
        self.assertEqual(len(exec_calls), 1)
        args, kwargs = exec_calls[0]
        self.assertEqual(args[3], CONTAINER)
        self.assertIn("test -s /var/lib/postgresql/data/PG_VERSION", args[-1])
        self.assertIn("default_transaction_read_only=on", args[-1])
        self.assertIn("psql -X -w -qAt -v ON_ERROR_STOP=1", args[-1])
        sql = kwargs["input"]
        self.assertTrue(sql.startswith("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;"))
        self.assertTrue(sql.endswith("ROLLBACK;\n"))
        self.assertNotRegex(sql.upper(), r"\b(INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE|COPY|DO|CALL)\b")
        self.assertNotIn("username FROM", sql)
        self.assertNotIn("content FROM", sql)
        self.assertEqual(kwargs["stderr"], subprocess.DEVNULL)

    def test_business_column_contract_matches_current_postgres_ddl(self):
        # Independent expected DDL fixture: types include varchar lengths, and
        # nullable owner_id must not be silently changed to a NOT NULL contract.
        timestamp = ("timestamp with time zone", False, "CURRENT_TIMESTAMP")
        expected = {
            "psych_user": {
                "id": ("bigint", False, "serial"),
                "username": ("character varying(64)", False, None),
                "password_hash": ("character varying(100)", False, None),
                "role": ("character varying(16)", False, "'USER'::character varying"),
                "status": ("character varying(16)", False, "'ACTIVE'::character varying"),
                "created_at": timestamp, "updated_at": timestamp,
                "last_login_at": ("timestamp with time zone", True, None),
                "disabled_at": ("timestamp with time zone", True, None),
                "disabled_reason": ("character varying(200)", True, None),
            },
            "psych_conversation": {
                "id": ("character varying(64)", False, None),
                "title": ("character varying(120)", False, None),
                "owner_id": ("bigint", True, None),
                "created_at": timestamp, "updated_at": timestamp,
            },
            "psych_chat_message": {
                "id": ("bigint", False, "serial"),
                "conversation_id": ("character varying(64)", False, None),
                "role": ("character varying(16)", False, None),
                "content": ("text", False, None),
                "client_msg_id": ("character varying(64)", True, None),
                "created_at": timestamp,
            },
            "psych_conversation_memory": {
                "conversation_id": ("character varying(64)", False, None),
                "digest": ("text", False, "''::text"),
                "covered_until_message_id": ("bigint", False, "0"),
                "covered_message_count": ("integer", False, "0"),
                "digest_chars": ("integer", False, "0"), "updated_at": timestamp,
            },
            "psych_chat_turn": {
                "conversation_id": ("character varying(64)", False, None),
                "client_msg_id": ("character varying(64)", False, None),
                "request_hash": ("character varying(64)", False, None),
                "status": ("character varying(16)", False, None),
                "answer": ("text", False, "''::text"),
                "effective_mode": ("character varying(16)", False, "'standard'::character varying"),
                "fallback": ("boolean", False, "false"), "updated_at": timestamp,
            },
            "psych_conversation_tombstone": {
                "conversation_id": ("character varying(64)", False, None),
                "owner_id": ("bigint", False, None), "deleted_at": timestamp,
            },
        }
        self.assertEqual(PREFLIGHT.COLUMN_CONTRACT, expected)
        queries = PREFLIGHT.schema_contract_queries()
        self.assertIn("format_type(a.atttypid,a.atttypmod)", queries["column_type_or_length_mismatch"])
        self.assertIn("attnotnull IS DISTINCT FROM", queries["column_nullability_mismatch"])
        for fragment in ("pg_get_expr(d.adbin,d.adrelid)", "pg_get_serial_sequence", "pg_depend",
                         "attidentity", "attgenerated", "IS NOT DISTINCT FROM def"):
            self.assertIn(fragment, queries["column_default_mismatch"])
        self.assertEqual(self.invoke(FakeDocker())[0], 0)

    def test_exact_key_and_index_contract_is_encoded_without_relying_on_names(self):
        queries = PREFLIGHT.schema_contract_queries()
        self.assertEqual(PREFLIGHT.PRIMARY_KEYS["psych_chat_turn"], ("conversation_id", "client_msg_id"))
        self.assertEqual(PREFLIGHT.PRIMARY_KEYS["vector_store"], ("id",))
        for fragment in ("unnest(k.conkey) WITH ORDINALITY", "ORDER BY x.ord)=r.cols",
                         "i.indisvalid", "i.indisready", "NOT k.condeferrable"):
            self.assertIn(fragment, queries["missing_primary_keys"])
        self.assertEqual(PREFLIGHT.FOREIGN_KEYS, (
            ("psych_conversation", "owner_id", "psych_user", "id", "a"),
            ("psych_chat_message", "conversation_id", "psych_conversation", "id", "c"),
            ("psych_conversation_memory", "conversation_id", "psych_conversation", "id", "c"),
            ("psych_chat_turn", "conversation_id", "psych_conversation", "id", "c"),
        ))
        for fragment in ("k.src_cols=ARRAY[r.col]", "k.dst_cols=ARRAY[r.dstcol]",
                         "k.confdeltype::text=r.del", "k.confupdtype='a'", "k.confmatchtype='s'",
                         "k.convalidated", "NOT k.condeferrable", ")<>1"):
            self.assertIn(fragment, queries["missing_validated_foreign_keys"])
        self.assertIn("psych_conversation_tombstone", queries["unexpected_foreign_keys"])
        self.assertEqual(PREFLIGHT.UNIQUE_INDEXES, (
            ("psych_user", ("username",), None),
            ("psych_chat_message", ("conversation_id", "client_msg_id"), None),
            ("psych_chat_turn", ("conversation_id",), "((status)::text = 'RUNNING'::text)"),
        ))
        for fragment in ("i.indisunique", "i.indisvalid", "i.indisready", "i.indislive",
                         "i.indimmediate", "NOT i.indnullsnotdistinct", "i.indexprs IS NULL",
                         "i.indnatts=i.indnkeyatts", "am.amname='btree'", "op.opcdefault",
                         "x.collation<>a.attcollation", "x.options<>0",
                         "pg_get_expr(i.indpred,i.indrelid) IS NOT DISTINCT FROM r.predicate"):
            self.assertIn(fragment, queries["critical_unique_index_mismatch"])
        self.assertNotIn("uk_psych_running_turn", queries["critical_unique_index_mismatch"])

    def test_catalog_drift_counts_block_before_baseline_candidate(self):
        # FakeDocker supplies synthetic catalog-query aggregates, not a SQL
        # execution engine; these cases verify fail-closed report decisions.
        cases = {
            "unexpected_business_column": "unexpected_columns",
            "wrong_type": "column_type_or_length_mismatch",
            "wrong_varchar_length": "column_type_or_length_mismatch",
            "wrong_nullability": "column_nullability_mismatch",
            "missing_timestamp_default": "column_default_mismatch",
            "wrong_status_default": "column_default_mismatch",
            "missing_or_unowned_serial_default": "column_default_mismatch",
            "wrong_primary_key_columns_or_order": "missing_primary_keys",
            "missing_or_unvalidated_foreign_key": "missing_validated_foreign_keys",
            "wrong_foreign_key_target_or_delete_action": "missing_validated_foreign_keys",
            "unexpected_tombstone_foreign_key": "unexpected_foreign_keys",
            "missing_username_unique_index": "critical_unique_index_mismatch",
            "wrong_message_unique_columns_or_order": "critical_unique_index_mismatch",
            "missing_running_partial_unique_index": "critical_unique_index_mismatch",
            "wrong_running_predicate_or_expression": "critical_unique_index_mismatch",
            "invalid_or_not_ready_unique_index": "critical_unique_index_mismatch",
        }
        for scenario, name in cases.items():
            with self.subTest(scenario=scenario):
                docker = FakeDocker()
                key = "blocker." + name
                docker.report[key] = "1"
                code, output = self.invoke(docker)
                self.assertEqual(code, 2)
                self.assertIn(key + "=1", output)
                self.assertNotIn("READY:", output)
                self.assertNotIn("schema_status=baseline_candidate", output)
                self.assertIn("schema_version=unversioned-initializers", output)
                self.assertNotIn("uk_psych_running_turn", output)

    def test_unexpected_columns_contract_covers_exactly_core_tables(self):
        query = PREFLIGHT.schema_contract_queries()["unexpected_columns"]
        for table, columns in PREFLIGHT.COLUMN_CONTRACT.items():
            for column in columns:
                self.assertIn("(" + PREFLIGHT.literal(table) + "," + PREFLIGHT.literal(column) + ")", query)
        self.assertIn("a.attnum>0 AND NOT a.attisdropped", query)
        self.assertIn("NOT EXISTS", query)
        self.assertNotIn("vector_store", query)

    def test_known_history_presence_blocks_even_when_empty_without_disclosing_contents(self):
        sql, expected = PREFLIGHT.build_sql()
        key = "blocker.preexisting_migration_history_tables"
        self.assertEqual(expected[key], "zero")
        self.assertEqual(PREFLIGHT.MIGRATION_HISTORY_TABLES,
                         ("psych_schema_history", "flyway_schema_history"))
        for table in PREFLIGHT.MIGRATION_HISTORY_TABLES:
            self.assertIn(PREFLIGHT.literal(table), sql)
            self.assertNotIn("FROM public." + table, sql)
        # Presence counts are synthetic; neither history contents nor row counts
        # can establish whether a prior migration registry is safe to baseline.
        for value in ("1", "2", "unavailable", None):
            with self.subTest(value=value):
                docker = FakeDocker()
                if value is None:
                    docker.omit = key
                else:
                    docker.report[key] = value
                code, output = self.invoke(docker)
                self.assertEqual(code, 2)
                self.assertNotIn("READY:", output)
                self.assertNotIn("schema_status=baseline_candidate", output)
                self.assertNotIn("schema_version=unversioned-initializers", output)
                self.assertNotIn(SECRET, output)
                if value is not None:
                    self.assertIn(key + "=" + value, output)
                    self.assertIn("schema_version=unknown-preexisting-history", output)

    def test_catalog_contract_metrics_missing_or_unavailable_fail_closed(self):
        for name in PREFLIGHT.schema_contract_queries():
            for unavailable in (False, True):
                with self.subTest(name=name, unavailable=unavailable):
                    docker = FakeDocker()
                    key = "blocker." + name
                    if unavailable:
                        docker.report[key] = "unavailable"
                    else:
                        docker.omit = key
                    code, output = self.invoke(docker)
                    self.assertEqual(code, 2)
                    self.assertNotIn("READY:", output)
                    self.assertNotIn("schema_status=baseline_candidate", output)

    def test_no_running_postgres_fails_without_exec(self):
        docker = FakeDocker()
        docker.ps = ""
        code, output = self.invoke(docker)
        self.assertEqual(code, 2)
        self.assertIn("BLOCKED", output)
        self.assertFalse(any(args[1] == "exec" for args, _ in docker.calls))

    def test_wrong_project_or_volume_is_blocked(self):
        mutations = [lambda d: d.labels.update({"com.docker.compose.project": "another-project"}),
                     lambda d: d.mounts[0].update({"Name": "another_volume"}),
                     lambda d: d.mounts[0].update({"Type": "bind"}),
                     lambda d: d.volume_labels.clear(),
                     lambda d: d.config["volumes"]["postgres_data"].update({"name": ""})]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                docker = FakeDocker()
                mutate(docker)
                code, _ = self.invoke(docker)
                self.assertEqual(code, 2)
                self.assertFalse(any(args[1] == "exec" for args, _ in docker.calls))

    def test_multiple_containers_are_blocked(self):
        docker = FakeDocker()
        docker.ps += "\n" + "b" * 64
        self.assertEqual(self.invoke(docker)[0], 2)

    def test_multiple_running_backends_block_before_psql_without_id_leakage(self):
        docker = FakeDocker()
        docker.add_current_services()
        second_backend = "e" * 64
        docker.other_containers[second_backend] = docker.other_containers[BACKEND_CONTAINER]
        code, output = self.invoke(docker)
        self.assertEqual(code, 2)
        self.assertIn("最多允许一个已运行的 backend", output)
        self.assertNotIn("READY:", output)
        self.assertNotIn(second_backend, output)
        self.assertFalse(any(args[1] == "exec" for args, _ in docker.calls))

    def test_stopped_backend_allows_preflight_with_running_postgres(self):
        docker = FakeDocker()
        docker.add_current_services()
        del docker.other_containers[BACKEND_CONTAINER]
        code, output = self.invoke(docker)
        self.assertEqual(code, 0)
        self.assertIn("READY:", output)
        self.assertIn("mount_inventory.coverage.services_without_running_container=1", output)
        exec_calls = [args for args, _ in docker.calls if args[1] == "exec"]
        self.assertEqual(len(exec_calls), 1)
        self.assertEqual(exec_calls[0][3], CONTAINER)

    def test_running_orphan_or_renamed_backend_blocks_before_psql(self):
        for current_backend_running in (True, False):
            with self.subTest(current_backend_running=current_backend_running):
                docker = FakeDocker()
                docker.add_current_services()
                if not current_backend_running:
                    del docker.other_containers[BACKEND_CONTAINER]
                orphan_id = "f" * 64
                orphan_service = "private-retired-backend"
                docker.other_containers[orphan_id] = (orphan_service, [
                    {"Type": "bind", "Source": HOST_PATH, "Destination": "/retired-private-data"}])
                code, output = self.invoke(docker)
                self.assertEqual(code, 2)
                self.assertIn("未声明服务的运行容器", output)
                self.assertNotIn("READY:", output)
                for hidden in (orphan_id, orphan_service, "/retired-private-data"):
                    self.assertNotIn(hidden, output)
                self.assertFalse(any(args[1] == "exec" for args, _ in docker.calls))

    def test_project_service_metadata_is_bounded_and_fail_closed(self):
        for metadata in ('null', '""', SECRET, '"postgres"\n' * 257, '"' + 'x' * 65536 + '"'):
            with self.subTest(metadata_length=len(metadata)):
                docker = FakeDocker()
                docker.project_services_output = metadata
                code, output = self.invoke(docker)
                self.assertEqual(code, 2)
                self.assertNotIn("READY:", output)
                self.assertFalse(any(args[1] == "exec" for args, _ in docker.calls))

    def test_old_schema_or_owner_integrity_never_adopts(self):
        for metric in ["missing_table.psych_chat_turn", "missing_column.psych_conversation.owner_id",
                       "blocker.owner_null", "blocker.owner_mismatch", "blocker.orphan_messages",
                       "blocker.orphan_memories", "blocker.orphan_turns", "blocker.tombstone_live_collision",
                       "blocker.missing_validated_foreign_keys", "blocker.missing_primary_keys",
                       "blocker.vector_dimension_mismatch"]:
            with self.subTest(metric=metric):
                docker = FakeDocker()
                docker.report[metric] = "1"
                code, output = self.invoke(docker)
                self.assertEqual(code, 2)
                self.assertIn(metric, output)
                self.assertIn("人工", output)

    def test_empty_new_database_never_counts_as_existing_ready(self):
        docker = FakeDocker()
        docker.report["rows.users"] = "0"
        self.assertEqual(self.invoke(docker)[0], 2)

    def test_empty_volume_or_query_error_fails_and_hides_stderr(self):
        docker = FakeDocker()
        docker.exec_code = 41
        code, output = self.invoke(docker)
        self.assertEqual(code, 2)
        self.assertNotIn("READY:", output)

    def test_missing_report_row_is_blocked(self):
        docker = FakeDocker()
        docker.omit = "snapshot_complete"
        self.assertEqual(self.invoke(docker)[0], 2)

    def test_unavailable_old_schema_metrics_are_blocked(self):
        docker = FakeDocker()
        docker.report["missing_column.psych_conversation.owner_id"] = "1"
        docker.report["blocker.owner_null"] = "unavailable"
        self.assertEqual(self.invoke(docker)[0], 2)

    def test_injected_output_and_secret_values_are_hidden(self):
        for extra in ["\n" + SECRET, "\nrows.users\t9"]:
            docker = FakeDocker()
            docker.extra_output = extra
            code, output = self.invoke(docker)
            self.assertEqual(code, 2)
            self.assertNotIn("READY:", output)
        docker = FakeDocker()
        docker.report["pgvector_version"] = SECRET
        self.assertEqual(self.invoke(docker)[0], 2)

    def test_readwrite_session_and_missing_extension_are_blocked(self):
        for metric, value in [("read_only", "off"), ("pgvector_version", "missing"), ("postgres_version_num", "170001")]:
            docker = FakeDocker()
            docker.report[metric] = value
            self.assertEqual(self.invoke(docker)[0], 2)

    def test_deleted_user_tombstones_are_informational(self):
        docker = FakeDocker()
        docker.report["info.tombstone_owner_absent"] = "50"
        self.assertEqual(self.invoke(docker)[0], 0)

    def test_docker_exception_does_not_disclose_error(self):
        docker = FakeDocker()
        docker.run = lambda *args, **kwargs: (_ for _ in ()).throw(OSError(SECRET))
        self.assertEqual(self.invoke(docker)[0], 2)


if __name__ == "__main__":
    unittest.main()
