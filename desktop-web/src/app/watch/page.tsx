"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import Link from "next/link";
import {
  ArrowLeft,
  AudioLines,
  Bookmark,
  BookmarkCheck,
  Captions,
  Gauge,
  Info,
  Loader2,
  Maximize,
  Maximize2,
  Minimize,
  Minimize2,
  Pause,
  Play,
  RotateCcw,
  RotateCw,
  Server,
  SkipForward,
  Star,
  Volume1,
  Volume2,
  VolumeX,
} from "lucide-react";
import { tmdb, type Kind } from "@/lib/tmdb/client";
import { resolveChain, SOURCE_NAMES, type PlayInfo, type Target } from "@/lib/providers";
import { attach, type Attached, type Tracks } from "@/lib/player/attach";
import { isFullscreen, setFullscreen } from "@/lib/player/fullscreen";
import { keyOf, useLibrary } from "@/lib/store/library";
import { watchHref } from "@/lib/watchHref";
import { SeekBar, fmtTime } from "@/components/player/SeekBar";
import { Menu } from "@/components/player/Menu";
import { WindowControls, usePlatform } from "@/components/TitleBar";
import { WatchFrame, keyIsForPanel, readFillLayout, saveFillLayout } from "@/components/watch/WatchFrame";
import { VodBelow, VodPanel } from "@/components/watch/VodPanel";

const HIDE_AFTER_MS = 3000;
const SAVE_EVERY_MS = 5000;
const NEXT_COUNTDOWN_S = 10;

type Phase = { kind: "resolving"; trying: string } | { kind: "playing" } | { kind: "failed"; reason: string };

function readVolume(): { v: number; muted: boolean } {
  try {
    const raw = localStorage.getItem("player_volume");
    if (raw) return JSON.parse(raw);
  } catch {
    /* ignore */
  }
  return { v: 1, muted: false };
}

function WatchView() {
  const params = useSearchParams();
  const router = useRouter();
  const kind = (params.get("kind") === "tv" ? "tv" : "movie") as Kind;
  const id = Number(params.get("id"));
  const season = kind === "tv" ? Number(params.get("s") ?? 1) : undefined;
  const episode = kind === "tv" ? Number(params.get("e") ?? 1) : undefined;
  const wantResume = params.get("resume") === "1";

  const details = useQuery({ queryKey: ["details", kind, id], queryFn: () => tmdb.details(kind, id), enabled: id > 0 });
  const eps = useQuery({
    queryKey: ["season", id, season],
    queryFn: () => tmdb.season(id, season!),
    enabled: kind === "tv" && !!season,
  });
  const ep = eps.data?.find((x) => x.number === episode);
  const saveProgress = useLibrary((s) => s.saveProgress);
  const saved = useLibrary.getState().progress[keyOf(kind, id)];

  const video = useRef<HTMLVideoElement>(null);
  const attached = useRef<Attached | null>(null);
  const resolveCtl = useRef<AbortController | null>(null);
  const tried = useRef<string[]>([]);
  const pendingSeek = useRef<number | null>(null);
  // Set every render: [start] is created once and must reach the current one.
  const failoverRef = useRef<(at?: number) => void>(() => undefined);

  const [phase, setPhase] = useState<Phase>({ kind: "resolving", trying: "" });
  const [play, setPlay] = useState<PlayInfo | null>(null);
  const [tracks, setTracks] = useState<Tracks | null>(null);
  const [paused, setPaused] = useState(true);
  const [waiting, setWaiting] = useState(false);
  const [pos, setPos] = useState(0);
  const [dur, setDur] = useState(0);
  const [buf, setBuf] = useState(0);
  const [vol, setVol] = useState(readVolume);
  const [fs, setFs] = useState(false);
  const [chrome, setChrome] = useState(true);
  const [note, setNote] = useState<string | null>(null);
  const [nextIn, setNextIn] = useState<number | null>(null);
  // The picture over the whole window (T), or beside the side panel.
  const [layoutFill, setLayoutFill] = useState(readFillLayout);
  const fill = layoutFill || fs;
  const toggleLayout = useCallback(() => {
    setLayoutFill((f) => !f);
  }, []);
  useEffect(() => saveFillLayout(layoutFill), [layoutFill]);
  const savedEntry = useLibrary((s) => s.saved[keyOf(kind, id)]);
  const toggleSaved = useLibrary((s) => s.toggleSaved);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastSave = useRef(0);

  const target: Target | null = useMemo(
    () =>
      details.data
        ? { tmdbId: id, kind, season, episode, title: details.data.title, year: details.data.year, imdbId: details.data.imdbId }
        : null,
    [details.data, id, kind, season, episode],
  );

  const next = useMemo(() => {
    if (kind !== "tv" || !details.data || !season || !episode) return null;
    const seasons = details.data.seasons;
    const cur = seasons.find((s) => s.number === season);
    if (cur && episode < cur.episodes) return { s: season, e: episode + 1 };
    const after = seasons.find((s) => s.number > season && s.episodes > 0);
    return after ? { s: after.number, e: 1 } : null;
  }, [kind, details.data, season, episode]);

  const flash = useCallback((msg: string) => {
    setNote(msg);
    setTimeout(() => setNote((n) => (n === msg ? null : n)), 4500);
  }, []);

  /** Put [info]'s stream ([url], default its own) on the video, at [at] seconds. */
  const start = useCallback(
    async (info: PlayInfo, url = info.url, at?: number) => {
      const v = video.current!;
      attached.current?.destroy();
      setTracks(null);
      pendingSeek.current = at ?? null;
      attached.current = await attach(v, info, url, {
        onTracks: setTracks,
        onLog: (m) => console.info(m),
        onFatal: (reason) => {
          console.warn(`${info.provider} failed: ${reason}`);
          // Dead link: the next source, from where the picture stopped.
          failoverRef.current(v.currentTime > 1 ? v.currentTime : at);
        },
      });
      setPlay(info);
      setPhase({ kind: "playing" });
      v.play().catch(() => setPaused(true));
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  /** Ask the sources. [keep]: something is playing and must stay if this finds nothing. */
  const resolve = useCallback(
    async (opts: { only?: string; dub?: string; keep?: boolean; at?: number } = {}) => {
      if (!target) return;
      resolveCtl.current?.abort();
      const ac = new AbortController();
      resolveCtl.current = ac;
      if (!opts.keep) {
        attached.current?.destroy();
        attached.current = null;
        setPhase({ kind: "resolving", trying: opts.only ?? "" });
      } else {
        flash(opts.dub ? `Looking for ${opts.dub}…` : `Trying ${opts.only}…`);
      }
      const info = await resolveChain(target, {
        signal: ac.signal,
        only: opts.only,
        dub: opts.dub,
        skip: opts.only ? undefined : tried.current,
        onTry: (name) => {
          if (!opts.keep) setPhase({ kind: "resolving", trying: name });
        },
        log: (m) => console.info(m),
      }).catch(() => null);
      if (ac.signal.aborted) return;
      if (!info) {
        if (opts.keep) {
          flash(opts.dub ? `${opts.dub} isn't available for this title` : `${opts.only} doesn't have this title`);
          return;
        }
        setPhase({ kind: "failed", reason: "No source has a stream for this right now." });
        return;
      }
      if (opts.dub && info.language !== opts.dub && opts.keep) {
        flash(`${opts.dub} isn't available for this title`);
        return;
      }
      if (!tried.current.includes(info.provider)) tried.current.push(info.provider);
      const at = opts.at ?? (video.current && video.current.currentTime > 1 ? video.current.currentTime : undefined);
      await start(info, info.url, at);
    },
    [target, start, flash],
  );

  const failover = useCallback(
    (at?: number) => {
      if (!target) return;
      setPhase({ kind: "resolving", trying: "" });
      resolve({ at });
    },
    [target, resolve],
  );
  failoverRef.current = failover;

  // Another title or episode picked from the side panel: the last one's
  // picture, times and tracks go at once, not when the new one is found.
  useEffect(() => {
    setPhase({ kind: "resolving", trying: "" });
    setPlay(null);
    setTracks(null);
    setPos(0);
    setDur(0);
    setBuf(0);
    setNextIn(null);
  }, [kind, id, season, episode]);

  // A new title or episode: start fresh.
  useEffect(() => {
    if (!target) return;
    tried.current = [];
    setNextIn(null);
    const at = wantResume && saved && saved.season === season && saved.episode === episode && saved.positionS > 30 ? saved.positionS : undefined;
    resolve({ at });
    return () => {
      resolveCtl.current?.abort();
      attached.current?.destroy();
      attached.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target]);

  // Video events.
  useEffect(() => {
    const v = video.current;
    if (!v) return;
    v.volume = vol.v;
    v.muted = vol.muted;
    const onTime = () => {
      setPos(v.currentTime);
      const b = v.buffered;
      for (let i = 0; i < b.length; i++) if (b.start(i) <= v.currentTime + 0.5 && b.end(i) >= v.currentTime) setBuf(b.end(i));
      if (Date.now() - lastSave.current > SAVE_EVERY_MS) {
        lastSave.current = Date.now();
        saveNow();
      }
    };
    const onMeta = () => {
      setDur(Number.isFinite(v.duration) ? v.duration : 0);
      if (pendingSeek.current != null) {
        v.currentTime = pendingSeek.current;
        pendingSeek.current = null;
      }
    };
    const onPlay = () => setPaused(false);
    const onPause = () => {
      setPaused(true);
      saveNow();
    };
    const onWaiting = () => setWaiting(true);
    const onPlaying = () => setWaiting(false);
    const onEnded = () => {
      saveNow();
      if (next) setNextIn(NEXT_COUNTDOWN_S);
    };
    v.addEventListener("timeupdate", onTime);
    v.addEventListener("loadedmetadata", onMeta);
    v.addEventListener("durationchange", onMeta);
    v.addEventListener("play", onPlay);
    v.addEventListener("pause", onPause);
    v.addEventListener("waiting", onWaiting);
    v.addEventListener("playing", onPlaying);
    v.addEventListener("ended", onEnded);
    return () => {
      v.removeEventListener("timeupdate", onTime);
      v.removeEventListener("loadedmetadata", onMeta);
      v.removeEventListener("durationchange", onMeta);
      v.removeEventListener("play", onPlay);
      v.removeEventListener("pause", onPause);
      v.removeEventListener("waiting", onWaiting);
      v.removeEventListener("playing", onPlaying);
      v.removeEventListener("ended", onEnded);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [next, details.data, play]);

  function saveNow() {
    const v = video.current;
    const d = details.data;
    if (!v || !d || !(v.duration > 0) || v.currentTime < 5) return;
    saveProgress({
      kind,
      id,
      title: d.title,
      poster: d.poster,
      backdrop: ep?.still?.replace("/w300/", "/w780/") ?? d.backdrop,
      season,
      episode,
      episodeName: ep?.name,
      positionS: v.currentTime,
      durationS: v.duration,
      provider: play?.provider,
    });
  }

  // Save on the way out too.
  useEffect(() => {
    const onHide = () => saveNow();
    window.addEventListener("beforeunload", onHide);
    return () => {
      window.removeEventListener("beforeunload", onHide);
      saveNow();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [details.data, play, ep]);

  // Next-episode countdown.
  useEffect(() => {
    if (nextIn === null || !next) return;
    if (nextIn <= 0) {
      router.replace(watchHref({ kind: "tv", id }, next.s, next.e));
      return;
    }
    const t = setTimeout(() => setNextIn((n) => (n === null ? null : n - 1)), 1000);
    return () => clearTimeout(t);
  }, [nextIn, next, id, router]);

  // Controls hide after a few seconds without the mouse moving, unless paused.
  const poke = useCallback(() => {
    setChrome(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setChrome(false), HIDE_AFTER_MS);
  }, []);
  useEffect(() => {
    poke();
    return () => {
      if (hideTimer.current) clearTimeout(hideTimer.current);
    };
  }, [poke]);

  useEffect(() => {
    isFullscreen().then(setFs).catch(() => undefined);
    const onFs = () => setFs(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", onFs);
    return () => document.removeEventListener("fullscreenchange", onFs);
  }, []);

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

  const seekBy = useCallback((d: number) => {
    const v = video.current;
    if (!v) return;
    v.currentTime = Math.max(0, Math.min((v.duration || Infinity) - 1, v.currentTime + d));
    poke();
  }, [poke]);

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
    saveNow();
    if (await isFullscreen()) await setFullscreen(false);
    if (history.length > 1) router.back();
    else router.push(`/title?kind=${kind}&id=${id}`);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router, kind, id]);

  // Keyboard.
  useEffect(() => {
    const onKey = async (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (keyIsForPanel(e)) return;
      const k = e.key.toLowerCase();
      if (k === " " || k === "k") {
        e.preventDefault();
        togglePlay();
      } else if (k === "arrowleft" || k === "j") seekBy(-10);
      else if (k === "arrowright" || k === "l") seekBy(10);
      else if (k === "arrowup") {
        e.preventDefault();
        setVolume(vol.v + 0.05);
      } else if (k === "arrowdown") {
        e.preventDefault();
        setVolume(vol.v - 0.05);
      } else if (k === "m") setVolume(vol.v, !vol.muted);
      else if (k === "f") toggleFs();
      else if (k === "t" && !fs) toggleLayout();
      else if (k === "n" && next) router.replace(watchHref({ kind: "tv", id }, next.s, next.e));
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
  }, [togglePlay, seekBy, setVolume, vol, toggleFs, toggleLayout, fs, layoutFill, next, id, router, goBack, poke]);

  // Menus.
  const qualityItems = tracks && tracks.levels.length > 1
    ? [
        { id: "-1", label: "Auto", selected: tracks.level === -1 },
        ...[...tracks.levels]
          .sort((a, b) => b.height - a.height)
          .map((l) => ({ id: String(l.index), label: `${l.height}p`, detail: `${(l.bitrate / 1e6).toFixed(1)} Mbit/s`, selected: tracks.level === l.index })),
      ]
    : (play?.qualities ?? []).map((q) => ({ id: `url:${q.url}`, label: q.label, selected: q.label === play?.selected }));

  const audioItems = [
    ...(tracks?.audio.length && tracks.audio.length > 1
      ? tracks.audio.map((a) => ({ id: `trk:${a.index}`, label: a.name, detail: a.lang, selected: a.index === tracks.audioIndex }))
      : []),
    ...(play && play.dubs.length
      ? [
          { id: "dub:Original", label: "Original", selected: play.language === "Original" },
          ...play.dubs.map((d) => ({ id: `dub:${d}`, label: d, selected: play.language === d })),
        ]
      : []),
  ];

  const subtitleItems = [
    { id: "-1", label: "Off", selected: !tracks || tracks.subtitleIndex === -1 },
    ...(tracks?.subtitles ?? []).map((s) => ({ id: String(s.index), label: s.name, detail: s.lang, selected: s.index === tracks?.subtitleIndex })),
  ];

  const sourceItems = SOURCE_NAMES.map((n) => ({ id: n, label: n, selected: play?.provider === n }));

  const platform = usePlatform();
  const title = details.data?.title ?? "";
  const subtitle = kind === "tv" ? `S${season} E${episode}${ep ? ` · ${ep.name}` : ""}` : details.data?.year ? String(details.data.year) : "";
  const showChrome = chrome || paused || phase.kind !== "playing";
  const VolIcon = vol.muted || vol.v === 0 ? VolumeX : vol.v < 0.5 ? Volume1 : Volume2;
  const runtime = ep?.runtime ?? details.data?.runtime;
  const overview = (kind === "tv" ? ep?.overview : undefined) || details.data?.overview || "";
  const iconBtn = "flex h-10 w-10 cursor-pointer items-center justify-center rounded-full text-ink hover:bg-white/15";

  const player = (
    <>
      <div className="absolute inset-0 overflow-hidden rounded-[inherit]">
        <video ref={video} className="absolute inset-0 h-full w-full" playsInline crossOrigin="anonymous" />

        {/* Before the picture: the title's art, and what is being asked. */}
        {phase.kind !== "playing" && (
          <div className="absolute inset-0">
            {details.data?.backdrop && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={details.data.backdrop} alt="" className="h-full w-full object-cover opacity-30 blur-sm" />
            )}
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 bg-black/40 px-6 text-center">
              {phase.kind === "resolving" ? (
                <>
                  <Loader2 className="h-10 w-10 animate-spin text-accent-soft" />
                  <p className="font-display text-xl font-bold text-ink">{title || "Loading"}</p>
                  <p className="text-sm text-ink-3">{phase.trying ? `Finding a stream on ${phase.trying}…` : "Finding a stream…"}</p>
                </>
              ) : (
                <>
                  <p className="font-display text-xl font-bold text-ink">Can&apos;t play this right now</p>
                  <p className="max-w-md text-sm text-ink-3">{phase.reason} Sources come and go; trying again in a few minutes often works.</p>
                  <div className="mt-2 flex gap-3">
                    <button
                      onClick={() => {
                        tried.current = [];
                        resolve();
                      }}
                      className="flex h-10 cursor-pointer items-center rounded-card bg-accent px-5 text-sm font-semibold text-white hover:bg-accent-hover"
                    >
                      Try again
                    </button>
                    <button onClick={goBack} className="flex h-10 cursor-pointer items-center rounded-card bg-white/10 px-5 text-sm font-semibold text-ink hover:bg-white/20">
                      Back
                    </button>
                  </div>
                </>
              )}
            </div>
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
            <p className="truncate font-display text-lg font-bold text-ink">{title}</p>
            {subtitle && <p className="truncate text-sm text-ink-3">{subtitle}</p>}
          </div>
          {play && (
            <span className="ml-auto rounded-full border border-white/20 px-3 py-1 text-xs font-medium text-ink-2">
              {play.provider}
              {play.language !== "Original" ? ` · ${play.language}` : ""}
            </span>
          )}
          {!fs && (platform === "windows" || platform === "linux") && <WindowControls className={clsx("-mr-6 -mt-5 self-start", !play && "ml-auto")} />}
        </div>
      )}

      {note && (
        <div role="status" aria-live="polite" className={clsx("absolute left-1/2 -translate-x-1/2 animate-rise rounded-full bg-black/80 px-4 py-2 text-sm text-ink backdrop-blur", fill ? "top-24" : "top-4")}>
          {note}
        </div>
      )}

      {nextIn !== null && next && (
        <div className={clsx("absolute w-80 animate-rise rounded-panel border border-line bg-[#15171c]/95 p-4 shadow-2xl backdrop-blur-xl", fill ? "bottom-32 right-8" : "bottom-24 right-4")}>
          <p className="text-xs font-semibold uppercase tracking-[0.12em] text-ink-4">Next episode in {nextIn} s</p>
          <p className="mt-1 text-sm font-semibold text-ink">
            S{next.s} E{next.e}
          </p>
          <div className="mt-3 flex gap-2">
            <button
              onClick={() => router.replace(watchHref({ kind: "tv", id }, next.s, next.e))}
              className="flex h-9 flex-1 cursor-pointer items-center justify-center gap-1.5 rounded-card bg-ink text-sm font-semibold text-bg hover:bg-ink-2"
            >
              <Play className="h-4 w-4 fill-current" strokeWidth={0} /> Play now
            </button>
            <button onClick={() => setNextIn(null)} className="h-9 cursor-pointer rounded-card bg-white/10 px-4 text-sm font-medium text-ink hover:bg-white/20">
              Cancel
            </button>
          </div>
        </div>
      )}

      {/* Control bar */}
      {phase.kind === "playing" && (
        <div
          className={clsx(
            "absolute inset-x-0 bottom-0 rounded-b-[inherit] bg-gradient-to-t from-black/90 via-black/50 to-transparent pb-3 pt-16 transition-opacity duration-base",
            fill ? "px-6 pb-4" : "px-4",
            showChrome ? "opacity-100" : "pointer-events-none opacity-0",
          )}
        >
          <SeekBar position={pos} duration={dur} buffered={buf} onSeek={(s) => video.current && (video.current.currentTime = s)} />
          <div className="mt-2 flex items-center gap-1">
            <button onClick={togglePlay} aria-label={paused ? "Play" : "Pause"} title={paused ? "Play (K)" : "Pause (K)"} className={iconBtn}>
              {paused ? <Play className="h-5 w-5 fill-current" strokeWidth={0} /> : <Pause className="h-5 w-5 fill-current" strokeWidth={0} />}
            </button>
            <button onClick={() => seekBy(-10)} aria-label="Back 10 seconds" title="Back 10 s (Left)" className={iconBtn}>
              <RotateCcw className="h-5 w-5" />
            </button>
            <button onClick={() => seekBy(10)} aria-label="Forward 10 seconds" title="Forward 10 s (Right)" className={iconBtn}>
              <RotateCw className="h-5 w-5" />
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
            <span className="ml-2 whitespace-nowrap text-sm tabular text-ink-2">
              {fmtTime(pos)} <span className="text-ink-4">/ {fmtTime(dur)}</span>
            </span>

            <div className="ml-auto flex items-center gap-1">
              {next && (
                <button
                  onClick={() => router.replace(watchHref({ kind: "tv", id }, next.s, next.e))}
                  aria-label="Next episode"
                  title="Next episode (N)"
                  className={clsx("flex h-10 cursor-pointer items-center gap-1.5 rounded-full text-sm font-medium text-ink hover:bg-white/15", fill ? "px-3" : "w-10 justify-center")}
                >
                  <SkipForward className="h-5 w-5" />
                  {fill && "Next episode"}
                </button>
              )}
              <Menu
                icon={<Server className="h-5 w-5" />}
                label="Source"
                heading="Source"
                items={sourceItems}
                onPick={(name) => name !== play?.provider && resolve({ only: name, keep: true })}
              />
              {qualityItems.length > 1 && (
                <Menu
                  icon={<Gauge className="h-5 w-5" />}
                  label="Quality"
                  heading="Quality"
                  items={qualityItems}
                  onPick={(sel) => {
                    if (sel.startsWith("url:")) {
                      const q = play?.qualities.find((x) => `url:${x.url}` === sel);
                      if (q && play) start({ ...play, selected: q.label }, q.url, video.current?.currentTime);
                    } else attached.current?.setLevel(Number(sel));
                  }}
                />
              )}
              {audioItems.length > 1 && (
                <Menu
                  icon={<AudioLines className="h-5 w-5" />}
                  label="Audio"
                  heading="Audio"
                  items={audioItems}
                  onPick={(sel) => {
                    if (sel.startsWith("trk:")) attached.current?.setAudio(Number(sel.slice(4)));
                    else {
                      const lang = sel.slice(4);
                      if (lang === play?.language) return;
                      resolve({ only: play?.provider, dub: lang === "Original" ? undefined : lang, keep: true });
                    }
                  }}
                />
              )}
              {(tracks?.subtitles.length ?? 0) > 0 && (
                <Menu
                  icon={<Captions className="h-5 w-5" />}
                  label="Subtitles"
                  heading="Subtitles"
                  items={subtitleItems}
                  onPick={(sel) => attached.current?.setSubtitle(Number(sel))}
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
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          {kind === "tv" && (
            <p className="text-sm font-semibold text-accent-soft">
              Season {season} · Episode {episode}
              {ep ? <span className="text-ink-2"> · {ep.name}</span> : null}
            </p>
          )}
          <h1 className="mt-0.5 font-display text-2xl font-extrabold tracking-tight text-ink [text-wrap:balance]">{title || " "}</h1>
          <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-ink-3">
            {details.data?.year ? <span>{details.data.year}</span> : null}
            {details.data?.rating ? (
              <span className="inline-flex items-center gap-1">
                <Star className="h-3.5 w-3.5 fill-gold text-gold" strokeWidth={0} />
                {details.data.rating.toFixed(1)}
              </span>
            ) : null}
            {runtime ? <span>{runtime} min</span> : null}
            {details.data?.certification ? <span className="rounded border border-line-strong px-1.5 text-xs">{details.data.certification}</span> : null}
            {details.data?.genres.slice(0, 3).map((g) => (
              <span key={g} className="text-ink-4">
                {g}
              </span>
            ))}
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <button
            onClick={() =>
              details.data &&
              toggleSaved({ kind, id, title: details.data.title, poster: details.data.poster, year: details.data.year })
            }
            disabled={!details.data}
            aria-pressed={!!savedEntry}
            className="flex h-9 cursor-pointer items-center gap-2 rounded-[10px] bg-white/[0.07] px-3.5 text-sm font-medium text-ink-2 transition-colors duration-fast hover:bg-white/[0.12] hover:text-ink disabled:cursor-default disabled:opacity-50"
          >
            {savedEntry ? <BookmarkCheck className="h-4 w-4 text-accent-soft" /> : <Bookmark className="h-4 w-4" />}
            {savedEntry ? "In my list" : "My list"}
          </button>
          <Link
            href={`/title?kind=${kind}&id=${id}`}
            className="flex h-9 items-center gap-2 rounded-[10px] bg-white/[0.07] px-3.5 text-sm font-medium text-ink-2 transition-colors duration-fast hover:bg-white/[0.12] hover:text-ink"
          >
            <Info className="h-4 w-4" />
            Details
          </Link>
        </div>
      </div>
      {overview && <p className="mt-3 line-clamp-3 max-w-3xl text-sm leading-6 text-ink-3">{overview}</p>}
      {play && (
        <p className="mt-3 text-xs text-ink-4">
          Playing from {play.provider}
          {play.language !== "Original" ? ` · ${play.language}` : ""}. If it struggles, the server button picks another source.
        </p>
      )}
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
          if (e.target === e.currentTarget || (e.target as HTMLElement).tagName === "VIDEO") togglePlay();
        },
        onDoubleClick: (e) => {
          if ((e.target as HTMLElement).tagName === "VIDEO") toggleFs();
        },
      }}
      player={player}
      info={info}
      below={<VodBelow kind={kind} id={id} season={season} episode={episode} details={details.data} />}
      panel={<VodPanel kind={kind} id={id} season={season} episode={episode} details={details.data} />}
    />
  );
}

export default function WatchPage() {
  return (
    <Suspense>
      <WatchView />
    </Suspense>
  );
}
