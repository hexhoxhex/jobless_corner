"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import clsx from "clsx";
import { ArrowLeft, Loader2, Maximize, Maximize2, Minimize, Minimize2, Pause, Play, Radio, RotateCw, Volume1, Volume2, VolumeX } from "lucide-react";
import { SiteSilentError, resolveChannel, type LiveStream } from "@/lib/live/resolver";
import { hostOf } from "@/lib/net/relay";
import { attachLive, type LiveAttached } from "@/lib/live/attachLive";
import { isFullscreen, setFullscreen } from "@/lib/player/fullscreen";
import { Menu } from "@/components/player/Menu";
import { WindowControls, usePlatform } from "@/components/TitleBar";
import { plain } from "@/lib/text";
import { recordChannel } from "@/lib/live/health";
import { WatchFrame, keyIsForPanel, readFillLayout, saveFillLayout } from "@/components/watch/WatchFrame";
import { LiveBelow, LivePanel } from "@/components/watch/LivePanel";

const HIDE_AFTER_MS = 3000;

type Phase =
  | { kind: "resolving"; route: string; reconnect: boolean }
  | { kind: "playing" }
  | { kind: "failed"; reason: string };

function readVolume(): { v: number; muted: boolean } {
  try {
    const raw = localStorage.getItem("player_volume");
    if (raw) return JSON.parse(raw);
  } catch {
    /* ignore */
  }
  return { v: 1, muted: false };
}

function LiveWatch() {
  const params = useSearchParams();
  const router = useRouter();
  const id = params.get("id") ?? "";
  const name = plain(params.get("name") ?? "Live");
  const eventTitle = params.get("event") ? plain(params.get("event")!) : null;
  let feeds: [string, string][] = [];
  try {
    feeds = JSON.parse(params.get("feeds") ?? "[]");
  } catch {
    feeds = [];
  }

  const video = useRef<HTMLVideoElement>(null);
  const attached = useRef<LiveAttached | null>(null);
  const ctl = useRef<AbortController | null>(null);
  /** Routes left out of the next resolve: each died twice in a short while. */
  const triedRoutes = useRef<string[]>([]);
  const deaths = useRef<Record<string, number[]>>({});
  const startRef = useRef<(reconnect: boolean) => void>(() => undefined);

  const [phase, setPhase] = useState<Phase>({ kind: "resolving", route: "", reconnect: false });
  const [stream, setStream] = useState<LiveStream | null>(null);
  const [paused, setPaused] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [behind, setBehind] = useState(0);
  const [vol, setVol] = useState(readVolume);
  const [fs, setFs] = useState(false);
  const [chrome, setChrome] = useState(true);
  // The picture over the whole window (T), or beside the side panel.
  const [layoutFill, setLayoutFill] = useState(readFillLayout);
  const fill = layoutFill || fs;
  const toggleLayout = useCallback(() => setLayoutFill((f) => !f), []);
  useEffect(() => saveFillLayout(layoutFill), [layoutFill]);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const start = useCallback(
    async (reconnect: boolean) => {
      ctl.current?.abort();
      const ac = new AbortController();
      ctl.current = ac;
      attached.current?.destroy();
      attached.current = null;
      setPhase({ kind: "resolving", route: "", reconnect });
      const t0 = Date.now();
      const log = (m: string) => console.info(`live ${id} +${((Date.now() - t0) / 1000).toFixed(1)}s: ${m}`);
      let switching = false;

      const play = async (s: LiveStream) => {
        setStream(s);
        log(`attaching ${s.route} (${s.how})`);
        attached.current?.destroy();
        attached.current = await attachLive(video.current!, s, {
          onLog: log,
          onFatal: (reason) => {
            console.warn(`live ${id} on ${s.route}: ${reason}`);
            // A route gets one fresh start (new token, new playlist: cheap
            // now that its player page is remembered); dying again soon
            // after sends it to the back for this channel.
            const now = Date.now();
            const recent = (deaths.current[s.route] ?? []).filter((t) => now - t < 3 * 60_000);
            deaths.current[s.route] = [...recent, now];
            if (recent.length >= 1 && !triedRoutes.current.includes(s.route)) triedRoutes.current.push(s.route);
            startRef.current(true);
          },
          onSlow: (why) => {
            log(`slow: ${why}`);
            if (switching || ac.signal.aborted) return;
            switching = true;
            // Keep playing this one while a faster route is looked for.
            resolveChannel(id, { signal: ac.signal, skip: [...triedRoutes.current, s.route], log })
              .then((alt) => {
                if (ac.signal.aborted) return;
                log(`switching to the ${alt.route} route (${hostOf(alt.url)})`);
                return play(alt).then(() => video.current?.play().catch(() => undefined));
              })
              .catch(() => log("no faster route answers; staying on this one"));
          },
        });
      };

      try {
        const s = await resolveChannel(id, {
          signal: ac.signal,
          skip: triedRoutes.current,
          onRoute: (route) => setPhase({ kind: "resolving", route, reconnect }),
          log,
        });
        if (ac.signal.aborted) return;
        await play(s);
        setPhase({ kind: "playing" });
        recordChannel(id, true);
        video.current!.play().catch(() => setPaused(true));
      } catch (e) {
        if (ac.signal.aborted) return;
        if (e instanceof SiteSilentError) {
          // Not this channel's fault: don't mark it as not reachable.
          triedRoutes.current = [];
          deaths.current = {};
          setPhase({ kind: "failed", reason: e.message });
          return;
        }
        // Every route tried once this time round: start over next time.
        const reason = triedRoutes.current.length
          ? "The stream stopped and no other route to this channel answers."
          : "None of this channel's routes answers right now.";
        triedRoutes.current = [];
        deaths.current = {};
        recordChannel(id, false);
        setPhase({ kind: "failed", reason });
      }
    },
    [id],
  );
  startRef.current = start;

  useEffect(() => {
    triedRoutes.current = [];
    deaths.current = {};
    start(false);
    return () => {
      ctl.current?.abort();
      attached.current?.destroy();
      attached.current = null;
    };
  }, [id, start]);

  useEffect(() => {
    const v = video.current;
    if (!v) return;
    v.volume = vol.v;
    v.muted = vol.muted;
    const onPlay = () => setPaused(false);
    const onPause = () => setPaused(true);
    const onWaiting = () => setWaiting(true);
    const onPlaying = () => setWaiting(false);
    const onTime = () => {
      const edge = attached.current?.hls.liveSyncPosition;
      setBehind(edge != null ? Math.max(0, edge - v.currentTime) : 0);
    };
    v.addEventListener("play", onPlay);
    v.addEventListener("pause", onPause);
    v.addEventListener("waiting", onWaiting);
    v.addEventListener("playing", onPlaying);
    v.addEventListener("timeupdate", onTime);
    return () => {
      v.removeEventListener("play", onPlay);
      v.removeEventListener("pause", onPause);
      v.removeEventListener("waiting", onWaiting);
      v.removeEventListener("playing", onPlaying);
      v.removeEventListener("timeupdate", onTime);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const poke = useCallback(() => {
    setChrome(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setChrome(false), HIDE_AFTER_MS);
  }, []);
  useEffect(() => {
    poke();
    isFullscreen().then(setFs).catch(() => undefined);
    const onFs = () => setFs(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", onFs);
    return () => document.removeEventListener("fullscreenchange", onFs);
  }, [poke]);

  const toggleFs = useCallback(async () => {
    const on = !(await isFullscreen());
    await setFullscreen(on);
    setFs(on);
  }, []);

  const togglePlay = useCallback(() => {
    const v = video.current;
    if (!v) return;
    if (v.paused) v.play().catch(() => undefined);
    else v.pause();
  }, []);

  const toLive = useCallback(() => {
    const edge = attached.current?.hls.liveSyncPosition;
    if (edge != null && video.current) video.current.currentTime = edge;
  }, []);

  const setVolume = useCallback((v: number, muted = false) => {
    const nv = { v: Math.max(0, Math.min(1, v)), muted };
    setVol(nv);
    if (video.current) {
      video.current.volume = nv.v;
      video.current.muted = nv.muted;
    }
    try {
      localStorage.setItem("player_volume", JSON.stringify(nv));
    } catch {
      /* ignore */
    }
  }, []);

  const goBack = useCallback(async () => {
    if (await isFullscreen()) await setFullscreen(false);
    if (history.length > 1) router.back();
    else router.push("/live");
  }, [router]);

  useEffect(() => {
    const onKey = async (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (keyIsForPanel(e)) return;
      const k = e.key.toLowerCase();
      if (k === " " || k === "k") {
        e.preventDefault();
        togglePlay();
      } else if (k === "arrowup") {
        e.preventDefault();
        setVolume(vol.v + 0.05);
      } else if (k === "arrowdown") {
        e.preventDefault();
        setVolume(vol.v - 0.05);
      } else if (k === "m") setVolume(vol.v, !vol.muted);
      else if (k === "f") toggleFs();
      else if (k === "t" && !fs) toggleLayout();
      else if (k === "escape") {
        // Full screen, then the bigger player, then leave.
        if (await isFullscreen()) {
          await setFullscreen(false);
          setFs(false);
        } else if (layoutFill) setLayoutFill(false);
        else goBack();
      } else return;
      poke();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [togglePlay, setVolume, vol, toggleFs, toggleLayout, fs, layoutFill, goBack, poke]);

  const pickFeed = (fid: string) => {
    if (fid === id) return;
    const f = feeds.find((x) => x[0] === fid);
    const q = new URLSearchParams(params.toString());
    q.set("id", fid);
    q.set("name", f?.[1] ?? fid);
    router.replace(`/live/watch?${q}`);
  };

  const platform = usePlatform();
  const showChrome = chrome || paused || phase.kind !== "playing";
  const VolIcon = vol.muted || vol.v === 0 ? VolumeX : vol.v < 0.5 ? Volume1 : Volume2;
  const others = feeds.filter((f) => f[0] !== id);
  const iconBtn = "flex h-10 w-10 cursor-pointer items-center justify-center rounded-full text-ink hover:bg-white/15";

  const player = (
    <>
      <div className="absolute inset-0 overflow-hidden rounded-[inherit]">
        <video ref={video} className="absolute inset-0 h-full w-full" playsInline />

        {phase.kind !== "playing" && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 bg-black/70 px-6 text-center">
            {phase.kind === "resolving" ? (
              <>
                <Loader2 className="h-10 w-10 animate-spin text-accent-soft" />
                <p className="font-display text-xl font-bold text-ink">{phase.reconnect ? "Reconnecting" : name}</p>
                <p className="text-sm text-ink-3">{phase.route ? `Trying the ${phase.route} route…` : "Finding the stream…"}</p>
              </>
            ) : (
              <>
                <p className="font-display text-xl font-bold text-ink">{name} isn&apos;t reachable right now</p>
                <p className="max-w-md text-sm text-ink-3">{phase.reason}</p>
                {others.length > 0 && (
                  <div className="mt-1 flex max-w-xl flex-wrap justify-center gap-2">
                    {others.slice(0, 8).map(([fid, fname]) => (
                      <button
                        key={fid}
                        onClick={() => pickFeed(fid)}
                        className="flex h-9 cursor-pointer items-center gap-1.5 rounded-full bg-white/10 px-3.5 text-sm font-medium text-ink hover:bg-accent"
                      >
                        <Radio className="h-4 w-4" />
                        {plain(fname)}
                      </button>
                    ))}
                  </div>
                )}
                <div className="mt-2 flex gap-3">
                  <button onClick={() => start(false)} className="flex h-10 cursor-pointer items-center rounded-card bg-accent px-5 text-sm font-semibold text-white hover:bg-accent-hover">
                    Try again
                  </button>
                  <button onClick={goBack} className="flex h-10 cursor-pointer items-center rounded-card bg-white/10 px-5 text-sm font-semibold text-ink hover:bg-white/20">
                    Back
                  </button>
                </div>
              </>
            )}
          </div>
        )}
      </div>

      {phase.kind === "playing" && waiting && !paused && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <Loader2 className="h-12 w-12 animate-spin text-white/80" />
        </div>
      )}

      {/* Top bar: only over the bigger player (beside the panel, the page has its own). */}
      {fill && (
        <div
          data-tauri-drag-region
          className={clsx(
            "absolute inset-x-0 top-0 flex items-center gap-4 bg-gradient-to-b from-black/80 to-transparent pb-12 pr-6 pt-5 transition-opacity duration-base",
            platform === "mac" && !fs ? "pl-[88px]" : "pl-6",
            showChrome ? "opacity-100" : "pointer-events-none opacity-0",
          )}
        >
          <button onClick={goBack} aria-label="Back" className={iconBtn}>
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="min-w-0">
            <p className="flex items-center gap-2 truncate font-display text-lg font-bold text-ink">
              <span className="inline-flex items-center gap-1.5 rounded bg-live px-1.5 py-0.5 text-[11px] font-bold tracking-wide text-white">
                <span className="h-1.5 w-1.5 rounded-full bg-white" />
                LIVE
              </span>
              {name}
            </p>
            {eventTitle && <p className="truncate text-sm text-ink-3">{eventTitle}</p>}
          </div>
          {stream && (
            <span className="ml-auto rounded-full border border-white/20 px-3 py-1 text-xs font-medium text-ink-2" title={`Route: ${stream.route}`}>
              {new URL(stream.url).host}
            </span>
          )}
          {!fs && (platform === "windows" || platform === "linux") && <WindowControls className={clsx("-mr-6 -mt-5 self-start", !stream && "ml-auto")} />}
        </div>
      )}

      {phase.kind === "playing" && (
        <div
          className={clsx(
            "absolute inset-x-0 bottom-0 rounded-b-[inherit] bg-gradient-to-t from-black/90 via-black/50 to-transparent pb-3 pt-16 transition-opacity duration-base",
            fill ? "px-6 pb-4" : "px-4",
            showChrome ? "opacity-100" : "pointer-events-none opacity-0",
          )}
        >
          <div className="flex items-center gap-1">
            <button onClick={togglePlay} aria-label={paused ? "Play" : "Pause"} title={paused ? "Play (K)" : "Pause (K)"} className={iconBtn}>
              {paused ? <Play className="h-5 w-5 fill-current" strokeWidth={0} /> : <Pause className="h-5 w-5 fill-current" strokeWidth={0} />}
            </button>
            <div className="group/vol flex items-center">
              <button onClick={() => setVolume(vol.v, !vol.muted)} aria-label={vol.muted ? "Unmute" : "Mute"} title={vol.muted ? "Unmute (M)" : "Mute (M)"} className={iconBtn}>
                <VolIcon className="h-5 w-5" />
              </button>
              <input
                type="range"
                min={0}
                max={1}
                step={0.01}
                value={vol.muted ? 0 : vol.v}
                onChange={(e) => setVolume(Number(e.target.value))}
                aria-label="Volume"
                className="w-0 cursor-pointer accent-[#2FF58B] opacity-0 transition-all duration-base group-hover/vol:w-24 group-hover/vol:opacity-100 focus:w-24 focus:opacity-100"
              />
            </div>
            {behind > 20 ? (
              <button onClick={toLive} className="ml-2 flex h-8 cursor-pointer items-center gap-1.5 whitespace-nowrap rounded-full bg-white/10 px-3 text-sm font-medium text-ink hover:bg-white/20">
                <RotateCw className="h-4 w-4" />
                Back to live ({Math.round(behind)} s behind)
              </button>
            ) : (
              <span className="ml-2 inline-flex items-center gap-1.5 text-sm text-ink-2">
                <span className="h-2 w-2 rounded-full bg-live" />
                Live
              </span>
            )}
            <div className="ml-auto flex items-center gap-1">
              {others.length > 0 && (
                <Menu
                  icon={<Radio className="h-5 w-5" />}
                  label="Other feeds"
                  heading={eventTitle ? "Other feeds for this event" : "Other feeds"}
                  items={feeds.map(([fid, fname]) => ({ id: fid, label: plain(fname), selected: fid === id }))}
                  onPick={pickFeed}
                />
              )}
              {!fs && (
                <button
                  onClick={toggleLayout}
                  aria-label={layoutFill ? "Smaller player" : "Bigger player"}
                  title={layoutFill ? "Smaller player, with the list (T)" : "Bigger player (T)"}
                  className={iconBtn}
                >
                  {layoutFill ? <Minimize2 className="h-5 w-5" /> : <Maximize2 className="h-5 w-5" />}
                </button>
              )}
              <button onClick={toggleFs} aria-label={fs ? "Exit full screen" : "Full screen"} title="Full screen (F)" className={iconBtn}>
                {fs ? <Minimize className="h-5 w-5" /> : <Maximize className="h-5 w-5" />}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );

  const info = (
    <div className="px-1 pb-5 pt-3">
      <p className="flex items-center gap-2 text-sm font-semibold text-ink-2">
        <span className="inline-flex items-center gap-1.5 rounded bg-live px-1.5 py-0.5 text-[11px] font-bold tracking-wide text-white">
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-white" />
          LIVE
        </span>
        {eventTitle ? name : "Live TV"}
      </p>
      <h1 className="mt-1.5 font-display text-2xl font-extrabold tracking-tight text-ink [text-wrap:balance]">{eventTitle ?? name}</h1>
      <p className="mt-1 text-sm text-ink-3">
        {phase.kind === "playing" && stream
          ? `Playing from ${new URL(stream.url).host}`
          : phase.kind === "resolving"
            ? "Connecting…"
            : "Not playing"}
        {others.length > 0 ? ` · ${others.length} other channel${others.length === 1 ? "" : "s"} show this match` : ""}
      </p>
    </div>
  );

  return (
    <WatchFrame
      fill={fill}
      onBack={goBack}
      hideCursor={!showChrome}
      playerProps={{
        onMouseMove: poke,
        onMouseLeave: () => {
          if (!paused && phase.kind === "playing") setChrome(false);
        },
        onClick: (e) => {
          if ((e.target as HTMLElement).tagName === "VIDEO") togglePlay();
        },
        onDoubleClick: (e) => {
          if ((e.target as HTMLElement).tagName === "VIDEO") toggleFs();
        },
      }}
      player={player}
      info={info}
      below={<LiveBelow eventTitle={eventTitle} ready={phase.kind !== "resolving"} />}
      panel={<LivePanel id={id} eventTitle={eventTitle} feeds={feeds} onPickFeed={pickFeed} ready={phase.kind !== "resolving"} />}
    />
  );
}

export default function LiveWatchPage() {
  return (
    <Suspense>
      <LiveWatch />
    </Suspense>
  );
}
