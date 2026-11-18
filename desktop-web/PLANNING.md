# Vijana BaruBaru desktop (Tauri 2 + Next.js)

Replaces the Compose Desktop app in `desktop/` (MovieBox-only, needs VLC
installed, 98 MB MSI). One TypeScript code base for Windows, macOS and Linux.
Decided 2026-11-18: full rewrite in TypeScript, not a wrapper around the TV
app's Kotlin. Source and live-TV fixes are therefore made in two places; keep
the TS modules shaped like their Kotlin counterparts (same names, same order
of attempts) so a fix ports file-to-file.

## Architecture

```
desktop-web/
  src/app/            pages (static export: no dynamic segments, ids go in ?query)
  src/components/     UI
  src/lib/net/        relay client, HostGate (Retry-After), timeouts
  src/lib/tmdb/       catalogue: home rows, browse, search, details, seasons
  src/lib/providers/  7Movies (vidrift), VixSrc, VidNest, MovieBox, 4KHDHub
  src/lib/player/     hls.js setup, custom loaders, prefetch, subtitles, skip
  src/lib/live/       channels, schedule, resolver, playlist rewrite, unseal,
                      disguised-chunk decode, failover
  src/lib/store/      continue watching, list, settings (localStorage)
  src-tauri/          Rust shell: window, relay, single instance, window state
  tools/devrelay.mjs  the relay for `next dev` in a normal browser
```

**Backend and UI.** The app is two parts: the Rust backend (the relay and
the hidden player window) and the web UI. They talk only over HTTP on
127.0.0.1, so the UI can be built and tested in an ordinary browser against
the backend running on its own, without opening the app:

    bun run engine     # the backend alone: relay + hidden window on :8797, secret "dev"
    bun run dev        # the UI on http://localhost:3010

`--engine` exists in debug builds only. In the app the backend picks a random
port and secret and hands them to the UI through the `relay_info` command.
(`bun run relay` is a Node stand-in for the relay alone, without the hidden
window, for machines without Rust.)

**The relay.** Browsers can't set Referer/Origin/User-Agent and are bound by
CORS; the stream sources need all three, and a `<video>` can't send headers
at all. So every source request goes through the backend:

`GET|POST http://127.0.0.1:<port>/r?s=<secret>&u=<url>&h=<base64url JSON headers>`

It forwards the request (and the player's Range and Accept-Encoding),
streams the answer back untouched with CORS headers, and reports the final
URL in `x-final-url`. hls.js keeps the original URLs (relative chunk paths
resolve correctly) and only the request goes through the relay (`xhrSetup`
opens the relayed URL; `RelayLoader` reports the real URL back).

**The hidden player window.** `GET /capture?s=<secret>&u=<page>&t=<ms>` opens
the page in a hidden, muted window (no pop-ups, no leaving the site), and
answers with the first playlist its player requests, the page that asked
and the browser's user agent. It is used only for players that need their
own script, after every plain route has failed, and never on a page that
shows a bot check.

TMDB and its images allow CORS and go direct.

## Phases

1. **Foundation** - scaffold, relay (Rust + dev), app shell and design system,
   window behaviour (remembered size/position, fullscreen, keyboard), installers
   for all three systems.
2. **Catalogue** - home (hero + rows), Movies, Series, search, detail page with
   seasons and episodes.
3. **Watching** - 7Movies, VixSrc, VidNest with failover; hls.js player with own
   controls (quality, audio language, subtitles, skip intro, next episode);
   continue watching with resume; parallel prefetch for cold CDNs.
4. **Live TV** - channels and schedule (published file + the site's page),
   resolver for the working servers, playlist token, unsealing, disguised
   chunks, polite failure (Retry-After), feed failover.
5. **Ship** - MovieBox and 4KHDHub sources, OpenSubtitles, update notice,
   release workflow builds `.exe/.msi`, `.dmg`, `.AppImage/.deb`; then remove
   `desktop/` and the `:desktop` Gradle module.

## Rules carried over from the TV app

- No emoji anywhere in the UI; icons are vectors (lucide).
- A failed fetch is not "there is nothing" (never cache an empty list from an
  error).
- Obey Retry-After; never retry in a tight loop; pace bursts.
- Never switch what is playing without the viewer asking.
- Never bypass bot checks (Cloudflare Turnstile etc.).
- The TMDB token comes from the environment at build time (`TMDB_TOKEN`; in
  development read from `../local.properties`), never from the repo.

## Codecs per system

- Windows: WebView2 (Chromium) - H.264/AAC via MSE everywhere; HEVC only with
  the system HEVC extension. Prefer H.264 renditions.
- macOS: WKWebView - native HLS plus MSE; HEVC supported.
- Linux: WebKitGTK - needs GStreamer `gst-plugins-good/bad` + `gst-libav`
  (the .deb declares them).
