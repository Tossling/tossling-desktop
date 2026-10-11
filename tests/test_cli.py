import hashlib
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOSSLING = os.path.join(ROOT, "bin", "tossling")


class CliTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="tossling-test-")
        self.config = os.path.join(self.home, ".config", "tossling", "config.json")
        self.old_config = os.path.join(self.home, ".config", "mnrh", "clip.json")
        self.fakes = os.path.join(self.home, "fake-bin")
        self.calls = os.path.join(self.home, "system-calls.log")
        os.makedirs(self.fakes)
        for tool in ("launchctl", "pluginkit", "pbs", "pbcopy", "pbpaste", "qlmanage", "osascript", "open"):
            path = os.path.join(self.fakes, tool)
            with open(path, "w") as f:
                f.write(f'#!/bin/sh\necho "{tool} $*" >> "{self.calls}"\nexit 1\n')
            os.chmod(path, 0o755)

    def tearDown(self):
        try:
            with open(self.calls) as f:
                calls = f.read()
        except OSError:
            calls = ""
        shutil.rmtree(self.home, ignore_errors=True)
        self.assertEqual(calls, "", "тест вызвал системные команды вне временного HOME")

    def run_tossling(self, *args, lang="ru", extra=None):
        env = {"HOME": self.home, "PATH": f"{self.fakes}:/usr/bin:/bin:/usr/sbin:/sbin", "TOSSLING_NO_MENU": "1",
               "LANG": "ru_RU.UTF-8", "TOSSLING_FROM_SOURCE": "1", **(extra or {})}
        if lang:
            env["TOSSLING_LANG"] = lang
        return subprocess.run([TOSSLING, *args], env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=60)

    def write(self, path, data, mode=0o600):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(data, f)
        os.chmod(path, mode)

    def test_help(self):
        r = self.run_tossling("--help")
        self.assertEqual(r.returncode, 0)
        self.assertIn("tossling setup", r.stdout)
        self.assertNotIn("mnrh", r.stdout)

    def test_english(self):
        self.assertIn("a menu of actions", self.run_tossling("--help", lang="en").stdout)
        self.assertIn("is not set up", self.run_tossling("status", lang="en").stdout)

    def test_language_setting(self):
        self.run_tossling("language", "ru", lang=None)
        self.assertIn("не настроен", self.run_tossling("status", lang=None).stdout)
        self.run_tossling("language", "en", lang=None)
        self.assertIn("is not set up", self.run_tossling("status", lang=None).stdout)
        with open(self.config) as f:
            self.assertEqual(json.load(f)["language"], "en")
        self.assertEqual(self.run_tossling("language", "klingon").returncode, 1)

    def test_bundled_cli_does_not_build(self):
        command = self.bundle_cli(os.path.join(self.home, "Applications", "Tossling.app"))
        self.write(self.config, {"server": "https://ntfy.example.com", "token": "tk_test", "room": "tossy-x", "key": "k", "device_id": "d"})
        env = {"HOME": self.home, "PATH": f"{self.fakes}:/usr/bin:/bin:/usr/sbin:/sbin", "TOSSLING_NO_MENU": "1", "TOSSLING_LANG": "en"}
        r = subprocess.run([command, "status"], env=env, capture_output=True, text=True,
                           stdin=subprocess.DEVNULL, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("Building", r.stdout + r.stderr)
        self.assertIn("is off", r.stdout)

    def bundle_cli(self, bundle):
        inside = os.path.join(bundle, "Contents", "Resources", "tossling")
        shutil.copytree(os.path.join(ROOT, "cli"), os.path.join(inside, "cli"), ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copytree(os.path.join(ROOT, "bin"), os.path.join(inside, "bin"))
        shutil.copy(os.path.join(ROOT, "VERSION"), inside)
        return os.path.join(inside, "bin", "tossling")

    def test_bundled_app_adopts_older_installs(self):
        command = self.bundle_cli(os.path.join(self.home, "Apps", "Tossling.app"))
        old = {"server": "https://ntfy.example.com", "token": "tk_old", "name": "Test Mac"}
        self.write(self.old_config, old, mode=0o644)
        source = os.path.join(self.home, "Applications", "Tossling.app")
        marker = os.path.join(self.home, "unregistered")
        os.makedirs(os.path.join(source, "Contents", "MacOS"))
        binary = os.path.join(source, "Contents", "MacOS", "Tossling")
        with open(binary, "w") as f:
            f.write(f'#!/bin/sh\necho "$*" >> "{marker}"\n')
        os.chmod(binary, 0o755)
        old_app = os.path.join(self.home, "Applications", "Tossy.app")
        os.makedirs(os.path.join(old_app, "Contents", "MacOS"))
        old_binary = os.path.join(old_app, "Contents", "MacOS", "Tossy")
        with open(old_binary, "w") as f:
            f.write(f'#!/bin/sh\necho "old $*" >> "{marker}"\n')
        os.chmod(old_binary, 0o755)
        finder = os.path.join(self.home, "Library", "Application Support", "Tossy", "Tossy Finder.app")
        os.makedirs(os.path.join(finder, "Contents", "PlugIns", "TossyFinder.appex"))
        env = {"HOME": self.home, "PATH": f"{self.fakes}:/usr/bin:/bin:/usr/sbin:/sbin", "TOSSLING_LANG": "en"}
        r = subprocess.run([command, "adopt"], env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        with open(self.config) as f:
            self.assertEqual(json.load(f), old)
        with open(marker) as f:
            self.assertEqual(sorted(f.read().splitlines()), ["--unregister", "old --unregister"])
        self.assertFalse(os.path.exists(source))
        self.assertFalse(os.path.exists(old_app))
        self.assertFalse(os.path.exists(finder))
        with open(self.calls) as f:
            calls = f.read()
        self.assertIn("launchctl list", calls)
        self.assertIn("pluginkit -r", calls)
        self.assertIn("pluginkit -e use", calls)
        os.remove(self.calls)

    def test_adopt_is_only_for_the_app(self):
        r = self.run_tossling("adopt")
        self.assertEqual(r.returncode, 2)
        self.assertFalse(os.path.exists(self.config))

    def test_off_keeps_room_members_and_remove_deletes_them(self):
        self.write(self.config, {"server": "https://ntfy.example.com", "token": "tk_test", "room": "tossy-x", "key": "k", "device_id": "d"})
        state = os.path.join(self.home, ".cache", "tossling", "state.json")
        self.write(state, {"pid": 0, "room": "tossy-x", "members": {"abc": {"name": "Pixel", "src": "android", "pk": "x"}}})
        self.assertEqual(self.run_tossling("off").returncode, 0)
        with open(state) as f:
            self.assertIn("abc", json.load(f)["members"])
        self.assertEqual(self.run_tossling("remove").returncode, 0)
        self.assertFalse(os.path.exists(state))
        self.assertFalse(os.path.exists(self.config))
        os.remove(self.calls)

    def test_unknown_action(self):
        self.assertEqual(self.run_tossling("nothing").returncode, 2)

    def test_version(self):
        with open(os.path.join(ROOT, "VERSION")) as f:
            self.assertEqual(self.run_tossling("--version").stdout.strip(), f.read().strip())

    def test_status_without_config(self):
        r = self.run_tossling("status")
        self.assertEqual(r.returncode, 0)
        self.assertIn("не настроен", r.stdout)
        self.assertFalse(os.path.exists(self.config))

    def test_bare_without_terminal_is_status(self):
        self.assertIn("не настроен", self.run_tossling().stdout)

    def test_config_json_empty(self):
        r = self.run_tossling("config", "--json")
        self.assertEqual(r.returncode, 1)
        self.assertEqual(json.loads(r.stdout), {})

    def test_config_json(self):
        self.write(self.config, {"server": "https://ntfy.example.com", "token": "tk_test", "key": "secret",
                                 "alert_topics": {"mac": False}})
        r = self.run_tossling("config", "--json")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(json.loads(r.stdout), {"server": "https://ntfy.example.com", "token": "tk_test",
                                                "alert_topics": {"mac": False}})

    def test_config_json_reads_mnrh_without_moving(self):
        self.write(self.old_config, {"server": "https://ntfy.example.com", "token": "tk_old"})
        r = self.run_tossling("config", "--json")
        self.assertEqual(json.loads(r.stdout)["token"], "tk_old")
        self.assertFalse(os.path.lexists(self.config))
        self.assertFalse(os.path.islink(self.old_config))

    def test_migration_from_mnrh(self):
        old = {"server": "https://ntfy.example.com", "token": "tk_old", "name": "Test Mac"}
        self.write(self.old_config, old, mode=0o644)
        history = os.path.join(self.home, ".cache", "mnrh", "clip-history")
        os.makedirs(history)
        with open(os.path.join(history, "index.json"), "w") as f:
            f.write("[]")
        log = os.path.join(self.home, "Library", "Logs", "mnrh-clip.log")
        os.makedirs(os.path.dirname(log))
        with open(log, "w") as f:
            f.write("old log\n")
        members = {"abc": {"name": "Pixel", "src": "android", "pk": "x"}}
        self.write(os.path.join(self.home, ".cache", "mnrh", "clip.json"), {"pid": 0, "last_id": "m1", "members": members})
        r = self.run_tossling("status")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("перенесены", r.stdout)
        with open(os.path.join(self.home, ".cache", "tossling", "state.json")) as f:
            self.assertEqual(json.load(f)["members"], members)
        with open(self.config) as f:
            self.assertEqual(json.load(f), old)
        self.assertEqual(stat.S_IMODE(os.stat(self.config).st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(os.stat(os.path.dirname(self.config)).st_mode), 0o700)
        self.assertTrue(os.path.islink(self.old_config))
        self.assertEqual(os.path.realpath(self.old_config), os.path.realpath(self.config))
        self.assertTrue(os.path.exists(os.path.join(self.home, ".cache", "tossling", "history", "index.json")))
        self.assertFalse(os.path.exists(history))
        self.assertTrue(os.path.exists(os.path.join(self.home, "Library", "Logs", "Tossling.log")))
        again = self.run_tossling("status")
        self.assertNotIn("перенесены", again.stdout)

    def test_migration_from_tossy(self):
        old_dir = os.path.join(self.home, ".config", "tossy")
        old = {"server": "https://tossy.example.com", "token": "tk_old", "room": "tossy-x", "key": "k", "device_id": "d"}
        self.write(os.path.join(old_dir, "config.json"), old)
        members = {"abc": {"name": "Pixel", "src": "android", "pk": "x"}}
        self.write(os.path.join(self.home, ".cache", "tossy", "state.json"), {"pid": 0, "room": "tossy-x", "members": members})
        log = os.path.join(self.home, "Library", "Logs", "Tossy.log")
        os.makedirs(os.path.dirname(log))
        with open(log, "w") as f:
            f.write("old log\n")
        r = self.run_tossling("status", lang="en")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("settings moved from ~/.config/tossy to ~/.config/tossling", r.stdout)
        with open(self.config) as f:
            self.assertEqual(json.load(f), old)
        self.assertTrue(os.path.islink(old_dir))
        self.assertEqual(os.path.realpath(old_dir), os.path.realpath(os.path.dirname(self.config)))
        with open(os.path.join(self.home, ".cache", "tossling", "state.json")) as f:
            self.assertEqual(json.load(f)["members"], members)
        self.assertTrue(os.path.exists(os.path.join(self.home, "Library", "Logs", "Tossling.log")))
        self.assertNotIn("settings moved", self.run_tossling("status", lang="en").stdout)

    def test_no_migration_over_existing_config(self):
        self.write(self.config, {"server": "https://new.example.com", "token": "tk_new"})
        self.write(self.old_config, {"server": "https://old.example.com", "token": "tk_old"})
        self.run_tossling("status")
        with open(self.config) as f:
            self.assertEqual(json.load(f)["token"], "tk_new")
        self.assertFalse(os.path.islink(self.old_config))

    def test_migration_leaves_launchd_alone_without_mnrh_helper(self):
        self.write(self.old_config, {"server": "https://ntfy.example.com", "token": "tk_old"})
        self.write(os.path.join(self.home, ".cache", "mnrh", "clip.json"), {"pid": os.getpid()})
        self.assertIn("перенесены", self.run_tossling("status").stdout)

    def test_projects_through_the_server_api(self):
        calls = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, code, body):
                data = json.dumps(body).encode() if body is not None else b""
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                calls.append(("GET", self.path, self.headers.get("Authorization")))
                self.reply(200, [{"topic": "backend-alerts", "name": "Backend", "publisher": "backend"}, {"topic": "mac", "name": "Mac"}])

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append(("POST", self.path, body))
                self.reply(201, {"topic": body["topic"], "name": body["name"] or body["topic"], "publisher": body["publisher"] or body["topic"],
                                 "token": "tk_" + "p" * 29, "example": "curl …"})

            def do_DELETE(self):
                calls.append(("DELETE", self.path, None))
                self.reply(204, None)

        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        url = f"http://127.0.0.1:{server.server_port}"
        self.write(self.config, {"server": url, "token": "tk_" + "d" * 29, "room": "tossy-x", "key": "k", "device_id": "d"})
        listed = self.run_tossling("project", lang="en")
        self.assertIn("backend-alerts", listed.stdout)
        self.assertIn("written by the devices", listed.stdout)
        added = self.run_tossling("project", "add", "router", "Home", "router", "--publisher", "net", lang="en")
        self.assertIn("tk_ppp", added.stdout)
        self.assertEqual(calls[-1], ("POST", "/v1/tossling/projects", {"topic": "router", "name": "Home router", "publisher": "net"}))
        self.run_tossling("project", "remove", "router", lang="en")
        self.assertEqual(calls[-1][:2], ("DELETE", "/v1/tossling/projects/router"))
        self.assertEqual(calls[0][2], "Bearer tk_" + "d" * 29)

    def test_refused_token_pauses_requests(self):
        calls = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                calls.append(self.path)
                data = json.dumps({"code": 40101, "http": 401, "error": "unauthorized"}).encode()
                self.send_response(401)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        url = f"http://127.0.0.1:{server.server_port}"
        self.write(self.config, {"server": url, "token": "tk_" + "d" * 29, "room": "tossy-x", "key": "k", "device_id": "d"})
        first = self.run_tossling("project", lang="en")
        self.assertIn("HTTP 401", first.stderr)
        self.assertEqual(len(calls), 1)
        second = self.run_tossling("project", lang="en")
        self.assertIn("no longer accepts this computer's token", second.stderr)
        self.assertEqual(len(calls), 1)
        with open(os.path.join(self.home, ".cache", "tossling", "refusals.json")) as f:
            entries = json.load(f)
        self.assertEqual([e["n"] for e in entries.values()], [1])
        self.assertTrue(55 < list(entries.values())[0]["until"] - time.time() <= 60)
        self.write(self.config, {"server": url, "token": "tk_" + "e" * 29, "room": "tossy-x", "key": "k", "device_id": "d"})
        self.run_tossling("project", lang="en")
        self.assertEqual(len(calls), 2)

    def fake_helper(self):
        app = os.path.join(self.home, "Applications", "Tossling.app", "Contents")
        os.makedirs(os.path.join(app, "Library", "LaunchAgents"))
        os.makedirs(os.path.join(app, "MacOS"))
        os.makedirs(os.path.join(app, "Resources"))
        with open(os.path.join(app, "MacOS", "Tossling"), "w") as f:
            f.write("#!/bin/sh\necho enabled\n")
        os.chmod(os.path.join(app, "MacOS", "Tossling"), 0o755)
        env = {"HOME": self.home, "PATH": f"{self.fakes}:/usr/bin:/bin:/usr/sbin:/sbin", "TOSSLING_FROM_SOURCE": "1"}
        stamp = subprocess.run(["/usr/bin/python3", "-c", "import sys; sys.path.insert(0, sys.argv[1]); from helper import Helper; print(Helper().source_hash())",
                                os.path.join(ROOT, "cli", "lib")], env=env, capture_output=True, text=True, timeout=60).stdout.strip()
        with open(os.path.join(app, "Resources", "source.sha256"), "w") as f:
            f.write(stamp)
        sleeper = subprocess.Popen(["/bin/sleep", "60"])
        self.addCleanup(sleeper.wait)
        self.addCleanup(sleeper.kill)
        self.write(os.path.join(self.home, ".cache", "tossling", "state.json"), {"pid": sleeper.pid, "started": time.time() + 100, "connected": False})

    def test_status_says_the_token_is_refused(self):
        self.fake_helper()
        token = "tk_" + "d" * 29
        self.write(self.config, {"server": "https://Ntfy.example.com", "token": token, "room": "tossy-x", "key": "k", "device_id": "d"})
        key = "token https://ntfy.example.com " + hashlib.sha256(token.encode()).hexdigest()[:32]
        self.write(os.path.join(self.home, ".cache", "tossling", "refusals.json"), {key: {"n": 1, "until": time.time() + 60}})
        refused = self.run_tossling("status", lang="en").stdout
        self.assertIn("The server no longer accepts this computer's token. Connect it to the room again.", refused)
        self.assertIn("tossling join", refused)
        self.assertNotIn("no connection", refused)
        self.write(os.path.join(self.home, ".cache", "tossling", "refusals.json"), {"address https://ntfy.example.com": {"n": 2, "until": time.time() + 120}})
        self.assertIn("слишком много запросов, сервер просит подождать", self.run_tossling("status").stdout)

    def device_server(self, devices):
        calls = []
        issued = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, code, body):
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def body(self):
                return self.rfile.read(int(self.headers.get("Content-Length") or 0))

            def do_GET(self):
                calls.append(("GET", self.path, self.headers.get("Authorization")))
                if self.path == "/v1/tossling/health":
                    self.reply(200, {"server": "tossling-server", "version": "0.5.0", **({"devices": True} if devices["on"] else {})})
                elif self.path == "/v1/account":
                    self.reply(200, {"username": "tossy"})
                elif self.path == "/v1/tossling/projects":
                    self.reply(200, [])
                else:
                    self.reply(404, {"error": "not found"})

            def do_PUT(self):
                self.body()
                calls.append(("PUT", self.path, self.headers.get("Authorization")))
                self.reply(200, {"id": "m1", "attachment": {"name": "probe.bin"}})

            def do_POST(self):
                body = json.loads(self.body())
                calls.append(("POST", self.path, self.headers.get("Authorization"), body))
                issued.append("tk_own" + str(len(issued) + 1) * 26)
                self.reply(200, {"token": issued[-1]})

            def do_DELETE(self):
                calls.append(("DELETE", self.path, self.headers.get("Authorization")))
                self.reply(200, {"removed": 1})

        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}", calls, issued

    def read_config(self):
        with open(self.config) as f:
            return json.load(f)

    def test_setup_gets_an_own_token_only_when_health_says_devices(self):
        devices = {"on": False}
        url, calls, issued = self.device_server(devices)
        self.fake_helper()
        room_token = "tk_" + "r" * 29
        first = self.run_tossling("setup", url, lang="en", extra={"TOSSLING_TOKEN": room_token})
        self.assertIn("attachments work", first.stdout)
        self.assertNotIn("token of its own", first.stdout)
        conf = self.read_config()
        self.assertEqual(conf["token"], room_token)
        self.assertNotIn("own", conf)
        self.assertFalse([c for c in calls if c[0] in ("POST", "DELETE")])
        self.assertIn("token: the room's shared token", self.run_tossling("status", lang="en").stdout)

        devices["on"] = True
        self.assertIn("the server gave this Mac a token of its own", self.run_tossling("setup", url, lang="en").stdout)
        conf = self.read_config()
        self.assertEqual(conf["own"], issued[0])
        self.assertEqual(conf["token"], room_token)
        post = [c for c in calls if c[0] == "POST"]
        self.assertEqual(post, [("POST", "/v1/tossling/devices", f"Bearer {room_token}", {"id": conf["device_id"], "name": post[0][3]["name"]})])
        self.assertTrue(post[0][3]["name"])
        self.assertIn("token: this Mac's own", self.run_tossling("status", lang="en").stdout)

        before = len(calls)
        self.run_tossling("project", lang="en")
        self.assertEqual(calls[before:], [("GET", "/v1/tossling/projects", f"Bearer {issued[0]}")])
        self.assertEqual(json.loads(self.run_tossling("config", "--json").stdout)["token"], issued[0])

        rotated = "tk_" + "n" * 29
        self.run_tossling("setup", url, "--token", lang="en", extra={"TOSSLING_TOKEN": rotated})
        conf = self.read_config()
        self.assertEqual((conf["token"], conf["own"]), (rotated, issued[0]))
        self.assertEqual(len(issued), 1)
        self.assertFalse([c for c in calls if c[0] == "DELETE"])

        room = conf["room"]
        self.run_tossling("setup", url, "--new", lang="en")
        conf = self.read_config()
        self.assertNotEqual(conf["room"], room)
        self.assertEqual([c for c in calls if c[0] == "DELETE"], [("DELETE", f"/v1/tossling/devices/{conf['device_id']}", f"Bearer {issued[0]}")])
        self.assertEqual(len(issued), 2)
        self.assertEqual((conf["token"], conf["own"]), (rotated, issued[1]))
        self.assertEqual(calls[-1][:3], ("POST", "/v1/tossling/devices", f"Bearer {rotated}"))
        with open(self.calls) as f:
            self.assertEqual({line.split()[0] for line in f.read().splitlines()}, {"launchctl"})
        os.remove(self.calls)

    def test_remove_deletes_the_own_token(self):
        url, calls, _ = self.device_server({"on": True})
        own = "tk_" + "o" * 29
        self.write(self.config, {"server": url, "token": "tk_" + "d" * 29, "own": own, "room": "tossling-x", "key": "k", "device_id": "d1"})
        self.assertEqual(self.run_tossling("remove").returncode, 0)
        self.assertEqual(calls, [("DELETE", "/v1/tossling/devices/d1", f"Bearer {own}")])
        self.assertFalse(os.path.exists(self.config))
        if os.path.exists(self.calls):
            os.remove(self.calls)

    def test_pause_writes_private_config(self):
        self.write(self.config, {"server": "https://ntfy.example.com", "token": "tk_test"})
        r = self.run_tossling("pause", "5")
        self.assertEqual(r.returncode, 0, r.stderr)
        with open(self.config) as f:
            self.assertGreater(json.load(f)["paused_until"], 0)
        self.assertEqual(stat.S_IMODE(os.stat(self.config).st_mode), 0o600)


if __name__ == "__main__":
    unittest.main()
