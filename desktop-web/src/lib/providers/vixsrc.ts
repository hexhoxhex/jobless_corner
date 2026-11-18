// VixSrc: TMDB-keyed adaptive HLS (1080/720/480, H.264) with audio and
// subtitle renditions. Port of the TV app's net/VixSrc.kt.
//
// Chain: /api/{movie/<tmdb> | tv/<tmdb>/<s>/<e>} -> {src} embed page ->
// token / expires / url in the page -> master playlist. Tokens are per page
// load, so the whole chain runs in one pass.

import { rfetch } from "../net/relay";
import type { Caption, PlayInfo, ResolveOpts, Source, Target } from "./types";

const BASE = "https://vixsrc.to";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const TOKEN_RE = /token["']\s*:\s*["']([^"']+)/;
const EXPIRES_RE = /expires["']\s*:\s*["']([^"']+)/;
const PLAYLIST_RE = /url\s*:\s*["']([^"']+)/;
const STREAM_INF_RE = /#EXT-X-STREAM-INF:[^\n]*RESOLUTION=(\d+)x(\d+)[^\n]*\n([^\n]+)/g;
const SUBS_RE = /#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*/g;

async function get(url: string, referer: string, signal?: AbortSignal): Promise<string | null> {
  try {
    const r = await rfetch(url, { headers: { "User-Agent": UA, Referer: referer }, timeoutMs: 15_000, signal });
    return r.ok ? await r.text() : null;
  } catch (e) {
    if (signal?.aborted) throw e;
    return null;
  }
}

export const VixSrc: Source = {
  name: "VixSrc",

  async resolve(t: Target, opts: ResolveOpts): Promise<PlayInfo | null> {
    const { signal } = opts;
    const api = t.kind === "tv" ? `${BASE}/api/tv/${t.tmdbId}/${t.season ?? 1}/${t.episode ?? 1}` : `${BASE}/api/movie/${t.tmdbId}`;
    const raw = await get(api, `${BASE}/`, signal);
    if (!raw) return null;
    let src = "";
    try {
      src = (JSON.parse(raw) as { src?: string }).src ?? "";
    } catch {
      return null;
    }
    if (!src) return null;
    const embed = src.startsWith("http") ? src : BASE + src;
    const html = await get(embed, `${BASE}/`, signal);
    if (!html) return null;
    const token = TOKEN_RE.exec(html)?.[1];
    const expires = EXPIRES_RE.exec(html)?.[1];
    const playlist = PLAYLIST_RE.exec(html)?.[1];
    if (!token || !expires || !playlist) return null;
    if (Number(expires) * 1000 < Date.now()) return null;
    const master = `${playlist}${playlist.includes("?") ? "&" : "?"}token=${token}&expires=${expires}&h=1`;
    const headers = { "User-Agent": UA, Referer: embed };
    const manifest = await get(master, embed, signal);
    if (!manifest?.startsWith("#EXTM3U")) return null;

    const ladder = [...manifest.matchAll(STREAM_INF_RE)]
      .map((m) => ({ label: `${m[2]}p`, url: new URL(m[3].trim(), master).toString(), h: Number(m[2]) }))
      .sort((a, b) => b.h - a.h)
      .map(({ label, url }) => ({ label, url }));
    const captions: Caption[] = [...manifest.matchAll(SUBS_RE)]
      .map((m) => {
        const line = m[0];
        const lang = /LANGUAGE="([^"]+)"/.exec(line)?.[1] ?? "";
        const uri = /URI="([^"]+)"/.exec(line)?.[1] ?? "";
        const label = /NAME="([^"]+)"/.exec(line)?.[1] ?? lang;
        return uri ? { lang, label, url: new URL(uri, master).toString() } : null;
      })
      .filter((c): c is Caption => !!c);

    opts.log?.(`VixSrc resolved ${t.kind}/${t.tmdbId}: ${ladder.length} renditions, ${captions.length} subtitles`);
    // The master playlist, so the player adapts; renditions are manual picks.
    return {
      provider: "VixSrc",
      url: master,
      headers,
      hls: true,
      selected: "Auto",
      qualities: [{ label: "Auto", url: master }, ...ladder],
      captions,
      language: "Original",
      dubs: [],
    };
  },
};
