import hashlib
import os
import plistlib
import shutil
import subprocess
import tempfile
import time

from tosslinglib import BUNDLE_ID, BUNDLED_APP, DEVICES, HOME, LSREGISTER, ROOT, SUPPORT, signing_identity, version

SRC = os.path.join(ROOT, "mac", "TosslingFinder")
APP = os.path.join(SUPPORT, "Tossling Finder.app")
APPEX = os.path.join(APP, "Contents", "PlugIns", "TosslingFinder.appex")
STAMP = os.path.join(APP, "Contents", "Resources", "source.sha256")
HOST_ID = f"{BUNDLE_ID}.finder"
EXT_ID = f"{HOST_ID}.send"
HOST_EXE = "TosslingFinderHost"


def source_hash(sign):
    h = hashlib.sha256((sign + HOST_ID + APP + version()).encode())
    for name in sorted(os.listdir(SRC)):
        with open(os.path.join(SRC, name), "rb") as f:
            h.update(name.encode() + f.read())
    return h.hexdigest()


def swiftc(*args):
    r = subprocess.run(["swiftc", "-O", "-swift-version", "5", *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr[-2000:])


def info(identifier, executable, package, extra, name="Tossling"):
    return plistlib.dumps({"CFBundleIdentifier": identifier, "CFBundleName": name,
                           "CFBundleDisplayName": name, "CFBundleExecutable": executable,
                           "CFBundlePackageType": package, "CFBundleVersion": str(int(time.time())),
                           "CFBundleShortVersionString": version(), "LSMinimumSystemVersion": "13.0", **extra})


def build(icon=None, dest=APP):
    sign = signing_identity()
    want = source_hash(sign)
    try:
        if dest == APP and open(STAMP).read().strip() == want and os.path.isdir(APPEX):
            return False
    except OSError:
        pass
    with tempfile.TemporaryDirectory() as tmp:
        app = os.path.join(tmp, os.path.basename(dest))
        contents = os.path.join(app, "Contents")
        appex = os.path.join(contents, "PlugIns", "TosslingFinder.appex")
        for d in (os.path.join(contents, "MacOS"), os.path.join(contents, "Resources"), os.path.join(appex, "Contents", "MacOS")):
            os.makedirs(d)
        swiftc(os.path.join(SRC, "main.swift"), "-o", os.path.join(contents, "MacOS", HOST_EXE))
        swiftc("-parse-as-library", "-application-extension", "-module-name", "TosslingFinder",
               "-Xlinker", "-e", "-Xlinker", "_NSExtensionMain", "-framework", "FinderSync",
               os.path.join(SRC, "FinderSync.swift"), "-o", os.path.join(appex, "Contents", "MacOS", "TosslingFinder"))
        appex_extra = {"NSExtension": {"NSExtensionAttributes": {}, "NSExtensionPointIdentifier": "com.apple.FinderSync",
                                       "NSExtensionPrincipalClass": "TosslingFinder.FinderSync"}}
        extra = {"LSUIElement": True}
        if icon and os.path.exists(icon):
            shutil.copy(icon, os.path.join(contents, "Resources", "AppIcon.icns"))
            os.makedirs(os.path.join(appex, "Contents", "Resources"), exist_ok=True)
            shutil.copy(icon, os.path.join(appex, "Contents", "Resources", "AppIcon.icns"))
            extra["CFBundleIconFile"] = "AppIcon"
            appex_extra["CFBundleIconFile"] = "AppIcon"
        with open(os.path.join(appex, "Contents", "Info.plist"), "wb") as f:
            f.write(info(EXT_ID, "TosslingFinder", "XPC!", appex_extra))
        with open(os.path.join(contents, "Info.plist"), "wb") as f:
            f.write(info(HOST_ID, HOST_EXE, "APPL", extra, name="Tossling Finder"))
        with open(os.path.join(contents, "Resources", "source.sha256"), "w") as f:
            f.write(want + "\n")
        entitlements = os.path.join(tmp, "sandbox.plist")
        with open(entitlements, "wb") as f:
            f.write(plistlib.dumps({"com.apple.security.app-sandbox": True,
                                    "com.apple.security.temporary-exception.files.home-relative-path.read-only":
                                        ["/" + os.path.relpath(DEVICES, HOME)]}))
        for target, ent in ((appex, entitlements), (app, None)):
            cmd = ["codesign", "--force", "--sign", sign] + (["--entitlements", ent] if ent else []) + [target]
            r = subprocess.run(cmd, capture_output=True, text=True)
            if r.returncode != 0:
                raise RuntimeError(r.stderr.strip())
        if dest == APP:
            subprocess.run(["pluginkit", "-r", APPEX], capture_output=True)
        shutil.rmtree(dest, ignore_errors=True)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        r = subprocess.run(["ditto", app, dest], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip())
    return True


def install(icon=None):
    if BUNDLED_APP:
        subprocess.run(["pluginkit", "-a", os.path.join(BUNDLED_APP, "Contents", "PlugIns", "TosslingFinder.appex")], capture_output=True)
        subprocess.run(["pluginkit", "-e", "use", "-i", EXT_ID], capture_output=True)
        return None
    try:
        rebuilt = build(icon)
    except (RuntimeError, OSError) as e:
        return str(e)
    if os.path.exists(LSREGISTER):
        subprocess.run([LSREGISTER, "-f", APP], capture_output=True)
    subprocess.run(["pluginkit", "-a", APPEX], capture_output=True)
    if rebuilt:
        subprocess.run(["pluginkit", "-e", "ignore", "-i", EXT_ID], capture_output=True)
        time.sleep(1)
    subprocess.run(["pluginkit", "-e", "use", "-i", EXT_ID], capture_output=True)
    return None


def enabled():
    out = subprocess.run(["pluginkit", "-m", "-i", EXT_ID], capture_output=True, text=True).stdout
    return out.strip().startswith("+")


def remove():
    subprocess.run(["pluginkit", "-e", "ignore", "-i", EXT_ID], capture_output=True)
    if BUNDLED_APP:
        return
    subprocess.run(["pluginkit", "-r", APPEX], capture_output=True)
    shutil.rmtree(APP, ignore_errors=True)
