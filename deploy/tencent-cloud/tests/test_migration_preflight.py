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
            elif args[-5:] == ["ps", "--status", "running", "-q", "postgres"]:
                output = self.ps
            else:
                raise AssertionError("Lifecycle or unapproved Compose command")
        elif args[:2] == ["docker", "inspect"]:
            output = "\n".join(json.dumps(x) for x in (self.labels, self.mounts, True))
        elif args[:3] == ["docker", "volume", "inspect"]:
            output = json.dumps(self.volume_labels)
        elif args[:3] == ["docker", "exec", "-i"]:
            output = "\n".join(f"{key}\t{value}" for key, value in self.report.items() if key != self.omit)
            output += self.extra_output
            return subprocess.CompletedProcess(args, self.exec_code, output, SECRET)
        else:
            raise AssertionError("Unapproved Docker command")
        return subprocess.CompletedProcess(args, 0, output, SECRET)


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
        return code, output.getvalue()

    def test_ready_is_read_only_and_python_does_not_open_env_or_restart(self):
        docker = FakeDocker()
        code, output = self.invoke(docker)
        self.assertEqual(code, 0)
        self.assertIn("READY:", output)
        self.assertIn("schema_version=unversioned-initializers", output)
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
