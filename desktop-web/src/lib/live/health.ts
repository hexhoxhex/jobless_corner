// What happened the last time each channel was opened on this computer, so
// the grid can say which ones play here (the TV's HomeHealthLedger, kept
// local). A failure is only recorded when no route answered at all, not when
// the viewer left before it started.

export type Verdict = { ok: boolean; at: number };

const KEY = "live_health";
const KEEP_MS = 24 * 3600_000;

function read(): Record<string, Verdict> {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? "{}") as Record<string, Verdict>;
  } catch {
    return {};
  }
}

export function recordChannel(id: string, ok: boolean) {
  const all = read();
  all[id] = { ok, at: Date.now() };
  try {
    localStorage.setItem(KEY, JSON.stringify(all));
  } catch {
    /* not kept */
  }
}

/** Recent verdicts only: a channel that failed yesterday may play today. */
export function channelHealth(): Record<string, Verdict> {
  const now = Date.now();
  return Object.fromEntries(Object.entries(read()).filter(([, v]) => now - v.at < KEEP_MS));
}
