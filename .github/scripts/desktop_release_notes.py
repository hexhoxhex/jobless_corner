"""Put the desktop section into a release's notes, replacing an older one.

Usage: TAG=... python3 desktop_release_notes.py body.md dist-desktop/
Used by release.yml. The section sits between two HTML comments so a re-run
(a failed macOS build tried again) replaces it instead of adding another,
and it lists only the installers that were actually built.
"""
import hashlib
import os
import sys

START, END = "<!-- desktop-start -->", "<!-- desktop-end -->"

path, folder = sys.argv[1], sys.argv[2]
body = open(path, encoding="utf-8").read().rstrip("\n")
files = sorted(os.listdir(folder))


def sha(name):
    h = hashlib.sha256()
    with open(os.path.join(folder, name), "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


have = lambda suffix: any(f.endswith(suffix) for f in files)
lines = []
if have("-windows-setup.exe") or have("-windows.msi"):
    lines.append("- Windows: `-windows-setup.exe` (installs for you only, no admin needed) or `-windows.msi`. Had the old desktop app? Use the `.msi`: it replaces it.")
if have("-macos.dmg"):
    lines.append("- macOS: `-macos.dmg` (Apple silicon and Intel). The build isn't notarised: the first time, right-click the app and choose Open. `-macos-app.zip` is the same app unpacked, for signing.")
else:
    lines.append("- macOS: not in this release yet (no Mac build finished).")
if have("-linux.AppImage") or have("-linux.deb"):
    lines.append("- Linux: `-linux.AppImage` (make it executable and run it) or `-linux.deb`.")

sums = "\n".join(f"- {f}: `{sha(f)}`" for f in files)
section = f"""{START}
### Desktop app (Windows, macOS, Linux)

{chr(10).join(lines)}

Windows SmartScreen may warn because the build isn't code-signed: *More info*, then *Run anyway*.

SHA-256:
{sums}
{END}"""

if START in body and END in body:
    head, rest = body.split(START, 1)
    tail = rest.split(END, 1)[1]
    body = head + section + tail
else:
    body = body + "\n\n" + section

with open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(body + "\n")
