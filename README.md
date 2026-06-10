# MovieBox TV

A native **Android phone + Android TV** app that streams MovieBox / aoneroom
content directly — **no proxy server**. The app signs API requests itself and
plays the CDN media with the required headers via ExoPlayer.

## Why no proxy?

The web player needed a server-side proxy because browsers can't set
`Referer`/`User-Agent` on a `<video>` request. On Android, **Media3 ExoPlayer
can** set those headers on its HTTP data source, so the app talks to the CDN
directly. Subtitles (SubRip `.srt`) are also rendered natively — no `.vtt`
conversion needed.

## Architecture

```
ui/ (Jetpack Compose)        Search → Detail → Player
  └─ MainViewModel           state + orchestration
data/
  ├─ Repository              search / details / resolvePlay (dub, quality, eps)
  ├─ Models                  domain types
  └─ dto/                    Moshi JSON models
net/
  ├─ Crypto                  request signing (X-Client-Token, x-tr-signature)
  ├─ Constants               secrets, host pool, device fingerprint, CDN headers
  ├─ ApiInterceptor          signs every request + host-pool fallback + token
  ├─ TokenStore              absorbs the x-user bearer token
  └─ ApiClient / MovieBoxApi Retrofit + Moshi
player                       ExoPlayer with header injection + native subtitles
```

### Request signing — validated

`net/Crypto.kt` is a byte-for-byte port of the Python `moviebox_api.v3.crypto`.
It has been verified to produce identical `X-Client-Token` and `x-tr-signature`
output to the reference implementation (otherwise the API returns 403).

## Features

- Search movies & TV series, posters, ratings
- Movie playback with a quality switcher (defaults to the **best available**)
- TV series: season/episode picker, **autoplay-next** with season rollover
- Audio/dub selection (maps each dub to its own subjectId)
- Native subtitle tracks (English selected by default)
- API host-pool fallback on 403/429/5xx

## Build & run

Requires Android Studio (Ladybug or newer), JDK 17+, Android SDK 35.

```sh
# From Android Studio: File ▸ Open ▸ select this folder, let Gradle sync, Run.
# Or CLI (after `gradle wrapper` has generated gradlew):
./gradlew :app:installDebug
```

Runs on Android phones (API 26+) and Android TV (Leanback launcher entry
included).

## Status

- ✅ Request signing validated in real Kotlin against the Python reference
- ✅ Full project scaffolded (networking, models, repository, Compose UI, player)
- ⏳ End-to-end run requires building in Android Studio (emulator/device)
