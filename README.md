# Vijana BaruBaru

A native **Android phone + Android TV** streaming app. Browses TMDB-curated
catalogs, plays the resolved CDN media via ExoPlayer, and pairs with a phone
over Wi-Fi to act as a touch remote — all without a proxy server.

## What it does

- **Browse** — TMDB-curated rows: Trending, Popular Movies, Popular Series,
  Netflix, HBO, Disney+, Prime, Apple TV+
- **Play** — direct CDN playback with the right headers via Media3 ExoPlayer,
  closest-lower quality fallback, auto-downgrade on decoder errors
- **Self-healing catalog** — titles that fail to resolve are cached as
  unavailable so the home gradually surfaces only what actually plays
- **Pre-check on Detail open** — verifies a stream URL exists *before* the user
  taps Play; offers a "pick from search" fallback if not
- **Native subtitles** — multi-track SRT/VTT, device-language default
- **TV series** — season/episode picker, autoplay-next with season rollover,
  per-episode downloads or whole-season grab
- **Watch history + Continue Watching** — Room-backed, resumes within ±1s
- **Live TV** — HLS via the [mkurugenzi_viewer](https://github.com/hexhoxhex/mkurugenzi_viewer)
  catalog with a 12s buffer for resilience
- **Downloads** — foreground service, offline playback works in airplane mode
- **Mobile remote** — phone scans a QR shown on the TV, joins same Wi-Fi,
  takes over as a touch remote (search, browse, play, transport, volume slider,
  quality + audio pickers, taste preferences, devices admin)

## Architecture

```
ui/ (Jetpack Compose, TV-aware via LocalIsTv)
  └─ MainViewModel          state + orchestration

data/
  ├─ Repository             search / details / resolvePlay / streamHome
  ├─ tmdb/                  TMDB client + repository
  ├─ live/                  Live TV channels + schedule
  ├─ TastePrefs             language deny list (SharedPreferences)
  ├─ UnavailableCatalog     TMDB ids that failed to bridge — self-healing
  └─ local/                 Room: Favourites, WatchHistory, Downloads

net/                        Signed-request layer (X-Client-Token, x-tr-signature)
player/                     ExoPlayer with header injection + HLS for live
remote/
  ├─ RemoteAccess           Token + role store (SUPERUSER/USER/PENDING/BLOCKED)
  ├─ RemoteController       Bridge between embedded HTTP server and main-thread VM
  └─ RemoteServer           NanoHTTPD: serves the SPA + /api/*
assets/                     The SPA (HTML/CSS/JS) the phone loads after QR pair
```

## Build & run

Requires JDK 17+, Android SDK 35.

```sh
# CLI
./gradlew :app:installDebug                 # build + install on the foreground device
./gradlew :app:assembleDebug                # just produce app/build/outputs/apk/debug/app-debug.apk

# Optional: put your TMDB v4 read token in local.properties (gitignored)
#   TMDB_TOKEN=eyJhbG...
# Without it, the Browse rows and home will be empty; everything else still works.
```

Runs on Android phones (API 26+) and Android TV / Google TV (Leanback launcher
entry included).

## Mobile-remote pairing flow

1. On the TV: Home → Mobile Remote icon (top-right). A QR appears.
2. On the phone (same Wi-Fi): scan it. Browser opens the embedded SPA.
3. First device to pair becomes **SUPERUSER** automatically; subsequent
   devices are USER (if allow-all is on) or PENDING for the superuser to
   approve.
4. The QR overlay auto-dismisses the moment the phone pairs.

The SPA gives you Browse / Search / Now / Downloads / Taste, plus a Devices
admin pane for SUPERUSER. The taste pane lets you pick favourite networks,
genres, and languages to hide — preferences are stored per-device.

## Project status

- ✅ Verified end-to-end on the Pixel 7 Pro AVD and an Android TV at 1080p
- ✅ Live TV verified against `pontos.phantemlis.top` HLS streams
- ✅ Source published anonymously under [hexhoxhex/jobless_corner](https://github.com/hexhoxhex/jobless_corner)
