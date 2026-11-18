// Live channels and the match schedule. Port of the TV app's
// data/live/LiveTvRepository.kt + SiteSchedule.kt + LiveModels.kt.
//
// The published files (hexhoxhex/mkurugenzi_viewer) are the base; GitHub runs
// their "every 30 min" refresh every few hours, so the site's own home page is
// read too and its events lead (the TV does the same since v0.1.318).

import { rtext } from "../net/relay";
import { wrapperBase } from "./site";

const DATA = "https://raw.githubusercontent.com/hexhoxhex/mkurugenzi_viewer/main/data";

export type Channel = {
  id: string;
  name: string;
  group: string;
  logo?: string;
};

export type ScheduleChannel = { id: string; name: string };

export type LiveEvent = {
  category: string;
  title: string;
  /** UTC seconds; null when only an "HH:MM" was known. */
  start: number | null;
  time: string;
  channels: ScheduleChannel[];
};

const ACRONYMS = new Set(
  ("uk usa us tv nz au hd uhd tnt espn espnu nbc abc cbs hbo bbc itv dazn tsn mena bt ufc nfl nba mlb nhl f1 cnn " +
    "rtl rai tvp zdf ard sbs cbc nhk uae ksa pga wwe mls fs1 fs2 ppv msg acc sec").split(" "),
);
const BRAND_CASE: Record<string, string> = {
  bein: "beIN",
  supersport: "SuperSport",
  dstv: "DStv",
  motogp: "MotoGP",
  laliga: "LaLiga",
  espnews: "ESPNews",
};
/** Characters the source mangled ("espa単ol" for "español"). */
const FIXUPS: [RegExp, string][] = [[/単/g, "ñ"]];

/** "tnt sports 1 uk" -> "TNT Sports 1 UK". */
export function displayName(name: string): string {
  for (const [re, to] of FIXUPS) name = name.replace(re, to);
  return name
    .split(" ")
    .map((w) => {
      const l = w.toLowerCase();
      return BRAND_CASE[l] ?? (ACRONYMS.has(l) ? w.toUpperCase() : w.charAt(0).toUpperCase() + w.slice(1));
    })
    .join(" ");
}

/** The page is double-encoded ("&amp;amp;"): undo until it stops changing. */
export function unescapeHtml(s: string): string {
  let cur = s;
  for (let i = 0; i < 3; i++) {
    const next = cur.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (m, name: string) => {
      const n = name.toLowerCase();
      if (n === "amp") return "&";
      if (n === "lt") return "<";
      if (n === "gt") return ">";
      if (n === "quot") return '"';
      if (n === "apos") return "'";
      const code = n.startsWith("#x") ? parseInt(n.slice(2), 16) : parseInt(n.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : m;
    });
    if (next === cur) break;
    cur = next;
  }
  return cur;
}

type RawChannel = { id: string; name: string; status?: string | null; stream_url?: string | null; logo?: string | null; group?: string | null };

export async function loadChannels(): Promise<Channel[]> {
  const res = await fetch(`${DATA}/channels.json`, { cache: "no-store" });
  if (!res.ok) throw new Error(`channels ${res.status}`);
  const raw = (await res.json()) as RawChannel[];
  // A failed fetch throws above: an empty list here would read as "no channels".
  return raw
    .filter((c) => c.status === "ok" && c.stream_url)
    .map((c) => ({
      id: String(c.id),
      name: displayName(unescapeHtml(c.name)),
      group: (c.group ?? "Other").replace(/\s*\(DADDY LIVE\)\s*/i, "").trim() || "Other",
      logo: c.logo || undefined,
    }));
}

type RawEvent = {
  category: string;
  time: string;
  title: string;
  channels: { id: string; name: string }[];
  start_unix?: number | null;
};

export async function publishedSchedule(): Promise<LiveEvent[]> {
  const res = await fetch(`${DATA}/schedule.json`, { cache: "no-store" });
  if (!res.ok) throw new Error(`schedule ${res.status}`);
  const raw = (await res.json()) as RawEvent[];
  return raw.map((e) => ({
    category: unescapeHtml(e.category),
    title: unescapeHtml(e.title),
    time: e.time,
    start: e.start_unix ?? null,
    channels: e.channels.map((c) => ({ id: String(c.id), name: displayName(unescapeHtml(c.name)) })),
  }));
}

// ---- The site's home page (port of SiteSchedule.kt) ----

const CAT_RE = /<div class="schedule__catHeader"[^>]*>[\s\S]*?<div class="card__meta">([^<]+)<\/div>/g;
const EVENT_RE =
  /<div class="schedule__event">\s*<div class="schedule__eventHeader"[^>]*data-title="([^"]*)"[^>]*>[\s\S]*?data-time="([^"]*)"[^>]*>([^<]+)<\/span>\s*<span class="schedule__eventTitle">([^<]+)<\/span>[\s\S]*?<div class="schedule__channels">([\s\S]*?)<\/div>/g;
const CHAN_RE = /<a[^>]+href="\/watch\.php\?id=(\d+)"[^>]*title="([^"]*)"/g;
const DAY_RE = /<div class="schedule__dayTitle">([^<]+)<\/div>/g;
const DAY_DATE_RE = /(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]+)\s+(\d{4})/;
const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
const STALE_AFTER_S = 3 * 3600;

function hhmm(t: string): [number, number] | null {
  const m = /^\s*(\d{1,2}):(\d{2})/.exec(t);
  if (!m) return null;
  const h = Number(m[1]);
  const mi = Number(m[2]);
  return h <= 23 && mi <= 59 ? [h, mi] : null;
}

export function parseSitePage(html: string, nowS = Date.now() / 1000): LiveEvent[] {
  const cats = [...html.matchAll(CAT_RE)].map((m) => [m.index!, unescapeHtml(m[1]).trim()] as const);
  if (!cats.length) return [];
  const days = [...html.matchAll(DAY_RE)].map((m) => {
    const d = DAY_DATE_RE.exec(unescapeHtml(m[1]));
    const month = d ? MONTHS.indexOf(d[2].slice(0, 3).toLowerCase()) : -1;
    return [m.index!, d && month >= 0 ? Date.UTC(Number(d[3]), month, Number(d[1])) / 1000 : null] as const;
  });
  const out: LiveEvent[] = [];
  for (const m of html.matchAll(EVENT_RE)) {
    const at = m.index!;
    const category = [...cats].reverse().find((c) => c[0] < at)?.[1] ?? "Other";
    const time = (m[2] || m[3]).trim();
    const day = [...days].reverse().find((d) => d[0] < at)?.[1] ?? null;
    const t = hhmm(time);
    let start: number | null = null;
    if (t) {
      // "Schedule Time UK GMT": the times are UTC.
      if (day != null) start = day + t[0] * 3600 + t[1] * 60;
      else {
        start = nowS - (nowS % 86400) + t[0] * 3600 + t[1] * 60;
        if (start < nowS - 12 * 3600) start += 86400;
      }
    }
    if (start != null && start < nowS - STALE_AFTER_S) continue;
    const seen = new Set<string>();
    const channels = [...m[5].matchAll(CHAN_RE)]
      .filter((c) => (seen.has(c[1]) ? false : (seen.add(c[1]), true)))
      .map((c) => ({ id: c[1], name: displayName(unescapeHtml(c[2]).trim()) }));
    out.push({ category, time, title: unescapeHtml(m[4]).trim(), start, channels });
  }
  return out;
}

/** The site's own page: ~650 KB trickled out, so it can take 20-40 s. */
export async function siteSchedule(): Promise<LiveEvent[] | null> {
  try {
    const base = await wrapperBase();
    const html = await rtext(`${base}/`, {
      headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" },
      timeoutMs: 45_000,
    });
    const events = parseSitePage(html);
    return events.length >= 10 ? events : null;
  } catch {
    return null;
  }
}

/** The site's events first; published ones it no longer lists (and that
 *  aren't over 3 h past) after. Either alone when the other isn't there. */
export function mergeSchedule(site: LiveEvent[] | null | undefined, published: LiveEvent[] | null | undefined): LiveEvent[] {
  const nowS = Date.now() / 1000;
  const fresh = (e: LiveEvent) => (e.start ?? Infinity) > nowS - STALE_AFTER_S;
  const merged = site
    ? [
        ...site,
        ...(published ?? []).filter(
          (e) => !site.some((s) => s.title.toLowerCase() === e.title.toLowerCase() && s.start === e.start) && fresh(e),
        ),
      ]
    : (published ?? []).filter(fresh);
  return merged.sort((a, b) => (a.start ?? Infinity) - (b.start ?? Infinity));
}
