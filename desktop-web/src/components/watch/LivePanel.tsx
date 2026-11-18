"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import { AudioLines } from "lucide-react";
import { loadChannels, mergeSchedule, publishedSchedule, siteSchedule, type Channel, type LiveEvent } from "@/lib/live/catalog";
import { homeLive, isLiveNow, playableChannels } from "@/lib/live/homeLive";
import { liveHref } from "@/lib/live/href";
import { channelHealth, type Verdict } from "@/lib/live/health";
import { plain } from "@/lib/text";
import { PanelEmpty, PanelHeading, PanelSkeleton, SidePanel, type PanelTab } from "./SidePanel";
import { BelowEmpty, BelowSection, GridSkeleton } from "./Below";

const COMING_UP_S = 12 * 3600;

function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();
}

function ChannelLogo({ c, size = "h-10 w-10" }: { c: { name: string; logo?: string }; size?: string }) {
  const [ok, setOk] = useState(true);
  const [shown, setShown] = useState(false);
  return (
    <span className={clsx("relative flex shrink-0 items-center justify-center overflow-hidden rounded-lg bg-white/[0.06] font-display text-xs font-bold text-ink-3", size)}>
      {initials(plain(c.name))}
      {c.logo && ok && (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={c.logo}
          alt=""
          loading="lazy"
          className={clsx("absolute inset-0 h-full w-full bg-[#1d2027] object-contain p-1 transition-opacity duration-base", shown ? "opacity-100" : "opacity-0")}
          onLoad={() => setShown(true)}
          onError={() => setOk(false)}
        />
      )}
    </span>
  );
}

function ChannelRow({ c, playing, verdict, onPick }: { c: { id: string; name: string; logo?: string }; playing: boolean; verdict?: Verdict; onPick: () => void }) {
  return (
    <button
      onClick={() => !playing && onPick()}
      aria-current={playing ? "true" : undefined}
      title={playing ? "Playing now" : `Watch ${plain(c.name)}`}
      className={clsx(
        "group flex w-full items-center gap-3 rounded-card px-2 py-2 text-left transition-colors duration-fast",
        playing ? "cursor-default bg-accent/[0.10] ring-1 ring-accent/30" : "cursor-pointer hover:bg-white/[0.05]",
        !playing && verdict && !verdict.ok && "opacity-60",
      )}
    >
      <ChannelLogo c={c} />
      <span className="min-w-0 flex-1">
        <span className={clsx("block truncate text-[13px] font-medium", playing ? "text-ink" : "text-ink-2 group-hover:text-ink")}>{plain(c.name)}</span>
        {verdict && !playing && (
          <span className={clsx("block text-xs", verdict.ok ? "text-accent-soft" : "text-ink-4")}>{verdict.ok ? "Played here today" : "Not reachable last try"}</span>
        )}
      </span>
      {playing && (
        <span className="inline-flex items-center gap-1 text-[11px] font-bold uppercase tracking-wide text-accent-soft">
          <AudioLines className="h-3.5 w-3.5" /> Playing
        </span>
      )}
    </button>
  );
}

function LivePill() {
  return (
    <span className="inline-flex items-center gap-1 rounded bg-live px-1.5 py-0.5 text-[10px] font-bold tracking-wide text-white">
      <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-white" />
      LIVE
    </span>
  );
}

function startTime(e: LiveEvent) {
  return e.start != null ? new Date(e.start * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : e.time;
}

function EventRow({ e, current, nowS, onPick }: { e: LiveEvent; current: boolean; nowS: number; onPick: () => void }) {
  const live = isLiveNow(e, nowS);
  const n = playableChannels(e).length;
  return (
    <button
      onClick={onPick}
      title={plain(e.title)}
      className={clsx(
        "group flex w-full cursor-pointer items-center gap-3 rounded-card px-2 py-2.5 text-left transition-colors duration-fast",
        current ? "bg-accent/[0.10] ring-1 ring-accent/30" : "hover:bg-white/[0.05]",
      )}
    >
      <span className="flex w-14 shrink-0 justify-center">{live ? <LivePill /> : <span className="font-display text-sm font-bold tabular text-ink-2">{startTime(e)}</span>}</span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-4">{plain(e.category)}</span>
        <span className={clsx("line-clamp-2 text-[13px] font-semibold leading-[18px]", current ? "text-ink" : "text-ink-2 group-hover:text-ink")}>{plain(e.title)}</span>
        <span className="block text-xs text-ink-4">
          {current ? "Watching now · " : ""}
          {n} channel{n === 1 ? "" : "s"}
        </span>
      </span>
    </button>
  );
}

/** A match or show as a card, for the grid under the player. */
function EventCard({ e, current, nowS, logos, onPick }: { e: LiveEvent; current: boolean; nowS: number; logos: Map<string, string | undefined>; onPick: () => void }) {
  const live = isLiveNow(e, nowS);
  const chans = playableChannels(e);
  return (
    <button
      onClick={onPick}
      title={current ? "Watching now" : `Watch ${plain(e.title)}`}
      aria-current={current ? "true" : undefined}
      className={clsx(
        "group flex min-h-[124px] cursor-pointer flex-col rounded-card border p-3 text-left transition-colors duration-fast",
        current ? "border-accent/40 bg-accent/[0.08]" : "border-line bg-white/[0.025] hover:border-line-strong hover:bg-white/[0.05]",
      )}
    >
      <span className="flex min-w-0 items-center gap-2">
        {live ? <LivePill /> : <span className="font-display text-[13px] font-bold tabular text-ink-2">{startTime(e)}</span>}
        <span className="truncate text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-4">{plain(e.category)}</span>
      </span>
      <span className={clsx("mt-2 line-clamp-2 text-sm font-semibold leading-5", current ? "text-ink" : "text-ink-2 group-hover:text-ink")}>{plain(e.title)}</span>
      <span className="mt-auto flex items-center gap-2 pt-3">
        <span className="flex -space-x-1.5">
          {chans.slice(0, 3).map((c) => (
            <ChannelLogo key={c.id} c={{ name: c.name, logo: logos.get(c.id) }} size="h-6 w-6 ring-2 ring-bg text-[9px]" />
          ))}
        </span>
        <span className="truncate text-xs text-ink-4">
          {current ? (
            <span className="inline-flex items-center gap-1 font-semibold text-accent-soft">
              <AudioLines className="h-3.5 w-3.5" /> Watching now
            </span>
          ) : (
            `${chans.length} channel${chans.length === 1 ? "" : "s"}`
          )}
        </span>
      </span>
    </button>
  );
}

/** The schedule and channel list both the side panel and the grid under the player use. */
function useLiveLists({ eventTitle, ready }: { eventTitle: string | null; ready: boolean }) {
  const router = useRouter();
  const pub = useQuery({ queryKey: ["live-schedule", "published"], queryFn: publishedSchedule, staleTime: 5 * 60_000, refetchInterval: 15 * 60_000 });
  // The site's own page is the fresh one (the published copy can be hours
  // stale), but it is ~650 KB trickled from the same site the stream is
  // found on: fetch it once the channel plays, not while it is being found.
  const site = useQuery({ queryKey: ["live-schedule", "site"], queryFn: siteSchedule, staleTime: 15 * 60_000, refetchInterval: 15 * 60_000, enabled: ready });
  const channels = useQuery({ queryKey: ["live-channels"], queryFn: loadChannels, staleTime: 30 * 60_000 });
  const [nowS, setNowS] = useState(() => Date.now() / 1000);
  useEffect(() => {
    const t = setInterval(() => setNowS(Date.now() / 1000), 60_000);
    return () => clearInterval(t);
  }, []);

  const schedule = useMemo(() => (pub.data || site.data ? mergeSchedule(site.data, pub.data) : undefined), [pub.data, site.data]);

  const { onNow, comingUp } = useMemo(() => {
    const all = (schedule ?? []).filter((e) => playableChannels(e).length > 0);
    const live = all.filter((e) => isLiveNow(e, nowS));
    // The big competitions first, as on Home.
    const top = homeLive(live, nowS);
    const rest = live.filter((e) => !top.includes(e)).sort((a, b) => (a.start ?? 0) - (b.start ?? 0));
    const later = all
      .filter((e) => e.start != null && e.start > nowS && e.start - nowS < COMING_UP_S)
      .sort((a, b) => (a.start ?? 0) - (b.start ?? 0))
      .slice(0, 30);
    return { onNow: [...top, ...rest], comingUp: later };
  }, [schedule, nowS]);

  const logos = useMemo(() => new Map((channels.data ?? []).map((c) => [c.id, c.logo])), [channels.data]);

  const playEvent = (e: LiveEvent) => {
    const chans = playableChannels(e);
    router.replace(liveHref(chans[0], { ...e, channels: chans }));
  };
  const isCurrent = (e: LiveEvent) => !!eventTitle && plain(e.title) === eventTitle;
  // Still finding out what is on: the published copy hasn't come, or the
  // site's page is on its way and nothing is known to be on yet.
  const loading = !schedule || (site.isFetching && !site.data && onNow.length === 0);
  const failed = pub.isError && !site.isFetching && !schedule;
  return { channels, schedule, onNow, comingUp, nowS, logos, playEvent, isCurrent, loading, failed, router };
}

const isShow = (e: LiveEvent) => /tv show/i.test(e.category);

/**
 * Under the player: what is on now as cards, sport first, then the TV
 * shows on the schedule, then what starts in the next hours.
 */
export function LiveBelow({ eventTitle, ready }: { eventTitle: string | null; ready: boolean }) {
  const { onNow, comingUp, nowS, logos, playEvent, isCurrent, loading, failed } = useLiveLists({ eventTitle, ready });
  const sport = onNow.filter((e) => !isShow(e));
  const shows = onNow.filter(isShow);
  const card = (e: LiveEvent, n: number) => (
    <EventCard key={`${e.title}-${e.start}-${n}`} e={e} current={isCurrent(e)} nowS={nowS} logos={logos} onPick={() => playEvent(e)} />
  );
  if (loading) {
    return failed ? (
      <BelowEmpty>The schedule didn&apos;t load.</BelowEmpty>
    ) : (
      <BelowSection title="On now">
        <GridSkeleton count={6} height="h-[124px]" />
      </BelowSection>
    );
  }
  return (
    <>
      <BelowSection title="Live sport" count={sport.length}>
        {sport.length ? sport.map(card) : <BelowEmpty>No sport on right now.</BelowEmpty>}
      </BelowSection>
      {shows.length > 0 && (
        <BelowSection title="TV shows on now" count={shows.length}>
          {shows.map(card)}
        </BelowSection>
      )}
      {comingUp.length > 0 && (
        <BelowSection title="Coming up" count={comingUp.length}>
          {comingUp.map(card)}
        </BelowSection>
      )}
    </>
  );
}

/** The live side panel: this match's other channels, every channel, and search across matches and channels. */
export function LivePanel({
  id,
  eventTitle,
  feeds,
  onPickFeed,
  ready,
}: {
  id: string;
  eventTitle: string | null;
  feeds: [string, string][];
  onPickFeed: (fid: string) => void;
  /** The channel is playing (or has given up): only then fetch the site's own schedule. */
  ready: boolean;
}) {
  const { channels, onNow, comingUp, nowS, playEvent, isCurrent, router } = useLiveLists({ eventTitle, ready });
  const [query, setQuery] = useState("");
  const words = query.toLowerCase().trim();
  const [tab, setTab] = useState(feeds.length > 1 ? "match" : "channels");
  useEffect(() => setTab(feeds.length > 1 ? "match" : "channels"), [eventTitle, feeds.length]);
  const [health] = useState(channelHealth);

  const channelGroups = useMemo(() => {
    const by = new Map<string, Channel[]>();
    for (const c of channels.data ?? []) {
      if (words && !plain(c.name).toLowerCase().includes(words)) continue;
      const g = by.get(c.group) ?? [];
      g.push(c);
      by.set(c.group, g);
    }
    const rank = (v?: Verdict) => (v ? (v.ok ? 0 : 2) : 1);
    return [...by.entries()]
      .sort((a, b) => Number(/sport/i.test(b[0])) - Number(/sport/i.test(a[0])) || b[1].length - a[1].length)
      .map(([g, list]) => [g, [...list].sort((a, b) => rank(health[a.id]) - rank(health[b.id]))] as const);
  }, [channels.data, words, health]);

  const pickEvent = (e: LiveEvent) => {
    setQuery("");
    playEvent(e);
  };
  const playChannel = (c: Channel) => {
    setQuery("");
    // A channel of the match being watched keeps the match's other feeds.
    if (feeds.some((f) => f[0] === c.id)) onPickFeed(c.id);
    else router.replace(liveHref(c));
  };
  const eventMatches = (e: LiveEvent) =>
    plain(e.title).toLowerCase().includes(words) ||
    plain(e.category).toLowerCase().includes(words) ||
    e.channels.some((c) => plain(c.name).toLowerCase().includes(words));

  const tabs: PanelTab[] = [
    ...(feeds.length > 1 ? [{ id: "match", label: "This match", count: feeds.length }] : []),
    { id: "channels", label: "Channels", count: channels.data?.length },
  ];

  let body: React.ReactNode;
  if (words) {
    const evs = [...onNow, ...comingUp].filter(eventMatches);
    const chanCount = channelGroups.reduce((n, [, l]) => n + l.length, 0);
    body = (
      <>
        {evs.length > 0 && (
          <>
            <PanelHeading>Matches and shows</PanelHeading>
            <div className="flex flex-col gap-1">
              {evs.slice(0, 20).map((e, n) => (
                <EventRow key={`${e.title}-${e.start}-${n}`} e={e} current={isCurrent(e)} nowS={nowS} onPick={() => pickEvent(e)} />
              ))}
            </div>
          </>
        )}
        {chanCount > 0 && (
          <>
            <PanelHeading>Channels</PanelHeading>
            <div className="flex flex-col gap-1">
              {channelGroups
                .flatMap(([, l]) => l)
                .slice(0, 60)
                .map((c) => (
                  <ChannelRow key={c.id} c={c} playing={c.id === id} verdict={health[c.id]} onPick={() => playChannel(c)} />
                ))}
            </div>
          </>
        )}
        {evs.length === 0 && chanCount === 0 && <PanelEmpty>No match or channel by that name.</PanelEmpty>}
      </>
    );
  } else if (tab === "match" && feeds.length > 1) {
    const logos = new Map((channels.data ?? []).map((c) => [c.id, c.logo]));
    body = (
      <>
        {eventTitle && <PanelHeading>{eventTitle}</PanelHeading>}
        <div className="flex flex-col gap-1">
          {feeds.map(([fid, fname]) => (
            <ChannelRow key={fid} c={{ id: fid, name: fname, logo: logos.get(fid) }} playing={fid === id} verdict={health[fid]} onPick={() => onPickFeed(fid)} />
          ))}
        </div>
      </>
    );
  } else {
    body = channels.isLoading ? (
      <PanelSkeleton rows={10} thumb="h-[56px]" />
    ) : channels.isError ? (
      <PanelEmpty>Channels didn&apos;t load.</PanelEmpty>
    ) : (
      channelGroups.map(([g, list]) => (
        <div key={g}>
          <PanelHeading>
            {plain(g)} <span className="ml-1 font-medium normal-case tracking-normal">{list.length}</span>
          </PanelHeading>
          <div className="flex flex-col gap-1">
            {list.map((c) => (
              <ChannelRow key={c.id} c={c} playing={c.id === id} verdict={health[c.id]} onPick={() => playChannel(c)} />
            ))}
          </div>
        </div>
      ))
    );
  }

  return (
    <SidePanel query={query} onQuery={setQuery} placeholder="Search matches and channels" tabs={tabs} tab={tab} onTab={setTab}>
      {body}
    </SidePanel>
  );
}
