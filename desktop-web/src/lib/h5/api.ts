/**
 * H5 API client — TypeScript port of `:shared/H5Api.kt`-ish (Android)
 * and `desktop/H5Api.kt` (Compose Desktop). One source-of-truth for
 * the network calls + response parsing on the web stack.
 *
 * Verified shape (live response, dumped 2026-06-29):
 *   /home                  GET  ?host=themoviebox.org
 *     data.operatingList[].title              row title
 *     data.operatingList[].subjects[]         items
 *     data.operatingList[].banner.items[].subject  items (BANNER row)
 *   /subject/search        POST {keyword, page, perPage, subjectType}
 *     data.items[]                            items
 *   /subject/play          POST {subjectId, se, ep, detailPath}
 *     data.streams[]                          playable URLs
 *
 * Bearer flow: ensureWarm() hits /country-code first to mint the
 * atp:3 anonymous-premium token from the x-user response header.
 * Token is in-memory only (lost on app restart — that's fine, the
 * warm call is ~300ms).
 */
import {
  H5_BASE, PAGE_REFERER, BROWSER_UA, X_CLIENT_INFO,
} from "./constants";
import { clientToken, trSignature } from "./crypto";
import type { H5Detail, H5Item, H5Play, H5Row, H5Season } from "./types";

let bearer: string | null = null;
let warmed = false;
let warmPromise: Promise<void> | null = null;

async function ensureWarm(): Promise<void> {
  if (warmed) return;
  // Coalesce concurrent warm calls.
  if (warmPromise) return warmPromise;
  warmPromise = (async () => {
    // Step 1: /country-code — the atp:3 bearer arrives in x-user here.
    try {
      const path = "/wefeed-h5api-bff/country-code";
      const ts = Date.now();
      const r = await fetch(`${H5_BASE}${path}`, {
        method: "GET",
        headers: {
          "Accept": "application/json",
          "User-Agent": BROWSER_UA,
          "Referer": PAGE_REFERER,
          "X-Client-Token": clientToken(ts),
          "x-tr-signature": trSignature("GET", "application/json", "", `${H5_BASE}${path}`, null, ts),
          "X-Client-Info": X_CLIENT_INFO,
          "x-request-lang": "en",
          "X-Client-Status": "0",
        },
      });
      absorbXUser(r.headers.get("x-user"));
    } catch { /* best-effort */ }
    warmed = true;
  })();
  return warmPromise;
}

function absorbXUser(xUser: string | null): void {
  if (!xUser) return;
  try {
    const o = JSON.parse(xUser);
    if (typeof o.token === "string" && o.token) bearer = o.token;
  } catch { /* malformed header */ }
}

async function signedRequest(
  method: "GET" | "POST",
  path: string,
  body: string | null,
  query: string = "",
): Promise<string> {
  await ensureWarm();
  const url = query ? `${H5_BASE}${path}?${query}` : `${H5_BASE}${path}`;
  const ts = Date.now();
  const contentType = body !== null ? "application/json; charset=utf-8" : "";
  const headers: Record<string, string> = {
    "Accept": "application/json",
    "User-Agent": BROWSER_UA,
    "Referer": PAGE_REFERER,
    "X-Client-Token": clientToken(ts),
    "x-tr-signature": trSignature(method, "application/json", contentType, url, body, ts),
    "X-Client-Info": X_CLIENT_INFO,
    "x-request-lang": "en",
    "X-Client-Status": "0",
  };
  if (body !== null) headers["Content-Type"] = contentType;
  if (bearer) headers["Authorization"] = `Bearer ${bearer}`;
  const r = await fetch(url, { method, headers, body });
  absorbXUser(r.headers.get("x-user"));
  return r.text();
}

// ---- Public API ----------------------------------------------------------

export async function home(): Promise<H5Row[]> {
  const raw = await signedRequest("GET", "/wefeed-h5api-bff/home", null, "host=themoviebox.org");
  return parseRows(raw);
}

export async function search(keyword: string, perPage = 12): Promise<H5Item[]> {
  const body = JSON.stringify({ keyword, page: 1, perPage, subjectType: 0 });
  const raw = await signedRequest("POST", "/wefeed-h5api-bff/subject/search", body);
  return parseItems(raw);
}

export async function detail(subjectId: string): Promise<H5Detail> {
  const raw = await signedRequest("GET", `/wefeed-h5api-bff/subject/detail/${subjectId}`, null);
  return parseDetail(raw, subjectId);
}

/** Resolve a play URL for a (subjectId, se, ep). Falls back to
 *  (0, 0) — the subject-level resource — if the asked episode has
 *  no streams. Mirrors Android :app v0.1.93. */
export async function play(subjectId: string, season = 0, episode = 0): Promise<H5Play> {
  const body = JSON.stringify({ subjectId, se: season, ep: episode, detailPath: "" });
  const raw = await signedRequest("POST", "/wefeed-h5api-bff/subject/play", body);
  const first = parsePlay(raw);
  if (first.streams.length > 0 || (season === 0 && episode === 0)) return first;
  const fbBody = JSON.stringify({ subjectId, se: 0, ep: 0, detailPath: "" });
  const fbRaw = await signedRequest("POST", "/wefeed-h5api-bff/subject/play", fbBody);
  const fb = parsePlay(fbRaw);
  return fb.streams.length > 0 ? fb : first;
}

// ---- Parsers --------------------------------------------------------------

interface RawSubject {
  subject?: RawSubject;
  subjectId?: string;
  title?: string;
  subjectType?: number;
  releaseDate?: string;
  imageUrl?: string;
  cover?: { url?: string };
  imdbRatingValue?: string;
}

function parseSubject(o: RawSubject): H5Item | null {
  const subj = o.subject ?? o;
  const id = subj.subjectId;
  if (!id) return null;
  const coverUrl = subj.cover?.url ?? subj.imageUrl ?? "";
  const rating = subj.imdbRatingValue ? parseFloat(subj.imdbRatingValue) : null;
  return {
    subjectId: id,
    title: subj.title ?? "",
    type: subj.subjectType ?? 0,
    year: subj.releaseDate ? parseInt(subj.releaseDate.slice(0, 4), 10) || null : null,
    coverUrl,
    rating: rating && rating > 0 ? rating : null,
  };
}

function parseItems(raw: string): H5Item[] {
  try {
    const j = JSON.parse(raw);
    const items: RawSubject[] = j?.data?.items ?? [];
    return items.map(parseSubject).filter((x): x is H5Item => x !== null);
  } catch {
    return [];
  }
}

function parseRows(raw: string): H5Row[] {
  try {
    const j = JSON.parse(raw);
    interface RawRow {
      title?: string;
      subjects?: RawSubject[];
      banner?: { items?: RawSubject[] };
    }
    const rows: RawRow[] = j?.data?.operatingList ?? [];
    const out: H5Row[] = [];
    for (const r of rows) {
      const items: H5Item[] = [];
      for (const s of r.subjects ?? []) {
        const item = parseSubject(s);
        if (item) items.push(item);
      }
      for (const s of r.banner?.items ?? []) {
        const item = parseSubject(s);
        if (item) items.push(item);
      }
      if (r.title && items.length) out.push({ title: r.title, items });
    }
    return out;
  } catch {
    return [];
  }
}

function parseDetail(raw: string, subjectId: string): H5Detail {
  try {
    const j = JSON.parse(raw);
    const data = j?.data ?? {};
    const subj = data.subject ?? data;
    const resObj = data.resource ?? subj.resource ?? {};
    const seasonsArr: { season?: number; maxEp?: number }[] = resObj.seasons ?? [];
    const seasons: H5Season[] = seasonsArr.map((s) => ({
      season: s.season ?? 0,
      episodes: s.maxEp ?? 0,
    }));
    return {
      subjectId,
      title: subj.title ?? "",
      description: subj.description ?? "",
      seasons,
      type: subj.subjectType ?? 0,
    };
  } catch {
    return { subjectId, title: "", description: "", seasons: [], type: 0 };
  }
}

function parsePlay(raw: string): H5Play {
  try {
    const j = JSON.parse(raw);
    const arr: { url?: string; resolution?: number; format?: string }[] =
      j?.data?.streams ?? j?.data?.list ?? [];
    const streams = arr
      .filter((s) => !!s.url)
      .map((s) => ({
        url: s.url as string,
        resolution: s.resolution ?? 0,
        format: s.format ?? "",
      }));
    return { streams };
  } catch {
    return { streams: [] };
  }
}
