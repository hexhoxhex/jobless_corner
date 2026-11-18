"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import { Search, X } from "lucide-react";
import { tmdb, type Kind } from "@/lib/tmdb/client";
import { PosterCard, PosterSkeleton } from "./PosterCard";

/** Ctrl K / "/" asks the page's search bar to take focus. */
export const FOCUS_SEARCH = "vbb:focus-search";

let bars = 0;
/** A section search bar is on screen (else Ctrl K goes to Home's). */
export function hasSectionBar(): boolean {
  return bars > 0;
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

/**
 * The section's search text, kept in the address as ?q= so Back from a
 * title returns to the same results. [q] is the settled text (300 ms).
 */
export function useSectionQuery() {
  const params = useSearchParams();
  const router = useRouter();
  const path = usePathname();
  const [text, setText] = useState(params.get("q") ?? "");
  const q = useDebounced(text.trim(), 300);
  useEffect(() => {
    if ((params.get("q") ?? "") === q) return;
    const next = new URLSearchParams(params.toString());
    if (q) next.set("q", q);
    else next.delete("q");
    next.delete("focus");
    const s = next.toString();
    router.replace(s ? `${path}?${s}` : path, { scroll: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q]);
  return { text, setText, q, searching: q.length >= 2 };
}

/**
 * The search bar at the top of a section, floating over the page as it
 * scrolls: what is typed shows its results right here, in place of the
 * section, and clearing it brings the section back. [overlay]: it sits over
 * a picture (Home's billboard) and only takes a background once scrolled.
 */
export function SectionBar({
  value,
  onChange,
  placeholder,
  right,
  overlay = false,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  right?: React.ReactNode;
  overlay?: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  const params = useSearchParams();
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    bars++;
    const focus = () => {
      input.current?.focus();
      input.current?.select();
    };
    window.addEventListener(FOCUS_SEARCH, focus);
    if (params.get("focus") === "1") focus();
    const main = document.getElementById("main");
    const onScroll = () => setScrolled((main?.scrollTop ?? 0) > 8);
    main?.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
    return () => {
      bars--;
      window.removeEventListener(FOCUS_SEARCH, focus);
      main?.removeEventListener("scroll", onScroll);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      className={clsx(
        "sticky top-0 z-20 transition-[background-color,border-color] duration-base",
        overlay ? "-mb-16" : "",
        scrolled || value ? "border-b border-line bg-bg/90 backdrop-blur-md" : "border-b border-transparent",
        overlay && !scrolled && !value && "bg-gradient-to-b from-bg/80 to-transparent",
      )}
    >
      <div className="flex h-16 items-center gap-4 px-10">
        <div className="relative w-full max-w-[520px]">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-ink-3" />
          <input
            ref={input}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                if (value) onChange("");
                else input.current?.blur();
              }
            }}
            placeholder={placeholder}
            aria-label={placeholder}
            autoComplete="off"
            spellCheck={false}
            className="peer h-10 w-full rounded-[10px] border border-line bg-surface pl-10 pr-20 text-[14px] text-ink placeholder:text-ink-4 transition-colors duration-fast hover:border-line-strong focus:border-accent/70 focus:outline-none"
          />
          {value ? (
            <button
              onClick={() => {
                onChange("");
                input.current?.focus();
              }}
              aria-label="Clear search"
              className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 cursor-pointer items-center justify-center rounded-md text-ink-3 hover:bg-white/10 hover:text-ink"
            >
              <X className="h-4 w-4" />
            </button>
          ) : (
            <kbd className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 font-sans text-[11px] font-medium text-ink-4 peer-focus:opacity-0">Ctrl K</kbd>
          )}
        </div>
        {right && <div className="ml-auto flex shrink-0 items-center gap-2">{right}</div>}
      </div>
    </div>
  );
}

/** Films and/or series matching [q], as a poster grid, in place of the section. */
export function SearchResults({ q, kind }: { q: string; kind?: Kind }) {
  const res = useQuery({
    queryKey: ["search", q],
    queryFn: ({ signal }) => tmdb.search(q, signal),
    enabled: q.length >= 2,
    staleTime: 5 * 60_000,
  });
  const items = (res.data ?? []).filter((t) => t.poster && (!kind || t.kind === kind));
  const what = kind === "movie" ? "films" : kind === "tv" ? "series" : "films and series";
  const count = (n: number) => (kind === "movie" ? (n === 1 ? "film" : "films") : kind === "tv" ? "series" : n === 1 ? "title" : "titles");

  return (
    <section className="px-10 pb-16 pt-6" aria-live="polite">
      {res.isError ? (
        <p className="text-sm text-ink-3">
          Search didn&apos;t answer.{" "}
          <button onClick={() => res.refetch()} className="cursor-pointer font-medium text-accent-soft hover:underline">
            Try again
          </button>
        </p>
      ) : res.isLoading ? (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-x-5 gap-y-7">
          {Array.from({ length: 12 }, (_, n) => (
            <PosterSkeleton key={n} width="fill" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <p className="text-sm text-ink-3">
          No {what} called &ldquo;{q}&rdquo;. Check the spelling, or try fewer words.
        </p>
      ) : (
        <>
          <p className="mb-5 text-sm text-ink-3">
            <span className="font-semibold text-ink-2">{items.length}</span> {count(items.length)} for &ldquo;{q}&rdquo;
          </p>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-x-5 gap-y-7">
            {items.map((t) => (
              <PosterCard key={`${t.kind}${t.id}`} t={t} width="fill" />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
