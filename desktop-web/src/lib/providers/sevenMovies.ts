// 7Movies (7movies.ac): TMDB-keyed HLS from several servers through the
// vidrift embed's own API. Port of the TV app's net/SevenMovies.kt; keep the
// two in step (same server order, same dub rules).
//
//   GET /api/boot/{movie/<tmdb> | tv/<tmdb>/<s>/<e>}  -> meta.playbackToken
//       (signed, per episode, ~12 h), meta.evionUrl (partner playlist, often
//       ""). A title it has blocked answers 451.
//   GET /api/source/<same>?token=..&provider=<server>&hevc=0|1 -> streams[]
//
// Servers: Vela (1080/720/480 H.264, separate playlists, no master), Titan
// (720p TS; films multi-audio), Orion (adaptive HEVC, a copy per language),
// Lyra (often 502), Evion (partner playlist from boot).

import { HttpError, rfetch } from "../net/relay";
import { probe } from "./probe";
import { canPlayHevc, type PlayInfo, type Quality, type ResolveOpts, type Source, type Target } from "./types";

const BASE = "https://embed.vidrift.net";
const STREAM_HEADERS = { Referer: `${BASE}/`, Origin: BASE };
const VELA_HEAD_START_MS = 3500;
const ORION_LIST_WAIT_MS = 2500;
const BUDGET_MS = 22_000;
const TOKEN_KEEP_MS = 60 * 60_000;
const DEFAULT_QUALITY = 1080; // a desktop screen: start Vela at 1080p

/** Who the API calls say they are, tried in turn. The site's firewall turns
 *  away requests that claim to be a browser without being one (2026-11-14),
 *  while a plain HTTP client's name gets through; the one that worked is kept. */
const IDENTITIES = [
  "okhttp/4.12.0",
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
];
let identity = 0;

type Stream = { url?: string; proxyUrl?: string; name?: string };
type SourceAnswer = { success?: boolean; maxRes?: number; streams?: Stream[] };
type Boot = { meta?: { playbackToken?: string; evionUrl?: string } };

const tokens = new Map<string, { meta: NonNullable<Boot["meta"]>; at: number }>();
/** The server that played a series' last episode: servers number some
 *  seasons differently, so a run of episodes stays on one server. */
const lastServer = new Map<string, string>();
/** Titles seen with dubs: Vela names no language, so it can't be trusted for them. */
const dubbedTitles = new Set<string>();

function pathOf(t: Target) {
  return t.kind === "tv" ? `tv/${t.tmdbId}/${t.season ?? 1}/${t.episode ?? 1}` : `movie/${t.tmdbId}`;
}
const titleKeyOf = (path: string) => path.split("/").slice(0, 2).join("/");

async function fetchJson<T>(url: string, signal?: AbortSignal, timeoutMs = 12_000): Promise<T | null> {
  for (let step = 0; step < IDENTITIES.length; step++) {
    const i = (identity + step) % IDENTITIES.length;
    try {
      const r = await rfetch(url, { headers: { "User-Agent": IDENTITIES[i], Accept: "application/json" }, timeoutMs, signal });
      const body = await r.text();
      // The firewall's block page: try the next identity.
      if (r.status === 403 && !body.trimStart().startsWith("{")) continue;
      if (i !== identity) identity = i;
      if (!r.ok) return null;
      return JSON.parse(body) as T;
    } catch (e) {
      if (signal?.aborted) throw e;
      if (e instanceof HttpError) return null;
      return null;
    }
  }
  return null;
}

/** A named language other than the original or English, or several in one
 *  stream ("Multi-audio": one TS with a track per language; the player hears
 *  only the first, often a dub). Held back; still plays when nothing else does. */
function isDub(name: string): boolean {
  const n = name.toLowerCase();
  if (!n.trim()) return false;
  if (n.includes("multi")) return true;
  return !(n.includes("original") || n.includes("english"));
}

function dubNames(a: SourceAnswer | null): string[] {
  const out = (a?.streams ?? [])
    .map((s) => s.name ?? "")
    .filter(isDub)
    .map((n) => (n.includes("·") ? n.split("·").pop()!.trim() : n.trim()))
    .filter(Boolean);
  return [...new Set(out)];
}

function entries(a: SourceAnswer): { url: string; name: string }[] {
  return (a.streams ?? [])
    .map((s) => {
      const raw = s.url || s.proxyUrl || "";
      return raw ? { url: raw.startsWith("/") ? BASE + raw : raw, name: s.name ?? "" } : null;
    })
    .filter((e): e is { url: string; name: string } => !!e);
}

function info(url: string, selected: string, qualities: Quality[], language: string, dubs: string[]): PlayInfo {
  return { provider: "7Movies", url, headers: STREAM_HEADERS, hls: true, selected, qualities, captions: [], language, dubs };
}

export const SevenMovies: Source = {
  name: "7Movies",

  async resolve(t: Target, opts: ResolveOpts): Promise<PlayInfo | null> {
    const log = opts.log ?? (() => undefined);
    const signal = opts.signal;
    const hevc = canPlayHevc();
    const path = pathOf(t);
    const titleKey = titleKeyOf(path);
    const started = Date.now();
    const spent = () => Date.now() - started;

    let meta = tokens.get(path);
    if (!meta || Date.now() - meta.at > TOKEN_KEEP_MS) {
      const boot = await fetchJson<Boot>(`${BASE}/api/boot/${path}`, signal);
      if (!boot?.meta?.playbackToken) {
        log(`7Movies ${path}: no playback token`);
        return null;
      }
      meta = { meta: boot.meta, at: Date.now() };
      tokens.set(path, meta);
    }
    const token = meta.meta.playbackToken!;
    const sourceUrl = (server: string) =>
      `${BASE}/api/source/${path}?token=${encodeURIComponent(token)}&provider=${server}&hevc=${hevc ? 1 : 0}`;
    const ask = (server: string) => fetchJson<SourceAnswer>(sourceUrl(server), signal);

    let dubbed: PlayInfo | null = null;
    let knownDubs: string[] = [];

    // A language the viewer asked for: Orion's copy in it, when this computer plays HEVC.
    if (opts.dub && isDub(opts.dub) && hevc) {
      const list = await ask("orion");
      knownDubs = dubNames(list);
      const match = (list?.streams ?? []).find(
        (s) => (s.name ?? "").split("·").pop()!.trim().toLowerCase() === opts.dub!.toLowerCase(),
      );
      const raw = match ? match.url || match.proxyUrl : undefined;
      if (raw) {
        const url = raw.startsWith("/") ? BASE + raw : raw;
        if ((await probe(url, STREAM_HEADERS, signal)) == null) {
          log(`7Movies ${path} via orion in ${opts.dub}, as asked`);
          return info(url, "Auto", [{ label: "Auto", url }], opts.dub, knownDubs);
        }
      }
      log(`7Movies ${path}: no ${opts.dub} copy that plays, the original instead`);
    }

    const consider = async (server: string, data: SourceAnswer | null): Promise<PlayInfo | null> => {
      if (!data?.success) return null;
      const es = entries(data);
      if (!es.length) return null;
      let found: PlayInfo | null = null;
      if (server === "vela") {
        const ladder = [2160, 1080, 720, 480, 360, 240].filter((h) => h <= (data.maxRes || 1080));
        const qualities = es.slice(0, ladder.length).map((e, i) => ({ label: `${ladder[i]}p`, url: e.url }));
        const height = (q: Quality) => Number(q.label.replace(/\D/g, "")) || 0;
        const first =
          qualities.filter((q) => height(q) <= DEFAULT_QUALITY).sort((a, b) => height(b) - height(a))[0] ??
          qualities[qualities.length - 1];
        const order = [first, ...qualities.filter((q) => q !== first).sort((a, b) => Math.abs(height(a) - height(first)) - Math.abs(height(b) - height(first)))];
        for (const q of order) {
          if ((await probe(q.url, STREAM_HEADERS, signal)) == null) {
            found = info(q.url, q.label, qualities, "Original", knownDubs);
            break;
          }
          log(`7Movies vela ${q.label}: the stream refused us`);
        }
      } else {
        const ordered = [...es].sort((a, b) => Number(isDub(a.name)) - Number(isDub(b.name)));
        for (const e of ordered) {
          if ((await probe(e.url, STREAM_HEADERS, signal)) == null) {
            const language = e.name.includes("·") ? e.name.split("·").pop()!.trim() : e.name.trim() || "Original";
            found = info(e.url, "Auto", [{ label: "Auto", url: e.url }], language || "Original", knownDubs);
            break;
          }
          log(`7Movies ${server} ${e.name}: the stream refused us`);
        }
      }
      if (!found) return null;
      if (isDub(found.language)) {
        dubbed ??= found;
        return null;
      }
      log(`7Movies ${path} via ${server} (${found.selected}) in ${spent()} ms`);
      lastServer.set(titleKey, server);
      return found;
    };

    // The server this series has been playing from goes first.
    const last = lastServer.get(titleKey);
    if (last && last !== "vela" && (last !== "orion" || hevc)) {
      const r = await consider(last, await ask(last));
      if (r) return r;
    }

    const vela = ask("vela");
    const titan = ask("titan");
    const orion = ask("orion");
    const within = <T,>(p: Promise<T>, ms: number) =>
      Promise.race([p, new Promise<undefined>((res) => setTimeout(() => res(undefined), ms))]);
    const velaEarly = await within(vela, VELA_HEAD_START_MS);
    const orionList = (await within(orion, ORION_LIST_WAIT_MS)) ?? null;
    const dubs = dubNames(orionList);
    if (dubs.length) {
      knownDubs = dubs;
      dubbedTitles.add(titleKey);
      log(`7Movies ${path}: dubs exist (${dubs.join(", ")}), preferring a copy labelled original`);
      if (hevc) {
        const r = await consider("orion", orionList);
        if (r) return r;
      }
    }
    // Orion's original doesn't play here (or there are no dubs): Vela.
    if (velaEarly !== undefined) {
      const r = await consider("vela", velaEarly);
      if (r) return r;
    }
    if (lastServer.get(titleKey) === "vela" && velaEarly === undefined) {
      const r = (await consider("vela", await vela)) ?? (await consider("vela", await ask("vela")));
      if (r) return r;
    }
    if (!dubs.length) {
      const r = await consider("titan", await titan);
      if (r) return r;
    }
    if (velaEarly === undefined) {
      const r = await consider("vela", await vela);
      if (r) return r;
    }
    for (const server of [hevc ? "orion" : null, "lyra"].filter(Boolean) as string[]) {
      if (spent() > BUDGET_MS) break;
      const r = await consider(server, server === "orion" && orionList ? orionList : await ask(server));
      if (r) return r;
    }
    const evion = meta.meta.evionUrl;
    if (evion && spent() <= BUDGET_MS && (await probe(evion, STREAM_HEADERS, signal)) == null) {
      log(`7Movies ${path} via evion in ${spent()} ms`);
      return info(evion, "Auto", [{ label: "Auto", url: evion }], "Original", knownDubs);
    }
    if (dubbed) log(`7Movies ${path}: only a dub (${(dubbed as PlayInfo).language})`);
    return dubbed;
  },
};
