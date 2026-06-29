"use client";

import { useEffect, useState } from "react";
import { home } from "@/lib/h5/api";
import type { H5Row } from "@/lib/h5/types";

/**
 * Home screen — loads the H5 catalog rows (Popular Movie, Popular
 * Series, Trending, Premium VIP HD, etc.) and renders each as a
 * horizontal scroll of poster cards.
 *
 * v2.0 of this is the placeholder you see now. Tomorrow we wire the
 * components/ folder (AppShell + RowSection + PosterCard) so this
 * file shrinks to a clean composition.
 */
export default function HomePage() {
  const [rows, setRows] = useState<H5Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    home()
      .then((data) => {
        setRows(data);
        setLoading(false);
      })
      .catch((e: Error) => {
        setError(e.message);
        setLoading(false);
      });
  }, []);

  return (
    <main className="px-6 py-6 max-w-[1400px] mx-auto">
      <h1 className="text-2xl font-bold mb-6">Vijana BaruBaru</h1>
      {loading && (
        <div className="text-text-muted">Loading catalog…</div>
      )}
      {error && (
        <div className="text-danger">Couldn't load: {error}</div>
      )}
      {!loading && !error && rows.length === 0 && (
        <div className="text-text-muted">Empty catalog.</div>
      )}
      <div className="space-y-8">
        {rows.map((row) => (
          <section key={row.title}>
            <h2 className="text-base font-semibold mb-3">{row.title}</h2>
            <div className="flex gap-3 overflow-x-auto pb-2">
              {row.items.map((item) => (
                <a
                  key={item.subjectId}
                  href={`/title/${item.subjectId}`}
                  className="w-[140px] flex-none group"
                >
                  <div className="w-[140px] h-[210px] rounded-card bg-surface-2 overflow-hidden group-hover:ring-2 group-hover:ring-accent transition">
                    {item.coverUrl ? (
                      // Next image optimization is disabled (static export),
                      // plain <img> is fine for remote CDN URLs.
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={item.coverUrl}
                        alt={item.title}
                        className="w-full h-full object-cover"
                      />
                    ) : (
                      <div className="w-full h-full grid place-items-center text-2xl font-bold text-text-hint">
                        {item.title.slice(0, 2).toUpperCase()}
                      </div>
                    )}
                  </div>
                  <div className="mt-2 text-[13px] font-medium truncate">
                    {item.title}
                  </div>
                  <div className="text-[11px] text-text-muted flex gap-2">
                    {item.year && <span>{item.year}</span>}
                    {item.rating && (
                      <span className="text-gold font-semibold">
                        ★ {item.rating.toFixed(1)}
                      </span>
                    )}
                  </div>
                </a>
              ))}
            </div>
          </section>
        ))}
      </div>
    </main>
  );
}
