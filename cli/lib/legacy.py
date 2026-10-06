import json
import os
import shutil
import subprocess

from tosslinglib import APP_DIR, BUNDLE_ID, BUNDLED_APP, CACHE, CONFIG, CONFIG_DIR, DEVICES, HISTORY, HOME, LOG, STATE

SUPPORT_ROOT = os.path.join(HOME, "Library", "Application Support")
MNRH_CONFIG = os.path.join(HOME, ".config", "mnrh", "clip.json")
MNRH_CACHE = os.path.join(HOME, ".cache", "mnrh")
MNRH_STATE = os.path.join(MNRH_CACHE, "clip.json")
MNRH_SUPPORT = os.path.join(SUPPORT_ROOT, "mnrh")
MNRH_LOG = os.path.join(HOME, "Library", "Logs", "mnrh-clip.log")
MNRH_BINARIES = (os.path.join(APP_DIR, "Tossy.app", "Contents", "MacOS", "mnrh-clip"),
                 os.path.join(MNRH_SUPPORT, "Tossy.app", "Contents", "MacOS", "mnrh-clip"),
                 os.path.join(MNRH_SUPPORT, "mnrh Clip.app", "Contents", "MacOS", "mnrh-clip"))
MNRH_APPS = tuple(os.path.join(MNRH_SUPPORT, name) for name in
                  ("Tossy.app", "mnrh Clip.app", "Tossy Finder.app", "mnrh Tossy Finder.app"))

TOSSY_BUNDLE_ID = "com.kopylovis.tossy.desktop"
TOSSY_CONFIG_DIR = os.path.join(HOME, ".config", "tossy")
TOSSY_CONFIG = os.path.join(TOSSY_CONFIG_DIR, "config.json")
TOSSY_CACHE = os.path.join(HOME, ".cache", "tossy")
TOSSY_LOG = os.path.join(HOME, "Library", "Logs", "Tossy.log")
TOSSY_APPS = (os.path.join(APP_DIR, "Tossy.app"), "/Applications/Tossy.app")
TOSSY_FINDER = os.path.join(SUPPORT_ROOT, "Tossy", "Tossy Finder.app")

SOURCE_APP = os.path.join(APP_DIR, "Tossling.app")
SOURCE_FINDER = os.path.join(SUPPORT_ROOT, "Tossling", "Tossling Finder.app")
AGENT_LABEL = BUNDLE_ID + ".agent"
LABEL_PREFIXES = (TOSSY_BUNDLE_ID + ".", BUNDLE_ID + ".", "com.mnrh.clip.")
PLISTS = tuple(os.path.join(HOME, "Library", "LaunchAgents", f"{label}.plist")
               for label in (TOSSY_BUNDLE_ID, BUNDLE_ID, "com.mnrh.clip"))
APPEX = ("Contents", "PlugIns")


def pending():
    return not os.path.lexists(CONFIG) and not tossy_pending() and os.path.isfile(MNRH_CONFIG) and not os.path.islink(MNRH_CONFIG)


def tossy_pending():
    return not os.path.lexists(CONFIG) and os.path.isfile(TOSSY_CONFIG) and not os.path.islink(TOSSY_CONFIG_DIR)


def read_config():
    for path in (CONFIG, TOSSY_CONFIG, MNRH_CONFIG):
        try:
            with open(path) as f:
                return json.load(f)
        except (OSError, ValueError):
            continue
    return {}


def unregister(app):
    macos = os.path.join(app, "Contents", "MacOS")
    for name in os.listdir(macos) if os.path.isdir(macos) else ():
        binary = os.path.join(macos, name)
        if os.access(binary, os.X_OK):
            subprocess.run([binary, "--unregister"], capture_output=True, timeout=30)


def bootout_old_labels():
    domain = f"gui/{os.getuid()}"
    found = False
    listed = subprocess.run(["launchctl", "list"], capture_output=True, text=True).stdout.splitlines()
    for line in listed:
        label = line.split("\t")[-1]
        if label.startswith(LABEL_PREFIXES) and label != AGENT_LABEL:
            subprocess.run(["launchctl", "bootout", f"{domain}/{label}"], capture_output=True)
            found = True
    for plist in PLISTS:
        if os.path.exists(plist):
            label = os.path.basename(plist)[:-len(".plist")]
            subprocess.run(["launchctl", "bootout", f"{domain}/{label}"], capture_output=True)
            os.unlink(plist)
            found = True
    return found


def remove_app(app, stop=True):
    if not os.path.isdir(app) or (BUNDLED_APP and os.path.realpath(app) == os.path.realpath(BUNDLED_APP)):
        return False
    if stop:
        unregister(app)
    plugins = os.path.join(app, *APPEX)
    for name in os.listdir(plugins) if os.path.isdir(plugins) else ():
        subprocess.run(["pluginkit", "-r", os.path.join(plugins, name)], capture_output=True)
    shutil.rmtree(app, ignore_errors=True)
    return True


def stop_mnrh_helper():
    binaries = [b for b in MNRH_BINARIES if os.access(b, os.X_OK)]
    plists = [p for p in PLISTS if os.path.exists(p)]
    if not binaries and not plists:
        return False
    try:
        with open(MNRH_STATE) as f:
            pid = int(json.load(f)["pid"])
        if pid <= 1:
            raise ValueError
        os.kill(pid, 0)
        was_running = True
    except (OSError, ValueError, KeyError, TypeError):
        pid = None
        was_running = False
    for binary in binaries:
        subprocess.run([binary, "--unregister"], capture_output=True, timeout=30)
    if bootout_old_labels():
        was_running = True
    if pid:
        try:
            os.kill(pid, 15)
        except OSError:
            pass
    return was_running


def move(src, dst):
    if os.path.lexists(src) and not os.path.lexists(dst):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.move(src, dst)


def move_dir(src, dst):
    if not os.path.isdir(src) or os.path.islink(src):
        return
    if not os.path.lexists(dst):
        move(src, dst)
        return
    for name in os.listdir(src):
        move(os.path.join(src, name), os.path.join(dst, name))
    shutil.rmtree(src, ignore_errors=True)


def link_back(old, new):
    try:
        os.symlink(new, old)
    except OSError:
        pass


def migrate_mnrh():
    was_running = stop_mnrh_helper()
    os.makedirs(CONFIG_DIR, mode=0o700, exist_ok=True)
    shutil.move(MNRH_CONFIG, CONFIG)
    os.chmod(CONFIG, 0o600)
    link_back(MNRH_CONFIG, CONFIG)
    move(os.path.join(MNRH_CACHE, "clip-history"), HISTORY)
    move(os.path.join(MNRH_CACHE, "tossy-devices.json"), DEVICES)
    move(MNRH_LOG, LOG)
    move(MNRH_STATE, STATE)
    for app in MNRH_APPS:
        remove_app(app, stop=False)
    return was_running


def migrate_tossy():
    was_running = any(os.path.isdir(app) for app in TOSSY_APPS)
    if was_running:
        for app in TOSSY_APPS:
            unregister(app)
        bootout_old_labels()
    move_dir(TOSSY_CONFIG_DIR, CONFIG_DIR)
    os.chmod(CONFIG_DIR, 0o700)
    link_back(TOSSY_CONFIG_DIR, CONFIG_DIR)
    move_dir(TOSSY_CACHE, CACHE)
    move(TOSSY_LOG, LOG)
    if os.path.lexists(MNRH_CONFIG) and os.path.islink(MNRH_CONFIG):
        os.unlink(MNRH_CONFIG)
        link_back(MNRH_CONFIG, CONFIG)
    return was_running


def migrate():
    if tossy_pending():
        return migrate_tossy()
    if pending():
        return migrate_mnrh()
    return None


def remove_older_installs():
    if not BUNDLED_APP:
        return False
    found = False
    for app in (SOURCE_APP, *TOSSY_APPS):
        if remove_app(app):
            found = True
    for app in (TOSSY_FINDER, SOURCE_FINDER):
        if remove_app(app, stop=False):
            found = True
    if bootout_old_labels():
        found = True
    try:
        os.rmdir(os.path.dirname(TOSSY_FINDER))
    except OSError:
        pass
    return found
