"use client";

import { useEffect, useState } from "react";
import clsx from "clsx";
import { Copy, Minus, Square, X } from "lucide-react";
import { inTauri } from "@/lib/net/relay";

type Platform = "windows" | "mac" | "linux" | "browser";

function detect(): Platform {
  if (typeof navigator === "undefined") return "browser";
  const ua = navigator.userAgent;
  if (!inTauri()) return "browser";
  if (/Mac/i.test(ua)) return "mac";
  if (/Windows/i.test(ua)) return "windows";
  return "linux";
}

/** Which title bar this window draws (after mount: it depends on the window). */
export function usePlatform(): Platform | null {
  const [p, setP] = useState<Platform | null>(null);
  useEffect(() => setP(detect()), []);
  return p;
}

async function win() {
  const { getCurrentWindow } = await import("@tauri-apps/api/window");
  return getCurrentWindow();
}

/** Minimise, maximise/restore and close, drawn by the app (Windows, Linux). */
export function WindowControls({ className }: { className?: string }) {
  const [maximized, setMaximized] = useState(false);
  const live = inTauri();

  useEffect(() => {
    if (!live) return;
    let off: (() => void) | undefined;
    win().then(async (w) => {
      setMaximized(await w.isMaximized());
      off = await w.onResized(async () => setMaximized(await w.isMaximized()));
    });
    return () => off?.();
  }, [live]);

  const btn =
    "flex h-9 w-[46px] cursor-default items-center justify-center text-ink-3 transition-colors duration-fast hover:bg-white/10 hover:text-ink";
  return (
    <div className={clsx("flex", className)}>
      <button aria-label="Minimise" title="Minimise" className={btn} onClick={() => live && win().then((w) => w.minimize())}>
        <Minus className="h-4 w-4" strokeWidth={1.5} />
      </button>
      <button
        aria-label={maximized ? "Restore" : "Maximise"}
        title={maximized ? "Restore" : "Maximise"}
        className={btn}
        onClick={() => live && win().then((w) => w.toggleMaximize())}
      >
        {maximized ? <Copy className="h-3.5 w-3.5 -scale-x-100" strokeWidth={1.5} /> : <Square className="h-3.5 w-3.5" strokeWidth={1.5} />}
      </button>
      <button
        aria-label="Close"
        title="Close"
        className={clsx(btn, "hover:!bg-[#e81123] hover:!text-white")}
        onClick={() => live && win().then((w) => w.close())}
      >
        <X className="h-4 w-4" strokeWidth={1.5} />
      </button>
    </div>
  );
}

/**
 * The window's top bar over the page, in the page's own colour: no white
 * system strip over a dark app. Drag it to move the window, double-click to
 * maximise. The sidebar runs the full height beside it (its top moves the
 * window too, and on macOS the system's own buttons sit there, titleBarStyle
 * "Overlay"). In a plain browser (development) it is drawn too, so the layout
 * matches.
 */
export function TitleBar() {
  const platform = usePlatform();
  if (!platform) return <div className="h-9 shrink-0 bg-bg" />;
  return (
    <div data-tauri-drag-region className="relative z-header flex h-9 shrink-0 select-none items-center bg-bg">
      <div data-tauri-drag-region className="flex-1 self-stretch" />
      {platform !== "mac" && <WindowControls />}
    </div>
  );
}
