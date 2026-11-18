"use client";

import { Suspense, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import { CalendarClock, Radio, Search, Tv2 } from "lucide-react";
import { loadChannels, mergeSchedule, publishedSchedule, siteSchedule, type Channel, type LiveEvent } from "@/lib/live/catalog";
import { liveHref } from "@/lib/live/href";
import { isPlaceholderChannel, plain } from "@/lib/text";
import { channelHealth, type Verdict } from "@/lib/live/health";

const LIVE_FOR_S = 2.5 * 3600; // an event counts as on for this long after it starts

function localTime(e: LiveEvent): string {
  if (e.start == null) return e.time;
  return new Date(e.start * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function dayLabel(start: number | null): string {
  if (start == null) return "Today";
  const d = new Date(start * 1000);
  const today = new Date();
  const tomorrow = new Date(Date.now() + 86400_000);
  if (d.toDateString() === today.toDateString()) return "Today";
  if (d.toDateString() === tomorrow.toDateString()) return "Tomorrow";
  return d.toLocaleDateString([], { weekday: "long", day: "numeric", month: "short" });
}

function Tab({ active, onClick, icon: Icon, children }: { active: boolean; onClick: () => void; icon: typeof Radio; children: React.ReactNode }) {
  return (
    <button
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={clsx(
        "flex h-9 cursor-pointer items-center gap-2 rounded-md px-3.5 text-sm font-medium transition-colors duration-fast",
        active ? "bg-surface-4 text-ink" : "text-ink-3 hover:text-ink-2",
      )}
    >
      <Icon className="h-4 w-4" />
      {children}
    </button>
  );
}

function ScheduleView({ query }: { query: string }) {
  // The published list shows at once; the site's own page (fresher, but slow
  // to arrive) is merged in when it lands.
  const pub = useQuery({ queryKey: ["live-schedule", "published"], queryFn: publishedSchedule, staleTime: 5 * 60_000, refetchInterval: 15 * 60_000 });
  const site = useQuery({ queryKey: ["live-schedule", "site"], queryFn: siteSchedule, staleTime: 15 * 60_000, refetchInterval: 15 * 60_000 });
  const data = useMemo(
    () => (pub.data || site.data ? mergeSchedule(site.data, pub.data) : undefined),
    [pub.data, site.data],
  );
  const q = {
    data,
    isLoading: !data && (pub.isLoading || site.isLoading),
    isError: !data && pub.isError && (site.isError || site.data === null),
    refetch: () => {
      pub.refetch();
      site.refetch();
    },
  };
  const [cat, setCat] = useState<string | null>(null);
  const nowS = Date.now() / 1000;

  const events = useMemo(() => {
    const list = (q.data ?? []).filter((e) => e.channels.length > 0);
    const words = query.toLowerCase().trim();
    return list.filter(
      (e) =>
        (!cat || e.category === cat) &&
        (!words || e.title.toLowerCase().includes(words) || e.category.toLowerCase().includes(words) || e.channels.some((c) => c.name.toLowerCase().includes(words))),
    );
  }, [q.data, cat, query]);

  const cats = useMemo(() => {
    const counts = new Map<string, number>();
    for (const e of q.data ?? []) counts.set(e.category, (counts.get(e.category) ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([c]) => c);
  }, [q.data]);

  if (q.isError)
    return (
      <p className="text-sm text-ink-3">
        The schedule didn&apos;t load.{" "}
        <button onClick={() => q.refetch()} className="font-medium text-accent-soft hover:underline">
          Try again
        </button>
      </p>
    );
  if (q.isLoading)
    return (
      <div className="flex flex-col gap-2">
        {Array.from({ length: 8 }, (_, n) => (
          <div key={n} className="skeleton h-16 rounded-card" />
        ))}
      </div>
    );

  let lastDay = "";
  return (
    <div>
      <div className="row-scroller -mx-10 mb-5 flex gap-2 px-10">
        <button
          onClick={() => setCat(null)}
          aria-pressed={!cat}
          className={clsx("h-8 shrink-0 cursor-pointer rounded-full border px-3.5 text-sm font-medium", !cat ? "border-transparent bg-ink text-bg" : "border-line text-ink-3 hover:text-ink-2")}
        >
          All sports
        </button>
        {cats.map((c) => (
          <button
            key={c}
            onClick={() => setCat(c === cat ? null : c)}
            aria-pressed={cat === c}
            className={clsx(
              "h-8 shrink-0 cursor-pointer rounded-full border px-3.5 text-sm font-medium",
              cat === c ? "border-transparent bg-ink text-bg" : "border-line text-ink-3 hover:text-ink-2",
            )}
          >
            {plain(c)}
          </button>
        ))}
      </div>
      {events.length === 0 && <p className="text-sm text-ink-3">No events match.</p>}
      <ol className="flex flex-col gap-1.5">
        {events.map((e, n) => {
          const live = e.start != null && e.start <= nowS && nowS - e.start < LIVE_FOR_S;
          const playable = e.channels.filter((c) => !isPlaceholderChannel(c.name));
          const day = dayLabel(e.start);
          const header = day !== lastDay ? day : null;
          lastDay = day;
          return (
            <li key={`${e.title}-${e.start}-${n}`}>
              {header && <h3 className="mb-2 mt-5 text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-4 first:mt-0">{header}</h3>}
              <div className={clsx("flex items-center gap-4 rounded-card border px-4 py-3", live ? "border-live/40 bg-live/[0.06]" : "border-line bg-surface-2/40")}>
                <div className="w-20 shrink-0 whitespace-nowrap text-center">
                  {live ? (
                    <span className="inline-flex items-center gap-1.5 rounded bg-live px-1.5 py-0.5 text-[11px] font-bold tracking-wide text-white">
                      <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-white" />
                      LIVE
                    </span>
                  ) : (
                    <span className="font-display text-base font-bold tabular text-ink-2">{localTime(e)}</span>
                  )}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-4">{plain(e.category)}</p>
                  <p className="truncate text-sm font-semibold text-ink" title={plain(e.title)}>
                    {plain(e.title)}
                  </p>
                </div>
                <div className="flex max-w-[45%] flex-wrap justify-end gap-1.5">
                  {playable.length === 0 && <span className="text-xs text-ink-4">No channel listed yet</span>}
                  {playable.slice(0, 6).map((c) => (
                    <Link
                      key={c.id}
                      href={liveHref(c, { ...e, channels: playable })}
                      className="flex h-8 cursor-pointer items-center gap-1.5 rounded-full bg-white/[0.07] px-3 text-xs font-medium text-ink-2 transition-colors hover:bg-accent hover:text-white"
                    >
                      <Radio className="h-3.5 w-3.5" />
                      {plain(c.name)}
                    </Link>
                  ))}
                  {playable.length > 6 && <span className="self-center text-xs text-ink-4">+{playable.length - 6}</span>}
                </div>
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();
}

function ChannelCard({ c, verdict }: { c: Channel; verdict?: Verdict }) {
  const [logoOk, setLogoOk] = useState(true);
  const [logoShown, setLogoShown] = useState(false);
  return (
    <Link
      href={liveHref(c)}
      className={clsx(
        "group flex cursor-pointer items-center gap-3 rounded-card border border-line bg-surface-2/40 p-3 transition-colors duration-fast hover:border-line-strong hover:bg-surface-3",
        verdict && !verdict.ok && "opacity-60",
      )}
      title={plain(c.name)}
    >
      <span className="relative flex h-11 w-11 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-white/[0.06] font-display text-sm font-bold text-ink-3">
        {initials(plain(c.name))}
        {c.logo && logoOk && (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={c.logo}
            alt=""
            loading="lazy"
            className={clsx(
              "absolute inset-0 h-full w-full bg-[#1d2027] object-contain p-1 transition-opacity duration-base",
              logoShown ? "opacity-100" : "opacity-0",
            )}
            onLoad={() => setLogoShown(true)}
            onError={() => setLogoOk(false)}
          />
        )}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium text-ink-2 group-hover:text-ink">{plain(c.name)}</span>
        {verdict && (
          <span className={clsx("block text-xs", verdict.ok ? "text-accent-soft" : "text-ink-4")}>
            {verdict.ok ? "Played here today" : "Not reachable last try"}
          </span>
        )}
      </span>
    </Link>
  );
}

const rank = (v?: Verdict) => (v ? (v.ok ? 0 : 2) : 1);

function ChannelsView({ query }: { query: string }) {
  const q = useQuery({ queryKey: ["live-channels"], queryFn: loadChannels, staleTime: 30 * 60_000 });
  const [health] = useState(channelHealth);
  const groups = useMemo(() => {
    const words = query.toLowerCase().trim();
    const by = new Map<string, Channel[]>();
    for (const c of q.data ?? []) {
      if (words && !c.name.toLowerCase().includes(words)) continue;
      const g = by.get(c.group) ?? [];
      g.push(c);
      by.set(c.group, g);
    }
    // Sports first, then the biggest groups.
    return [...by.entries()].sort((a, b) => Number(/sport/i.test(b[0])) - Number(/sport/i.test(a[0])) || b[1].length - a[1].length);
  }, [q.data, query]);

  if (q.isError)
    return (
      <p className="text-sm text-ink-3">
        Channels didn&apos;t load.{" "}
        <button onClick={() => q.refetch()} className="font-medium text-accent-soft hover:underline">
          Try again
        </button>
      </p>
    );
  if (q.isLoading)
    return (
      <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3">
        {Array.from({ length: 18 }, (_, n) => (
          <div key={n} className="skeleton h-[70px] rounded-card" />
        ))}
      </div>
    );
  if (!groups.length) return <p className="text-sm text-ink-3">No channel matches.</p>;
  return (
    <div className="flex flex-col gap-8">
      {groups.map(([g, list]) => (
        <section key={g}>
          <h3 className="mb-3 font-display text-base font-bold text-ink">
            {plain(g)} <span className="ml-1 text-sm font-medium text-ink-4">{list.length}</span>
          </h3>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3">
            {[...list]
              // Channels that played here today first, ones that didn't last.
              .sort((a, b) => rank(health[a.id]) - rank(health[b.id]))
              .map((c) => (
                <ChannelCard key={c.id} c={c} verdict={health[c.id]} />
              ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function LiveView() {
  const params = useSearchParams();
  const router = useRouter();
  const tab = params.get("tab") === "channels" ? "channels" : "schedule";
  const [query, setQuery] = useState("");
  const setTab = (t: string) => router.replace(t === "schedule" ? "/live" : `/live?tab=${t}`, { scroll: false });

  return (
    <div className="px-10 pb-16 pt-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
        <h1 className="font-display text-2xl font-extrabold tracking-tight text-ink">Live TV</h1>
        <div className="flex items-center gap-3">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-4" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={tab === "schedule" ? "Team, league or channel" : "Channel name"}
              aria-label={tab === "schedule" ? "Filter the schedule" : "Filter channels"}
              className="h-9 w-64 rounded-card border border-line bg-surface-2 pl-9 pr-3 text-sm text-ink placeholder:text-ink-4 focus:border-accent/60 focus:outline-none"
            />
          </div>
          <div className="flex gap-1 rounded-card border border-line p-1" role="tablist" aria-label="Live TV">
            <Tab active={tab === "schedule"} onClick={() => setTab("schedule")} icon={CalendarClock}>
              Schedule
            </Tab>
            <Tab active={tab === "channels"} onClick={() => setTab("channels")} icon={Tv2}>
              Channels
            </Tab>
          </div>
        </div>
      </div>
      {tab === "schedule" ? <ScheduleView query={query} /> : <ChannelsView query={query} />}
    </div>
  );
}

export default function LivePage() {
  return (
    <Suspense>
      <LiveView />
    </Suspense>
  );
}
