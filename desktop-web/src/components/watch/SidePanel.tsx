"use client";

import { useEffect, useRef, useState } from "react";
import clsx from "clsx";
import { Search, X } from "lucide-react";

export function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

export type PanelTab = { id: string; label: string; count?: number };

/**
 * The side panel's frame: a search box, tabs, and the list. Ctrl K or "/"
 * puts the cursor in the search box (on the watch page they search here,
 * not on the search page, so the picture keeps playing).
 */
export function SidePanel({
  query,
  onQuery,
  placeholder,
  tabs,
  tab,
  onTab,
  children,
}: {
  query: string;
  onQuery: (q: string) => void;
  placeholder: string;
  tabs: PanelTab[];
  tab: string;
  onTab: (id: string) => void;
  children: React.ReactNode;
}) {
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable);
      if (((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") || (e.key === "/" && !typing)) {
        e.preventDefault();
        input.current?.focus();
        input.current?.select();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const searching = query.trim().length > 0;
  return (
    <>
      <div className="shrink-0 border-b border-line p-3">
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-4" />
          <input
            ref={input}
            value={query}
            onChange={(e) => onQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                e.stopPropagation();
                if (query) onQuery("");
                else input.current?.blur();
              }
            }}
            placeholder={placeholder}
            aria-label={placeholder}
            className="h-10 w-full rounded-[10px] border border-line bg-surface-2/70 pl-9 pr-9 text-sm text-ink placeholder:text-ink-4 transition-colors duration-fast focus:border-accent/60 focus:bg-surface-2 focus:outline-none"
          />
          {searching ? (
            <button
              onClick={() => {
                onQuery("");
                input.current?.focus();
              }}
              aria-label="Clear search"
              className="absolute right-1.5 top-1/2 flex h-7 w-7 -translate-y-1/2 cursor-pointer items-center justify-center rounded-md text-ink-4 hover:bg-white/10 hover:text-ink-2"
            >
              <X className="h-4 w-4" />
            </button>
          ) : (
            <kbd className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 rounded border border-line px-1.5 font-sans text-[11px] font-medium text-ink-4">
              Ctrl K
            </kbd>
          )}
        </div>
        {!searching && tabs.length > 1 && (
          <div role="tablist" className="row-scroller -mx-3 mt-3 flex gap-1.5 px-3">
            {tabs.map((t) => (
              <button
                key={t.id}
                role="tab"
                aria-selected={tab === t.id}
                onClick={() => onTab(t.id)}
                className={clsx(
                  "flex h-8 shrink-0 cursor-pointer items-center gap-1.5 rounded-full px-3.5 text-[13px] font-medium transition-colors duration-fast",
                  tab === t.id ? "bg-ink text-bg" : "bg-white/[0.06] text-ink-3 hover:bg-white/10 hover:text-ink-2",
                )}
              >
                {t.label}
                {t.count != null && <span className={clsx("tabular text-xs", tab === t.id ? "text-bg/60" : "text-ink-4")}>{t.count}</span>}
              </button>
            ))}
          </div>
        )}
      </div>
      <div className="scroll-area min-h-0 flex-1 p-2">{children}</div>
    </>
  );
}

export function PanelHeading({ children }: { children: React.ReactNode }) {
  return <p className="px-2 pb-1.5 pt-3 text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-4 first:pt-1">{children}</p>;
}

export function PanelEmpty({ children }: { children: React.ReactNode }) {
  return <p className="px-3 py-6 text-center text-sm text-ink-4">{children}</p>;
}

export function PanelSkeleton({ rows = 6, thumb = "h-[76px]" }: { rows?: number; thumb?: string }) {
  return (
    <div className="flex flex-col gap-2 p-1">
      {Array.from({ length: rows }, (_, n) => (
        <div key={n} className={clsx("skeleton rounded-card", thumb)} />
      ))}
    </div>
  );
}
