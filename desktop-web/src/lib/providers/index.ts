// The order sources are asked in, and the failover between them: the first
// that hands back a link that plays wins. Mirrors the TV app's Provider order
// (7Movies first, VixSrc as the gap-filler).

import { HeldError } from "../net/relay";
import { SevenMovies } from "./sevenMovies";
import { VixSrc } from "./vixsrc";
import type { PlayInfo, ResolveOpts, Source, Target } from "./types";

export const SOURCES: Source[] = [SevenMovies, VixSrc];
export const SOURCE_NAMES = SOURCES.map((s) => s.name);

/** Per source, so one that hangs can't eat the whole start. */
const SOURCE_BUDGET_MS = 30_000;

async function withBudget(s: Source, t: Target, opts: ResolveOpts): Promise<PlayInfo | null> {
  const ac = new AbortController();
  const onAbort = () => ac.abort(opts.signal?.reason);
  opts.signal?.addEventListener("abort", onAbort);
  const timer = setTimeout(() => ac.abort(new DOMException("source budget", "TimeoutError")), SOURCE_BUDGET_MS);
  try {
    return await s.resolve(t, { ...opts, signal: ac.signal });
  } catch (e) {
    if (opts.signal?.aborted) throw e;
    if (e instanceof HeldError) opts.log?.(`${s.name}: ${e.message}`);
    else opts.log?.(`${s.name}: ${(e as Error)?.message ?? e}`);
    return null;
  } finally {
    clearTimeout(timer);
    opts.signal?.removeEventListener("abort", onAbort);
  }
}

/** Try sources in order (skipping [skip]); [onTry] reports which is being asked. */
export async function resolveChain(
  t: Target,
  opts: ResolveOpts & { onTry?: (name: string) => void; skip?: string[]; only?: string },
): Promise<PlayInfo | null> {
  const list = opts.only ? SOURCES.filter((s) => s.name === opts.only) : SOURCES.filter((s) => !opts.skip?.includes(s.name));
  for (const s of list) {
    if (opts.signal?.aborted) return null;
    opts.onTry?.(s.name);
    const info = await withBudget(s, t, opts);
    if (info) return info;
    opts.log?.(`${s.name}: no link that plays`);
  }
  return null;
}

export type { PlayInfo, Target } from "./types";
