"use client";

import { useQuery } from "@tanstack/react-query";
import { Hero } from "@/components/Hero";
import { HeroSkeleton } from "@/components/Hero";
import { Row } from "@/components/Row";
import { PosterCard, PosterSkeleton } from "@/components/PosterCard";
import { ContinueCard } from "@/components/ContinueCard";
import { LiveNowRow } from "@/components/LiveNowRow";
import { Networks, tmdb, type Title } from "@/lib/tmdb/client";
import { continueList, useLibrary } from "@/lib/store/library";

type RowDef = { key: string; title: string; seeAll?: string; load: () => Promise<Title[]>; ranked?: boolean };

const ROWS: RowDef[] = [
  { key: "trending", title: "Trending now", load: () => tmdb.trending("day"), ranked: true },
  { key: "pop-movie", title: "Popular movies", seeAll: "/movies", load: () => tmdb.popular("movie") },
  { key: "pop-tv", title: "Popular series", seeAll: "/series", load: () => tmdb.popular("tv") },
  { key: "now", title: "New in cinemas", load: () => tmdb.nowPlaying() },
  { key: "netflix", title: "Netflix", seeAll: "/series?network=213", load: () => tmdb.discover("tv", { network: Networks.NETFLIX }) },
  { key: "hbo", title: "HBO", seeAll: "/series?network=49", load: () => tmdb.discover("tv", { network: Networks.HBO }) },
  { key: "disney", title: "Disney+", seeAll: "/series?network=2739", load: () => tmdb.discover("tv", { network: Networks.DISNEY_PLUS }) },
  { key: "prime", title: "Prime Video", seeAll: "/series?network=1024", load: () => tmdb.discover("tv", { network: Networks.PRIME }) },
  { key: "apple", title: "Apple TV+", seeAll: "/series?network=2552", load: () => tmdb.discover("tv", { network: Networks.APPLE_TV }) },
  { key: "top-movie", title: "Top rated movies", load: () => tmdb.topRated("movie") },
];

function CatalogRow({ def }: { def: RowDef }) {
  const q = useQuery({ queryKey: ["row", def.key], queryFn: def.load });
  return (
    <Row title={def.title} seeAll={def.seeAll} loading={q.isLoading} error={q.isError} onRetry={() => q.refetch()}>
      {q.isLoading
        ? Array.from({ length: 9 }, (_, n) => <PosterSkeleton key={n} />)
        : (q.data ?? []).slice(0, 20).map((t, n) => <PosterCard key={`${t.kind}${t.id}`} t={t} rank={def.ranked ? n + 1 : undefined} />)}
    </Row>
  );
}

export default function HomePage() {
  const hero = useQuery({ queryKey: ["row", "trending"], queryFn: () => tmdb.trending("day") });
  const progress = useLibrary((s) => s.progress);
  const resume = continueList(progress);

  return (
    <div className="pb-16">
      {hero.isLoading ? <HeroSkeleton /> : <Hero items={(hero.data ?? []).filter((t) => t.backdrop)} />}
      <div className="relative -mt-6 flex flex-col gap-9">
        {resume.length > 0 && (
          <Row title="Continue watching">
            {resume.map((p) => (
              <ContinueCard key={p.key} p={p} />
            ))}
          </Row>
        )}
        <LiveNowRow />
        {ROWS.map((r) => (
          <CatalogRow key={r.key} def={r} />
        ))}
      </div>
    </div>
  );
}
