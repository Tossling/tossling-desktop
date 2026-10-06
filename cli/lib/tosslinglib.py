import json
import os
import re
import subprocess
import sys

HOME = os.path.expanduser("~")
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TTY = sys.stdout.isatty()

CONFIG_DIR = os.path.join(HOME, ".config", "tossling")
CONFIG = os.path.join(CONFIG_DIR, "config.json")
CACHE = os.path.join(HOME, ".cache", "tossling")
STATE = os.path.join(CACHE, "state.json")
HISTORY = os.path.join(CACHE, "history")
DEVICES = os.path.join(CACHE, "devices.json")
LOG = os.path.join(HOME, "Library", "Logs", "Tossling.log")
APP_DIR = os.path.join(HOME, "Applications")
SUPPORT = os.path.join(HOME, "Library", "Application Support", "Tossling")
BUNDLE_ID = "com.kopylovis.tossling.desktop"
BUNDLED_APP = os.path.dirname(os.path.dirname(os.path.dirname(ROOT))) if ROOT.endswith(os.path.join("Contents", "Resources", "tossling")) else None
LSREGISTER = ("/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/"
              "Support/lsregister")


def version():
    try:
        with open(os.path.join(ROOT, "VERSION")) as f:
            return f.read().strip()
    except OSError:
        return "0"


def paint(text, code):
    return f"\x1b[{code}m{text}\x1b[0m" if TTY else text


OK = paint("✓", "32")
WARN = paint("!", "33")
BAD = paint("✗", "31")


def tilde(path):
    return "~" + path[len(HOME):] if path.startswith(HOME + os.sep) else path


def has_flag(args, *names):
    return any(a in args for a in names)


def signing_identity():
    out = subprocess.run(["security", "find-identity", "-v", "-p", "codesigning"], capture_output=True, text=True).stdout
    m = re.search(r'"(Apple Development: [^"]+)"', out)
    return m.group(1) if m else "-"


SETTINGS_NAMES = {
    "settings": ("Системные настройки", "System Settings"),
    "general": ("Основные", "General"),
    "login": ("Объекты входа и расширения", "Login Items & Extensions"),
}
_english = None


def system_english():
    global _english
    chosen = os.environ.get("TOSSLING_LANG") or os.environ.get("TOSSY_LANG")
    if _english is None and chosen in ("ru", "en"):
        _english = chosen == "en"
    if _english is None:
        try:
            with open(CONFIG) as f:
                chosen = json.load(f).get("language")
            if chosen in ("ru", "en"):
                _english = chosen == "en"
        except (OSError, ValueError, AttributeError):
            pass
    if _english is None:
        try:
            langs = subprocess.run(["defaults", "read", "-g", "AppleLanguages"], capture_output=True, text=True,
                                   timeout=5).stdout
        except (OSError, subprocess.TimeoutExpired):
            langs = ""
        m = re.search(r'"?([A-Za-z]{2})', langs)
        _english = bool(m) and m.group(1).lower() != "ru"
    return _english


def settings_path(*keys):
    return " → ".join(SETTINGS_NAMES[k][1 if system_english() else 0] if k in SETTINGS_NAMES else k for k in keys)


def t(ru, en):
    return en if system_english() else ru
