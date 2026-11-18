import type { Headers } from "../net/relay";

export type Quality = { label: string; url: string };

export type Caption = { lang: string; label: string; url: string };

/** What a source hands the player. Shaped like the TV app's PlayInfo. */
export type PlayInfo = {
  provider: string;
  url: string;
  /** Sent with every request for this stream (through the relay). */
  headers: Headers;
  hls: boolean;
  selected: string;
  qualities: Quality[];
  captions: Caption[];
  /** Audio language of this copy: "Original", or the dub's name. */
  language: string;
  /** Other languages the source has this title in. */
  dubs: string[];
};

export type Target = {
  tmdbId: number;
  kind: "movie" | "tv";
  season?: number;
  episode?: number;
  title: string;
  year?: number;
  imdbId?: string;
};

export type ResolveOpts = {
  signal?: AbortSignal;
  /** An audio language other than the original, when the viewer asked. */
  dub?: string;
  log?: (msg: string) => void;
};

export interface Source {
  name: string;
  resolve(t: Target, opts: ResolveOpts): Promise<PlayInfo | null>;
}

/** Can this computer decode HEVC in the player (MSE)? */
export function canPlayHevc(): boolean {
  try {
    return (
      typeof MediaSource !== "undefined" &&
      (MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.B0"') ||
        MediaSource.isTypeSupported('video/mp4; codecs="hev1.1.6.L93.B0"'))
    );
  } catch {
    return false;
  }
}
