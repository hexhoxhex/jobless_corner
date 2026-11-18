import type { Kind } from "./tmdb/client";

/** The player page for a film, or for an episode of a series (S1E1 unless given). */
export function watchHref(t: { kind: Kind; id: number }, season?: number, episode?: number) {
  const q = new URLSearchParams({ kind: t.kind, id: String(t.id) });
  if (t.kind === "tv") {
    q.set("s", String(season ?? 1));
    q.set("e", String(episode ?? 1));
  }
  return `/watch?${q}`;
}
