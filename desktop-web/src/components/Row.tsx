"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import clsx from "clsx";

/** A titled row that scrolls sideways: arrow buttons appear on hover when
 *  there is more to see in that direction. */
export function Row({
  title,
  seeAll,
  children,
  loading,
  error,
  onRetry,
}: {
  title: string;
  seeAll?: string;
  children: React.ReactNode;
  loading?: boolean;
  error?: boolean;
  onRetry?: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [edges, setEdges] = useState({ left: false, right: false });

  const measure = () => {
    const el = ref.current;
    if (!el) return;
    setEdges({ left: el.scrollLeft > 8, right: el.scrollLeft + el.clientWidth < el.scrollWidth - 8 });
  };

  useEffect(() => {
    measure();
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [children]);

  const page = (dir: 1 | -1) => {
    const el = ref.current;
    if (el) el.scrollBy({ left: dir * el.clientWidth * 0.85, behavior: "smooth" });
  };

  return (
    <section className="group/row relative animate-rise">
      <div className="mb-3 flex items-baseline justify-between px-10">
        <h2 className="font-display text-lg font-bold tracking-tight text-ink">{title}</h2>
        {seeAll && (
          <Link href={seeAll} className="text-sm font-medium text-ink-3 transition-colors hover:text-accent-soft">
            See all
          </Link>
        )}
      </div>
      {error ? (
        <div className="mx-10 flex h-24 items-center gap-4 rounded-card border border-line px-5 text-sm text-ink-3">
          This row didn&apos;t load.
          {onRetry && (
            <button onClick={onRetry} className="font-medium text-accent-soft hover:underline">
              Try again
            </button>
          )}
        </div>
      ) : (
        <div className="relative">
          <div ref={ref} onScroll={measure} className="row-scroller flex gap-4 px-10 pb-2" aria-busy={loading}>
            {children}
          </div>
          {(["left", "right"] as const).map((side) => (
            <button
              key={side}
              aria-label={side === "left" ? `Scroll ${title} left` : `Scroll ${title} right`}
              onClick={() => page(side === "left" ? -1 : 1)}
              className={clsx(
                "absolute top-0 z-10 flex h-[calc(100%-46px)] w-10 cursor-pointer items-center justify-center bg-gradient-to-r opacity-0 transition-opacity duration-base group-hover/row:opacity-100",
                side === "left" ? "left-0 from-bg to-transparent" : "right-0 from-transparent to-bg",
                !edges[side] && "pointer-events-none !opacity-0",
              )}
            >
              <span className="flex h-9 w-9 items-center justify-center rounded-full bg-surface-4/90 text-ink shadow-lg">
                {side === "left" ? <ChevronLeft className="h-5 w-5" /> : <ChevronRight className="h-5 w-5" />}
              </span>
            </button>
          ))}
        </div>
      )}
    </section>
  );
}
