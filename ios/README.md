# Vijana BaruBaru for iPhone and iPad

The iOS version of the TV app in this repository: films and series (TMDB
catalogue, 7Movies and VixSrc sources) and live TV (channels and the sports
schedule from [hexhoxhex/mkurugenzi_viewer](https://github.com/hexhoxhex/mkurugenzi_viewer)).

It plays like Apple's TV app:

- keeps playing with the screen locked, with controls on the lock screen
- AirPlay to an Apple TV, including screen mirroring, and it keeps going
  while the phone is locked
- picture in picture when you leave the app
- remembers where you stopped, and plays the next episode

## Install it (no Mac needed)

1. Download `vijanabarubaru-<version>.ipa` from the
   [latest release](https://github.com/hexhoxhex/jobless_corner/releases/latest),
   next to the Android APK and the Windows MSI. Test builds from other
   branches are under **Actions > iOS app > (a green run) > Artifacts**.
2. Install [Sideloadly](https://sideloadly.io) on Windows or a Mac. On
   Windows it also needs the iTunes and iCloud apps from Apple's website,
   not the Microsoft Store versions.
3. Connect the iPhone or iPad by cable, open Sideloadly, drop the `.ipa` in,
   enter your Apple ID and press Start.
4. On the device, open **Settings > General > VPN & Device Management**, tap
   your Apple ID and choose **Trust**.
5. On iOS 16 and later, turn on **Settings > Privacy & Security > Developer
   Mode** when asked, and restart.

A free Apple ID signs the app for **7 days**. After that it stops opening
until you install it again with Sideloadly (your library stays). A paid
developer account signs it for a year. AltStore works too, and re-signs it
over Wi-Fi.

The first time you AirPlay a live channel, iOS asks to let the app find
devices on your local network. Say yes: the Apple TV fetches live TV from
the phone (see below).

## Build it

There is no Xcode project in the repository. `project.yml` describes the
app and [XcodeGen](https://github.com/yonaskolb/XcodeGen) makes the project
from it. The workflow `.github/workflows/ios.yml` runs on GitHub's Macs on
every push that touches `ios/`. It:

1. generates the project
2. runs the tests in the iPhone simulator
3. screenshots each tab
4. builds an unsigned `.ipa`

The `.ipa`, the screenshots and the logs are the run's artifacts.

Releases carry the `.ipa` too:

- A `v*` tag builds it from the tag in `release.yml`, next to the APK and
  MSI.
- A green build on main replaces the `.ipa` on the latest release, so iPhone
  fixes reach people without a new release.
- The release notes' iPhone section is written by
  `.github/scripts/ios_release_notes.py`.

On a Mac:

```
brew install xcodegen
cd ios && xcodegen generate && open VijanaBaruBaru.xcodeproj
```

For TMDB to work, set the build setting `TMDB_TOKEN` to a TMDB v4 read
token. The workflow uses the repository secret `TMDB_TOKEN`. Never commit
the token.

## How it works

```
App/
  VijanaBaruBaruApp.swift   app entry, tabs, colours
  Core/        AppLog (log + share file, links and tokens removed), Net (HTTP), Config
  Data/        Models, TMDB (catalogue), Library (continue watching, recent channels)
  Sources/     SevenMovies, VixSrc, Sources (the order they are tried in)
  Live/        LiveSource (find a channel's stream + its token), Unwrap (disguised chunks),
               LiveServer (the in-app HTTP server the player reads live TV from)
  Player/      PlayerModel (playing, failover, reconnects, lock screen),
               PlayerPresenter (Apple's full-screen player, background rules)
  UI/          Home, Detail, Search, Live, Library, Settings
Tests/         OfflineTests (no network), OnlineTests (real services; skip when unreachable)
```

**Films and series.** Titles come from TMDB, so every source is asked by TMDB
id. `Sources.resolve` asks 7Movies, then VixSrc. When 7Movies lists dubbed
copies, its original-language copy is picked: Apple's player, like the TV
app's, only plays the first audio track of a multi-language stream.

**Live TV.** This needs the most care:

- The channel's page names its stream. The stream's server wants a token
  that lasts two minutes; the app fetches it and renews it at half-life.
- The video chunks are disguised as PNG images. The picture's pixels hold a
  gzip of the real MPEG-TS, and `Unwrap` undoes that.
- Apple's player can't do any of this itself. It reads the channel from
  `LiveServer`, a small HTTP server in the app, which serves clean playlists
  and chunks.
- With AirPlay, the Apple TV reads from that same server over the home
  Wi-Fi. That is why the server listens on the phone's Wi-Fi address while
  AirPlaying.
- Film streams whose server wants headers go through the server's `/p/`
  route for AirPlay too, because an Apple TV can't send those headers.

**Staying up.** `PlayerModel` watches the picture every 2 seconds:

- **A film that doesn't start in 30 s** (a dead link) moves to the next
  source.
- **A film that stalls for 45 s** reloads once, then moves on.
- **A live channel that doesn't start in 75 s or stalls for 30 s**
  reconnects. It gives up after 5 reconnects without a steady stretch in
  between.

**Background.** Playback continues because of three things together:

- the `audio` background mode (`project.yml`)
- a `.playback` audio session with the long-form video policy
- taking the player off the screen when the phone locks
  (`PlayerPresenter`), which Apple requires for video to keep its sound in
  the background

## When something breaks

1. In the app, open **Settings > App logs > Share** and send the log, with
   what you were watching and when. Links, tokens and IP addresses are
   already removed from it.
2. Nearly every breakage is a site changing: a new domain, a renamed JSON
   field, a new token step. Compare what the site sends now with what the
   code expects. The Android TV app (`app/src/main/java/com/moviebox/tv/`)
   usually meets the change first, and its fix shows what to port:
   - `net/SevenMovies.kt` maps to `Sources/SevenMovies.swift`
   - `net/VixSrc.kt` maps to `Sources/VixSrc.swift`
   - `data/live/LiveResolver.kt` and `LiveStreamProxy.kt` map to
     `Live/LiveSource.swift` and `Live/LiveServer.swift`
   - `data/live/DisguisedSegment.kt` maps to `Live/Unwrap.swift`
3. Push the change to a branch. The workflow builds and tests it and leaves
   an `.ipa` to try.

Live TV can't be tested on GitHub's machines: the live sources don't serve
cloud addresses. Test live changes on a device, using the app log.

## Fixing it with an AI agent

Coding agents such as Claude Code can read this folder, compare it with the
Android app, and drive the GitHub build from a Windows PC.

1. Describe the problem in plain words and attach the shared log.
2. Ask it to find the cause, and show you the evidence, before it changes
   anything.
3. Ask it to change as little as possible and to keep the tests passing.
   For a parsing fix, ask for a new test with the site's current answer.
4. Read its changes before you install them.

Never paste passwords, Apple ID details or API tokens into the chat. The
TMDB token belongs in the repository secret only.

## Making your own copy

Fork the repository and add a `TMDB_TOKEN` secret (a free TMDB account
gives you one). The iOS workflow then builds your fork. To make it yours,
change `PRODUCT_BUNDLE_IDENTIFIER` and the display name in `project.yml`,
and the repository names in `Core/Net.swift` (`Config`) and
`UI/SettingsView.swift`.
