# Vijana BaruBaru — desktop v2 (Next.js + Tauri)

User asked for a pivot from Compose Desktop to a TS-first stack
("the UI is awful... we will use nextjs and maybe tauri this will
make our work simple and even testing simple cause backend can be ts
with ui on tsx and we support both windows and mac clean ui supporting
tv and movies and series clean UI layouts"). This doc captures the
chosen architecture so tomorrow we just build features.

## Why this stack

- **Next.js (App Router, static export)** — TSX components, fast
  iteration, ecosystem of UI libraries (shadcn/ui, framer-motion, etc.)
  for the "clean UI" the user wants. Static export so it runs inside
  Tauri's WebView with no Node runtime.
- **Tauri 2.x** — Rust shell, ~5 MB final binary (Electron is ~100 MB).
  Native Windows + Mac + Linux builds from one codebase. Built-in
  installer (.msi/.dmg) via `cargo tauri build`.
- **TypeScript everywhere** — H5 client logic in `src/lib/h5/`,
  components in `src/components/`. Easy to unit-test with vitest.

## Layers

```
desktop-web/
  src/
    app/                      ← Next.js App Router pages
      layout.tsx              ← root layout, theme, fonts
      page.tsx                ← Home (catalog rows)
      title/[id]/page.tsx     ← Detail page
      watch/[id]/page.tsx     ← Player page (videojs / hls.js)
      live/page.tsx           ← Live TV grid
      search/page.tsx         ← Search
      globals.css             ← Tailwind base
    lib/
      h5/
        crypto.ts             ← HMAC-MD5 signing (port of shared/Crypto.kt)
        constants.ts          ← H5_BASE, signing key, X-Client-Info
        api.ts                ← search(), home(), detail(), play()
        types.ts              ← H5Item, H5Row, H5Detail, H5Play
      tauri/
        commands.ts           ← invoke() wrappers for Rust commands
    components/
      PosterCard.tsx
      RowSection.tsx
      AppShell.tsx            ← left-rail nav (Home / Live / Search / Library)
      Player.tsx              ← hls.js or videojs wrapped
  src-tauri/                  ← Tauri Rust shell
    Cargo.toml
    tauri.conf.json
    src/
      main.rs                 ← entry point
      lib.rs                  ← command handlers
    icons/                    ← Mac .icns + Win .ico (reuse Android source)
  package.json
  next.config.js              ← output: 'export'
  tailwind.config.ts          ← dark MovieWay palette
  tsconfig.json
  PLANNING.md                 ← this doc
```

## H5 client architecture decision (revisit tomorrow)

Two options for where the HmacMD5 signing + HTTP calls live:

### Option A — pure TypeScript in the WebView
- Use `crypto-js` for HMAC-MD5 (Web Crypto doesn't support MD5)
- `fetch()` directly to `h5-api.aoneroom.com`
- Pros: single language; easy to share with the GitHub Pages site
- Cons: signing key is in the bundle (already public in the Android app, so not a real secret), CORS on aoneroom (the H5 origin sets `Access-Control-Allow-Origin: *` for themoviebox.org — Tauri WebView may need a custom origin header to pass CORS)

### Option B — Tauri commands in Rust
- Frontend invokes `invoke('h5_home')`, Rust signs + fetches via `reqwest`, returns JSON
- Pros: mature crypto + HTTP libs; signing key in compiled Rust (slightly harder to extract); bypasses CORS entirely
- Cons: two languages; more boilerplate

**Recommendation for tomorrow**: **start with Option A** (pure TS).
Drop to Rust later only if CORS or perf becomes an issue. Faster
iteration is more valuable than a hypothetical security gain.

## What does NOT change

- `app/` — Android Kotlin app stays (it's working v0.1.108)
- `shared/` — Kotlin shared module stays (Android still uses it)
- `desktop/` — Compose Desktop stays IN-TREE for now but is
  deprecated. Once `desktop-web` ships and the user signs off,
  delete `desktop/` and remove the `:desktop` include in
  `settings.gradle.kts`.

## What we'll build first (tomorrow)

Order matters — each builds on the prior:

1. **Scaffold runs**: `bun run dev` opens Next.js in the browser at
   localhost:3000 with the MovieWay dark theme + a placeholder Home.
2. **H5 client port** (`src/lib/h5/`): `clientToken`, `trSignature`,
   `home()`, `search()`, `detail()`, `play()`. Verified by a unit
   test against the live H5 endpoint.
3. **Home page** (`src/app/page.tsx`): real rows + posters via
   AsyncImage equivalent (`<Image>` from next/image or a `<img>` with
   the H5 cover URL).
4. **AppShell**: left rail with Home / Live TV / Search / Library
   icons. Same dark gradient + brand-green accent as Android.
5. **Detail page** (`src/app/title/[id]/page.tsx`): hero, summary,
   season/episode picker for series, Play CTA.
6. **Player** (`src/app/watch/[id]/page.tsx`): hls.js for HLS/MP4.
   Plays movies from the H5 play endpoint.
7. **Tauri shell**: `cargo tauri dev` opens the same UI in a native
   window. `cargo tauri build` produces the .msi + .dmg.
8. **GitHub Actions**: extend the release.yml's `msi` job to use
   `cargo tauri build` instead of the Compose `packageMsi`. Add a
   `dmg` job on macOS runner once Mac is in scope.

## Live TV (Phase 2)

Same approach as Android: a local HLS resolver that proxies the
`dlhd.pk` → `daddy.php` token mint. On desktop, this can live in
the Tauri Rust backend (reqwest + tokio for the per-segment refresh)
so the frontend just plays a `localhost:port/master/<id>` URL through
hls.js. Same shape as the Android NanoHTTPD `LiveStreamProxy`,
re-implemented in Rust for the desktop runtime.

Defer until after Phase 1 (movies/series) is solid.

## What's on disk now (tonight)

Scaffold-only — no `bun install` yet, no `cargo build` yet, no
running app. All ready for tomorrow's `bun install && bun run dev`.

```
desktop-web/
  package.json                  ← deps pinned (next 15, react 19, tauri 2.1, tailwind 3.4)
  tsconfig.json
  next.config.js                ← output: 'export' for Tauri WebView
  tailwind.config.ts            ← MovieWay-dark palette
  postcss.config.js
  .gitignore
  PLANNING.md                   ← this doc
  src/
    app/
      globals.css               ← Tailwind base + CSS vars
      layout.tsx                ← root layout, dark theme
      page.tsx                  ← Home (placeholder; loads H5 home rows)
    lib/h5/
      constants.ts              ← H5_BASE, signing key, X-Client-Info
      crypto.ts                 ← clientToken + trSignature (crypto-js)
      types.ts                  ← H5Item, H5Row, H5Detail, H5Play
      api.ts                    ← home(), search(), detail(), play()
    components/                 ← (empty — to fill tomorrow)
  src-tauri/
    Cargo.toml                  ← tauri 2.1.1, tauri-plugin-opener
    tauri.conf.json             ← productName, identifier, msi+dmg targets
    build.rs
    src/
      main.rs
      lib.rs
    capabilities/default.json   ← min permissions
    icons/
      32x32.png  128x128.png  128x128@2x.png  icon.ico  icon.icns
```

## Tomorrow's first commands

```bash
cd desktop-web
bun install                    # one-time; pulls Next.js + Tauri deps
bun run dev                    # opens http://localhost:3000
# (in another terminal)
bun run tauri:dev              # opens the Tauri native window pointing at localhost:3000
```

If `bun run dev` shows the dark Home with real H5 rows, the TS H5
client port is verified end-to-end. Then we build components.

## Out-of-scope for desktop v2 (intentional)

- Downloads (Android does this with WorkManager + the Storage Access
  Framework; on desktop you'd just write to ~/Downloads/. Add when
  asked.)
- Casting to a TV from the desktop app (Chromecast / DLNA). Big
  surface; defer.
- Remote-control SPA (already exists at `app/src/main/assets/`; the
  Android app serves it via NanoHTTPD. Desktop doesn't need it
  because the desktop IS the UI.)
