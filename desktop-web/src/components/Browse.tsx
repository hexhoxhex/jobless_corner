"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef } from "react";
import clsx from "clsx";
import { tmdb, type Kind } from "@/lib/tmdb/client";
import { PosterCard, PosterSkeleton } from "./PosterCard";
import { SearchResults, SectionBar, useSectionQuery } from "./SectionSearch";

const SORTS = [
  { id: "popularity.desc", label: "Popular" },
  { id: "vote_average.desc", label: "Top rated" },
  { id: "newest", label: "Newest" },
] as const;

const NETWORK_NAMES: Record<string, string> = {
  "213": "Netflix",
  "49": "HBO",
  "2739": "Disney+",
  "1024": "Prime Video",
  "2552": "Apple TV+",
};

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={clsx(
        "h-9 shrink-0 cursor-pointer rounded-full border px-4 text-sm font-medium transition-colors duration-fast",
        active ? "border-transparent bg-ink text-bg" : "border-line text-ink-3 hover:border-line-strong hover:text-ink-2",
      )}
    >
      {children}
    </button>
  );
}

/** Movies or Series: a search bar over the section, genre chips, a sort, and a grid that loads more as it nears the end. */
export function Browse({ kind }: { kind: Kind }) {
  const params = useSearchParams();
  const router = useRouter();
  const search = useSectionQuery();
  const genre = params.get("genre") ? Number(params.get("genre")) : undefined;
  const sort = params.get("sort") ?? "popularity.desc";
  const network = params.get("network") ?? undefined;

  const set = (k: string, v?: string) => {
    const q = new URLSearchParams(params.toString());
    if (v) q.set(k, v);
    else q.delete(k);
    router.replace(`?${q}`, { scroll: false });
    document.getElementById("main")?.scrollTo({ top: 0 });
  };

  const genres = useQuery({ queryKey: ["genres", kind], queryFn: () => tmdb.genres(kind), staleTime: 24 * 3600_000 });
  const sortBy = sort === "newest" ? (kind === "movie" ? "primary_release_date.desc" : "first_air_date.desc") : sort;

  const q = useInfiniteQuery({
    queryKey: ["browse", kind, genre, sortBy, network],
    initialPageParam: 1,
    queryFn: ({ pageParam }) =>
      tmdb.discover(kind, {
        genre,
        sort: sortBy,
        page: pageParam,
        network: network ? Number(network) : undefined,
      }),
    getNextPageParam: (last, all) => (last.length > 0 && all.length < 25 ? all.length + 1 : undefined),
  });

  const sentinel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (es) => {
        if (es[0].isIntersecting && q.hasNextPage && !q.isFetchingNextPage) q.fetchNextPage();
      },
      { root: document.getElementById("main"), rootMargin: "800px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [q]);

  const seen = new Set<number>();
  const items = (q.data?.pages.flat() ?? []).filter((t) => (seen.has(t.id) ? false : (seen.add(t.id), true)));
  const heading = network ? NETWORK_NAMES[network] ?? "Series" : kind === "movie" ? "Movies" : "Series";

  const sorter = (
    <div className="flex gap-1 rounded-card border border-line p-1" role="group" aria-label="Sort">
      {SORTS.map((s) => (
        <button
          key={s.id}
          onClick={() => set("sort", s.id === "popularity.desc" ? undefined : s.id)}
          aria-pressed={sort === s.id}
          className={clsx(
            "h-8 cursor-pointer rounded-md px-3 text-sm font-medium transition-colors duration-fast",
            sort === s.id ? "bg-surface-4 text-ink" : "text-ink-3 hover:text-ink-2",
          )}
        >
          {s.label}
        </button>
      ))}
    </div>
  );

  return (
    <>
      <SectionBar
        value={search.text}
        onChange={search.setText}
        placeholder={kind === "movie" ? "Search films" : "Search series"}
        right={search.searching ? undefined : sorter}
      />
      {search.searching ? (
        <SearchResults q={search.q} kind={kind} />
      ) : (
    <div className="px-10 pb-16 pt-4">
      <h1 className="mb-5 font-display text-[26px] font-extrabold tracking-tight text-ink">{heading}</h1>
      <div className="row-scroller -mx-10 mb-7 flex gap-2 px-10">
        <Chip active={!genre} onClick={() => set("genre")}>
          All
        </Chip>
        {(genres.data ?? []).map((g) => (
          <Chip key={g.id} active={genre === g.id} onClick={() => set("genre", String(g.id))}>
            {g.name}
          </Chip>
        ))}
      </div>

      {q.isError ? (
        <div className="flex items-center gap-4 rounded-card border border-line p-6 text-sm text-ink-3">
          Couldn&apos;t load this list.
          <button onClick={() => q.refetch()} className="font-medium text-accent-soft hover:underline">
            Try again
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-x-5 gap-y-7">
          {items.map((t) => (
            <PosterCard key={t.id} t={t} width="fill" />
          ))}
          {(q.isLoading || q.isFetchingNextPage) &&
            Array.from({ length: 12 }, (_, n) => <PosterSkeleton key={`s${n}`} width="fill" />)}
        </div>
      )}
      <div ref={sentinel} className="h-4" />
    </div>
      )}
    </>
  );
}
