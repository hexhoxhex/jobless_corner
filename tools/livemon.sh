#!/usr/bin/env bash
# livemon.sh — one-line health snapshot of a live-streaming Vijana BaruBaru
# session, every N seconds. Reads a tailed logcat written by:
#
#   adb -s <device> logcat --pid <pid> -v threadtime > /tmp/livemon/full_<TS>.log
#
# Per snapshot we report:
#   t       wall-clock HH:MM:SS
#   codec   "ok" if MediaCodec QIB ticked since last snapshot, "STALL" if not
#   fps     frames per second from the last MediaCodec QIB line
#   buf     last seen ExoPlayer buffer state (BUFFERING / READY / IDLE / ENDED)
#   inner   count of /inner/<id> serves from LiveStreamProxy in the window
#   splice  count of "cut detected" splices in the window (our new code path)
#   stall   count of "stalled" upstream events in the window
#   err     count of 4xx/5xx HTTP errors in the window
#
# Plus, before each snapshot, ANY of these patterns triggers an alert line:
#   - "Live STATE_ENDED" (our new live-recovery path)
#   - "cut detected"     (proxy spliced an upstream change silently)
#   - "stalled "          (proxy detected upstream sequence not advancing)
#   - "Reconnecting…"    (LiveResilience surfaced the user-facing pill)
#   - "Source error" / "PlaybackException"  (player error fired)
set -euo pipefail
LOG="${1:-$(ls -t /tmp/livemon/full_*.log | head -1)}"
INTERVAL="${INTERVAL:-30}"

prev_qib_line=""
prev_offset=0
echo "Monitoring $LOG every ${INTERVAL}s. Ctrl-C to stop."
printf '%-10s %-6s %-5s %-12s %-6s %-6s %-6s %-4s\n' \
    t codec fps buf inner splice stall err
printf '%s\n' "------------------------------------------------------------"

while true; do
    sleep "$INTERVAL"
    cur_size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    if [ "$cur_size" -le "$prev_offset" ]; then
        prev_offset="$cur_size"
        continue
    fi
    # Read only the new bytes since last poll
    window=$(tail -c "+$((prev_offset + 1))" "$LOG" 2>/dev/null || true)
    prev_offset="$cur_size"

    # codec rate from last QIB line in the window
    last_qib=$(echo "$window" | grep -oE "QIB:[0-9]+/s" | tail -1)
    fps="${last_qib#QIB:}"; fps="${fps%/s}"; fps="${fps:--}"
    if [ -z "$last_qib" ]; then codec="STALL"; else codec="ok"; fi

    # buffer / state
    buf=$(echo "$window" | grep -oE "playbackState=[A-Z_]+" | tail -1 | sed 's/playbackState=//')
    buf="${buf:--}"

    # proxy counts
    inner=$(echo "$window" | grep -cE "handleInner|/inner/" || echo 0)
    splice=$(echo "$window" | grep -c "cut detected" || echo 0)
    stall=$(echo "$window" | grep -cE "stalled [0-9]+ms" || echo 0)
    err=$(echo "$window" | grep -cE "HTTP/1\.[01]\" [45][0-9][0-9]|HttpDataSource\$InvalidResponseCodeException" || echo 0)

    # Alert lines for noteworthy events
    if echo "$window" | grep -q "Live STATE_ENDED"; then
        echo "  !! STATE_ENDED on live → re-prepare fired"
    fi
    if [ "$splice" -gt 0 ]; then
        echo "  !! $splice silent upstream splice(s) — DISCONTINUITY inserted"
    fi
    if [ "$stall" -gt 0 ]; then
        echo "$window" | grep -E "stalled [0-9]+ms" | tail -1 | \
            sed 's/^.*\(stalled .*\)$/  !! upstream \1/'
    fi
    if echo "$window" | grep -q "Reconnecting"; then
        echo "  !! user-visible 'Reconnecting…' pill shown"
    fi
    if echo "$window" | grep -qE "Source error|PlaybackException"; then
        echo "$window" | grep -E "Source error|PlaybackException" | head -1 | \
            sed 's/^/  !! /'
    fi

    printf '%-10s %-6s %-5s %-12s %-6s %-6s %-6s %-4s\n' \
        "$(date +%H:%M:%S)" "$codec" "$fps" "$buf" "$inner" "$splice" "$stall" "$err"
done
