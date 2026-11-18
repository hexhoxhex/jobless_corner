// Where the live site lives today. It moves (dlhd.st -> dlive.sx ->
// thedaddy.li, 2026-11-18, and dlive.sx stopped resolving), so follow the
// seed's redirects once and remember the landing place for a while.

import { rfetch } from "../net/relay";

const SEED = "https://dlhd.st";
const KEY = "live_wrapper_base";
const KEEP_MS = 12 * 3600_000;

let pending: Promise<string> | null = null;

export function wrapperBase(force = false): Promise<string> {
  if (!force) {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) {
        const { base, at } = JSON.parse(raw) as { base: string; at: number };
        if (base?.startsWith("https://") && Date.now() - at < KEEP_MS) return Promise.resolve(base);
      }
    } catch {
      /* fall through to a fresh lookup */
    }
  }
  if (!pending) {
    pending = (async () => {
      try {
        const res = await rfetch(`${SEED}/`, {
          headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" },
          timeoutMs: 20_000,
        });
        await res.body?.cancel().catch(() => undefined);
        const base = new URL(res.finalUrl).origin;
        try {
          localStorage.setItem(KEY, JSON.stringify({ base, at: Date.now() }));
        } catch {
          /* not kept: looked up again next time */
        }
        return base;
      } catch {
        return SEED;
      } finally {
        pending = null;
      }
    })();
  }
  return pending;
}
