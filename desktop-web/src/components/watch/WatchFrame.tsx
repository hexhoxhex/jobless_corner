"use client";

import { useEffect, useRef } from "react";
import clsx from "clsx";
import { ArrowLeft } from "lucide-react";
import { BrandMark } from "@/components/BrandMark";
import { WindowControls, usePlatform } from "@/components/TitleBar";

export const LAYOUT_KEY = "player_layout";

/** "fill": the picture takes the whole window; else the player sits beside the side panel. */
export function readFillLayout(): boolean {
  try {
    return localStorage.getItem(LAYOUT_KEY) === "fill";
  } catch {
    return false;
  }
}

export function saveFillLayout(fill: boolean) {
  try {
    localStorage.setItem(LAYOUT_KEY, fill ? "fill" : "panel");
  } catch {
    /* not kept */
  }
}

const PANEL_KEYS = new Set(["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Enter", "Tab", "PageUp", "PageDown", "Home", "End"]);

/**
 * Keys meant for the side panel, not the player: anything typed into its
 * search box, and moving through its lists. Space, K, M, F and T still
 * reach the player after an item in the list was clicked.
 */
export function keyIsForPanel(e: KeyboardEvent): boolean {
  const t = e.target as HTMLElement | null;
  if (!t) return false;
  if (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable) return true;
  return PANEL_KEYS.has(e.key) && !!t.closest("[data-side-panel]");
}

/**
 * The watch page, as on a video site: the player, what is playing under it,
 * then more to watch as cards ([below]: episodes, what is on now), with a
 * side panel to search and pick without leaving. The player stays pinned at
 * the top while the cards scroll under it. With [fill] the player takes the
 * whole window instead (the bigger-player button, T, or full screen). The
 * player element itself never moves in the tree, so switching layouts
 * doesn't restart the stream.
 */
export function WatchFrame({
  fill,
  onBack,
  player,
  playerProps,
  hideCursor,
  info,
  below,
  panel,
}: {
  fill: boolean;
  onBack: () => void;
  player: React.ReactNode;
  playerProps: React.HTMLAttributes<HTMLDivElement>;
  hideCursor: boolean;
  info: React.ReactNode;
  below?: React.ReactNode;
  panel: React.ReactNode;
}) {
  const platform = usePlatform();
  const box = useRef<HTMLDivElement>(null);

  // The control bar's menus open upwards: keep them inside the picture.
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => el.style.setProperty("--menu-max", `${Math.max(160, el.clientHeight - 84)}px`));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  return (
    <div className="fixed inset-0 z-player flex flex-col bg-bg">
      <header
        data-tauri-drag-region
        className={clsx("flex h-12 shrink-0 select-none items-center gap-2", platform === "mac" ? "pl-[84px]" : "pl-3")}
      >
        <button
          onClick={onBack}
          className="flex h-9 cursor-pointer items-center gap-2 rounded-[10px] px-2.5 text-sm font-medium text-ink-3 transition-colors duration-fast hover:bg-white/[0.06] hover:text-ink"
        >
          <ArrowLeft className="h-[18px] w-[18px]" />
          Back
        </button>
        <span data-tauri-drag-region className="ml-1 flex items-center gap-2 text-sm font-semibold text-ink-3">
          <BrandMark className="pointer-events-none h-6 w-6 shadow-none" />
          <span data-tauri-drag-region className="font-display font-extrabold tracking-tight text-ink-2">
            Vijana BaruBaru
          </span>
        </span>
        <div data-tauri-drag-region className="flex-1 self-stretch" />
        {(platform === "windows" || platform === "linux") && <WindowControls className="self-start" />}
      </header>

      <div className="flex min-h-0 flex-1 gap-5 px-5 pb-5">
        <div className="scroll-area min-w-0 flex-1 pr-1">
          {/* At most 62% of the window tall, so there is always room under
              it for the cards to scroll through. */}
          <div className="mx-auto w-full" style={{ maxWidth: "calc(62vh * 16 / 9)" }}>
            <div className={clsx(!fill && "sticky top-0 z-10 bg-bg pb-1")}>
              <div
                ref={box}
                {...playerProps}
                className={clsx(
                  "select-none bg-black",
                  fill ? "fixed inset-0 z-[5]" : "relative aspect-video w-full rounded-panel ring-1 ring-line",
                  hideCursor && "cursor-none",
                )}
              >
                {player}
              </div>
            </div>
            {info}
            {below && <div className="pt-2">{below}</div>}
          </div>
        </div>
        <aside
          data-side-panel
          aria-label="More to watch"
          className="flex min-h-0 w-[300px] shrink-0 flex-col overflow-hidden rounded-panel border border-line bg-[#13151a] lg:w-[340px] xl:w-[380px]"
        >
          {panel}
        </aside>
      </div>
    </div>
  );
}
