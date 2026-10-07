import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "cli", "lib"))
from tosslinglib import version
from build_app import DOWNLOAD_URL, NOTES_URL, SPARKLE_ACCOUNT, SPARKLE_DIR

OUT = os.path.join(ROOT, "build", "release")
REPO = "Tossling/tossling-desktop"
TAP = ("Tossling/homebrew-tap", "Casks/tossling.rb")
FEED = ("kopylovis/landing-web", "public/tossling/appcast.xml")
WINDOWS_FEED = ("kopylovis/landing-web", "public/tossling/windows.json")


def run(*args, capture=True):
    r = subprocess.run(args, capture_output=capture, text=True)
    if r.returncode != 0:
        sys.exit(f"{' '.join(args[:4])}… failed:\n{(r.stderr or r.stdout or '')[-2000:]}")
    return r.stdout or ""


def put(repo, path, source, message):
    with open(source, "rb") as f:
        content = base64.b64encode(f.read()).decode()
    current = subprocess.run(["gh", "api", f"repos/{repo}/contents/{path}", "--jq", ".sha"], capture_output=True, text=True)
    body = {"message": message, "content": content}
    if current.returncode == 0 and current.stdout.strip():
        body["sha"] = current.stdout.strip()
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(body, f)
    try:
        run("gh", "api", "-X", "PUT", f"repos/{repo}/contents/{path}", "--input", f.name)
    finally:
        os.remove(f.name)


def artifact(run_id, name, directory):
    found = run("gh", "api", f"repos/{REPO}/actions/runs/{run_id}/artifacts", "--jq", f'.artifacts[] | select(.name == "{name}") | .id').strip()
    if not found:
        sys.exit(f"The build {run_id} has no {name}.")
    path = os.path.join(directory, name)
    with open(path, "wb") as f:
        if subprocess.run(["gh", "api", f"repos/{REPO}/actions/artifacts/{found}/zip"], stdout=f).returncode != 0:
            sys.exit(f"Could not download {name}.")
    return path


def linux(v, tag, run_id):
    with tempfile.TemporaryDirectory() as tmp:
        files = [artifact(run_id, name, tmp) for name in (f"tossling_{v}_amd64.deb", f"Tossling-{v}-linux-x64.tar.gz")]
        run("gh", "release", "upload", tag, *files, "-R", REPO, "--clobber")
    print("The Linux .deb and tar.gz added to", tag)


def windows(v, tag):
    print("Waiting for the Windows installer from CI…")
    run_id = None
    for _ in range(60):
        runs = json.loads(run("gh", "run", "list", "-R", REPO, "--workflow", "jvm.yml", "--branch", tag, "--json", "databaseId", "--limit", "1"))
        if runs:
            run_id = runs[0]["databaseId"]
            break
        time.sleep(10)
    if run_id is None:
        sys.exit(f"No Windows build started for {tag}: run scripts/publish.py --windows-only once it has.")
    if subprocess.run(["gh", "run", "watch", str(run_id), "-R", REPO, "--exit-status", "--interval", "30"], capture_output=True).returncode != 0:
        sys.exit(f"The Windows build {run_id} failed: fix it, rerun it, then scripts/publish.py --windows-only.")
    name = f"Tossling-{v}.msi"
    with tempfile.TemporaryDirectory() as tmp:
        msi = artifact(run_id, name, tmp)
        signed = run(os.path.join(SPARKLE_DIR, "bin", "sign_update"), "--account", SPARKLE_ACCOUNT, msi)
        match = re.search(r'edSignature="([^"]+)"', signed)
        if not match:
            sys.exit("sign_update did not return a signature.")
        with open(msi, "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        manifest = os.path.join(tmp, "manifest.txt")
        with open(manifest, "w") as f:
            f.write(f"tossling-windows-update\n{v}\n{os.path.getsize(msi)}\n{digest}\n")
        signed_manifest = re.search(r'edSignature="([^"]+)"', run(os.path.join(SPARKLE_DIR, "bin", "sign_update"), "--account", SPARKLE_ACCOUNT, manifest))
        if not signed_manifest:
            sys.exit("sign_update did not sign the manifest.")
        run("gh", "release", "upload", tag, msi, "-R", REPO, "--clobber")
        print(f"{name} added to {tag}")
        feed = os.path.join(tmp, "windows.json")
        with open(feed, "w") as f:
            json.dump({
                "version": v,
                "url": DOWNLOAD_URL.format(version=v, name=name),
                "size": os.path.getsize(msi),
                "sha256": digest,
                "signature": match.group(1),
                "manifest_signature": signed_manifest.group(1),
                "notes": NOTES_URL.format(version=v),
            }, f, indent=2)
        put(*WINDOWS_FEED, feed, f"Tossling {v} for Windows")
    print("Windows feed updated: Tossling on Windows will offer the update once the site is rebuilt")
    linux(v, tag, run_id)


def main():
    v = version()
    tag = f"v{v}"
    if "--windows-only" in sys.argv:
        windows(v, tag)
        return
    image = os.path.join(OUT, f"Tossling-{v}.dmg")
    cask = os.path.join(OUT, "tossling.rb")
    feed = os.path.join(OUT, "appcast.xml")
    for path in (image, cask, feed):
        if not os.path.isfile(path):
            sys.exit(f"{path} is missing: run make release first.")
    if run("git", "-C", ROOT, "status", "--porcelain").strip():
        sys.exit("The working tree has changes: commit them first.")
    if subprocess.run(["gh", "release", "view", tag, "-R", REPO], capture_output=True).returncode == 0:
        sys.exit(f"{tag} is already released.")
    notes = sys.argv[sys.argv.index("--notes") + 1] if "--notes" in sys.argv else None
    if not notes or not os.path.isfile(notes):
        sys.exit("Release notes are needed: make publish NOTES=<file>.")

    run("git", "-C", ROOT, "push", "-q", "origin", "HEAD")
    run("git", "-C", ROOT, "tag", "-a", tag, "-m", f"Tossling Desktop {v}")
    run("git", "-C", ROOT, "push", "-q", "origin", tag)
    run("gh", "release", "create", tag, image, "-R", REPO, "--title", f"Tossling Desktop {v}", "--notes-file", notes)
    print(f"Released {tag}")
    put(*TAP, cask, f"Tossling {v}")
    print("Homebrew cask updated")
    put(*FEED, feed, f"Tossling {v} appcast")
    print("Appcast updated: Tossling on other Macs will offer the update once the site is rebuilt")
    windows(v, tag)


if __name__ == "__main__":
    main()
