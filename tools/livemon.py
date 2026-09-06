#!/usr/bin/env python3
"""
livemon.py — health snapshot of a live-streaming Vijana BaruBaru session.

Tails the logcat file produced by:
    adb -s <device> logcat --pid <pid> -v threadtime > /tmp/livemon/full_<TS>.log

Prints one row every INTERVAL seconds with:
    t       wall-clock HH:MM:SS
    codec   "ok" if MediaCodec QIB ticked in this window, "STALL" otherwise
    fps     QIB frames-per-second from the last QIB line
    inner   count of /inner/<id> proxy fetch log lines in the window
    splice  count of "cut detected" events (our new continuity splice path)
    stall   count of "stalled NNNms" upstream-stall events
    err     count of HTTP 4xx/5xx or InvalidResponseCode events

Plus, for noteworthy events, prints an immediate alert line:
    !! STATE_ENDED on live → re-prepare fired
    !! N silent upstream splice(s) — DISCONTINUITY inserted
    !! upstream stalled NNNms
    !! user-visible 'Reconnecting…' pill shown
    !! Source error / PlaybackException
    !! upstream HTTP <code> for <url>
"""
from __future__ import annotations
import argparse
import functools
import glob
import os
import re
import time
from pathlib import Path

# Monitor() reads stdout line by line — must flush every print to avoid
# events sitting in Python's stdout buffer indefinitely.
print = functools.partial(print, flush=True)  # noqa: A001

QIB_RE     = re.compile(r"QIB:(\d+)/s")
SPLICE_RE  = re.compile(r"cut detected")
STALL_RE   = re.compile(r"stalled (\d+)ms")
INNER_RE   = re.compile(r"handleInner|/inner/")
HTTP_ERR_RE = re.compile(r"HTTP/1\.[01]\" ([45]\d\d)|InvalidResponseCodeException")
STATE_ENDED_RE = re.compile(r"Live STATE_ENDED")
RECONNECT_RE   = re.compile(r"Reconnecting")
SRC_ERR_RE     = re.compile(r"Source error|PlaybackException|ExoPlayerImplInternal.*error")


def latest_log(base=None) -> str | None:
    # /tmp on Git Bash for Windows is %LOCALAPPDATA%\Temp; Python on Windows
    # doesn't resolve "/tmp" directly. Walk both shells' notion of /tmp.
    candidates = [base] if base else [
        os.path.expandvars(r"%LOCALAPPDATA%\Temp\livemon"),
        "/tmp/livemon",
    ]
    for c in candidates:
        if not c:
            continue
        files = sorted(glob.glob(os.path.join(c, "full_*.log")),
                       key=os.path.getmtime, reverse=True)
        if files:
            return files[0]
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile", nargs="?", default=None)
    ap.add_argument("--interval", type=int, default=30)
    ap.add_argument("--alerts-only", action="store_true",
                    help="Only print alert lines, suppress periodic status rows")
    args = ap.parse_args()

    logfile = args.logfile or latest_log()
    if not logfile or not os.path.exists(logfile):
        print(f"ERROR: no log file. Tried {logfile}")
        return 1
    print(f"Monitoring {logfile} every {args.interval}s. Ctrl-C to stop.")
    hdr = f"{'t':<10}{'codec':<7}{'fps':<5}{'inner':<7}{'splice':<7}{'stall':<7}{'err':<5}"
    print(hdr)
    print("-" * len(hdr))

    f = open(logfile, "r", encoding="utf-8", errors="replace")
    f.seek(0, os.SEEK_END)

    # Wall-clock of the most recent QIB we observed in any window. Used
    # to suppress STALL alerts when the user has been idle on the grid
    # with no codec ever active in this monitoring session (otherwise
    # the alert spams every interval forever).
    last_qib_seen_at: float = 0.0

    try:
        while True:
            time.sleep(args.interval)
            chunk = f.read()
            if not chunk:
                # In alerts-only mode silence is the point — don't ping the
                # operator every interval just to say "still nothing".
                if not args.alerts_only:
                    print(f"{time.strftime('%H:%M:%S'):<10}{'-':<7}{'-':<5}"
                          f"{'0':<7}{'0':<7}{'0':<7}{'0':<5}  (no new log lines)")
                continue

            # Stat counters
            qibs = QIB_RE.findall(chunk)
            last_fps = qibs[-1] if qibs else None
            codec_ok = bool(qibs)

            splice = len(SPLICE_RE.findall(chunk))
            stalls = STALL_RE.findall(chunk)
            stall_count = len(stalls)
            inner = len(INNER_RE.findall(chunk))
            err = len(HTTP_ERR_RE.findall(chunk))

            # Alert events (one line each)
            if STATE_ENDED_RE.search(chunk):
                print("  !! STATE_ENDED on live → re-prepare fired")
            if splice > 0:
                print(f"  !! {splice} silent upstream splice(s) "
                      f"— DISCONTINUITY inserted")
            if stall_count > 0:
                print(f"  !! upstream stalled {stalls[-1]}ms "
                      f"({stall_count} stall log(s) this window)")
            if RECONNECT_RE.search(chunk):
                print(flush=True); print("  !!user-visible 'Reconnecting…' pill shown")
            if SRC_ERR_RE.search(chunk):
                m = SRC_ERR_RE.search(chunk)
                # Try to grab the short message after the match
                ctx = chunk[m.start():m.start() + 160].splitlines()[0]
                print(f"  !! {ctx}")

            # Status row
            codec = "ok" if codec_ok else "STALL"
            # In alerts-only mode, only emit on degraded state.
            if args.alerts_only:
                if last_fps:
                    last_qib_seen_at = time.time()
                # Only fire a STALL alert if we actually saw QIB activity
                # in the last 2 min — otherwise the user is just on the
                # channel grid (no codec, no playback) and the "stall"
                # is a false positive we'd spam every interval forever.
                if codec == "STALL" and last_qib_seen_at and \
                        (time.time() - last_qib_seen_at) < 120:
                    print(f"  !! codec STALL ({time.strftime('%H:%M:%S')}) — "
                          f"no MediaCodec QIB in the last {args.interval}s")
                # Otherwise stay silent — silence == healthy.
            else:
                row = (f"{time.strftime('%H:%M:%S'):<10}"
                       f"{codec:<7}{(last_fps or '-'):<5}"
                       f"{str(inner):<7}{str(splice):<7}"
                       f"{str(stall_count):<7}{str(err):<5}")
                print(row, flush=True)
    except KeyboardInterrupt:
        print("\nstopped.")
    finally:
        f.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
