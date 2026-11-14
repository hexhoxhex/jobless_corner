"""Smoke test the app on a real TV through its remote API: films, series,
each VOD source on its own, the audio-language switch, and live channels.

Every test starts something, waits for it to play (or to fail), records
which source served it, how long it took and how long the video is, then
moves on. Paced on purpose: a burst of starts is exactly what gets an
address banned by a stream server.

Run with the app open and `adb forward tcp:8080 tcp:8080` in place:
    python tools/smoke_tv.py            # everything
    python tools/smoke_tv.py vod        # films and series only
    python tools/smoke_tv.py live       # live channels only
Writes smoke_result.json next to where it is run. Test plays show up in the
TV's watch history and move the saved place of the titles they play; what
was on screen before the run, and where, is put back at the end.
"""
import json
import sys
import time
import urllib.parse
import urllib.request

API = "http://127.0.0.1:8080"
START_TIMEOUT_S = 60
PAUSE_S = 4

# (search words, season, episode); season 0 = a film.
TITLES = [
    ("Spider-Man", 0, 0),
    ("The Dark Knight", 0, 0),
    ("Oppenheimer", 0, 0),
    ("Gladiator", 0, 0),
    ("Banshee", 1, 1),
    ("Breaking Bad", 1, 1),
    ("The Office", 2, 1),
    ("Game of Thrones", 1, 1),
]
# One title tried on each source by itself.
PER_SOURCE_TITLE = ("Banshee", 1, 1)
SOURCES = ["7Movies", "MovieBox", "VidNest", "VixSrc", "4KHDHub"]
LIVE = [("38", "Sky Sports Main Event"), ("345", "CNN USA"), ("44", "ESPN USA"),
        ("31", "TNT Sports 1 UK"), ("356", "BBC One UK"), ("130", "Sky Sports Premier League"),
        ("414", "SuperSport Premier League")]


def call(path, method="GET", **params):
    url = API + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None)
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read().decode("utf-8", "replace")
    try:
        return json.loads(body)
    except ValueError:
        return body


def state():
    try:
        return call("/api/state")
    except Exception as e:
        return {"error": str(e)}


def close():
    try:
        call("/api/player/close", "POST")
    except Exception:
        pass
    time.sleep(2)


def wait_playing(started, want_live=False):
    """Seconds until it plays, or None; and the last state seen."""
    t0 = time.time()
    last = {}
    while time.time() - t0 < START_TIMEOUT_S:
        s = state()
        last = s
        if s.get("playing") and (want_live or (s.get("duration") or 0) > 0):
            return round(time.time() - started, 1), s
        loading = (s.get("loading") or "")
        if "No source" in loading or "offline" in loading.lower():
            return None, s
        time.sleep(2)
    return None, last


def find(words, series):
    hits = call("/api/search", q=words)
    # A query that also names a person comes back as {"results": [...], "person": ...}.
    if isinstance(hits, dict):
        hits = hits.get("results") or []
    hits = hits if isinstance(hits, list) else []
    for h in hits:
        if bool(h.get("isSeries")) == series and words.lower() in h.get("title", "").lower():
            return h
    return hits[0] if hits else None


def play_title(words, se, ep, pin=None):
    h = find(words, se > 0)
    if not h:
        return {"test": words, "ok": False, "note": "not found by search"}
    close()
    started = time.time()
    params = dict(subjectId=h["subjectId"], title=h.get("title", words), type=h.get("type", 0),
                  year=h.get("year") or "")
    if se > 0:
        params.update(se=se, ep=ep)
    call("/api/play", "POST", **params)
    if pin:
        # Let the normal start pick something, then ask for the one source.
        wait_playing(started)
        started = time.time()
        call("/api/provider", "POST", label=pin)
        time.sleep(3)
    secs, s = wait_playing(started)
    ok = secs is not None and (not pin or s.get("provider") == pin)
    return {
        "test": "%s%s%s" % (h.get("title", words), " S%dE%d" % (se, ep) if se else "",
                             " on " + pin if pin else ""),
        "ok": ok,
        "secs": secs,
        "source": s.get("provider"),
        "minutes": round((s.get("duration") or 0) / 60000, 1),
        "note": "" if ok else (s.get("loading") or s.get("error") or "did not start"),
    }


def dub_switch():
    """The language switch must never stop what is playing."""
    r = play_title(*PER_SOURCE_TITLE)
    if not r["ok"]:
        return [dict(r, test="language switch (setup)")]
    out = []
    for lang in ("Hindi", "French"):
        before = state()
        call("/api/dub", "POST", name=lang)
        time.sleep(15)
        s = state()
        still = bool(s.get("playing")) and s.get("subjectId") == before.get("subjectId")
        out.append({"test": "pick %s mid-episode" % lang, "ok": still, "secs": None,
                    "source": s.get("provider"), "minutes": round((s.get("duration") or 0) / 60000, 1),
                    "note": "audio now %s" % (s.get("dub") or "?") if still else
                    (s.get("loading") or s.get("error") or "stopped")})
        time.sleep(PAUSE_S)
    return out


def play_live(cid, name):
    close()
    started = time.time()
    call("/api/live/play", "POST", id=cid)
    secs, s = wait_playing(started, want_live=True)
    if secs is not None:
        # Still playing a little later, not just a first frame.
        time.sleep(15)
        later = state()
        if not later.get("playing"):
            return {"test": name, "ok": False, "secs": secs, "source": "live",
                    "minutes": 0, "note": "stopped after starting: %s" % (later.get("loading") or "")}
    return {"test": name, "ok": secs is not None, "secs": secs, "source": "live", "minutes": 0,
            "note": "" if secs is not None else (s.get("loading") or "did not start")}


def restore(before):
    """Put back what was playing before the run, at the same place."""
    live = before.get("live")
    try:
        if live and live.get("id"):
            call("/api/live/play", "POST", id=live["id"])
            return "live %s" % live.get("name", live["id"])
        sid = before.get("subjectId")
        if not sid:
            close()
            return "nothing was playing"
        params = dict(subjectId=sid, title=before.get("showTitle") or before.get("title", ""),
                      type=before.get("type") or 0, year=before.get("year") or "")
        if before.get("season"):
            params.update(se=before["season"], ep=before.get("episode") or 1)
        call("/api/play", "POST", **params)
        started = time.time()
        wait_playing(started)
        pos = before.get("position") or 0
        if pos > 0:
            call("/api/seek", "POST", ms=pos)
        return "%s at %d:%02d" % (params["title"], pos // 60000, pos // 1000 % 60)
    except Exception as e:
        return "could not restore: %s" % e


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    results = []
    before = state()

    def note(r):
        results.append(r)
        print("%-4s %-42s %6s  %-9s %6s  %s" % (
            "PASS" if r["ok"] else "FAIL", r["test"][:42], r.get("secs") or "-",
            r.get("source") or "-", r.get("minutes") or "-", r.get("note", "")[:60]), flush=True)
        time.sleep(PAUSE_S)

    print("%-4s %-42s %6s  %-9s %6s  %s" % ("", "test", "start", "source", "min", "note"))
    if which in ("all", "vod"):
        for t in TITLES:
            note(play_title(*t))
        for src in SOURCES:
            note(play_title(*PER_SOURCE_TITLE, pin=src))
        for r in dub_switch():
            note(r)
    if which in ("all", "live"):
        for cid, name in LIVE:
            note(play_live(cid, name))
    passed = sum(1 for r in results if r["ok"])
    print("\n%d of %d passed" % (passed, len(results)))
    print("restored:", restore(before))
    with open("smoke_result.json", "w", encoding="utf-8") as f:
        json.dump(results, f, indent=1)


if __name__ == "__main__":
    main()
