import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "cli", "lib"))
from tosslinglib import version

OUT = os.path.join(ROOT, "build", "release")
REPO = "Tossling/tossling-desktop"
TAP = ("Tossling/homebrew-tap", "Casks/tossling.rb")
FEED = ("kopylovis/landing-web", "public/tossling/appcast.xml")


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


def main():
    v = version()
    tag = f"v{v}"
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
    with tempfile.TemporaryDirectory() as tmp:
        stable = os.path.join(tmp, "Tossling.dmg")
        shutil.copy(image, stable)
        run("gh", "release", "create", tag, image, stable, "-R", REPO, "--title", f"Tossling Desktop {v}", "--notes-file", notes)
    print(f"Released {tag}")
    put(*TAP, cask, f"Tossling {v}")
    print("Homebrew cask updated")
    put(*FEED, feed, f"Tossling {v} appcast")
    print("Appcast updated: Tossling on other Macs will offer the update once the site is rebuilt")


if __name__ == "__main__":
    main()
