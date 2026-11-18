"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import { AudioLines, Star } from "lucide-react";
import { tmdb, type Details, type Kind, type Title } from "@/lib/tmdb/client";
import { keyOf, useLibrary } from "@/lib/store/library";
import { watchHref } from "@/lib/watchHref";
import { PanelEmpty, PanelHeading, PanelSkeleton, SidePanel, useDebounced, type PanelTab } from "./SidePanel";
import { BelowEmpty, BelowSection, GridSkeleton } from "./Below";

/** A 16:9 thumbnail from TMDB art, at a list's size (the w1280 backdrop is far too big here). */
function thumbOf(t: { backdrop?: string; poster?: string }) {
  return t.backdrop?.replace("/w1280/", "/w300/").replace("/original/", "/w300/") ?? t.poster;
}

/** Where a title starts: a series where the viewer stopped, else its first episode. */
function useStartHref() {
  const progress = useLibrary((s) => s.progress);
  return (t: { kind: Kind; id: number }) => {
    const p = progress[keyOf(t.kind, t.id)];
    if (t.kind === "tv" && p?.season) return watchHref(t, p.season, p.episode) + "&resume=1";
    if (t.kind === "movie" && p) return watchHref(t) + "&resume=1";
    return watchHref(t);
  };
}

function Meta({ t }: { t: Title }) {
  return (
    <span className="mt-1 flex flex-wrap items-center gap-x-1.5 whitespace-nowrap text-xs text-ink-4">
      {t.kind === "tv" ? "Series" : "Film"}
      {t.year ? <span>· {t.year}</span> : null}
      {t.rating ? (
        <span className="inline-flex items-center gap-0.5">
          · <Star className="h-3 w-3 fill-gold text-gold" strokeWidth={0} /> {t.rating.toFixed(1)}
        </span>
      ) : null}
    </span>
  );
}

function TitleRow({ t, onPick }: { t: Title; onPick: () => void }) {
  const art = thumbOf(t);
  return (
    <button
      onClick={onPick}
      title={`Play ${t.title}`}
      className="group flex w-full cursor-pointer items-start gap-3 rounded-card p-2 text-left transition-colors duration-fast hover:bg-white/[0.05]"
    >
      <span className="relative aspect-video w-[136px] shrink-0 overflow-hidden rounded-lg bg-surface-2 ring-1 ring-line">
        {art && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={art} alt="" loading="lazy" className="h-full w-full object-cover transition-transform duration-base group-hover:scale-[1.04]" />
        )}
      </span>
      <span className="min-w-0 pt-0.5">
        <span className="line-clamp-2 text-[13px] font-semibold leading-[18px] text-ink-2 group-hover:text-ink">{t.title}</span>
        <Meta t={t} />
      </span>
    </button>
  );
}

function TitleList({ items, loading, empty, onPick }: { items?: Title[]; loading: boolean; empty: string; onPick: (t: Title) => void }) {
  if (loading && !items) return <PanelSkeleton />;
  if (!items?.length) return <PanelEmpty>{empty}</PanelEmpty>;
  return (
    <div className="flex flex-col gap-1">
      {items.map((t) => (
        <TitleRow key={`${t.kind}:${t.id}`} t={t} onPick={() => onPick(t)} />
      ))}
    </div>
  );
}

/** A title as a card, for the grid under the player. */
function TitleCard({ t, onPick }: { t: Title; onPick: () => void }) {
  const art = thumbOf(t);
  return (
    <button onClick={onPick} title={`Play ${t.title}`} className="group flex cursor-pointer flex-col text-left">
      <span className="relative aspect-video w-full overflow-hidden rounded-card bg-surface-2 ring-1 ring-line transition-shadow duration-fast group-hover:ring-line-strong">
        {art && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={art} alt="" loading="lazy" className="h-full w-full object-cover transition-transform duration-base group-hover:scale-[1.04]" />
        )}
      </span>
      <span className="mt-2 line-clamp-2 text-sm font-semibold leading-5 text-ink-2 group-hover:text-ink">{t.title}</span>
      <Meta t={t} />
    </button>
  );
}

/** This series' episodes as cards, a season at a time. */
function EpisodeGrid({ id, details, season, episode }: { id: number; details?: Details; season: number; episode: number }) {
  const router = useRouter();
  const [shown, setShown] = useState(season);
  useEffect(() => setShown(season), [season]);
  const eps = useQuery({ queryKey: ["season", id, shown], queryFn: () => tmdb.season(id, shown), enabled: shown > 0 });
  const saved = useLibrary((s) => s.progress[keyOf("tv", id)]);
  const seasons = details?.seasons ?? [];

  const chips =
    seasons.length > 1 ? (
      <div className="row-scroller flex min-w-0 flex-1 gap-1.5 py-0.5">
        {seasons.map((s) => (
          <button
            key={s.number}
            onClick={() => setShown(s.number)}
            aria-pressed={shown === s.number}
            className={clsx(
              "h-8 shrink-0 cursor-pointer rounded-lg border px-3 text-[13px] font-medium transition-colors duration-fast",
              shown === s.number ? "border-accent/60 bg-accent/[0.12] text-ink" : "border-line text-ink-3 hover:border-line-strong hover:text-ink-2",
            )}
          >
            Season {s.number}
          </button>
        ))}
      </div>
    ) : null;

  return (
    <BelowSection title="Episodes" count={eps.data?.length} aside={chips}>
      {eps.isLoading ? (
        <GridSkeleton count={8} />
      ) : eps.isError ? (
        <BelowEmpty>This season&apos;s episodes didn&apos;t load.</BelowEmpty>
      ) : (
        (eps.data ?? []).map((ep) => {
          const isPlaying = shown === season && ep.number === episode;
          const resume = saved && saved.season === shown && saved.episode === ep.number && saved.durationS > 0 ? saved.positionS / saved.durationS : 0;
          return (
            <button
              key={ep.number}
              aria-current={isPlaying ? "true" : undefined}
              onClick={() => !isPlaying && router.replace(watchHref({ kind: "tv", id }, shown, ep.number))}
              title={isPlaying ? "Playing now" : `Play episode ${ep.number}`}
              className={clsx("group flex flex-col text-left", isPlaying ? "cursor-default" : "cursor-pointer")}
            >
              <span
                className={clsx(
                  "relative aspect-video w-full overflow-hidden rounded-card bg-surface-2 transition-shadow duration-fast",
                  isPlaying ? "ring-2 ring-accent" : "ring-1 ring-line group-hover:ring-line-strong",
                )}
              >
                {ep.still && (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={ep.still} alt="" loading="lazy" className="h-full w-full object-cover" />
                )}
                {isPlaying && (
                  <span className="absolute inset-0 flex items-center justify-center gap-1.5 bg-black/55 text-xs font-bold uppercase tracking-wide text-accent-soft">
                    <AudioLines className="h-4 w-4" /> Playing
                  </span>
                )}
                {!isPlaying && resume > 0.02 && (
                  <span className="absolute inset-x-0 bottom-0 h-[3px] bg-black/60">
                    <span className="block h-full bg-accent" style={{ width: `${Math.min(100, resume * 100)}%` }} />
                  </span>
                )}
              </span>
              <span className="mt-2 text-xs text-ink-4">
                Episode {ep.number}
                {ep.runtime ? ` · ${ep.runtime} min` : ""}
              </span>
              <span className={clsx("line-clamp-2 text-sm font-semibold leading-5", isPlaying ? "text-ink" : "text-ink-2 group-hover:text-ink")}>{ep.name}</span>
            </button>
          );
        })
      )}
    </BelowSection>
  );
}

/**
 * Under the player: a series' episodes (season chips beside the heading),
 * or, for a film, more like it.
 */
export function VodBelow({ kind, id, season, episode, details }: { kind: Kind; id: number; season?: number; episode?: number; details?: Details }) {
  const router = useRouter();
  const startHref = useStartHref();
  if (kind === "tv") return <EpisodeGrid id={id} details={details} season={season ?? 1} episode={episode ?? 1} />;
  const similar = details?.similar?.filter((t) => !(t.kind === kind && t.id === id));
  return (
    <BelowSection title="More like this" count={similar?.length}>
      {!details ? (
        <GridSkeleton count={8} />
      ) : similar?.length ? (
        similar.map((t) => <TitleCard key={`${t.kind}:${t.id}`} t={t} onPick={() => router.replace(startHref(t))} />)
      ) : (
        <BelowEmpty>No suggestions for this title.</BelowEmpty>
      )}
    </BelowSection>
  );
}

/** The film and series side panel: more like it (series), popular now, search. */
export function VodPanel({ kind, id, details }: { kind: Kind; id: number; season?: number; episode?: number; details?: Details }) {
  const router = useRouter();
  const startHref = useStartHref();
  const [query, setQuery] = useState("");
  const q = useDebounced(query.trim(), 300);
  // A film's "more like this" is under the player already.
  const first = kind === "tv" ? "more" : "popular";
  const [tab, setTab] = useState(first);
  useEffect(() => setTab(first), [first, id]);

  const results = useQuery({
    queryKey: ["search", q],
    queryFn: ({ signal }) => tmdb.search(q, signal),
    enabled: q.length >= 2,
    staleTime: 5 * 60_000,
  });
  const popular = useQuery({ queryKey: ["trending", "day"], queryFn: () => tmdb.trending("day"), enabled: tab === "popular", staleTime: 30 * 60_000 });

  const tabs: PanelTab[] = useMemo(
    () => [...(kind === "tv" ? [{ id: "more", label: "More like this" }] : []), { id: "popular", label: "Popular now" }],
    [kind],
  );

  const pick = (t: Title) => {
    setQuery("");
    router.replace(startHref(t));
  };
  const others = (list?: Title[]) => list?.filter((t) => !(t.kind === kind && t.id === id));

  return (
    <SidePanel query={query} onQuery={setQuery} placeholder="Search films and series" tabs={tabs} tab={tab} onTab={setTab}>
      {query.trim() ? (
        q.length < 2 || (results.isLoading && q === query.trim()) || q !== query.trim() ? (
          <PanelSkeleton rows={5} />
        ) : (
          <>
            <PanelHeading>Results for &ldquo;{q}&rdquo;</PanelHeading>
            <TitleList items={results.data?.filter((t) => t.poster || t.backdrop).slice(0, 30)} loading={results.isLoading} empty="Nothing found. Check the spelling, or try the original title." onPick={pick} />
          </>
        )
      ) : tab === "more" && kind === "tv" ? (
        <TitleList items={others(details?.similar)} loading={!details} empty="No suggestions for this title." onPick={pick} />
      ) : (
        <TitleList items={others(popular.data)} loading={popular.isLoading} empty="Nothing to show right now." onPick={pick} />
      )}
    </SidePanel>
  );
}
