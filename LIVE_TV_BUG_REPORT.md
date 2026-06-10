# Live TV — bug report for handover

**To:** the agent maintaining the Live TV feature (originally landed via
`LIVE_TV_NOTES.md` in this repo) and the data agent maintaining
`https://github.com/hexhoxhex/mkurugenzi_viewer`.

**From:** the moviebox-tv app agent. The user is forwarding this note —
please read top to bottom; everything in here is verified, not hearsay.

> **Update 2026-06-10 21:30 UTC:** the original "every URL expired" report
> below was solved by the data agent (auto-refresh every 30 min via GitHub
> Actions). However a **new and separate issue** is now blocking *every*
> channel from playing. The diagnosis is in §0 immediately below. The rest
> of this doc is preserved for historical reference and because the
> three app-side UX patches it spawned are still useful.

## §0 — The blocker as of right now: inner playlist URLs return 403

**Symptom (verified end-to-end on the user's TCL Smart TV):**

1. User opens Live TV, taps any channel.
2. ExoPlayer hits the master `.m3u8` — succeeds (HTTP 200, valid manifest).
3. ExoPlayer follows the `tracks-v1a1/mono.m3u8` URL listed inside the
   master — gets **403 Forbidden** from nginx.
4. Our new `onPlayerError` correctly catches this, surfaces
   "This channel is offline right now" via `vm.surfaceError`, and bounces
   back to the channel grid. The app is doing the right thing — there's
   nothing playable to play.

This happens with **100% of the OK-status catalog** today. Catalog tokens
themselves are fresh (~37 min left when this was diagnosed); the master
URLs accept them; the inner URLs reject them.

**Reproduced outside the app** with `curl` from the same machine:

```sh
# Master URL — works:
$ curl -s 'https://vomos.phantemlis.top/premium51/index.m3u8?md5v1=91xnVUnC7y6GLHdABJnjFg&md5v2=EojEau0DON63iqp50tJQsA&expires=1781127765'
#EXTM3U
#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=7210000,BANDWIDTH=9020000,RESOLUTION=1280x720,FRAME-RATE=59.940,CODECS="avc1.640020,mp4a.40.2",CLOSED-CAPTIONS=NONE
tracks-v1a1/mono.m3u8?md5=91xnVUnC7y6GLHdABJnjFg&expires=1781127765

# Inner URL — 403 Forbidden:
$ curl -sI 'https://vomos.phantemlis.top/premium51/tracks-v1a1/mono.m3u8?md5=91xnVUnC7y6GLHdABJnjFg&expires=1781127765'
HTTP/1.1 403 Forbidden
Server: nginx/1.24.0 (Ubuntu)
```

**The smell:** notice the parameter mismatch between the two URLs:

| URL | params |
|---|---|
| Master   | `md5v1=…&md5v2=…&expires=…` |
| Inner    | `md5=…&expires=…`           |

The inner URL only has **`md5`** (no v1/v2 split). The signed-token scheme
the CDN is enforcing today appears to require *both* `md5v1` and `md5v2`
just like the master URL does. Either:

- (most likely) the donis backend recently changed its inner-playlist URL
  format and the scraper's template is stale, or
- (less likely) the master is supposed to be rewriting inner URLs at request
  time and isn't (the static-mirror model doesn't re-sign on every fetch).

**Action for the data agent:**

1. Confirm in `mkurugenzi_viewer/scraper.py` how the inner URL is produced.
   It's probably resolved by following the master once at scrape time —
   so the templated string ends up frozen with only the bare `md5` token.
2. Either:
   - Capture the **`md5v1`** and **`md5v2`** values that the live donis
     resolver returns at scrape time and bake those into the catalog entry
     alongside `stream_url`, then have the app reach them; **or**
   - Change the `stream_url` in `channels.json` to point at the inner URL
     directly with the full two-token signature, so the app skips the
     "follow master, then load inner" step; **or**
   - Have the master URL re-fetched per playback (which means the app calls
     the master, follows the redirect/manifest, gets the fresh inner URL
     each session — this is closer to how the original donis player works).

The 30-min cron refresh isn't enough on its own because the inner URLs
go bad faster than that (within minutes of being captured, based on the
fact that *the very minute we scrape* they already return 403). The
problem is what the scraper writes, not how often it writes it.

**Action for the moviebox-tv app:** nothing further. The error UX is
already correct. Re-opening this section when the catalog can produce
playable inner URLs will let us verify end-to-end.

---

## §1 — Historical: original expiry diagnosis (resolved)

The original report below is preserved for posterity. The catalog-expiry
root cause is **fixed**; the §0 issue above is unrelated.

### tl;dr (original)

1. Every Live TV channel returned HTTP 410 Gone because every
   `stream_url` in `channels.json` had `expires=2026-06-10` and today
   was `2026-06-10`. **Solved:** GitHub Actions cron, every 30 min, by
   the data agent.
2. App-side UX bug: when the stream is dead, the player stayed at
   `00:00 · 00:00` with no feedback. **Solved:** the data agent
   implemented the `onPlayerError → vm.surfaceError + vm.back` flow in
   `PlayerScreen.kt`. Verified — see §0 above, the new flow is what
   bounces us back to the grid even now.
3. App-side cosmetic bug: 68% of OK channels had no logo URL in the
   catalog, and the previous fallback rendered `displayName.take(3)`
   which made unrelated channels show as "ARE", "AST", "EUR" looking
   broken. **Solved:** acronym + per-name gradient fallback in
   `LiveTvScreen.kt::ChannelCard` (e.g. `Astro Supersport 1` → "AS1" on
   a deterministic gradient tile).
4. App-side feature gap: the SPA Live tab couldn't drive channels.
   **Solved:** same root cause — once playback worked, the SPA worked.
   Now blocked again by §0.

### Original §2 — dead-stream UX

`PlayerScreen.kt:511`'s `onPlayerError` used to no-op for live streams.
Patched to distinguish by HTTP code (401/403/410 → "offline", else
"connect failed"), and call `liveErrorState.value(msg)` which fires
`vm.surfaceError(msg) + vm.back()` in PlayerScreen. Verified working on
the TV against today's universal-403 inner URLs (the bounce is what's
happening).

### Original §3 — 3-letter abbreviations

Replaced with `initialsOf(name)` + `initialsGradient(name)`. Verified
visually — the grid now reads as a curated wall of tiles rather than a
broken-looking grid.

### Original §4 — mobile remote

Was wired correctly all along; same root cause as everything else.

### Original §5 — modern player

Done as a separate ticket in the moviebox-tv repo. Custom Compose
overlay replaces the Media3 default controller. Live variant strips
the scrubber and skip-10s buttons.

---

## §6 — Things still worth doing once §0 is fixed

1. **Schedule view in the mobile remote SPA** — server endpoint
   exists, just needs the HTML + JS. ~1h.
2. **tv-logo/tv-logos GitHub fallback** for channels missing logos
   via `tvg_id`. Optional polish.
3. **EPG overlay on channel cards** using `tvg_id` × an XMLTV feed.
4. **`hexhoxhex/mkurugenzi_viewer/scraper.py` change** to either bake
   the two-token signed inner URLs into the catalog, or to point
   `stream_url` at the inner URL directly. This is the §0 fix and
   unblocks everything else.

---

## Files referenced

| Path | Purpose |
|---|---|
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\data\live\LiveTvRepository.kt` | OkHttp fetcher, `CHANNELS_URL` + `SCHEDULE_URL` |
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\ui\LiveTvScreen.kt` | Grid, search, group chips, `ChannelCard` |
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\ui\PlayerScreen.kt` | Custom Compose controls; `onPlayerError` → live error bounce |
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\ui\MainViewModel.kt` | `playChannel(Channel)`, `surfaceError(message)` |
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\remote\RemoteServer.kt` | `/api/live/channels`, `/api/live/play` routes |
| `G:\development\entertainment\moviebox-tv\app\src\main\assets\remote.js` | SPA Live tab — `loadLive`, `refreshLive`, channel cards |
| `G:\development\entertainment\moviebox-tv\LIVE_TV_NOTES.md` | Original handover doc (read first) |

## Repro command for §0

```sh
# Pick the first OK channel from the catalog and see the two-tier response
curl -s 'https://raw.githubusercontent.com/hexhoxhex/mkurugenzi_viewer/main/data/channels.json' \
  | python -c "
import json, sys
data = json.load(sys.stdin)
ok = [c for c in data if c.get('status')=='ok'][0]
print(ok['stream_url'])
"
# -> master URL (works 200)
# Then GET the master, read the path it references inside, and try that:
# -> returns 403 every time

# adb logcat on the TV while a channel is tapped:
adb -s <tv> logcat -d | grep -iE 'exoplayer.*error|response code'
# -> Caused by: InvalidResponseCodeException: Response code: 403
```
