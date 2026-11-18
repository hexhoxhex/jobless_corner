"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Search, X } from "lucide-react";
import { tmdb } from "@/lib/tmdb/client";
import { PosterCard, PosterSkeleton } from "@/components/PosterCard";

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

function SearchView() {
  const params = useSearchParams();
  const router = useRouter();
  const [text, setText] = useState(params.get("q") ?? "");
  const query = useDebounced(text.trim(), 300);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => input.current?.focus(), []);
  useEffect(() => {
    router.replace(query ? `/search?q=${encodeURIComponent(query)}` : "/search", { scroll: false });
  }, [query, router]);

  const q = useQuery({
    queryKey: ["search", query],
    queryFn: ({ signal }) => tmdb.search(query, signal),
    enabled: query.length >= 2,
    staleTime: 5 * 60_000,
  });
  const results = (q.data ?? []).filter((t) => t.poster);

  return (
    <div className="px-10 pb-16 pt-8">
      <label htmlFor="search" className="mb-3 block font-display text-2xl font-extrabold tracking-tight text-ink">
        Search
      </label>
      <div className="relative mb-8 max-w-2xl">
        <Search className="pointer-events-none absolute left-4 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-4" />
        <input
          id="search"
          ref={input}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && setText("")}
          placeholder="Films and series by title"
          autoComplete="off"
          spellCheck={false}
          className="h-12 w-full rounded-panel border border-line bg-surface-2 pl-12 pr-11 text-base text-ink placeholder:text-ink-4 transition-colors focus:border-accent/60 focus:outline-none"
        />
        {text && (
          <button
            onClick={() => {
              setText("");
              input.current?.focus();
            }}
            aria-label="Clear search"
            className="absolute right-3 top-1/2 flex h-7 w-7 -translate-y-1/2 cursor-pointer items-center justify-center rounded-full text-ink-3 hover:bg-white/10 hover:text-ink"
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </div>

      {query.length < 2 ? (
        <p className="text-sm text-ink-4">Type at least two letters.</p>
      ) : q.isError ? (
        <p className="text-sm text-ink-3">
          Search didn&apos;t answer.{" "}
          <button onClick={() => q.refetch()} className="font-medium text-accent-soft hover:underline">
            Try again
          </button>
        </p>
      ) : q.isLoading ? (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-x-5 gap-y-7">
          {Array.from({ length: 12 }, (_, n) => (
            <PosterSkeleton key={n} width="fill" />
          ))}
        </div>
      ) : results.length === 0 ? (
        <p className="text-sm text-ink-3">Nothing matches &ldquo;{query}&rdquo;. Check the spelling, or try fewer words.</p>
      ) : (
        <>
          <p className="mb-4 text-sm text-ink-4">
            {results.length} result{results.length === 1 ? "" : "s"}
          </p>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-x-5 gap-y-7">
            {results.map((t) => (
              <PosterCard key={`${t.kind}${t.id}`} t={t} width="fill" />
            ))}
          </div>
        </>
      )}
    </div>
  );
}

export default function SearchPage() {
  return (
    <Suspense>
      <SearchView />
    </Suspense>
  );
}
