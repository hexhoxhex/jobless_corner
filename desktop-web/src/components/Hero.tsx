"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Info, Play, Star } from "lucide-react";
import clsx from "clsx";
import type { Title } from "@/lib/tmdb/client";
import { titleHref } from "./PosterCard";
import { watchHref } from "@/lib/watchHref";
import { useGenreNames } from "@/lib/tmdb/hooks";

const ADVANCE_MS = 9000;

/** The billboard: one trending title at a time, crossfading every few
 *  seconds unless the pointer or focus is on it. */
export function Hero({ items }: { items: Title[] }) {
  const [i, setI] = useState(0);
  const [paused, setPaused] = useState(false);
  const genreName = useGenreNames();
  const shown = items.slice(0, 6);
  const t = shown[i % Math.max(shown.length, 1)];

  useEffect(() => {
    if (paused || shown.length < 2) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const id = setTimeout(() => setI((n) => (n + 1) % shown.length), ADVANCE_MS);
    return () => clearTimeout(id);
  }, [i, paused, shown.length]);

  if (!t) return <HeroSkeleton />;

  const genres = t.genreIds.map(genreName).filter(Boolean).slice(0, 3);

  return (
    <section
      className="relative h-[min(72vh,620px)] min-h-[440px] w-full overflow-hidden"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
      aria-roledescription="carousel"
      aria-label="Trending now"
    >
      {shown.map((s, n) => (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          key={s.id}
          src={s.backdrop}
          alt=""
          className={clsx(
            "absolute inset-0 h-full w-full object-cover object-[center_25%] transition-opacity duration-[900ms] ease-out",
            n === i ? "opacity-100" : "opacity-0",
          )}
          loading={n === 0 ? "eager" : "lazy"}
        />
      ))}
      <div className="absolute inset-0 bg-[linear-gradient(90deg,#101114_0%,rgba(16,17,20,.86)_28%,rgba(16,17,20,.35)_58%,rgba(16,17,20,0)_80%)]" />
      <div className="absolute inset-x-0 bottom-0 h-2/3 bg-gradient-to-t from-bg via-bg/60 to-transparent" />

      <div key={t.id} className="absolute bottom-14 left-10 max-w-[560px] animate-rise">
        <p className="mb-3 text-[11px] font-semibold uppercase tracking-[0.16em] text-accent-soft">
          Trending · {t.kind === "tv" ? "Series" : "Movie"}
        </p>
        <h1 className="font-display text-4xl font-extrabold tracking-tight text-ink [text-wrap:balance] max-[1180px]:text-3xl">
          {t.title}
        </h1>
        <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-ink-3">
          {t.rating ? (
            <span className="flex items-center gap-1 font-semibold text-ink-2">
              <Star className="h-3.5 w-3.5 fill-gold text-gold" strokeWidth={0} />
              {t.rating.toFixed(1)}
            </span>
          ) : null}
          {t.year && <span className="tabular">{t.year}</span>}
          {genres.length > 0 && <span>{genres.join(" · ")}</span>}
        </div>
        <p className="mt-4 line-clamp-3 text-base text-ink-2/90">{t.overview}</p>
        <div className="mt-6 flex gap-3">
          <Link
            href={watchHref(t)}
            className="flex h-11 cursor-pointer items-center gap-2 rounded-card bg-accent px-6 text-sm font-semibold text-white shadow-[0_8px_24px_-8px_rgba(7,184,78,.7)] transition-colors duration-fast hover:bg-accent-hover"
          >
            <Play className="h-4 w-4 fill-current" strokeWidth={0} />
            Play
          </Link>
          <Link
            href={titleHref(t)}
            className="flex h-11 cursor-pointer items-center gap-2 rounded-card bg-white/10 px-5 text-sm font-semibold text-ink backdrop-blur-md transition-colors duration-fast hover:bg-white/20"
          >
            <Info className="h-4 w-4" strokeWidth={2} />
            More info
          </Link>
        </div>
      </div>

      {shown.length > 1 && (
        <div className="absolute bottom-16 right-10 flex gap-2" role="tablist" aria-label="Choose a title">
          {shown.map((s, n) => (
            <button
              key={s.id}
              role="tab"
              aria-selected={n === i}
              aria-label={s.title}
              onClick={() => setI(n)}
              className={clsx(
                "h-1.5 cursor-pointer rounded-full transition-all duration-base",
                n === i ? "w-7 bg-ink" : "w-3 bg-white/30 hover:bg-white/50",
              )}
            />
          ))}
        </div>
      )}
    </section>
  );
}

export function HeroSkeleton() {
  return (
    <section className="relative h-[min(72vh,620px)] min-h-[440px] w-full">
      <div className="skeleton absolute inset-0 opacity-40" />
      <div className="absolute bottom-14 left-10 w-[460px]">
        <div className="skeleton h-3 w-28 rounded" />
        <div className="skeleton mt-4 h-12 w-80 rounded" />
        <div className="skeleton mt-4 h-4 w-full rounded" />
        <div className="skeleton mt-2 h-4 w-5/6 rounded" />
        <div className="mt-6 flex gap-3">
          <div className="skeleton h-11 w-28 rounded-card" />
          <div className="skeleton h-11 w-32 rounded-card" />
        </div>
      </div>
    </section>
  );
}
