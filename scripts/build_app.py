import hashlib
import os
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from email.utils import formatdate
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "cli", "lib"))
from tosslinglib import BUNDLE_ID, DEVICES, HOME, version

OUT = os.path.join(ROOT, "build", "release")
APP = os.path.join(OUT, "Tossling.app")
AGENT_LABEL = BUNDLE_ID + ".agent"
EXT_ID = BUNDLE_ID + ".finder.send"
NOTARY_PROFILE = os.environ.get("TOSSLING_NOTARY_PROFILE", "tossy-notary")
ARCHES = ("arm64", "x86_64")
SPARKLE_VERSION = "2.10.0"
SPARKLE_SHA256 = "c2bf58aa8387266ac179357b1415d6f2635f044da8be41042af32425dae6da0c"
SPARKLE_DIR = os.path.join(ROOT, "build", "cache", f"sparkle-{SPARKLE_VERSION}")
SPARKLE_ACCOUNT = "tossling"
SPARKLE_PUBLIC_KEY = os.environ.get("TOSSLING_SPARKLE_PUBLIC_KEY", "")
FEED_URL = "https://monoroh.com/tossling/appcast.xml"
DOWNLOAD_URL = "https://github.com/tossling/tossling-desktop/releases/download/v{version}/{name}"
NOTES_URL = "https://github.com/tossling/tossling-desktop/releases/tag/v{version}"
CASK = """cask "tossling" do
  version "{version}"
  sha256 "{sha256}"

  url "https://github.com/tossling/tossling-desktop/releases/download/v#{{version}}/Tossling-#{{version}}.dmg"
  name "Tossling"
  desc "One end-to-end encrypted clipboard for your Macs and Android phone"
  homepage "https://github.com/tossling/tossling-desktop"

  auto_updates true
  depends_on macos: :ventura

  app "Tossling.app"
  binary "#{{appdir}}/Tossling.app/Contents/Resources/tossling/bin/tossling"
  binary "#{{appdir}}/Tossling.app/Contents/Resources/tossling/bin/tossling", target: "tossy"

  uninstall launchctl: "com.kopylovis.tossling.desktop.agent",
            quit:      "com.kopylovis.tossling.desktop"

  zap trash: [
    "~/.cache/tossling",
    "~/.config/tossling",
    "~/Library/Logs/Tossling.log",
  ]
end
"""


def run(*args, **kwargs):
    r = subprocess.run(args, capture_output=True, text=True, **kwargs)
    if r.returncode != 0:
        sys.exit(f"{' '.join(args[:3])}… failed:\n{(r.stderr or r.stdout)[-3000:]}")
    return r.stdout


def identities():
    out = run("security", "find-identity", "-v", "-p", "codesigning")
    return re.findall(r'"([^"]+)"', out)


def pick_identity():
    found = identities()
    for prefix in ("Developer ID Application:", "Apple Development:"):
        for name in found:
            if name.startswith(prefix):
                return name
    return "-"


def universal(sources, output, extra):
    with tempfile.TemporaryDirectory() as tmp:
        parts = []
        for arch in ARCHES:
            part = os.path.join(tmp, arch)
            run("swiftc", "-O", "-swift-version", "5", "-target", f"{arch}-apple-macos13.0", *extra, *sources, "-o", part)
            parts.append(part)
        os.makedirs(os.path.dirname(output), exist_ok=True)
        run("lipo", "-create", *parts, "-output", output)


def sparkle():
    framework = os.path.join(SPARKLE_DIR, "Sparkle.framework")
    if os.path.isdir(framework):
        return SPARKLE_DIR
    os.makedirs(SPARKLE_DIR, exist_ok=True)
    archive = os.path.join(SPARKLE_DIR, "sparkle.tar.xz")
    print(f"Downloading Sparkle {SPARKLE_VERSION}…")
    urllib.request.urlretrieve(f"https://github.com/sparkle-project/Sparkle/releases/download/{SPARKLE_VERSION}/Sparkle-{SPARKLE_VERSION}.tar.xz", archive)
    if hashlib.sha256(open(archive, "rb").read()).hexdigest() != SPARKLE_SHA256:
        os.remove(archive)
        sys.exit("The Sparkle download does not match its checksum.")
    run("tar", "-xf", archive, "-C", SPARKLE_DIR, "Sparkle.framework", "bin", "LICENSE")
    os.remove(archive)
    return SPARKLE_DIR


def icns(resources):
    png = os.path.join(ROOT, "mac", "Tossling", "icon.png")
    with tempfile.TemporaryDirectory() as tmp:
        iconset = os.path.join(tmp, "AppIcon.iconset")
        os.makedirs(iconset)
        for size in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                px = str(size * scale)
                name = f"icon_{size}x{size}{'@2x' if scale == 2 else ''}.png"
                run("sips", "-z", px, px, png, "--out", os.path.join(iconset, name))
        run("iconutil", "-c", "icns", iconset, "-o", os.path.join(resources, "AppIcon.icns"))


def build():
    shutil.rmtree(OUT, ignore_errors=True)
    contents = os.path.join(APP, "Contents")
    resources = os.path.join(contents, "Resources")
    os.makedirs(resources)
    helper_src = os.path.join(ROOT, "mac", "Tossling")
    sources = [os.path.join(helper_src, "main.swift")] + sorted(
        os.path.join(helper_src, f) for f in os.listdir(helper_src) if f.endswith(".swift") and f != "main.swift")
    print("Building Tossling (arm64 + x86_64)…")
    frameworks = sparkle()
    universal(sources, os.path.join(contents, "MacOS", "Tossling"),
              ["-F", frameworks, "-framework", "Sparkle", "-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"])
    bundled = os.path.join(contents, "Frameworks", "Sparkle.framework")
    shutil.copytree(os.path.join(frameworks, "Sparkle.framework"), bundled, symlinks=True)
    for xpc in (os.path.join(bundled, "XPCServices"), os.path.join(bundled, "Versions", "B", "XPCServices")):
        if os.path.islink(xpc):
            os.unlink(xpc)
        else:
            shutil.rmtree(xpc, ignore_errors=True)
    appex = os.path.join(contents, "PlugIns", "TosslingFinder.appex")
    print("Building the Finder extension…")
    universal([os.path.join(ROOT, "mac", "TosslingFinder", "FinderSync.swift")], os.path.join(appex, "Contents", "MacOS", "TosslingFinder"),
              ["-parse-as-library", "-application-extension", "-module-name", "TosslingFinder",
               "-Xlinker", "-e", "-Xlinker", "_NSExtensionMain", "-framework", "FinderSync"])
    icns(resources)
    os.makedirs(os.path.join(appex, "Contents", "Resources"))
    shutil.copy(os.path.join(resources, "AppIcon.icns"), os.path.join(appex, "Contents", "Resources", "AppIcon.icns"))

    build_number = run("git", "-C", ROOT, "rev-list", "--count", "HEAD").strip()
    common = {"CFBundleShortVersionString": version(), "CFBundleVersion": build_number, "LSMinimumSystemVersion": "13.0",
              "CFBundleIconFile": "AppIcon", "NSHumanReadableCopyright": "GPL-3.0"}
    with open(os.path.join(contents, "Info.plist"), "wb") as f:
        plistlib.dump({**common, "CFBundleIdentifier": BUNDLE_ID, "CFBundleName": "Tossling", "CFBundleDisplayName": "Tossling",
                       "CFBundleExecutable": "Tossling", "CFBundlePackageType": "APPL", "LSUIElement": True,
                       "TosslingServiceLabel": AGENT_LABEL, "TosslingDistribution": True,
                       **({"SUFeedURL": FEED_URL, "SUPublicEDKey": SPARKLE_PUBLIC_KEY, "SUEnableAutomaticChecks": True} if SPARKLE_PUBLIC_KEY else {}),
                       "CFBundleURLTypes": [{"CFBundleURLName": BUNDLE_ID, "CFBundleURLSchemes": ["tossling"]}]}, f)
    with open(os.path.join(appex, "Contents", "Info.plist"), "wb") as f:
        plistlib.dump({**common, "CFBundleIdentifier": EXT_ID, "CFBundleName": "Tossling", "CFBundleDisplayName": "Tossling",
                       "CFBundleExecutable": "TosslingFinder", "CFBundlePackageType": "XPC!",
                       "NSExtension": {"NSExtensionAttributes": {}, "NSExtensionPointIdentifier": "com.apple.FinderSync",
                                       "NSExtensionPrincipalClass": "TosslingFinder.FinderSync"}}, f)
    agents = os.path.join(contents, "Library", "LaunchAgents")
    os.makedirs(agents)
    with open(os.path.join(agents, AGENT_LABEL + ".plist"), "wb") as f:
        plistlib.dump({"Label": AGENT_LABEL, "BundleProgram": "Contents/MacOS/Tossling", "ProgramArguments": ["Tossling", "--agent"],
                       "AssociatedBundleIdentifiers": [BUNDLE_ID], "RunAtLoad": True, "KeepAlive": {"SuccessfulExit": False},
                       "ProcessType": "Interactive"}, f)

    cli = os.path.join(resources, "tossling")
    shutil.copytree(os.path.join(ROOT, "cli"), os.path.join(cli, "cli"), ignore=shutil.ignore_patterns("__pycache__"))
    shutil.copytree(os.path.join(ROOT, "bin"), os.path.join(cli, "bin"), symlinks=True)
    shutil.copy(os.path.join(ROOT, "VERSION"), cli)
    shutil.copy(os.path.join(ROOT, "LICENSE"), resources)
    shutil.copy(os.path.join(frameworks, "LICENSE"), os.path.join(resources, "Sparkle-LICENSE"))
    return build_number


def sign(identity):
    developer_id = identity.startswith("Developer ID Application:")
    hardened = ["--options", "runtime", "--timestamp"] if developer_id else []
    with tempfile.TemporaryDirectory() as tmp:
        entitlements = os.path.join(tmp, "finder.plist")
        with open(entitlements, "wb") as f:
            plistlib.dump({"com.apple.security.app-sandbox": True,
                           "com.apple.security.temporary-exception.files.home-relative-path.read-only": ["/" + os.path.relpath(DEVICES, HOME)]}, f)
        run("codesign", "--force", "--sign", identity, *hardened, "--entitlements", entitlements,
            os.path.join(APP, "Contents", "PlugIns", "TosslingFinder.appex"))
    framework = os.path.join(APP, "Contents", "Frameworks", "Sparkle.framework")
    for part in (os.path.join(framework, "Versions", "B", "Autoupdate"), os.path.join(framework, "Versions", "B", "Updater.app"), framework):
        run("codesign", "--force", "--sign", identity, *hardened, part)
    run("codesign", "--force", "--sign", identity, *hardened, "--identifier", BUNDLE_ID, APP)
    run("codesign", "--verify", "--deep", "--strict", APP)
    return developer_id


def submit(path):
    out = run("xcrun", "notarytool", "submit", path, "--keychain-profile", NOTARY_PROFILE, "--wait")
    if "status: Accepted" not in out:
        sys.exit("Notarization did not pass:\n" + out[-2000:])
    run("xcrun", "stapler", "staple", path)


def notarize(identity, name):
    archive = os.path.join(OUT, "notarize.zip")
    run("ditto", "-c", "-k", "--keepParent", APP, archive)
    print("Notarizing the app (a few minutes)…")
    out = run("xcrun", "notarytool", "submit", archive, "--keychain-profile", NOTARY_PROFILE, "--wait")
    os.remove(archive)
    if "status: Accepted" not in out:
        sys.exit("Notarization did not pass:\n" + out[-2000:])
    run("xcrun", "stapler", "staple", APP)
    run("spctl", "--assess", "--type", "execute", APP)
    image = os.path.join(OUT, name)
    with tempfile.TemporaryDirectory() as tmp:
        shutil.copytree(APP, os.path.join(tmp, "Tossling.app"), symlinks=True)
        os.symlink("/Applications", os.path.join(tmp, "Applications"))
        run("hdiutil", "create", "-volname", "Tossling", "-srcfolder", tmp, "-fs", "HFS+", "-format", "UDZO", "-ov", image)
    run("codesign", "--force", "--sign", identity, "--timestamp", image)
    print("Notarizing the disk image…")
    submit(image)
    run("spctl", "--assess", "--type", "open", "--context", "context:primary-signature", image)
    return image


def appcast(image, build_number):
    signed = run(os.path.join(SPARKLE_DIR, "bin", "sign_update"), "--account", SPARKLE_ACCOUNT, image).strip()
    name = os.path.basename(image)
    item = f"""<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>Tossling</title>
    <link>https://monoroh.com/tossling</link>
    <item>
      <title>Tossling {escape(version())}</title>
      <pubDate>{formatdate(usegmt=True)}</pubDate>
      <sparkle:version>{build_number}</sparkle:version>
      <sparkle:shortVersionString>{escape(version())}</sparkle:shortVersionString>
      <sparkle:minimumSystemVersion>13.0</sparkle:minimumSystemVersion>
      <sparkle:fullReleaseNotesLink>{NOTES_URL.format(version=version())}</sparkle:fullReleaseNotesLink>
      <enclosure url="{DOWNLOAD_URL.format(version=version(), name=name)}" {signed} type="application/octet-stream"/>
    </item>
  </channel>
</rss>
"""
    path = os.path.join(OUT, "appcast.xml")
    with open(path, "w") as f:
        f.write(item)
    return path


def main():
    release = "--release" in sys.argv
    build_number = build()
    identity = pick_identity()
    developer_id = sign(identity)
    name = f"Tossling-{version()}.dmg"
    print(f"Signed with: {identity}")
    if release:
        if not developer_id:
            sys.exit("A Developer ID Application certificate is needed for a release; this build is for local testing only.")
        if not SPARKLE_PUBLIC_KEY:
            sys.exit("The Sparkle public key is missing: SPARKLE_PUBLIC_KEY in scripts/build_app.py.")
        archive = notarize(identity, name)
        digest = hashlib.sha256(open(archive, "rb").read()).hexdigest()
        with open(os.path.join(OUT, "tossling.rb"), "w") as f:
            f.write(CASK.format(version=version(), sha256=digest))
        feed = appcast(archive, build_number)
        print(f"{archive}\nversion {version()} ({build_number}), sha256 {digest}\ncask: {os.path.join(OUT, 'tossling.rb')}\nappcast: {feed}")
    else:
        print(f"{APP}\nversion {version()} ({build_number}); {'ready to notarize' if developer_id else 'for local testing, not for distribution'}")


if __name__ == "__main__":
    main()
