"""Synthetic command-boundary tests; never contact Docker or a real database."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


MANAGE = Path(__file__).resolve().parents[1] / "manage.sh"
SECRET = "must-not-appear-private-docker-diagnostic"
CONFIG = {
    "name": "synthetic-project",
    "services": {
        "postgres": {"volumes": [{"type": "volume", "source": "postgres_data", "target": "/var/lib/postgresql/data"}]},
        "backend": {"volumes": [
            {"type": "volume", "source": "onnx_cache", "target": "/tmp/spring-ai-onnx-generative"},
            {"type": "volume", "source": "djl_cache", "target": "/home/app/.djl.ai"}]},
        "frontend": {}, "ai-worker": {},
    },
    "volumes": {key: {"name": "synthetic-project_" + key}
                for key in ("postgres_data", "onnx_cache", "djl_cache")},
}

DOCKER_STUB = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p = Path(os.environ["SYNTHETIC_STATE"])
s = json.loads(p.read_text())
a = sys.argv[1:]
with open(os.environ["SYNTHETIC_EVENTS"], "a") as f:
    f.write(json.dumps(["docker"] + a) + "\n")
def emit_rows(values):
    for value in values:
        print(json.dumps(value))
if a[:1] == ["info"]:
    sys.exit(0)
if a[:1] == ["compose"]:
    a = a[1:]
    if a == ["version"]:
        sys.exit(0)
    a = a[4:]  # --env-file PATH -f PATH
    if a[:1] == ["config"]:
        if s.get("config_error"):
            print(os.environ["SYNTHETIC_SECRET"], file=sys.stderr)
            sys.exit(1)
        if "--format" in a:
            print(json.dumps(s["config"]))
        sys.exit(0)
    if a[:1] == ["stop"]:
        s["active"] = [v for v in s["active"] if v not in a[1:]]
        p.write_text(json.dumps(s))
        sys.exit(s.get("stop_code", 0))
    if a[:1] == ["up"]:
        if s.get("up_error"):
            print(os.environ["SYNTHETIC_SECRET"], file=sys.stderr)
            sys.exit(1)
        sys.exit(0)
    if a[:1] == ["ps"]:
        sys.exit(0)
elif a[:2] == ["volume", "ls"]:
    emit_rows(s.get("old_database_volumes", []) if "--filter" in a else s.get("volumes", []))
    sys.exit(0)
elif a[:2] == ["volume", "inspect"]:
    if s.get("missing_volume"):
        print(os.environ["SYNTHETIC_SECRET"], file=sys.stderr)
        sys.exit(1)
    print(json.dumps(s.get("volume_labels", {"com.docker.compose.project": "synthetic-project",
                                             "com.docker.compose.volume": "postgres_data"})))
    sys.exit(0)
elif a[:1] == ["ps"]:
    if any(v.startswith("volume=") for v in a):
        emit_rows(s.get("volume_users", []))
    elif "-a" in a:
        emit_rows(s.get("containers", []))
    else:
        emit_rows(s["active"])
    sys.exit(0)
raise SystemExit("unsupported synthetic Docker call")
'''

PREFLIGHT_STUB = r'''import json, os, sys
from pathlib import Path
s = json.loads(Path(os.environ["SYNTHETIC_STATE"]).read_text())
with open(os.environ["SYNTHETIC_EVENTS"], "a") as f:
    f.write(json.dumps(["preflight"]) + "\n")
code = s.get("preflight_code", 0 if s["active"] == ["postgres"] else 2)
print("READY: synthetic read-only snapshot" if code == 0 else "BLOCKED: synthetic snapshot")
sys.exit(code)
'''


@unittest.skipUnless(os.name == "posix" and shutil.which("bash") and shutil.which("python3"),
                     "requires POSIX Bash and Python 3; all external commands remain synthetic")
class ManageMigrationGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copyfile(MANAGE, self.root / "manage.sh")
        (self.root / "migration-preflight.py").write_text(PREFLIGHT_STUB, encoding="utf-8")
        app = self.root / "dk-ai-agent"
        app.mkdir()
        (app / "docker-compose.yml").write_text("name: ${COMPOSE_PROJECT_NAME}\n", encoding="utf-8")
        (app / ".env").write_text(
            "DEEPSEEK_API_KEY=synthetic-long-api-key\nPOSTGRES_PASSWORD=synthetic-long-db-password\n"
            "AI_WORKER_SHARED_SECRET=synthetic-long-worker-secret\nADMIN_INITIAL_PASSWORD=synthetic-admin-password\n"
            "SESSION_COOKIE_SECURE=false\nFRONTEND_BIND_ADDRESS=127.0.0.1\nSTARTUP_TIMEOUT_SECONDS=5\n",
            encoding="utf-8")
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        for command, source in {"docker": DOCKER_STUB, "curl": "#!/bin/sh\nexit 0\n",
                                "ss": "#!/bin/sh\nexit 0\n"}.items():
            target = bin_dir / command
            target.write_text(source, encoding="utf-8")
            target.chmod(0o700)
        self.state_path = self.root / "state.json"
        self.events_path = self.root / "events.jsonl"
        self.state = {"config": json.loads(json.dumps(CONFIG)), "active": ["postgres"],
                      "containers": [], "volumes": []}
        self.env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                        SYNTHETIC_STATE=str(self.state_path), SYNTHETIC_EVENTS=str(self.events_path),
                        SYNTHETIC_SECRET=SECRET)

    def invoke(self, command):
        self.state_path.write_text(json.dumps(self.state), encoding="utf-8")
        result = subprocess.run(["bash", str(self.root / "manage.sh"), command], env=self.env,
                                text=True, encoding="utf-8", stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=30)
        events = [json.loads(line) for line in self.events_path.read_text().splitlines()]
        self.assertNotIn(SECRET, result.stdout)
        return result.returncode, result.stdout, events

    def ups(self, events):
        return [event for event in events if event[0:2] == ["docker", "compose"] and "up" in event]

    def test_existing_deploy_runs_preflight_before_any_up(self):
        code, _, events = self.invoke("deploy")
        self.assertEqual(code, 0)
        self.assertEqual(len(self.ups(events)), 1)
        self.assertLess(events.index(["preflight"]), events.index(self.ups(events)[0]))

    def test_blocked_or_failed_preflight_never_starts_apps(self):
        for command in ("deploy", "start", "restart"):
            for preflight_code in (2, 1, 97):
                with self.subTest(command=command, preflight_code=preflight_code):
                    self.events_path.unlink(missing_ok=True)
                    self.state["preflight_code"] = preflight_code
                    code, _, events = self.invoke(command)
                    self.assertEqual(code, 2)
                    self.assertFalse(self.ups(events))

    def test_stopped_postgres_with_existing_volume_never_becomes_first_install(self):
        self.state.update(active=[], volumes=["synthetic-project_postgres_data"])
        for command in ("deploy", "start", "restart", "deploy-initial"):
            with self.subTest(command=command):
                self.events_path.unlink(missing_ok=True)
                code, _, events = self.invoke(command)
                self.assertEqual(code, 2)
                self.assertFalse(self.ups(events))

    def test_default_deploy_on_empty_host_requires_explicit_initial_command(self):
        self.state["active"] = []
        code, _, events = self.invoke("deploy")
        self.assertEqual(code, 2)
        self.assertFalse(self.ups(events))

    def test_explicit_initial_allows_only_confirmed_unused_project_and_storage(self):
        self.state["active"] = []
        code, _, events = self.invoke("deploy-initial")
        self.assertEqual(code, 0)
        self.assertEqual(len(self.ups(events)), 1)
        self.assertNotIn(["preflight"], events)

    def test_initial_refuses_old_stopped_container_cache_volume_or_renamed_project(self):
        scenarios = ({"containers": ["stopped-old-container"]},
                     {"volumes": ["synthetic-project_onnx_cache"]},
                     {"old_database_volumes": ["other-project_postgres_data"]})
        for changes in scenarios:
            with self.subTest(changes=changes):
                self.events_path.unlink(missing_ok=True)
                self.state.update(active=[], containers=[], volumes=[], old_database_volumes=[])
                self.state.update(changes)
                code, _, events = self.invoke("deploy-initial")
                self.assertEqual(code, 2)
                self.assertFalse(self.ups(events))

    def test_initial_refuses_bind_data_directory(self):
        self.state["active"] = []
        self.state["config"]["services"]["postgres"]["volumes"][0].update(type="bind", source="/private/data")
        code, output, events = self.invoke("deploy-initial")
        self.assertEqual(code, 2)
        self.assertNotIn("/private/data", output)
        self.assertFalse(self.ups(events))

    def test_deploy_and_start_refuse_running_old_apps_without_stopping_them(self):
        self.state["active"] = ["postgres", "backend"]
        for command in ("deploy", "start"):
            with self.subTest(command=command):
                self.events_path.unlink(missing_ok=True)
                code, _, events = self.invoke(command)
                self.assertEqual(code, 2)
                self.assertFalse(self.ups(events))
                self.assertNotIn(["preflight"], events)
                self.assertFalse(any("stop" in event for event in events))

    def test_restart_stops_apps_then_preflights_and_starts_only_apps(self):
        self.state["active"] = ["postgres", "backend", "frontend", "ai-worker"]
        code, _, events = self.invoke("restart")
        self.assertEqual(code, 0)
        stop = next(event for event in events if "stop" in event)
        up = self.ups(events)[0]
        self.assertLess(events.index(stop), events.index(["preflight"]))
        self.assertLess(events.index(["preflight"]), events.index(up))
        self.assertNotIn("postgres", stop)
        self.assertNotIn("postgres", up)
        self.assertIn("--no-deps", up)

    def test_start_database_starts_only_verified_existing_volume(self):
        self.state["active"] = []
        code, _, events = self.invoke("start-database")
        self.assertEqual(code, 0)
        up = self.ups(events)[0]
        self.assertEqual(up[-1], "postgres")
        self.assertIn("--no-deps", up)
        self.assertNotIn(["preflight"], events)
        for service in ("backend", "frontend", "ai-worker"):
            self.assertNotIn(service, up)

    def test_start_database_refuses_missing_wrong_or_busy_volume(self):
        for changes in ({"missing_volume": True}, {"volume_labels": {}},
                        {"volume_labels": {"com.docker.compose.project": "wrong", "com.docker.compose.volume": "postgres_data"}},
                        {"volume_users": ["another-running-container"]}, {"active": ["postgres"]}):
            with self.subTest(changes=changes):
                self.events_path.unlink(missing_ok=True)
                self.state = {"config": json.loads(json.dumps(CONFIG)), "active": [], **changes}
                code, _, events = self.invoke("start-database")
                self.assertEqual(code, 2)
                self.assertFalse(self.ups(events))

    def test_operational_errors_use_one_and_hide_raw_diagnostics(self):
        self.state["config_error"] = True
        code, _, events = self.invoke("deploy")
        self.assertEqual(code, 1)
        self.assertFalse(self.ups(events))


if __name__ == "__main__":
    unittest.main()
