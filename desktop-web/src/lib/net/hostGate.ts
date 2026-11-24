// A server that answers 403/429/503 with Retry-After is asking us to stay
// away. Asking again anyway is what got the TV's address banned for an hour
// (2026-11-07), so held hosts get no requests until the time is up; one
// probe is let through every [PROBE_EVERY_MS] to see whether it relented.
// Port of the TV app's data/live/HostGate.kt.

const PROBE_EVERY_MS = 5 * 60_000;
const MAX_HOLD_MS = 2 * 3600_000;

export class HeldError extends Error {
  constructor(public host: string, public untilMs: number) {
    super(`${host} asked us to wait ${Math.ceil((untilMs - Date.now()) / 1000)} s`);
  }
}

type Hold = { until: number; lastProbe: number };

const KEY = "host_gate";

function load(): Map<string, Hold> {
  try {
    const raw = typeof localStorage !== "undefined" ? localStorage.getItem(KEY) : null;
    if (!raw) return new Map();
    const now = Date.now();
    const entries = Object.entries(JSON.parse(raw) as Record<string, number>)
      .filter(([, until]) => until > now)
      // After a restart the next probe is a full interval away.
      .map(([h, until]) => [h, { until, lastProbe: now }] as const);
    return new Map(entries);
  } catch {
    return new Map();
  }
}

const holds = load();

function save() {
  try {
    const obj: Record<string, number> = {};
    holds.forEach((v, k) => (obj[k] = v.until));
    localStorage.setItem(KEY, JSON.stringify(obj));
  } catch {
    /* storage unavailable: holds stay in memory */
  }
}

function parseRetryAfter(v: string | null): number | null {
  if (!v) return null;
  const s = Number(v.trim());
  if (Number.isFinite(s)) return Math.max(0, s * 1000);
  const t = Date.parse(v);
  return Number.isFinite(t) ? Math.max(0, t - Date.now()) : null;
}

export const HostGate = {
  /** Throws while [host] is held, unless a probe is due. */
  check(host: string) {
    const h = holds.get(host);
    if (!h) return;
    const now = Date.now();
    if (now >= h.until) {
      holds.delete(host);
      save();
      return;
    }
    if (now - h.lastProbe >= PROBE_EVERY_MS) {
      h.lastProbe = now;
      return;
    }
    throw new HeldError(host, h.until);
  },

  observe(host: string, status: number, retryAfter: string | null) {
    if (status >= 200 && status < 400) {
      if (holds.delete(host)) save();
      return;
    }
    if (status !== 403 && status !== 429 && status !== 503) return;
    const ms = parseRetryAfter(retryAfter);
    if (ms == null || ms <= 0) return;
    const now = Date.now();
    holds.set(host, { until: now + Math.min(ms, MAX_HOLD_MS), lastProbe: now });
    save();
  },

  /** Hold [host] for [ms] on our own judgement (it stopped answering). */
  hold(host: string, ms: number) {
    const now = Date.now();
    holds.set(host, { until: now + Math.min(ms, MAX_HOLD_MS), lastProbe: now });
    save();
  },

  heldForMs(host: string): number {
    const h = holds.get(host);
    return h ? Math.max(0, h.until - Date.now()) : 0;
  },
};
