"use client";

import Link from "next/link";
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { Radio } from "lucide-react";
import { mergeSchedule, publishedSchedule, siteSchedule } from "@/lib/live/catalog";
import { homeLive, playableChannels } from "@/lib/live/homeLive";
import { liveHref } from "@/lib/live/href";
import { plain } from "@/lib/text";
import { Row } from "./Row";

/** Big competitions on right now; nothing at all when nothing is on. */
export function LiveNowRow() {
  const pub = useQuery({ queryKey: ["live-schedule", "published"], queryFn: publishedSchedule, staleTime: 5 * 60_000 });
  const site = useQuery({ queryKey: ["live-schedule", "site"], queryFn: siteSchedule, staleTime: 15 * 60_000 });
  const events = useMemo(
    () => (pub.data || site.data ? homeLive(mergeSchedule(site.data, pub.data)) : []),
    [pub.data, site.data],
  );
  if (!events.length) return null;
  return (
    <Row title="Live now" seeAll="/live">
      {events.slice(0, 12).map((e) => {
        const chans = playableChannels(e);
        const first = chans[0];
        return (
          <Link
            key={`${e.title}-${e.start}`}
            href={liveHref(first, { ...e, channels: chans })}
            className="group relative flex h-[150px] w-[300px] shrink-0 cursor-pointer flex-col justify-between overflow-hidden rounded-card border border-live/30 bg-[radial-gradient(120%_120%_at_0%_0%,rgba(229,72,77,.18),transparent_55%),linear-gradient(160deg,#1b1e26,#121318)] p-4 transition-colors duration-base hover:border-live/60"
            title={plain(e.title)}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="inline-flex items-center gap-1.5 rounded bg-live px-1.5 py-0.5 text-[11px] font-bold tracking-wide text-white">
                <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-white" />
                LIVE
              </span>
              <span className="truncate text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-4">{plain(e.category)}</span>
            </div>
            <p className="line-clamp-2 font-display text-base font-bold leading-snug text-ink">{plain(e.title)}</p>
            <p className="flex items-center gap-1.5 truncate text-xs text-ink-3 group-hover:text-ink-2">
              <Radio className="h-3.5 w-3.5 shrink-0" />
              {plain(first.name)}
              {chans.length > 1 ? ` · ${chans.length - 1} more feed${chans.length > 2 ? "s" : ""}` : ""}
            </p>
          </Link>
        );
      })}
    </Row>
  );
}
