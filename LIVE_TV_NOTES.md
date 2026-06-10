# Live TV integration — handover notes

These notes describe the Live TV feature that was added to the moviebox-tv
Android app. Build is green; everything below has compiled and passed
`./gradlew :app:compileDebugKotlin`.

> The data source is a **separate** sibling repo:
> **`https://github.com/hexhoxhex/mkurugenzi_viewer`** (Python scraper +
> published JSON). The app fetches from it; if you fork that repo, change
> `REPO` in `LiveTvRepository.kt` and re-push the data.

## What got added

| File | Purpose |
|---|---|
| `app/src/main/java/com/moviebox/tv/data/live/LiveModels.kt` | `Channel`, `ScheduleEvent`, `ScheduleChannelRef` — Moshi DTOs mapping `data/channels.json` + `data/schedule.json` |
| `app/src/main/java/com/moviebox/tv/data/live/LiveTvRepository.kt` | OkHttp fetcher with in-memory cache (30 min channels TTL, 5 min schedule TTL). Filters to `status == "ok"` by default. Has `channels()`, `schedule()`, `groups()`, `scheduleByCategory()` |
| `app/src/main/java/com/moviebox/tv/ui/LiveTvScreen.kt` | Sub-tab pills (Channels / Schedule), search box, horizontal group filter chips, **6-col grid (TV) / 3-col grid (phone)** of channel cards, collapsible schedule with playable channel chips |

## What got modified

| File | Change |
|---|---|
| `gradle/libs.versions.toml` + `app/build.gradle.kts` | added `media3-exoplayer-hls` |
| `app/src/main/java/com/moviebox/tv/data/Models.kt` | `PlayInfo` gained `isLive: Boolean = false` and `subtitle: String = ""` |
| `app/src/main/java/com/moviebox/tv/ui/MainViewModel.kt` | new `Tab.LIVE` and `LiveSubTab`, live state fields, `loadLive()`, `selectLiveSubTab()`, `selectLiveGroup()`, `onLiveQuery()`, `playChannel()`, `playScheduleChannel()`. `back()` now returns to `Tab.LIVE` from a live player |
| `app/src/main/java/com/moviebox/tv/ui/AppRoot.kt` | `Live TV` nav item next to Home, routes `Tab.LIVE` to `LiveTvScreen` |
| `app/src/main/java/com/moviebox/tv/ui/PlayerScreen.kt` | HLS-aware media source factory when `isLive`; 12s live target offset via `LiveConfiguration`; clean `DefaultHttpDataSource.Factory` (no MovieBox `Referer` injected for live); skip resume seek + save-progress + autoplay-next + quality/dub UI in live mode; red `LIVE` pill in title bar |
| `app/src/main/java/com/moviebox/tv/remote/RemoteController.kt` | `liveChannels()`, `liveSchedule()`, `liveLoaded()`, `ensureLiveLoaded()`, `playLiveChannel(id)` |
| `app/src/main/java/com/moviebox/tv/remote/RemoteServer.kt` | new endpoints `/api/live/channels`, `/api/live/groups`, `/api/live/schedule`, `POST /api/live/play` |
| `app/src/main/assets/remote.html` + `remote.js` + `remote.css` | `Live` nav button + `pane-live` with search, group chips, channel grid; SPA polls until TV finishes first load |

## The 12-second "head-space" buffer

In `PlayerScreen.kt` when `isLive == true`:

```kotlin
MediaItem.LiveConfiguration.Builder()
    .setTargetOffsetMs(12_000)   // sit ~12s behind live edge
    .setMinOffsetMs(6_000)
    .setMaxOffsetMs(30_000)
    .setMinPlaybackSpeed(0.97f)  // small speed window so ExoPlayer can
    .setMaxPlaybackSpeed(1.03f)  // quietly drift back to target after rebuffer
    .build()
```

Combined with a tighter `LoadControl` (15s min buffer / 40s max / 3s startup
/ 6s after rebuffer) — "tight live but resilient". Tweak the numbers in
`PlayerScreen.kt`'s `remember(isLive)` block if your network is flakier or
if you want the latency lower.

## Why we do NOT inject MovieBox headers on live streams

The catalog's stream URLs are on `pontos.phantemlis.top` /
`fomis.phantemlis.top` / etc. They authenticate via signed-token query
parameters (`md5v1`, `md5v2`, `expires`), require no `Referer`/`Origin`/
custom `User-Agent`, and return `Access-Control-Allow-Origin: *`. Injecting
the MovieBox `Referer` either gets ignored or actively rejected by some
nodes. The live factory is intentionally bare:

```kotlin
DefaultHttpDataSource.Factory()
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(15_000)
    .setReadTimeoutMs(20_000)
    .setUserAgent("Mozilla/5.0 (Linux; Android 12) ...Chrome/131...")
    // NOTE: no setDefaultRequestProperties(Constants.mediaHeaders)
```

VOD playback still uses the header-injecting factory through the same
`VideoPlayer` composable — the `isLive` parameter switches between them.

## CORS

CORS is a browser-only concern. Android (OkHttp + ExoPlayer) ignores it.
We do **not** need to do anything about CORS in the app. The catalog
happens to be CORS-open as a side effect of serving the web tester, but
that's not relevant here.

## How channels.json is structured

Excerpt of one channel:

```json
{
  "id": "657",
  "name": "discovery family",
  "stream_url": "https://fomis.phantemlis.top/premium657/index.m3u8?md5v1=...&expires=...",
  "status": "ok",         // "ok" / "down" / "unreachable"
  "logo": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/...",
  "group": "USA (DADDY LIVE)",
  "tvg_id": "Discovery.Family.Channel.us",
  "daddy_endpoint": "daddy.php",
  "players": [
    {"name":"P1","path":"stream","target_host":"donis.jimpenopisonline.online","available":true},
    ...
  ]
}
```

Schedule:
```json
{ "category": "Soccer", "time": "22:00", "title": "Arsenal vs Chelsea",
  "channels": [{"id":"758","name":"Fox Sports 2 USA"}] }
```

The app today only consumes `id`, `name`, `stream_url`, `status`, `logo`,
`group`, `tvg_id`. The `players` array is recorded so future code can
fall over to iframe-style alt backends if the direct `stream_url` 5xx's.

## Stream-URL expiry

Each `stream_url` carries an `expires=<unix>` query param, usually ~2
months out from when the scraper last ran. After expiry the stream returns
401/403 instead of the manifest. **Re-run the scraper before that** — the
data repo's README explains how. For app users this means: if a previously-
working channel suddenly stops playing, the catalog needs a refresh.

## Mobile remote SPA — what works now

When a phone pairs with the TV via the existing RemoteServer flow, the
new `Live` tab shows:
- Search field
- Horizontal group chips (All / USA / Sports / ...)
- Channel cards with logo + name + red `LIVE` pill
- Tap a card → TV starts that channel immediately

The SPA polls `/api/live/channels` every 1.5s when first opened until the
TV reports `loaded: true` (in case the user opens the phone Live tab
before the TV ever opened LIVE itself).

> The schedule API (`/api/live/schedule`) is implemented server-side but
> the SPA currently only renders the channels view. Adding a schedule
> sub-pane is the next obvious enhancement — mirror what
> `LiveTvScreen.kt` does on the TV.

## Known gaps / things still to do

1. **Schedule view in the mobile SPA** — server endpoint exists, just needs
   the HTML + JS.
2. **Per-channel header overrides** — if/when a future channel needs custom
   `Referer`/`Origin`, extend `Channel` with `headers: Map<String,String>?`
   and pass them per `MediaItem`. Not needed for today's catalog.
3. **Auto-refresh of the data repo** — currently the user re-runs
   `python scraper.py` manually on their machine and pushes. A GitHub
   Actions workflow (every 6 hours) was discussed but not built.
4. **Down/unreachable iframe fallback** — for channels marked `down`, the
   data already says which alt backends are reachable. The TV app could
   embed `dlhd.pk/cast/stream-{id}.php` in a `WebView` as a fallback, like
   the web tester does. Not built.
5. **The native player fades the system bars on entry** — fine for VOD,
   but for live a user might expect the channel name to stay visible
   longer. Controls already auto-hide on inactivity; if you want a
   persistent overlay on live, tweak the `AnimatedVisibility(controlsVisible)`
   in `PlayerScreen.kt`.
6. **EPG (electronic program guide)** — `tvg_id` is recorded but not
   used. An XMLTV-style EPG could overlay "what's on now / next" on each
   channel card.

## Build sanity

```sh
./gradlew :app:compileDebugKotlin   # clean, only the pre-existing UnstableApi warning
./gradlew :app:installDebug         # to push to a connected device/emulator
```

`./gradlew assembleDebug` will produce `app/build/outputs/apk/debug/app-debug.apk`.
