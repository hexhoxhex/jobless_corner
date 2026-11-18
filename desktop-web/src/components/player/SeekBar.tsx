"use client";

import { useRef, useState } from "react";

export function fmtTime(s: number): string {
  if (!Number.isFinite(s) || s < 0) s = 0;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = Math.floor(s % 60);
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}` : `${m}:${String(sec).padStart(2, "0")}`;
}

/** Played and buffered ranges, a hover time, click or drag to seek. */
export function SeekBar({
  position,
  duration,
  buffered,
  onSeek,
}: {
  position: number;
  duration: number;
  buffered: number;
  onSeek: (s: number) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [drag, setDrag] = useState<number | null>(null);

  const at = (clientX: number) => {
    const r = ref.current!.getBoundingClientRect();
    return Math.min(1, Math.max(0, (clientX - r.left) / r.width)) * duration;
  };
  const shown = drag ?? position;
  const pct = (v: number) => (duration > 0 ? `${Math.min(100, (v / duration) * 100)}%` : "0%");

  return (
    <div
      ref={ref}
      role="slider"
      aria-label="Seek"
      aria-valuemin={0}
      aria-valuemax={Math.round(duration)}
      aria-valuenow={Math.round(shown)}
      aria-valuetext={`${fmtTime(shown)} of ${fmtTime(duration)}`}
      tabIndex={0}
      className="group relative flex h-5 cursor-pointer items-center"
      onMouseMove={(e) => setHover(at(e.clientX))}
      onMouseLeave={() => setHover(null)}
      onPointerDown={(e) => {
        if (duration <= 0) return;
        (e.target as HTMLElement).setPointerCapture(e.pointerId);
        setDrag(at(e.clientX));
      }}
      onPointerMove={(e) => drag !== null && setDrag(at(e.clientX))}
      onPointerUp={(e) => {
        if (drag !== null) {
          onSeek(at(e.clientX));
          setDrag(null);
        }
      }}
    >
      <div className="relative h-1 w-full overflow-hidden rounded-full bg-white/20 transition-[height] duration-fast group-hover:h-1.5">
        <div className="absolute inset-y-0 left-0 bg-white/30" style={{ width: pct(buffered) }} />
        <div className="absolute inset-y-0 left-0 bg-accent" style={{ width: pct(shown) }} />
      </div>
      <div
        className="pointer-events-none absolute h-3.5 w-3.5 -translate-x-1/2 rounded-full bg-white opacity-0 shadow transition-opacity duration-fast group-hover:opacity-100"
        style={{ left: pct(shown) }}
      />
      {hover !== null && duration > 0 && (
        <div
          className="pointer-events-none absolute -top-8 -translate-x-1/2 rounded-md bg-black/85 px-2 py-0.5 text-xs font-medium tabular text-ink"
          style={{ left: pct(hover) }}
        >
          {fmtTime(hover)}
        </div>
      )}
    </div>
  );
}
