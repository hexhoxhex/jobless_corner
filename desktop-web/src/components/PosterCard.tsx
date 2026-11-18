"use client";

import Link from "next/link";
import { Star } from "lucide-react";
import type { Title } from "@/lib/tmdb/client";

export function titleHref(t: { kind: string; id: number }) {
  return `/title?kind=${t.kind}&id=${t.id}`;
}

/** A 2:3 poster with title and year under it. */
export function PosterCard({ t, rank, width = 168 }: { t: Title; rank?: number; width?: number | "fill" }) {
  return (
    <Link
      href={titleHref(t)}
      className="group block shrink-0 cursor-pointer outline-none"
      style={width === "fill" ? undefined : { width }}
      title={t.title}
    >
      <div className="relative aspect-[2/3] overflow-hidden rounded-card bg-surface-2 ring-1 ring-line transition-[transform,box-shadow] duration-base ease-out group-hover:-translate-y-1 group-hover:shadow-[0_14px_30px_-12px_rgba(0,0,0,.8)] group-hover:ring-line-strong group-focus-visible:ring-2 group-focus-visible:ring-accent-soft">
        {t.poster ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={t.poster}
            alt=""
            loading="lazy"
            decoding="async"
            className="h-full w-full object-cover transition-transform duration-[400ms] ease-out group-hover:scale-[1.03]"
          />
        ) : (
          <div className="flex h-full items-center justify-center p-3 text-center font-display text-sm font-bold text-ink-3">
            {t.title}
          </div>
        )}
        {rank !== undefined && (
          <span className="absolute left-0 top-0 rounded-br-card bg-black/70 px-2 py-0.5 font-display text-sm font-extrabold tabular text-ink">
            {String(rank).padStart(2, "0")}
          </span>
        )}
        {t.rating ? (
          <span className="absolute right-1.5 top-1.5 flex items-center gap-1 rounded-md bg-black/65 px-1.5 py-0.5 text-[11px] font-semibold tabular text-ink-2 backdrop-blur-sm">
            <Star className="h-3 w-3 fill-gold text-gold" strokeWidth={0} />
            {t.rating.toFixed(1)}
          </span>
        ) : null}
      </div>
      <div className="mt-2 px-0.5">
        <p className="truncate text-sm font-medium text-ink-2 group-hover:text-ink">{t.title}</p>
        <p className="text-xs text-ink-4">
          {t.year ?? ""}
          {t.year ? " · " : ""}
          {t.kind === "tv" ? "Series" : "Movie"}
        </p>
      </div>
    </Link>
  );
}

export function PosterSkeleton({ width = 168 }: { width?: number | "fill" }) {
  return (
    <div className="shrink-0" style={width === "fill" ? undefined : { width }}>
      <div className="skeleton aspect-[2/3] rounded-card" />
      <div className="skeleton mt-2 h-3.5 w-3/4 rounded" />
      <div className="skeleton mt-1.5 h-3 w-1/3 rounded" />
    </div>
  );
}
