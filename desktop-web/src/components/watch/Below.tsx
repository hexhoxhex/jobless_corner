"use client";

import clsx from "clsx";

/** One section of the grid under the player: a heading, then cards that fill the width. */
export function BelowSection({ title, count, children, aside }: { title: string; count?: number; children: React.ReactNode; aside?: React.ReactNode }) {
  return (
    <section className="pb-6">
      <div className="mb-3 flex items-center gap-3">
        <h2 className="font-display text-[17px] font-bold tracking-tight text-ink">
          {title}
          {count != null && <span className="ml-2 text-sm font-medium text-ink-4">{count}</span>}
        </h2>
        {aside}
      </div>
      <div className="grid gap-3 [grid-template-columns:repeat(auto-fill,minmax(210px,1fr))]">{children}</div>
    </section>
  );
}

export function BelowEmpty({ children }: { children: React.ReactNode }) {
  return <p className="col-span-full rounded-card border border-dashed border-line px-4 py-6 text-center text-sm text-ink-4">{children}</p>;
}

export function GridSkeleton({ count = 6, height = "aspect-video" }: { count?: number; height?: string }) {
  return (
    <>
      {Array.from({ length: count }, (_, n) => (
        <div key={n} className={clsx("skeleton rounded-card", height)} />
      ))}
    </>
  );
}
