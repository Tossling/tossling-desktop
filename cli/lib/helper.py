import hashlib
import json
import os
import plistlib
import shutil
import subprocess
import sys
import tempfile
import time

from tosslinglib import APP_DIR, BUNDLE_ID, BUNDLED_APP, CONFIG, LOG, LSREGISTER, ROOT, STATE, WARN, settings_path, signing_identity, t, version

SRC = os.path.join(ROOT, "mac", "Tossling")
EXE = "Tossling"
URL_SCHEMES = ("tossling",)


class Helper:
    def __init__(self, app_dir=APP_DIR):
        self.base = BUNDLE_ID
        self.display = "Tossling"
        self.bundled = BUNDLED_APP is not None
        self.app = BUNDLED_APP or os.path.join(app_dir, "Tossling.app")
        self.bin = os.path.join(self.app, "Contents", "MacOS", EXE)
        self.stamp = os.path.join(self.app, "Contents", "Resources", "source.sha256")
        self.state_file = STATE
        self.log = LOG
        self.service_args = ["--config", CONFIG]
        self.domain = f"gui/{os.getuid()}"
        self.agents_dir = os.path.join(self.app, "Contents", "Library", "LaunchAgents")

    @property
    def label(self):
        try:
            with open(os.path.join(self.app, "Contents", "Info.plist"), "rb") as f:
                info = plistlib.load(f)
                return info.get("TosslingServiceLabel") or self.base
        except (OSError, ValueError):
            return self.base

    def sources(self):
        return [os.path.join(SRC, "main.swift")] + sorted(os.path.join(SRC, f) for f in os.listdir(SRC)
                                                          if f.endswith(".swift") and f != "main.swift")

    def icon(self):
        p = os.path.join(SRC, "icon.png")
        return p if os.path.exists(p) else None

    def content_hash(self):
        h = hashlib.sha256()
        for p in (*self.sources(), self.icon()):
            if p:
                with open(p, "rb") as f:
                    h.update(f.read())
        return h

    def build_label(self):
        h = self.content_hash()
        h.update(json.dumps([self.service_args, self.state_file, self.log]).encode())
        return f"{self.base}.{h.hexdigest()[:8]}"

    def source_hash(self):
        if self.bundled:
            return "bundled"
        h = self.content_hash()
        h.update(self.service_plist(self.build_label()))
        h.update(repr((URL_SCHEMES, version(), signing_identity())).encode())
        return h.hexdigest()

    def service_plist(self, label):
        return plistlib.dumps({
            "Label": label,
            "BundleProgram": f"Contents/MacOS/{EXE}",
            "ProgramArguments": [EXE, *self.service_args, "--state", self.state_file],
            "AssociatedBundleIdentifiers": [label],
            "RunAtLoad": True,
            "KeepAlive": True,
            "ProcessType": "Interactive",
            "StandardErrorPath": self.log,
            "StandardOutPath": self.log,
        })

    def launchctl(self, *a):
        return subprocess.run(["launchctl", *a], capture_output=True, text=True)

    def loaded(self):
        return self.launchctl("print", f"{self.domain}/{self.label}").returncode == 0

    def installed(self):
        return self.service("--service-status") in ("enabled", "requiresApproval")

    def service(self, command):
        if not os.access(self.bin, os.X_OK) or not os.path.isdir(self.agents_dir):
            return "notRegistered"
        r = subprocess.run([self.bin, command], capture_output=True, text=True, timeout=30)
        return (r.stdout.strip() or r.stderr.strip() or "error").splitlines()[-1]

    def approve_login_item(self):
        print(t(f"{WARN} macOS ждёт разрешения для фонового объекта: " f"{settings_path('settings', 'general', 'login')} → «{self.display}».", f"{WARN} macOS waits for permission to run in the background: " f"{settings_path('settings', 'general', 'login')} → «{self.display}»."))
        subprocess.run(["open", "x-apple.systempreferences:com.apple.LoginItems-Settings.extension"])
        if not sys.stdin.isatty():
            return False
        print(t("Жду до двух минут...", "Waiting up to two minutes..."), end="", flush=True)
        for _ in range(120):
            time.sleep(1)
            if self.service("--service-status") == "enabled":
                print(t(" есть.", " done."))
                return True
        print(t("\nНе дождался. Как разрешишь — снова tossling on.", "\nGave up waiting. Once allowed, run tossling on again."))
        return False

    def state(self):
        try:
            with open(self.state_file) as f:
                s = json.load(f)
            pid = int(s["pid"])
            if pid <= 1:
                return None
            os.kill(pid, 0)
            return s
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def make_icns(self, tmp, resources):
        png = self.icon()
        if not png:
            return False
        iconset = os.path.join(tmp, "AppIcon.iconset")
        os.makedirs(iconset)
        for size in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                name = f"icon_{size}x{size}{'@2x' if scale == 2 else ''}.png"
                px = str(size * scale)
                subprocess.run(["sips", "-z", px, px, png, "--out", os.path.join(iconset, name)], capture_output=True)
        out = os.path.join(resources, "AppIcon.icns")
        return subprocess.run(["iconutil", "-c", "icns", iconset, "-o", out], capture_output=True).returncode == 0

    def built(self):
        if self.bundled:
            return True
        try:
            with open(self.stamp) as f:
                return f.read().strip() == self.source_hash() and os.access(self.bin, os.X_OK)
        except OSError:
            return False

    def build(self):
        if self.built():
            return False
        if not shutil.which("swiftc"):
            sys.exit(t("Нужен компилятор Swift из Xcode Command Line Tools: xcode-select --install", "The Swift compiler from the Xcode Command Line Tools is needed: xcode-select --install"))
        print(t(f"Собираю {self.display}...", f"Building {self.display}..."))
        want = self.source_hash()
        sign = signing_identity()
        with tempfile.TemporaryDirectory() as tmp:
            app = os.path.join(tmp, os.path.basename(self.app))
            contents = os.path.join(app, "Contents")
            binary = os.path.join(contents, "MacOS", EXE)
            resources = os.path.join(contents, "Resources")
            os.makedirs(os.path.dirname(binary))
            os.makedirs(resources)
            r = subprocess.run(["swiftc", "-O", "-swift-version", "5", *self.sources(), "-o", binary],
                               capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(t("Сборка не удалась:\n", "The build failed:\n") + r.stderr[-2000:])
            has_icon = self.make_icns(tmp, resources)
            label = self.build_label()
            agents = os.path.join(contents, "Library", "LaunchAgents")
            os.makedirs(agents)
            with open(os.path.join(agents, f"{label}.plist"), "wb") as f:
                f.write(self.service_plist(label))
            info = {"CFBundleIdentifier": self.base, "CFBundleName": self.display, "TosslingServiceLabel": label,
                    "CFBundleDisplayName": self.display, "CFBundleExecutable": EXE,
                    "CFBundlePackageType": "APPL", "CFBundleVersion": str(int(time.time())),
                    "CFBundleShortVersionString": version(), "LSMinimumSystemVersion": "13.0", "LSUIElement": True,
                    "CFBundleURLTypes": [{"CFBundleURLName": label, "CFBundleURLSchemes": list(URL_SCHEMES)}]}
            if has_icon:
                info["CFBundleIconFile"] = "AppIcon"
            with open(os.path.join(contents, "Info.plist"), "wb") as f:
                plistlib.dump(info, f)
            with open(os.path.join(resources, os.path.basename(self.stamp)), "w") as f:
                f.write(want + "\n")
            r = subprocess.run(["codesign", "--force", "--sign", sign, "--identifier", self.base, app],
                               capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(t("Не удалось подписать помощника:\n", "Could not sign the helper:\n") + r.stderr)
            shutil.rmtree(self.app, ignore_errors=True)
            os.makedirs(os.path.dirname(self.app), exist_ok=True)
            r = subprocess.run(["ditto", app, self.app], capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(t("Не удалось положить помощника на место:\n", "Could not put the helper in place:\n") + r.stderr)
        if os.path.exists(LSREGISTER) and os.path.dirname(self.app) == APP_DIR:
            subprocess.run([LSREGISTER, "-f", self.app], capture_output=True)
        return True

    def stop(self):
        self.service("--unregister")
        if self.loaded():
            self.launchctl("bootout", f"{self.domain}/{self.label}")
            for _ in range(20):
                if not self.loaded():
                    break
                time.sleep(0.25)

    def start(self):
        try:
            if os.path.getsize(self.log) > 1048576:
                os.truncate(self.log, 0)
        except OSError:
            pass
        was = self.service("--service-status") == "enabled"
        for _ in range(16):
            status = self.service("--register")
            if status == "requiresApproval":
                return status
            if status != "enabled":
                sys.exit(t(f"macOS не зарегистрировал помощника: {status}", f"macOS did not register the helper: {status}"))
            if was:
                self.launchctl("kickstart", "-k", f"{self.domain}/{self.label}")
            if self.wait_state():
                return True
            self.service("--unregister")
            was = False
            time.sleep(5)
        return False

    def wait_state(self):
        for _ in range(20):
            if self.state():
                return True
            time.sleep(0.25)
        return False

    def restart(self):
        self.launchctl("kickstart", "-k", f"{self.domain}/{self.label}")
        time.sleep(1)
        return self.state()

    def uninstall(self):
        self.stop()
