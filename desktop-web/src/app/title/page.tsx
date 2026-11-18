"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import { ArrowLeft, Bookmark, BookmarkCheck, Play, RotateCcw, Star } from "lucide-react";
import { tmdb, type Details, type Kind } from "@/lib/tmdb/client";
import { Row } from "@/components/Row";
import { PosterCard } from "@/components/PosterCard";
import { keyOf, useLibrary } from "@/lib/store/library";
import { watchHref } from "@/lib/watchHref";

function fmtRuntime(min?: number) {
  if (!min) return undefined;
  const h = Math.floor(min / 60);
  return h ? `${h} h ${min % 60} min` : `${min} min`;
}

function Episodes({ d }: { d: Details }) {
  const progress = useLibrary((s) => s.progress[keyOf("tv", d.id)]);
  const [season, setSeason] = useState<number>(progress?.season ?? d.seasons[0]?.number ?? 1);
  const eps = useQuery({ queryKey: ["season", d.id, season], queryFn: () => tmdb.season(d.id, season) });
  const today = new Date().toISOString().slice(0, 10);

  return (
    <section className="px-10">
      <div className="mb-4 flex flex-wrap items-center gap-4">
        <h2 className="font-display text-lg font-bold tracking-tight text-ink">Episodes</h2>
        <div className="row-scroller flex gap-1.5" role="tablist" aria-label="Season">
          {d.seasons.map((s) => (
            <button
              key={s.number}
              role="tab"
              aria-selected={season === s.number}
              onClick={() => setSeason(s.number)}
              className={clsx(
                "h-8 shrink-0 cursor-pointer rounded-full px-3.5 text-sm font-medium transition-colors duration-fast",
                season === s.number ? "bg-ink text-bg" : "bg-white/5 text-ink-3 hover:bg-white/10 hover:text-ink-2",
              )}
            >
              Season {s.number}
            </button>
          ))}
        </div>
      </div>
      {eps.isError ? (
        <p className="text-sm text-ink-3">
          Episodes didn&apos;t load.{" "}
          <button onClick={() => eps.refetch()} className="font-medium text-accent-soft hover:underline">
            Try again
          </button>
        </p>
      ) : (
        <ol className="grid grid-cols-[repeat(auto-fill,minmax(340px,1fr))] gap-4">
          {(eps.data ?? []).map((e) => {
            const upcoming = !!e.airDate && e.airDate > today;
            const current = progress?.season === e.season && progress?.episode === e.number;
            const pct = current && progress.durationS ? (progress.positionS / progress.durationS) * 100 : 0;
            const body = (
              <>
                <div className="relative aspect-video w-44 shrink-0 overflow-hidden rounded-md bg-surface-3">
                  {e.still && (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={e.still} alt="" loading="lazy" className="h-full w-full object-cover" />
                  )}
                  {!upcoming && (
                    <span className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition-opacity duration-base group-hover:opacity-100">
                      <Play className="h-6 w-6 fill-white text-white" strokeWidth={0} />
                    </span>
                  )}
                  {pct > 0 && (
                    <div className="absolute inset-x-0 bottom-0 h-1 bg-white/20">
                      <div className="h-full bg-accent" style={{ width: `${pct}%` }} />
                    </div>
                  )}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-semibold tabular text-ink-4">
                    E{e.number}
                    {e.runtime ? ` · ${e.runtime} min` : ""}
                    {upcoming ? ` · Airs ${e.airDate}` : ""}
                  </p>
                  <p className="truncate text-sm font-semibold text-ink-2 group-hover:text-ink">{e.name}</p>
                  <p className="mt-0.5 line-clamp-2 text-xs leading-5 text-ink-3">{e.overview}</p>
                </div>
              </>
            );
            return (
              <li key={e.number}>
                {upcoming ? (
                  <div className="flex gap-3 rounded-card p-2 opacity-60">{body}</div>
                ) : (
                  <Link
                    href={watchHref({ kind: "tv", id: d.id }, e.season, e.number)}
                    className={clsx(
                      "group flex cursor-pointer gap-3 rounded-card p-2 transition-colors duration-fast hover:bg-white/5",
                      current && "bg-white/5 ring-1 ring-accent/40",
                    )}
                  >
                    {body}
                  </Link>
                )}
              </li>
            );
          })}
          {eps.isLoading &&
            Array.from({ length: 6 }, (_, n) => (
              <li key={n} className="flex gap-3 p-2">
                <div className="skeleton aspect-video w-44 rounded-md" />
                <div className="flex-1">
                  <div className="skeleton h-3 w-16 rounded" />
                  <div className="skeleton mt-2 h-4 w-3/4 rounded" />
                  <div className="skeleton mt-2 h-3 w-full rounded" />
                </div>
              </li>
            ))}
        </ol>
      )}
    </section>
  );
}

function TitleView() {
  const params = useSearchParams();
  const kind = (params.get("kind") === "tv" ? "tv" : "movie") as Kind;
  const id = Number(params.get("id"));
  const d = useQuery({ queryKey: ["details", kind, id], queryFn: () => tmdb.details(kind, id), enabled: id > 0 });
  const saved = useLibrary((s) => !!s.saved[keyOf(kind, id)]);
  const toggleSaved = useLibrary((s) => s.toggleSaved);
  const progress = useLibrary((s) => s.progress[keyOf(kind, id)]);

  useEffect(() => {
    document.getElementById("main")?.scrollTo({ top: 0 });
  }, [id]);

  if (d.isError) {
    return (
      <div className="p-10 text-sm text-ink-3">
        This title didn&apos;t load.{" "}
        <button onClick={() => d.refetch()} className="font-medium text-accent-soft hover:underline">
          Try again
        </button>
      </div>
    );
  }
  if (!d.data) {
    return (
      <div>
        <div className="skeleton h-[min(64vh,560px)] w-full opacity-40" />
      </div>
    );
  }
  const t = d.data;
  const meta = [
    t.year?.toString(),
    kind === "tv" ? "Series" : "Movie",
    t.certification,
    kind === "tv" ? `${t.seasons.length} season${t.seasons.length === 1 ? "" : "s"}` : fmtRuntime(t.runtime),
  ].filter(Boolean);
  const resumeLabel =
    progress && progress.positionS > 30
      ? kind === "tv"
        ? `Resume S${progress.season} E${progress.episode}`
        : "Resume"
      : kind === "tv"
        ? "Play S1 E1"
        : "Play";
  const playHref =
    progress && progress.positionS > 30
      ? watchHref({ kind, id }, progress.season, progress.episode) + "&resume=1"
      : watchHref({ kind, id });

  return (
    <div className="pb-16">
      <section className="relative h-[min(64vh,560px)] min-h-[420px] w-full overflow-hidden">
        {t.backdrop && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={t.backdrop} alt="" className="absolute inset-0 h-full w-full object-cover object-[center_20%]" />
        )}
        <div className="absolute inset-0 bg-[linear-gradient(90deg,#101114_0%,rgba(16,17,20,.88)_30%,rgba(16,17,20,.3)_62%,rgba(16,17,20,0)_85%)]" />
        <div className="absolute inset-x-0 bottom-0 h-2/3 bg-gradient-to-t from-bg via-bg/60 to-transparent" />
        <button
          onClick={() => history.back()}
          className="absolute left-8 top-6 flex h-9 cursor-pointer items-center gap-1.5 rounded-full bg-black/40 pl-2.5 pr-3.5 text-sm font-medium text-ink-2 backdrop-blur-md transition-colors hover:bg-black/60 hover:text-ink"
        >
          <ArrowLeft className="h-4 w-4" /> Back
        </button>
        <div className="absolute bottom-10 left-10 max-w-[600px] animate-rise">
          {t.logo ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={t.logo} alt={t.title} className="mb-5 max-h-28 max-w-[420px] object-contain object-left drop-shadow-[0_4px_18px_rgba(0,0,0,.6)]" />
          ) : (
            <h1 className="mb-4 font-display text-4xl font-extrabold tracking-tight text-ink [text-wrap:balance]">{t.title}</h1>
          )}
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-ink-3">
            {t.rating ? (
              <span className="flex items-center gap-1 font-semibold text-ink-2">
                <Star className="h-3.5 w-3.5 fill-gold text-gold" strokeWidth={0} />
                {t.rating.toFixed(1)}
              </span>
            ) : null}
            {meta.map((m, n) => (
              <span key={n} className={clsx(m === t.certification && "rounded border border-line-strong px-1.5 text-xs")}>
                {m}
              </span>
            ))}
          </div>
          {t.genres.length > 0 && <p className="mt-1.5 text-sm text-ink-4">{t.genres.join(" · ")}</p>}
          <p className="mt-4 line-clamp-4 text-base text-ink-2/90">{t.overview}</p>
          <div className="mt-6 flex flex-wrap gap-3">
            <Link
              href={playHref}
              className="flex h-11 cursor-pointer items-center gap-2 rounded-card bg-accent px-6 text-sm font-semibold text-white shadow-[0_8px_24px_-8px_rgba(7,184,78,.7)] transition-colors duration-fast hover:bg-accent-hover"
            >
              <Play className="h-4 w-4 fill-current" strokeWidth={0} />
              {resumeLabel}
            </Link>
            {progress && progress.positionS > 30 && (
              <Link
                href={kind === "tv" ? watchHref({ kind, id }, progress.season, progress.episode) : watchHref({ kind, id })}
                className="flex h-11 cursor-pointer items-center gap-2 rounded-card bg-white/10 px-5 text-sm font-semibold text-ink backdrop-blur-md transition-colors hover:bg-white/20"
              >
                <RotateCcw className="h-4 w-4" />
                Start over
              </Link>
            )}
            <button
              onClick={() => toggleSaved({ kind, id, title: t.title, poster: t.poster, year: t.year })}
              aria-pressed={saved}
              className="flex h-11 cursor-pointer items-center gap-2 rounded-card bg-white/10 px-5 text-sm font-semibold text-ink backdrop-blur-md transition-colors hover:bg-white/20"
            >
              {saved ? <BookmarkCheck className="h-4 w-4 text-accent-soft" /> : <Bookmark className="h-4 w-4" />}
              {saved ? "In my list" : "My list"}
            </button>
          </div>
        </div>
      </section>

      <div className="mt-6 flex flex-col gap-10">
        {kind === "tv" && t.seasons.length > 0 && <Episodes d={t} />}
        {t.cast.length > 0 && (
          <Row title="Cast">
            {t.cast.map((c) => (
              <div key={c.id} className="w-[112px] shrink-0 text-center">
                <div className="mx-auto h-[112px] w-[112px] overflow-hidden rounded-full bg-surface-3 ring-1 ring-line">
                  {c.photo && (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={c.photo} alt="" loading="lazy" className="h-full w-full object-cover" />
                  )}
                </div>
                <p className="mt-2 truncate text-sm font-medium text-ink-2">{c.name}</p>
                <p className="truncate text-xs text-ink-4">{c.character}</p>
              </div>
            ))}
          </Row>
        )}
        {t.similar.length > 0 && (
          <Row title="More like this">
            {t.similar.map((s) => (
              <PosterCard key={s.id} t={s} />
            ))}
          </Row>
        )}
      </div>
    </div>
  );
}

export default function TitlePage() {
  return (
    <Suspense>
      <TitleView />
    </Suspense>
  );
}
