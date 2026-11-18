// Does a link actually play? Fetch the playlist (and the first variant of a
// master), then the first 1 KB of the first chunk. A source whose links
// don't play is skipped rather than counted as a success. Port of the TV
// app's net/StreamProbe.kt.

import { rfetch, type Headers } from "../net/relay";

const TIMEOUT_MS = 6000;

function resolve(base: string, ref: string): string {
  try {
    return new URL(ref, base).toString();
  } catch {
    return ref;
  }
}

async function text(url: string, headers: Headers, signal?: AbortSignal): Promise<string | null> {
  try {
    const r = await rfetch(url, { headers, timeoutMs: TIMEOUT_MS, signal });
    if (!r.ok) return null;
    return await r.text();
  } catch {
    return null;
  }
}

function firstUri(playlist: string): string | null {
  for (const raw of playlist.split(/\r?\n/)) {
    const l = raw.trim();
    if (l && !l.startsWith("#")) return l;
  }
  return null;
}

/** null when it plays; otherwise a short reason. */
export async function probe(url: string, headers: Headers, signal?: AbortSignal): Promise<string | null> {
  let body = await text(url, headers, signal);
  if (body == null) return "playlist refused";
  if (!body.startsWith("#EXTM3U")) return null; // a file, not a playlist: the answer itself is the check
  let base = url;
  if (body.includes("#EXT-X-STREAM-INF")) {
    const v = firstUri(body);
    if (!v) return "empty master";
    base = resolve(url, v);
    body = await text(base, headers, signal);
    if (!body || !body.startsWith("#EXTM3U")) return "variant refused";
  }
  const seg = firstUri(body);
  if (!seg) return "no chunks";
  try {
    const r = await rfetch(resolve(base, seg), {
      headers: { ...headers, Range: "bytes=0-1023" },
      timeoutMs: TIMEOUT_MS,
      signal,
    });
    await r.body?.cancel().catch(() => undefined);
    return r.ok ? null : `chunk ${r.status}`;
  } catch {
    return "chunk failed";
  }
}
