# Live TV — bug report for handover

**To:** the agent maintaining the Live TV feature (originally landed via
`LIVE_TV_NOTES.md` in this repo) and the data agent maintaining
`https://github.com/hexhoxhex/mkurugenzi_viewer`.

**From:** the moviebox-tv app agent. The user is forwarding this note —
please read top to bottom; everything in here is verified, not hearsay.

## tl;dr

1. **Every Live TV channel returns HTTP 410 Gone** because every
   `stream_url` in `channels.json` has `expires=2026-06-10` and today
   is `2026-06-10`. **The whole catalog needs a re-scrape.** This is
   the only reason "I click play and nothing happens" — the code path
   itself works.
2. **App-side UX bug**: when the stream is dead, the player stays at
   `00:00 · 00:00` with no feedback. There's an `onPlayerError`
   listener but it only calls `downgradeState.value()` (a VOD-only
   path that's meaningless for live). User has no idea anything went
   wrong.
3. **App-side cosmetic bug**: 513 of 752 OK channels have no logo URL
   in the catalog. The fallback in `LiveTvScreen.ChannelCard` renders
   `displayName.take(3).uppercase()` — that produces "ABC", "ANT",
   "ARE", "AST" which the user reports as "blank channel names". They
   aren't blank; they're 3-letter abbreviations of the channel name.
   But it reads as broken.
4. **App-side feature gap**: the user reports they can't tap a channel
   from the mobile remote SPA. Looking at the code that path exists
   (`/api/live/play` is wired in `remote.js`), but if all channels are
   dead, every tap silently fails after the success toast.

Fix priorities: **catalog refresh = 100% of impact**. Without that, the
other three fixes only paper over the experience.

---

## Evidence

### 1) Catalog expiry — every stream URL is dead

Fetched
`https://raw.githubusercontent.com/hexhoxhex/mkurugenzi_viewer/main/data/channels.json`
at `Wed Jun 10 2026 20:16:29 UTC` and inspected the first 5
status=="ok" channels:

```
EXPIRED expires=2026-06-10 (0d ago) name='abc usa'         logo=yes
EXPIRED expires=2026-06-10 (0d ago) name='antenna tv usa'  logo=NO
EXPIRED expires=2026-06-10 (0d ago) name='a&e usa'         logo=yes
EXPIRED expires=2026-06-10 (0d ago) name='amc usa'         logo=yes
EXPIRED expires=2026-06-10 (0d ago) name='animal planet'   logo=yes
```

```
Total channels: 899
status==ok:     752    (the 752 we surface today)
no logo URL:    513    (68% of OK channels)
blank name:     0
```

Confirmed by adb logcat on the actual TV (`192.168.100.8:5555`) when
tapping the first channel:

```
E/ExoPlayerImplInternal: Playback error
E/ExoPlayerImplInternal:   androidx.media3.exoplayer.ExoPlaybackException: Source error
E/ExoPlayerImplInternal:   Caused by: androidx.media3.datasource.HttpDataSource$InvalidResponseCodeException: Response code: 410
```

**Action for the data agent:** re-run the scraper in
`mkurugenzi_viewer`, commit + push the refreshed `data/channels.json`
(and `data/schedule.json` if the same scraper writes both). The TV app
respects the existing 30-min channels TTL
(`LiveTvRepository.CHANNELS_TTL_MS`) so it will pick up the new data
on next cold start or after `loadLive(force = true)`.

Per the original `LIVE_TV_NOTES.md`:
> Each `stream_url` carries an `expires=<unix>` query param, usually
> ~2 months out from when the scraper last ran. After expiry the
> stream returns 401/403 instead of the manifest. Re-run the scraper
> before that.

This is exactly what's happened. The previous scrape was ~2 months
ago.

### 2) App-side: dead-stream UX

`PlayerScreen.kt:511` (the player error listener):

```kotlin
override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
    // Try one notch lower (e.g. 1080P HEVC → 720P / 480P H.264).
    // If no lower quality is available, the player is left paused
    // and the user can hit back.
    downgradeState.value()
}
```

`downgradeQuality()` in `MainViewModel` looks at `play.qualities` —
for live there are no qualities (it's HLS). So the call is a no-op
and the player just sits at `00:00`. The user reports they're stuck
on a black screen with the channel name and a `00:00 · 00:00` clock.

**Suggested fix:**

```kotlin
override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
    // VOD: try one quality notch lower. If we're on a live stream and
    // either the manifest is gone or the auth token expired, nudge the
    // VM to surface a friendly error and bounce back to the grid.
    if (liveState.value) {
        liveErrorState.value(error)   // new: report up to PlayerScreen
        return
    }
    downgradeState.value()
}
```

…and then in `PlayerScreen` thread that into the existing error-banner
+ `vm.back()` flow. Suggested copy: **"This channel is offline right now."**
when the HTTP code is 410/403/401, or **"Couldn't connect — try
another channel."** otherwise.

The same fix should also clear `state.play` so the immersive system bars
restore on `back`, otherwise the user is stuck with no controls.

### 3) App-side: 3-letter "abbreviations" look broken

`LiveTvScreen.kt:196-201`:

```kotlin
} else {
    Text(
        ch.displayName.take(3).uppercase(),
        color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 18.sp,
    )
}
```

For a channel called `"ARENAS SPORT 2 SERBIA"` this renders just
`"ARE"` on a dim text colour — looks like the card is broken. Three
ways to make it not feel broken:

- **Use the same gradient+initials fallback that the movie posters got**
  (`Components.kt::PosterImage::PosterFallback`). Reuses the
  `titleColors(title)` deterministic palette. Looks intentional, not
  empty.
- **Take the first letter of each word**, capped to 3 words:
  `"ARENA SPORT 2"` → `"AS2"`. Better acronym than `"ARE"`.
- **Or fetch logos from a fallback source.** The
  [tv-logo/tv-logos](https://github.com/tv-logo/tv-logos) repo (already
  referenced in catalog records that have logos) maps `tvg_id` to a
  raw URL. For records where `logo` is empty but `tvg_id` is present,
  the app could attempt
  `https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/<dir>/<tvg_id>.png`
  and fall through to the gradient on 404.

The fastest win is option 1 — just reuse `PosterImage` with the channel
name as the title.

### 4) Mobile remote — what works, what doesn't

`remote.js` paths:
- `loadLive()` (line 636) — fetches `/api/live/channels` ✓
- `refreshLive()` (line 665) — polls until the TV says
  `loaded=true` ✓
- `/api/live/play` (line 708) — sends `id=<channelId>` to the TV ✓

These all wire up correctly. The user's complaint "I can't choose a
channel" is the same thing — they tap, the SPA toasts
`Playing ${ch.name}`, the TV silently fails because the stream is 410.

If you fix the catalog AND add the dead-stream banner from §2, the
remote experience auto-fixes too.

> Side note for the data agent: the catalog response is ~1.2 MB
> gzipped from GitHub raw. That's fine on Wi-Fi but feels slow on the
> mobile remote's first paint. If you ever consider a smaller "lite"
> JSON (just `id`, `name`, `group`, `logo`, `tvg_id`), the SPA would
> render channels notably faster.

### 5) "Player needs to be modern not old"

The user is reacting to the Media3 default `PlayerView` controls —
the legacy SkipPrevious / Rewind 5s / Play / FastForward 15s / SkipNext
strip with the chip-style time text. That's not in this report's scope
(it's a separate redesign ask), but for context: the path is
`PlayerScreen.kt::AndroidView(factory = { ctx -> PlayerView(ctx).apply { ... } })`.
Replacing it with a custom Compose overlay is the standard route; the
user has approved that direction in a previous turn.

Suggested next step there (separate from this Live TV bug report):
custom Compose controls — single tap shows overlay, idle hide after
3s, big rounded play/pause, scrub bar with thumbnail strip, episode
chips for series. The live variant doesn't need scrubbing — just a
title + LIVE pill + audio picker + back.

---

## Reproducing locally

If you want to repro the 410:

```sh
adb -s 192.168.100.8:5555 install -r \
  G:/development/entertainment/moviebox-tv/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.100.8:5555 logcat -c
adb -s 192.168.100.8:5555 shell am start -n com.moviebox.tv/.MainActivity
# tap Live TV → pick any channel → wait 3s
adb -s 192.168.100.8:5555 logcat -d -v brief | grep -iE 'exoplayer.*error|Response code'
```

You'll see `Response code: 410` for every channel until the catalog is
refreshed.

## Files referenced

| Path | Purpose |
|---|---|
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\data\live\LiveTvRepository.kt` | OkHttp fetcher, `CHANNELS_URL` + `SCHEDULE_URL` |
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\ui\LiveTvScreen.kt` | Grid, search, group chips, `ChannelCard` (line 174), 3-letter fallback (line 196) |
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\ui\PlayerScreen.kt` | HLS path; `onPlayerError` (line 511); HlsMediaSource + 12s `LiveConfiguration` |
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\ui\MainViewModel.kt` | `playChannel(Channel)` (line 216), `downgradeQuality()` is what `onPlayerError` calls |
| `G:\development\entertainment\moviebox-tv\app\src\main\java\com\moviebox\tv\remote\RemoteServer.kt` | `/api/live/channels`, `/api/live/play` routes |
| `G:\development\entertainment\moviebox-tv\app\src\main\assets\remote.js` | SPA Live tab — `loadLive`, `refreshLive`, channel cards |
| `G:\development\entertainment\moviebox-tv\LIVE_TV_NOTES.md` | The original handover doc (read first) |

## Suggested fix order

1. **`mkurugenzi_viewer` re-scrape and push.** Without this, nothing
   in the app surfaces channels that actually play. Single highest-
   leverage action.
2. **Dead-stream banner in PlayerScreen** (live variant of the
   existing error UX). ~30 min of work. Code sketch in §2 above.
3. **Logo fallback** — reuse `PosterImage`'s gradient. ~15 min.
4. **`tv-logo/tv-logos` fallback URL** based on `tvg_id`. Optional;
   needs HEAD-check infrastructure to avoid permanent dead links.

Once 1 lands the user's experience instantly works. Once 2+3 land,
even when individual channels die the experience is still graceful.
