// A channel id -> a playable live playlist. Port of the parts of the TV app's
// data/live/LiveResolver.kt that work without running a page's JavaScript:
//
//   <site>/<route>/stream-<id>.php   (the wrapper; its <iframe> is at the end)
//     -> the embed page: window.atob('<b64>'), a literal .m3u8 (after
//        un-escaping "\/"), an XOR-encoded array, or one nested iframe (max 2).
//
// Routes are walked ONE AT A TIME: these pages share the site's trickle
// (~650 KB each, ~17 s per page on 2026-11-18) and volume gets the address
// refused (2026-09-26). So what a walk learns is kept: each channel's player
// page per route (the next start skips the 17 s page), the route that last
// played each channel (tried first), players that need their own script
// (straight to the hidden window, no page fetch), and servers that refuse us
// or are too slow to keep up (their routes go last).

import { HostGate } from "../net/hostGate";
import { hostOf, rfetch, type Headers } from "../net/relay";
import { wrapperBase } from "./site";
import { canCapture, captureStream } from "./capture";

export const ROUTES = ["player", "casting", "plus", "cast", "stream", "watch", "hub"] as const;

const PAGE_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
export const STREAM_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const IFRAME_RE = /<iframe[^>]+src="(https?:\/\/[^"]+)"/i;
const ANY_IFRAME_RE = /<iframe[^>]+src=["']([^"']+)["']/i;
const M3U8_RE = /https?:\/\/[a-z0-9.-]+\/[^\s"'`]*\.m3u8[^\s"'`]*/i;
const B64_RE = /window\.atob\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)/;
const XOR_RE = /=\[([0-9,\s]{40,})\],\s*_?\w+=(\d+),\s*_?\w+=(\d+)/;
// The token renewal some players do themselves (wideiptv: POST
// refresh_token.php {channel, current_token} 30 s before expiry).
const REFRESH_RE = /fetch\(\s*[`'"](https?:\/\/[^`'"]+refresh_token\.php)/;
const SLUG_RE = /channelSlug:\s*"([^"]+)"/;
const TOKEN_RE = /currentToken:\s*"([^"]+)"/;
const EXPIRES_RE = /tokenExpiresIn:\s*(\d+)/;

const REFUSE_MS = 30 * 60_000;
const SLOW_MS = 20 * 60_000;
const EMBED_KEEP_MS = 7 * 24 * 3600_000;
const BUDGET_MS = 60_000;

export type TokenRefresh = { endpoint: string; channel: string; token: string; expiresIn: number; origin: string };

export type LiveStream = {
  channelId: string;
  url: string;
  route: string;
  how: string;
  /** Sent with every playlist and chunk request for this stream. */
  headers: Headers;
  refresh?: TokenRefresh;
};

export class LiveError extends Error {}

// ---- what we have learned, kept on this computer ----

type Embed = { iframe: string; ref: string; at: number };
type Memory = {
  pathHost: Record<string, string>;
  refusing: Record<string, number>;
  leader?: string;
  /** "<channel>:<route>" -> the player page that route's wrapper links. */
  embeds: Record<string, Embed>;
  /** Player hosts whose page shows its stream only when its script runs. */
  scripted: Record<string, number>;
  /** Channel -> the route that last played it. */
  winner: Record<string, string>;
  /** Stream servers measured too slow to keep up, until when. */
  slow: Record<string, number>;
  /** Player hosts that showed no stream in the hidden window, until when. */
  capFail: Record<string, number>;
  /** Player hosts that last showed a stream in the hidden window, when. */
  capOk: Record<string, number>;
};

function load(): Memory {
  try {
    const m = JSON.parse(localStorage.getItem("live_routes") ?? "{}") as Partial<Memory>;
    const now = Date.now();
    const embeds = Object.fromEntries(Object.entries(m.embeds ?? {}).filter(([, e]) => now - e.at < EMBED_KEEP_MS));
    return {
      pathHost: m.pathHost ?? {},
      refusing: m.refusing ?? {},
      leader: m.leader,
      embeds,
      scripted: m.scripted ?? {},
      winner: m.winner ?? {},
      slow: m.slow ?? {},
      capFail: m.capFail ?? {},
      capOk: m.capOk ?? {},
    };
  } catch {
    return { pathHost: {}, refusing: {}, embeds: {}, scripted: {}, winner: {}, slow: {}, capFail: {}, capOk: {} };
  }
}
const mem = load();
function save() {
  try {
    localStorage.setItem("live_routes", JSON.stringify(mem));
  } catch {
    /* in memory only */
  }
}

export function isRefusing(host: string): boolean {
  const until = mem.refusing[host];
  return (!!until && until > Date.now()) || HostGate.heldForMs(host) > 0;
}

export function noteRefused(host: string) {
  mem.refusing[host] = Date.now() + REFUSE_MS;
  save();
}

export function isSlow(host: string): boolean {
  return (mem.slow[host] ?? 0) > Date.now();
}

/** [host] couldn't deliver its stream in real time: its routes go last for a while. */
export function noteSlow(host: string) {
  mem.slow[host] = Date.now() + SLOW_MS;
  save();
}

function embedKey(channelId: string, route: string) {
  return `${channelId}:${route}`;
}

function isScripted(channelId: string, route: string): boolean {
  const e = mem.embeds[embedKey(channelId, route)];
  return !!e && !!mem.scripted[hostOf(e.iframe)];
}

/** This channel's last winner first, then routes that give their stream
 *  without a script, then the rest; routes to refusing or slow servers last. */
function routeOrder(channelId: string): string[] {
  const all = [...ROUTES] as string[];
  const first = [mem.winner[channelId], mem.leader].filter((r): r is string => !!r && all.includes(r));
  const lead = [...new Set([...first, ...all])];
  const cost = (r: string) => {
    const host = mem.pathHost[r] ?? "";
    if (r === mem.winner[channelId] && !isRefusing(host) && !isSlow(host)) return -1;
    return (isRefusing(host) || isSlow(host) ? 2 : 0) + (isScripted(channelId, r) ? 1 : 0);
  };
  return lead.sort((a, b) => cost(a) - cost(b));
}

// ---- page walking ----

async function page(url: string, referer: string, signal?: AbortSignal): Promise<{ body: string; finalUrl: string } | null> {
  try {
    const r = await rfetch(url, { headers: { "User-Agent": PAGE_UA, Referer: referer }, timeoutMs: 30_000, signal, whole: true });
    if (!r.ok) {
      await r.body?.cancel().catch(() => undefined);
      return null;
    }
    return { body: await r.text(), finalUrl: r.finalUrl };
  } catch (e) {
    if (signal?.aborted) throw e;
    return null;
  }
}

function decodeB64(s: string): string | null {
  try {
    return atob(s);
  } catch {
    return null;
  }
}

/** cdnlivetv's player answers 401 without the account its own directory names. */
function withCdnLiveAccess(url: string): string {
  if (!hostOf(url).endsWith("cdnlivetv.tv") || !url.includes("/channels/player") || url.includes("user=")) return url;
  return url + (url.includes("?") ? "&" : "?") + "user=cdnlivetv&plan=free";
}

type Found = { url: string; how: string; pageUrl: string; body: string };

async function walkEmbed(
  pageUrl: string,
  referer: string,
  depth: number,
  signal?: AbortSignal,
  seen?: { unreachable?: boolean },
): Promise<Found | null> {
  const p = await page(pageUrl, referer, signal);
  if (!p) {
    if (seen && depth === 0) seen.unreachable = true;
    return null;
  }
  const body = p.body.replace(/\\\//g, "/");
  const b64 = B64_RE.exec(body)?.[1];
  if (b64) {
    const d = decodeB64(b64);
    if (d?.includes(".m3u8")) return { url: d, how: "atob", pageUrl, body };
  }
  const direct = M3U8_RE.exec(body)?.[0];
  if (direct) return { url: direct, how: "direct", pageUrl, body };
  const x = XOR_RE.exec(body);
  if (x) {
    const k = Number(x[2]);
    const s = Number(x[3]);
    const dec = x[1]
      .split(",")
      .map((n) => n.trim())
      .filter(Boolean)
      .map((n) => String.fromCharCode((((Number(n) ^ k) - s + 256) & 255)))
      .join("");
    const u = M3U8_RE.exec(dec)?.[0];
    if (u) return { url: u, how: "xor", pageUrl, body };
  }
  if (depth < 2) {
    const src = ANY_IFRAME_RE.exec(body)?.[1];
    if (src) {
      let nested: string | null = null;
      try {
        nested = new URL(src.trim(), pageUrl).toString();
      } catch {
        nested = null;
      }
      if (nested && !/(dlhd|dlive|thedaddy)\./.test(nested)) {
        const f = await walkEmbed(nested, pageUrl, depth + 1, signal);
        if (f) return { ...f, how: `nested/${f.how}` };
      }
    }
  }
  return null;
}

function headersFor(pageUrl: string): Headers {
  const origin = new URL(pageUrl).origin;
  return { "User-Agent": STREAM_UA, Referer: `${origin}/`, Origin: origin, Accept: "*/*", "Accept-Language": "en-US,en;q=0.9" };
}

/** The stream's playlist answers, and with a playlist. [log] gets what came
 *  back when it isn't one. */
async function check(
  url: string,
  headers: Headers,
  signal?: AbortSignal,
  log?: (m: string) => void,
): Promise<"ok" | "refused" | "bad"> {
  try {
    const r = await rfetch(url, { headers, timeoutMs: 15_000, signal, whole: true });
    const text = await r.text();
    if (r.ok && text.trimStart().startsWith("#EXTM3U")) return "ok";
    log?.(`  ${hostOf(url)} answered HTTP ${r.status}: ${text.slice(0, 80).replace(/\s+/g, " ")}`);
    return r.status === 401 || r.status === 403 ? "refused" : "bad";
  } catch (e) {
    if (signal?.aborted) throw e;
    log?.(`  ${hostOf(url)}: ${(e as Error)?.message ?? e}`);
    return "bad";
  }
}

/** A route whose player needs its own script: worth the hidden window only
 *  once every plain route has failed. */
type Deferred = { route: string; wrapper: string; iframe: string };

async function tryRoute(
  channelId: string,
  route: string,
  signal?: AbortSignal,
  log?: (m: string) => void,
): Promise<LiveStream | Deferred | null> {
  const base = await wrapperBase();
  const wrapper = `${base}/${route}/stream-${channelId}.php`;
  const key = embedKey(channelId, route);
  let found: Found | null = null;

  // The player page this route linked last time: no 17 s wrapper fetch.
  const known = mem.embeds[key];
  if (known) {
    if (mem.scripted[hostOf(known.iframe)]) {
      log?.(`${route}: ${hostOf(known.iframe)} needs its own script (known)`);
      return canCapture() ? { route, wrapper, iframe: withCdnLiveAccess(known.iframe) } : null;
    }
    found = await walkEmbed(withCdnLiveAccess(known.iframe), known.ref, 0, signal);
    if (found) log?.(`${route}: player page from memory`);
    else {
      // Moved or gone: ask the wrapper again.
      delete mem.embeds[key];
      save();
    }
  }

  if (!found) {
    const w = await page(wrapper, `${base}/watch.php?id=${channelId}`, signal);
    if (!w) {
      log?.(`${route}: wrapper didn't answer`);
      return null;
    }
    const iframe = IFRAME_RE.exec(w.body)?.[1];
    if (!iframe || /(dlhd|dlive|thedaddy)\./.test(iframe)) {
      log?.(`${route}: no player in the page`);
      return null;
    }
    mem.embeds[key] = { iframe, ref: w.finalUrl, at: Date.now() };
    save();
    const seen: { unreachable?: boolean } = {};
    found = await walkEmbed(withCdnLiveAccess(iframe), w.finalUrl, 0, signal, seen);
    if (!found) {
      if (seen.unreachable) {
        log?.(`${route}: ${hostOf(iframe)} didn't answer`);
        return null;
      }
      log?.(`${route}: ${hostOf(iframe)} needs its own script to find the stream`);
      mem.scripted[hostOf(iframe)] = Date.now();
      save();
      return canCapture() ? { route, wrapper, iframe: withCdnLiveAccess(iframe) } : null;
    }
  }
  const host = hostOf(found.url);
  mem.pathHost[route] = host;
  save();
  if (isRefusing(host)) {
    log?.(`${route}: leads to ${host}, which is refusing us`);
    return null;
  }
  const headers = headersFor(found.pageUrl);
  const verdict = await check(found.url, headers, signal, log);
  if (verdict === "refused") {
    noteRefused(host);
    log?.(`${route}: ${host} refused the playlist`);
    return null;
  }
  if (verdict === "bad") {
    log?.(`${route}: ${host} gave no playlist`);
    return null;
  }
  const endpoint = REFRESH_RE.exec(found.body)?.[1];
  const slug = SLUG_RE.exec(found.body)?.[1];
  const token = TOKEN_RE.exec(found.body)?.[1];
  const expires = Number(EXPIRES_RE.exec(found.body)?.[1] ?? 0);
  log?.(`${route}: ${host} via ${found.how}`);
  return {
    channelId,
    url: found.url,
    route,
    how: found.how,
    headers,
    refresh:
      endpoint && slug && token && expires > 0
        ? { endpoint, channel: slug, token, expiresIn: expires, origin: new URL(found.pageUrl).origin }
        : undefined,
  };
}

/** A player that only shows its stream when its own script runs: let it run
 *  in the hidden window and take the playlist it asks for. The site's page
 *  is opened (so the player sits where it expects, with the referrer it
 *  expects); cdnlivetv's player directly, since the page links it without
 *  the account it needs. */
async function captureRoute(
  channelId: string,
  route: string,
  wrapper: string,
  iframe: string,
  signal?: AbortSignal,
  log?: (m: string) => void,
): Promise<LiveStream | null> {
  const target = hostOf(iframe).endsWith("cdnlivetv.tv") ? iframe : wrapper;
  log?.(`${route}: running ${hostOf(iframe)}'s player in the hidden window`);
  // The site's page alone takes 10-21 s to load there (2026-11-18): the
  // route that played this channel last gets longer before giving up.
  const wait = route === mem.winner[channelId] ? 40_000 : 30_000;
  let cap;
  try {
    cap = await captureStream(target, wait, signal);
  } catch (e) {
    if (signal?.aborted) throw e;
    log?.(`${route}: hidden window: ${(e as Error)?.message ?? e}`);
    mem.capFail[hostOf(iframe)] = Date.now() + REFUSE_MS;
    save();
    return null;
  }
  if (signal?.aborted) return null;
  const host = hostOf(cap.url);
  mem.pathHost[route] = host;
  save();
  if (isRefusing(host)) {
    log?.(`${route}: its player leads to ${host}, which is refusing us`);
    return null;
  }
  // Repeat the request as the hidden browser made it: its user agent, the
  // asking page's origin as Origin and Referer.
  const headers: Headers = { ...headersFor(`${cap.origin}/`), ...(cap.ua ? { "User-Agent": cap.ua } : {}) };
  const verdict = await check(cap.url, headers, signal, log);
  if (verdict !== "ok") {
    if (verdict === "refused") noteRefused(host);
    log?.(`${route}: ${host} ${verdict === "refused" ? "refused the" : "gave no"} playlist seen in the hidden window (${cap.url.slice(0, 90)})`);
    return null;
  }
  log?.(`${route}: ${host} via the hidden window`);
  mem.capOk[hostOf(iframe)] = Date.now();
  delete mem.capFail[hostOf(iframe)];
  save();
  return { channelId, url: cap.url, route, how: "hidden window", headers };
}

/** Walk the routes until one gives a playlist that plays. [skip]: routes
 *  already tried for this channel (a failover asks for the next one). */
export async function resolveChannel(
  channelId: string,
  opts: { signal?: AbortSignal; skip?: string[]; log?: (m: string) => void; onRoute?: (route: string) => void } = {},
): Promise<LiveStream> {
  const started = Date.now();
  const deferred: Deferred[] = [];
  const slowOnes: string[] = [];
  const won = (s: LiveStream) => {
    mem.winner[channelId] = s.route;
    // The route tried first for channels never played here: only one that
    // works without the hidden window.
    if (s.how !== "hidden window") mem.leader = s.route;
    save();
    return s;
  };
  const DAY = 24 * 3600_000;
  const capCost = (d: Deferred) => {
    const host = hostOf(d.iframe);
    const now = Date.now();
    const workedLately = now - (mem.capOk[host] ?? 0) < DAY;
    const justFailed = (mem.capFail[host] ?? 0) > now;
    if (d.route === mem.winner[channelId] && (workedLately || !justFailed)) return 0;
    if (workedLately) return 1; // one miss doesn't bury a player that works
    return justFailed ? 3 : 2;
  };
  const captured = new Set<string>();
  // Plain fetches first: a route that gives its stream without running the
  // player costs two page loads (one, once its player page is known).
  for (const route of routeOrder(channelId)) {
    if (opts.skip?.includes(route)) continue;
    if (Date.now() - started > BUDGET_MS) break;
    const known = mem.pathHost[route];
    if (known && isRefusing(known)) {
      opts.log?.(`${route}: skipped, it leads to ${known}, which is refusing us`);
      continue;
    }
    if (known && isSlow(known)) {
      // Kept as a last resort: a slow picture beats none.
      slowOnes.push(route);
      continue;
    }
    opts.onRoute?.(route);
    const r = await tryRoute(channelId, route, opts.signal, opts.log);
    if (r && "url" in r) return won(r);
    if (r && route === mem.winner[channelId] && capCost(r) <= 1) {
      // The hidden window played this channel last time: straight there.
      captured.add(r.route);
      const s = await captureRoute(channelId, r.route, r.wrapper, r.iframe, opts.signal, opts.log);
      if (s) return won(s);
      continue;
    }
    if (r) deferred.push(r);
  }
  // Then the players that need their own script, one at a time in the
  // hidden window (each costs up to 30-40 s): the one that played this
  // channel last, then players that have shown a stream lately, then ones
  // not tried; routes whose server was too slow; and last the players that
  // just showed nothing and haven't worked lately.
  deferred.sort((a, b) => capCost(a) - capCost(b));
  const capStarted = Date.now();
  const tryCaptures = async (list: Deferred[]) => {
    for (const d of list) {
      if (Date.now() - capStarted > 2 * BUDGET_MS) break;
      if (captured.has(d.route)) continue;
      captured.add(d.route);
      opts.onRoute?.(d.route);
      const s = await captureRoute(channelId, d.route, d.wrapper, d.iframe, opts.signal, opts.log);
      if (s) return won(s);
    }
    return null;
  };
  const good = await tryCaptures(deferred.filter((d) => capCost(d) <= 2));
  if (good) return good;
  for (const route of slowOnes) {
    opts.log?.(`${route}: its server was slow earlier; trying it anyway`);
    opts.onRoute?.(route);
    const r = await tryRoute(channelId, route, opts.signal, opts.log);
    if (r && "url" in r) return won(r);
    if (r && !deferred.some((d) => d.route === r.route)) deferred.push(r);
  }
  const rest = await tryCaptures(deferred.filter((d) => capCost(d) > 2));
  if (rest) return rest;
  throw new LiveError("No route to this channel answers right now");
}

/** A new token for a stream whose player renews its own (see REFRESH_RE). */
export async function renewToken(r: TokenRefresh, signal?: AbortSignal): Promise<{ token: string; expiresIn: number } | null> {
  try {
    const res = await rfetch(r.endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json", "User-Agent": STREAM_UA, Origin: r.origin, Referer: `${r.origin}/` },
      body: JSON.stringify({ channel: r.channel, current_token: r.token }),
      timeoutMs: 15_000,
      signal,
    });
    const data = (await res.json()) as { success?: boolean; token?: string; expires_in?: number };
    return data.success && data.token ? { token: data.token, expiresIn: Number(data.expires_in) || 300 } : null;
  } catch {
    return null;
  }
}
