// Play a resolved live stream. What the TV app's LiveStreamProxy taught,
// applied with hls.js:
//
//  - Every request carries the stream's headers (through the relay).
//  - Players that renew their own token (wideiptv/hdm1: 300 s tokens) get it
//    renewed 30 s before expiry, and every request to that server carries
//    the newest token.
//  - cdn.hdm1.net deletes a chunk the moment it leaves its 4-chunk list
//    (2026-11-18), so the player stays two chunks back from live and a
//    missing chunk is a jump to the live edge, not a stop.
//  - Never speeds playback up to catch up.

import Hls, { type ErrorData, type LoaderCallbacks, type LoaderConfiguration, type LoaderContext } from "hls.js";
import { ensureRelay, hostOf, relayUrl } from "../net/relay";
import { noteSlow, renewToken, type LiveStream } from "./resolver";

export type LiveEvents = {
  onLog?(m: string): void;
  /** This stream can't go on: the caller tries another route. */
  onFatal?(reason: string): void;
  /** Plays, but its server can't deliver chunks as fast as they play: the
   *  caller may look for a faster route (this one keeps playing meanwhile). */
  onSlow?(reason: string): void;
};

export type LiveAttached = { destroy(): void; hls: Hls };

const XhrLoader = Hls.DefaultConfig.loader as unknown as new (config: unknown) => {
  load(context: LoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<LoaderContext>): void;
};

/** Edge jumps allowed per minute before the stream counts as broken. */
const MAX_JUMPS_PER_MIN = 5;

export async function attachLive(video: HTMLVideoElement, stream: LiveStream, ev: LiveEvents = {}): Promise<LiveAttached> {
  await ensureRelay();
  const host = hostOf(stream.url);
  let token = stream.refresh?.token;
  let renewTimer: ReturnType<typeof setTimeout> | null = null;
  const ac = new AbortController();

  /** The newest token on any URL to the stream's own server. */
  const withToken = (u: string) =>
    token && hostOf(u) === host ? u.replace(/([?&]token=)[^&#]+/, (_m, p: string) => p + encodeURIComponent(token!)) : u;

  class LiveLoader extends XhrLoader {
    load(context: LoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<LoaderContext>) {
      const original = context.url;
      super.load(context, config, {
        ...callbacks,
        onSuccess: (response, stats, ctx, details) => {
          // Relative chunk paths resolve against the real playlist, not the relay.
          const xhr = details as XMLHttpRequest | undefined;
          response.url = xhr?.getResponseHeader?.("x-final-url") || original;
          callbacks.onSuccess(response, stats, ctx, details);
        },
      });
    }
  }

  const hls = new Hls({
    loader: LiveLoader as unknown as typeof Hls.DefaultConfig.loader,
    xhrSetup: (xhr, u) => {
      // Chunks in parallel pieces: some live servers trickle each connection.
      const chunk = !/\.m3u8(\?|#|$)/i.test(u);
      xhr.open("GET", relayUrl(withToken(u), stream.headers, { pieces: chunk }), true);
    },
    liveSyncDurationCount: 2,
    liveMaxLatencyDurationCount: 6,
    maxLiveSyncPlaybackRate: 1,
    backBufferLength: 30,
    maxBufferLength: 30,
    enableWorker: true,
    lowLatencyMode: false,
    manifestLoadPolicy: {
      default: {
        maxTimeToFirstByteMs: 15_000,
        maxLoadTimeMs: 20_000,
        timeoutRetry: { maxNumRetry: 2, retryDelayMs: 0, maxRetryDelayMs: 0 },
        errorRetry: { maxNumRetry: 2, retryDelayMs: 1000, maxRetryDelayMs: 4000 },
      },
    },
    playlistLoadPolicy: {
      default: {
        maxTimeToFirstByteMs: 10_000,
        maxLoadTimeMs: 15_000,
        timeoutRetry: { maxNumRetry: 3, retryDelayMs: 0, maxRetryDelayMs: 0 },
        errorRetry: { maxNumRetry: 4, retryDelayMs: 1000, maxRetryDelayMs: 6000 },
      },
    },
    fragLoadPolicy: {
      default: {
        maxTimeToFirstByteMs: 8_000,
        maxLoadTimeMs: 30_000,
        timeoutRetry: { maxNumRetry: 2, retryDelayMs: 0, maxRetryDelayMs: 0 },
        errorRetry: {
          maxNumRetry: 3,
          retryDelayMs: 500,
          maxRetryDelayMs: 3000,
          // A deleted chunk never comes back: don't ask again.
          shouldRetry: (_cfg, _count, _timeout, resp, retry) => (resp?.code === 404 ? false : retry),
        },
      },
    },
  });

  // Token renewal, as the source's own player does it.
  const scheduleRenew = (expiresIn: number) => {
    if (!stream.refresh) return;
    // 90 s early, not the page's 30: a minimised window's timers can run up
    // to a minute late.
    const wait = Math.max(20, expiresIn - 90) * 1000;
    renewTimer = setTimeout(async () => {
      const r = await renewToken({ ...stream.refresh!, token: token ?? stream.refresh!.token }, ac.signal);
      if (ac.signal.aborted) return;
      if (r) {
        token = r.token;
        ev.onLog?.(`token renewed, ${r.expiresIn} s`);
        scheduleRenew(r.expiresIn);
      } else {
        ev.onLog?.("token renewal refused");
        // The next playlist refusal ends this stream; the caller resolves again.
      }
    }, wait);
  };
  scheduleRenew(stream.refresh?.expiresIn ?? 0);

  const jumps: number[] = [];
  let mediaRecoveries = 0;
  hls.on(Hls.Events.ERROR, (_e, data: ErrorData) => {
    if (!data.fatal) return;
    const code = data.response?.code;
    ev.onLog?.(`hls fatal ${data.type} ${data.details} ${code ?? ""}`);
    if (data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries < 3) {
      mediaRecoveries++;
      hls.recoverMediaError();
      return;
    }
    const playlistRefused =
      (data.details === Hls.ErrorDetails.LEVEL_LOAD_ERROR || data.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR) &&
      (code === 401 || code === 403);
    if (data.type === Hls.ErrorTypes.NETWORK_ERROR && !playlistRefused) {
      const now = Date.now();
      while (jumps.length && now - jumps[0] > 60_000) jumps.shift();
      if (jumps.length < MAX_JUMPS_PER_MIN) {
        jumps.push(now);
        ev.onLog?.("jumping to the live edge");
        hls.startLoad(-1);
        return;
      }
    }
    ev.onFatal?.(playlistRefused ? `the server refused the playlist (HTTP ${code})` : `${data.details}`);
  });

  // Can this server keep up? Chunks that arrive slower than they play, or
  // don't arrive at all, three times within a minute mean the picture will
  // keep stopping (cdn.hdm1.net, 2026-11-18: 0.2-1.2 Mbit/s for a 3 Mbit/s
  // stream; its chunks were deleted from the live list before they finished).
  const trouble: number[] = [];
  let slowReported = false;
  const noteTrouble = (why: string) => {
    const now = Date.now();
    trouble.push(now);
    while (trouble.length && now - trouble[0] > 60_000) trouble.shift();
    if (!slowReported && trouble.length >= 3) {
      slowReported = true;
      noteSlow(host);
      ev.onSlow?.(`${host}: ${why}`);
    }
  };
  hls.on(Hls.Events.FRAG_LOADED, (_e, data) => {
    const st = data.frag.stats;
    const took = st.loading.end - st.loading.start;
    const dur = data.frag.duration;
    if (!(dur > 0) || !(took > 0) || !st.loaded) return;
    const ratio = took / 1000 / dur;
    const mbps = (st.loaded * 8) / took / 1000;
    ev.onLog?.(`chunk ${(st.loaded / 1024) | 0} KB in ${(took / 1000).toFixed(1)} s, ${mbps.toFixed(2)} Mbit/s (${ratio.toFixed(2)}x its length)`);
    if (ratio > 1) noteTrouble(`chunks arrive slower than they play (${mbps.toFixed(1)} Mbit/s)`);
  });
  hls.on(Hls.Events.ERROR, (_e, data: ErrorData) => {
    if (data.fatal) return;
    if (data.details === Hls.ErrorDetails.FRAG_LOAD_ERROR || data.details === Hls.ErrorDetails.FRAG_LOAD_TIMEOUT) {
      ev.onLog?.(`chunk failed: ${data.details} ${data.response?.code ?? ""}`);
      noteTrouble(`chunks fail to arrive (${data.details})`);
    }
  });

  hls.loadSource(stream.url);
  hls.attachMedia(video);

  return {
    hls,
    destroy() {
      ac.abort();
      if (renewTimer) clearTimeout(renewTimer);
      hls.destroy();
    },
  };
}
