"use client";

import Link from "next/link";
import { Play, X } from "lucide-react";
import type { Progress } from "@/lib/store/library";
import { useLibrary } from "@/lib/store/library";
import { watchHref } from "@/lib/watchHref";

/** A 16:9 card that resumes where the viewer stopped. */
export function ContinueCard({ p }: { p: Progress }) {
  const remove = useLibrary((s) => s.removeProgress);
  const pct = p.durationS > 0 ? Math.min(100, (p.positionS / p.durationS) * 100) : 0;
  const left = Math.max(0, Math.round((p.durationS - p.positionS) / 60));
  return (
    <div className="group relative w-[300px] shrink-0">
      <Link href={watchHref(p, p.season, p.episode) + "&resume=1"} className="block cursor-pointer" title={`Resume ${p.title}`}>
        <div className="relative aspect-video overflow-hidden rounded-card bg-surface-2 ring-1 ring-line transition-[box-shadow] duration-base group-hover:ring-line-strong">
          {p.backdrop || p.poster ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={p.backdrop || p.poster} alt="" loading="lazy" className="h-full w-full object-cover" />
          ) : null}
          <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-black/10 to-transparent" />
          <span className="absolute left-1/2 top-1/2 flex h-12 w-12 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-black/55 opacity-0 backdrop-blur transition-opacity duration-base group-hover:opacity-100">
            <Play className="h-5 w-5 fill-white text-white" strokeWidth={0} />
          </span>
          <div className="absolute inset-x-3 bottom-2.5">
            <p className="truncate text-sm font-semibold text-ink">{p.title}</p>
            <p className="truncate text-xs text-ink-3">
              {p.season ? `S${p.season} E${p.episode}${p.episodeName ? ` · ${p.episodeName}` : ""}` : `${left} min left`}
            </p>
          </div>
          <div className="absolute inset-x-0 bottom-0 h-1 bg-white/15">
            <div className="h-full bg-accent" style={{ width: `${pct}%` }} />
          </div>
        </div>
      </Link>
      <button
        onClick={() => remove(p.key)}
        aria-label={`Remove ${p.title} from Continue watching`}
        className="absolute right-2 top-2 flex h-7 w-7 cursor-pointer items-center justify-center rounded-full bg-black/60 text-ink-2 opacity-0 transition-opacity duration-fast hover:bg-black/80 hover:text-ink group-hover:opacity-100 focus-visible:opacity-100"
      >
        <X className="h-4 w-4" />
      </button>
    </div>
  );
}
