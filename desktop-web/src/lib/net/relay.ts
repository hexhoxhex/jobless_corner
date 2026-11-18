// Every source request goes through the local relay (src-tauri/src/relay.rs,
// or tools/devrelay.mjs in development), which can set the headers a page
// can't (Referer, Origin, User-Agent) and isn't bound by CORS.

import { HostGate, HeldError } from "./hostGate";

type RelayInfo = { base: string; secret: string };

let info: RelayInfo | null = null;
let pending: Promise<RelayInfo> | null = null;

export function inTauri(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

/** Where the relay is. Call once at start; afterwards [relayUrl] works synchronously. */
export function ensureRelay(): Promise<RelayInfo> {
  if (info) return Promise.resolve(info);
  if (!pending) {
    pending = (async () => {
      if (inTauri()) {
        const { invoke } = await import("@tauri-apps/api/core");
        const r = await invoke<{ port: number; secret: string }>("relay_info");
        info = { base: `http://127.0.0.1:${r.port}`, secret: r.secret };
      } else {
        info = { base: "http://127.0.0.1:8797", secret: "dev" };
      }
      return info;
    })();
  }
  return pending;
}

function b64url(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export type Headers = Record<string, string>;

/** [url] fetched through the relay with [headers]. Needs [ensureRelay] done.
 *  [pieces]: the relay fetches the file in parallel ranges (servers that give
 *  each connection a trickle); answers without ranges pass through as usual. */
export function relayUrl(url: string, headers: Headers = {}, opts: { pieces?: boolean } = {}): string {
  if (!info) throw new Error("relay not ready");
  const h = Object.keys(headers).length ? `&h=${b64url(JSON.stringify(headers))}` : "";
  const p = opts.pieces ? "&p=1" : "";
  return `${info.base}/r?s=${encodeURIComponent(info.secret)}&u=${encodeURIComponent(url)}${h}${p}`;
}

export type RelayInit = {
  method?: "GET" | "POST" | "HEAD";
  headers?: Headers;
  body?: string | Uint8Array;
  timeoutMs?: number;
  signal?: AbortSignal;
  /** The time limit covers reading the body too, not just the headers (a page
   *  that trickles: one embed page took 54 s to finish, 2026-11-18). */
  whole?: boolean;
};

export class HttpError extends Error {
  constructor(public status: number, public url: string, public body = "") {
    super(`HTTP ${status} from ${hostOf(url)}`);
  }
}

export function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return "";
  }
}

/** fetch through the relay. Throws [HeldError] while the host has asked us to
 *  wait (Retry-After), and records any such request it gets back. */
export async function rfetch(url: string, init: RelayInit = {}): Promise<Response & { finalUrl: string }> {
  await ensureRelay();
  const host = hostOf(url);
  HostGate.check(host);
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(new DOMException("timeout", "TimeoutError")), init.timeoutMs ?? 20_000);
  const onAbort = () => ac.abort(init.signal?.reason);
  init.signal?.addEventListener("abort", onAbort, { once: true });
  try {
    const res = await fetch(relayUrl(url, init.headers), {
      method: init.method ?? "GET",
      body: init.body as BodyInit | undefined,
      signal: ac.signal,
    });
    HostGate.observe(host, res.status, res.headers.get("retry-after"));
    return Object.assign(res, { finalUrl: res.headers.get("x-final-url") || url });
  } finally {
    // [whole]: the timer and the caller's signal keep governing the body;
    // aborting a finished fetch does nothing.
    if (!init.whole) {
      clearTimeout(timer);
      init.signal?.removeEventListener("abort", onAbort);
    }
  }
}

/** Text of [url]; non-2xx is an [HttpError]. */
export async function rtext(url: string, init: RelayInit = {}): Promise<string> {
  const res = await rfetch(url, init);
  const text = await res.text();
  if (!res.ok) throw new HttpError(res.status, url, text.slice(0, 300));
  return text;
}

/** JSON of [url]; non-2xx is an [HttpError]. */
export async function rjson<T = unknown>(url: string, init: RelayInit = {}): Promise<T> {
  const text = await rtext(url, init);
  return JSON.parse(text) as T;
}

export { HeldError };
