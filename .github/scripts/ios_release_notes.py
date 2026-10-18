"""Put the iPhone/iPad section into a release's notes, replacing an older one.

Usage: SHA=... NAME=... BUILT=... python3 ios_release_notes.py body.md
Used by release.yml (new releases) and ios.yml (a newer build from main
replacing the .ipa on the latest release). The section sits between two
HTML comments so a later build can replace it instead of adding another.
"""
import os
import sys

START, END = "<!-- ios-start -->", "<!-- ios-end -->"

path = sys.argv[1]
body = open(path, encoding="utf-8").read().rstrip("\n")
repo = os.environ.get("GITHUB_REPOSITORY", "hexhoxhex/jobless_corner")

section = f"""{START}
### iPhone and iPad: `{os.environ["NAME"]}`

Unsigned. Install it with [Sideloadly](https://sideloadly.io) or AltStore,
which sign it with your own Apple ID (a free Apple ID needs a reinstall every
7 days). Step by step: [ios/README.md](https://github.com/{repo}/blob/main/ios/README.md).

**SHA-256:** `{os.environ["SHA"]}` (built {os.environ.get("BUILT", "for this release")})
{END}"""

if START in body and END in body:
    head, rest = body.split(START, 1)
    tail = rest.split(END, 1)[1]
    body = head + section + tail
else:
    body = body + "\n\n" + section

with open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(body + "\n")
