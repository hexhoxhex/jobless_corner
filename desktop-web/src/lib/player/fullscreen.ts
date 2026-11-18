// Fullscreen: the app window itself in Tauri (the whole screen, not just the
// webview's area), the page's own fullscreen in a browser.

import { inTauri } from "../net/relay";

export async function isFullscreen(): Promise<boolean> {
  if (inTauri()) {
    const { getCurrentWindow } = await import("@tauri-apps/api/window");
    return getCurrentWindow().isFullscreen();
  }
  return !!document.fullscreenElement;
}

export async function setFullscreen(on: boolean): Promise<void> {
  if (inTauri()) {
    const { getCurrentWindow } = await import("@tauri-apps/api/window");
    await getCurrentWindow().setFullscreen(on);
    return;
  }
  if (on && !document.fullscreenElement) await document.documentElement.requestFullscreen().catch(() => undefined);
  if (!on && document.fullscreenElement) await document.exitFullscreen().catch(() => undefined);
}
