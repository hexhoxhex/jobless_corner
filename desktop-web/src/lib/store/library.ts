// Continue watching and My list, kept on this computer (localStorage via
// zustand's persist; the Tauri window keeps its own storage).

import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { Kind } from "../tmdb/client";

export type Progress = {
  key: string; // "movie:123" or "tv:456"
  kind: Kind;
  id: number;
  title: string;
  poster?: string;
  backdrop?: string;
  season?: number;
  episode?: number;
  episodeName?: string;
  positionS: number;
  durationS: number;
  provider?: string;
  updatedAt: number;
};

export type Saved = {
  key: string;
  kind: Kind;
  id: number;
  title: string;
  poster?: string;
  year?: number;
  addedAt: number;
};

type State = {
  progress: Record<string, Progress>;
  saved: Record<string, Saved>;
  saveProgress: (p: Omit<Progress, "key" | "updatedAt">) => void;
  removeProgress: (key: string) => void;
  toggleSaved: (s: Omit<Saved, "key" | "addedAt">) => void;
};

export const keyOf = (kind: Kind, id: number) => `${kind}:${id}`;

/** Within the last 3% (credits) a title counts as finished. */
export function isFinished(p: { positionS: number; durationS: number }) {
  return p.durationS > 0 && p.positionS / p.durationS > 0.97;
}

export const useLibrary = create<State>()(
  persist(
    (set) => ({
      progress: {},
      saved: {},
      saveProgress: (p) =>
        set((s) => {
          const key = keyOf(p.kind, p.id);
          return { progress: { ...s.progress, [key]: { ...p, key, updatedAt: Date.now() } } };
        }),
      removeProgress: (key) =>
        set((s) => {
          const next = { ...s.progress };
          delete next[key];
          return { progress: next };
        }),
      toggleSaved: (item) =>
        set((s) => {
          const key = keyOf(item.kind, item.id);
          const next = { ...s.saved };
          if (next[key]) delete next[key];
          else next[key] = { ...item, key, addedAt: Date.now() };
          return { saved: next };
        }),
    }),
    { name: "library", version: 1 },
  ),
);

/** Continue watching, most recent first, finished films left out. */
export function continueList(progress: Record<string, Progress>): Progress[] {
  return Object.values(progress)
    .filter((p) => !(p.kind === "movie" && isFinished(p)))
    .sort((a, b) => b.updatedAt - a.updatedAt);
}
