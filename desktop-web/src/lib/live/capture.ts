// The hidden player window, served by the backend's relay as /capture (see
// src-tauri/src/capture.rs): for players that only reveal their stream when
// their own script runs. The same call works in the app and, against the
// engine (`vijanabarubaru --engine`), from the UI in a browser.

import { ensureRelay } from "../net/relay";

export type Captured = { url: string; origin: string; page: string; ua: string };

export class CaptureError extends Error {}

/** False once the backend turned out to have no hidden window (the plain
 *  Node relay used in development). */
let available = true;

export function canCapture(): boolean {
  return available;
}

export async function captureStream(pageUrl: string, timeoutMs = 25_000, signal?: AbortSignal): Promise<Captured> {
  const r = await ensureRelay();
  const q = new URLSearchParams({ s: r.secret, u: pageUrl, t: String(timeoutMs) });
  const res = await fetch(`${r.base}/capture?${q}`, { signal });
  if (res.status === 404) {
    available = false;
    throw new CaptureError("this backend has no hidden window");
  }
  const data = (await res.json()) as ({ ok: true } & Captured) | { ok: false; error: string };
  if (!data.ok) throw new CaptureError(data.error);
  return data;
}
