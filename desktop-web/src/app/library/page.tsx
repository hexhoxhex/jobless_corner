"use client";

import Link from "next/link";
import { Bookmark } from "lucide-react";
import { ContinueCard } from "@/components/ContinueCard";
import { PosterCard } from "@/components/PosterCard";
import { continueList, useLibrary } from "@/lib/store/library";

export default function LibraryPage() {
  const progress = useLibrary((s) => s.progress);
  const saved = useLibrary((s) => s.saved);
  const resume = continueList(progress);
  const list = Object.values(saved).sort((a, b) => b.addedAt - a.addedAt);

  return (
    <div className="px-10 pb-16 pt-8">
      <h1 className="mb-8 font-display text-2xl font-extrabold tracking-tight text-ink">My list</h1>
      {resume.length > 0 && (
        <section className="mb-10">
          <h2 className="mb-3 font-display text-lg font-bold text-ink">Continue watching</h2>
          <div className="flex flex-wrap gap-4">
            {resume.map((p) => (
              <ContinueCard key={p.key} p={p} />
            ))}
          </div>
        </section>
      )}
      <section>
        <h2 className="mb-3 font-display text-lg font-bold text-ink">Saved</h2>
        {list.length === 0 ? (
          <div className="flex max-w-lg items-start gap-4 rounded-panel border border-line p-6">
            <Bookmark className="mt-0.5 h-5 w-5 shrink-0 text-ink-4" />
            <div>
              <p className="text-sm font-medium text-ink-2">Nothing saved yet</p>
              <p className="mt-1 text-sm text-ink-3">
                Use <span className="font-medium text-ink-2">My list</span> on any film or series to keep it here.{" "}
                <Link href="/" className="font-medium text-accent-soft hover:underline">
                  Browse
                </Link>
              </p>
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-x-5 gap-y-7">
            {list.map((s) => (
              <PosterCard
                key={s.key}
                width="fill"
                t={{ id: s.id, kind: s.kind, title: s.title, poster: s.poster, year: s.year, overview: "", genreIds: [] }}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
