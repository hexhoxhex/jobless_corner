// Attach a source's stream to a <video>. HLS goes through hls.js with every
// request sent via the relay (xhrSetup opens the relayed URL, so hls.js still
// resolves relative chunk paths against the real playlist URL); anything else
// plays as a file through the relay, Range requests and all.

import Hls, {
  type ErrorData,
  type Level,
  type LoaderCallbacks,
  type LoaderConfiguration,
  type LoaderContext,
  type MediaPlaylist,
} from "hls.js";
import { relayUrl, ensureRelay } from "../net/relay";
import type { PlayInfo } from "../providers/types";

export type Tracks = {
  levels: { index: number; height: number; bitrate: number }[];
  level: number; // -1 = auto
  audio: { index: number; name: string; lang?: string }[];
  audioIndex: number;
  subtitles: { index: number; name: string; lang?: string }[];
  subtitleIndex: number; // -1 = off
};

export type Attached = {
  hls: Hls | null;
  destroy(): void;
  setLevel(i: number): void;
  setAudio(i: number): void;
  setSubtitle(i: number): void;
};

export type AttachEvents = {
  onTracks?(t: Tracks): void;
  /** Unrecoverable: the caller decides (next source, retry, message). */
  onFatal?(reason: string): void;
  onLog?(msg: string): void;
};

function tracksOf(hls: Hls): Tracks {
  return {
    levels: hls.levels.map((l: Level, index) => ({ index, height: l.height, bitrate: l.bitrate })),
    level: hls.autoLevelEnabled ? -1 : hls.currentLevel,
    audio: hls.audioTracks.map((a: MediaPlaylist, index) => ({ index, name: a.name || a.lang || `Track ${index + 1}`, lang: a.lang })),
    audioIndex: hls.audioTrack,
    subtitles: hls.subtitleTracks.map((s: MediaPlaylist, index) => ({ index, name: s.name || s.lang || `Track ${index + 1}`, lang: s.lang })),
    subtitleIndex: hls.subtitleTrack,
  };
}

/**
 * hls.js resolves a playlist's relative paths against the address the answer
 * came from, which is the relay's. Report the real address instead (after
 * redirects: the relay's x-final-url), so "enc.key" or "v1.m3u8" resolve
 * against the source and not against 127.0.0.1.
 */
const XhrLoader = Hls.DefaultConfig.loader as unknown as new (config: unknown) => {
  load(context: LoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<LoaderContext>): void;
};
class RelayLoader extends XhrLoader {
  load(context: LoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<LoaderContext>) {
    const original = context.url;
    super.load(context, config, {
      ...callbacks,
      onSuccess: (response, stats, ctx, details) => {
        const xhr = details as XMLHttpRequest | undefined;
        response.url = xhr?.getResponseHeader?.("x-final-url") || original;
        callbacks.onSuccess(response, stats, ctx, details);
      },
    });
  }
}

export async function attach(video: HTMLVideoElement, play: PlayInfo, url: string, ev: AttachEvents = {}): Promise<Attached> {
  await ensureRelay();
  const headers = play.headers;

  if (!play.hls) {
    video.src = relayUrl(url, headers);
    return {
      hls: null,
      destroy() {
        video.removeAttribute("src");
        video.load();
      },
      setLevel() {},
      setAudio() {},
      setSubtitle() {},
    };
  }

  const hls = new Hls({
    loader: RelayLoader as unknown as typeof Hls.DefaultConfig.loader,
    xhrSetup: (xhr, u) => {
      xhr.open("GET", relayUrl(u, headers), true);
    },
    // Cold CDNs serve the first chunks slowly: start low and let ABR climb
    // (the TV's setInitialBitrateEstimate(1.2 Mbit/s), same reason).
    abrEwmaDefaultEstimate: 1_200_000,
    capLevelToPlayerSize: true,
    maxBufferLength: 60,
    maxMaxBufferLength: 180,
    backBufferLength: 60,
    renderTextTracksNatively: true,
    enableWorker: true,
    fragLoadPolicy: {
      default: {
        maxTimeToFirstByteMs: 10_000,
        maxLoadTimeMs: 60_000,
        timeoutRetry: { maxNumRetry: 4, retryDelayMs: 0, maxRetryDelayMs: 0 },
        errorRetry: { maxNumRetry: 6, retryDelayMs: 1000, maxRetryDelayMs: 8000 },
      },
    },
  });

  let recovered = 0;
  const emit = () => ev.onTracks?.(tracksOf(hls));
  hls.on(Hls.Events.MANIFEST_PARSED, emit);
  hls.on(Hls.Events.LEVEL_SWITCHED, emit);
  hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, emit);
  hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, emit);
  hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, emit);
  hls.on(Hls.Events.SUBTITLE_TRACK_SWITCH, emit);
  hls.on(Hls.Events.ERROR, (_e, data: ErrorData) => {
    if (!data.fatal) return;
    ev.onLog?.(`hls fatal ${data.type} ${data.details} ${data.response?.code ?? ""}`);
    // One recovery per kind before giving up on this stream.
    if (data.type === Hls.ErrorTypes.MEDIA_ERROR && recovered < 2) {
      recovered++;
      hls.recoverMediaError();
      return;
    }
    if (data.type === Hls.ErrorTypes.NETWORK_ERROR && recovered < 2 && data.details !== Hls.ErrorDetails.MANIFEST_LOAD_ERROR) {
      recovered++;
      hls.startLoad();
      return;
    }
    ev.onFatal?.(`${data.details}${data.response?.code ? ` (HTTP ${data.response.code})` : ""}`);
  });

  hls.loadSource(url);
  hls.attachMedia(video);

  return {
    hls,
    destroy() {
      hls.destroy();
    },
    setLevel(i) {
      hls.currentLevel = i; // -1 = auto
      emit();
    },
    setAudio(i) {
      hls.audioTrack = i;
    },
    setSubtitle(i) {
      hls.subtitleTrack = i;
      hls.subtitleDisplay = i >= 0;
    },
  };
}
