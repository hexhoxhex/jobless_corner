//! The hidden player window: for channels whose player only reveals its
//! stream when the page's own script runs (cdnlivetv, waterera, ddyplayer,
//! ...), load the site's page in a hidden, muted window and note the first
//! playlist the player asks for. The desktop counterpart of the TV app's
//! JsIframeResolver / SourceTeacher.
//!
//! - The script below runs in every frame. Frames hand what they see to the
//!   top page, which writes it into the window title; the title change is
//!   how it reaches us (no network and no permission prompt involved).
//! - New windows (pop-up ads) are refused, the top page may not navigate to
//!   another site, every video is muted, and the window is gone as soon as
//!   there is an answer or the time is up.
//! - A page that shows a bot check (Cloudflare challenge / Turnstile) ends
//!   the attempt: we never try to get past one.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::Serialize;
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};

const PREFIX: &str = "VBBCAP:";

const SCRIPT: &str = r#"
(() => {
  if (window.__vbbCap) return;
  window.__vbbCap = true;
  const TOP = window.top === window;
  const report = (m) => { try { document.title = "VBBCAP:" + JSON.stringify(m); } catch (e) {} };
  const send = (kind, value) => {
    const m = { __vbb: 1, kind, value, origin: location.origin, href: location.href, ua: navigator.userAgent };
    if (TOP) report(m); else { try { window.top.postMessage(m, "*"); } catch (e) {} }
  };
  if (TOP) window.addEventListener("message", (e) => { const d = e.data; if (d && d.__vbb === 1) report(d); }, true);
  const seen = new Set();
  const consider = (u) => {
    if (!u) return;
    let abs;
    try { abs = new URL(String(u), location.href).href; } catch (e) { return; }
    if (/\.m3u8(\?|#|$)/i.test(abs) && !seen.has(abs)) { seen.add(abs); send("m3u8", abs); }
  };
  try {
    const open = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) { consider(u); return open.apply(this, arguments); };
  } catch (e) {}
  try {
    const f = window.fetch;
    if (f) window.fetch = function (i) { consider(typeof i === "string" ? i : (i && i.url)); return f.apply(this, arguments); };
  } catch (e) {}
  try {
    const d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "src");
    Object.defineProperty(HTMLMediaElement.prototype, "src", {
      get() { return d.get.call(this); },
      set(v) { consider(v); return d.set.call(this, v); },
      configurable: true,
    });
  } catch (e) {}
  // Never a sound from this window.
  try {
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () { try { this.muted = true; this.volume = 0; } catch (e) {} return play.apply(this, arguments); };
  } catch (e) {}
  setInterval(() => {
    try { for (const e of performance.getEntriesByType("resource")) consider(e.name); } catch (e) {}
    try {
      for (const v of document.querySelectorAll("video,audio")) { v.muted = true; v.volume = 0; consider(v.currentSrc); }
    } catch (e) {}
    try {
      if (/challenges\.cloudflare\.com/.test(location.host) ||
          document.querySelector('iframe[src*="challenges.cloudflare.com"], .cf-turnstile, #challenge-form, #cf-challenge-running')) {
        send("challenge", location.host);
      }
    } catch (e) {}
  }, 300);
})();
"#;

#[derive(Serialize, Clone)]
pub struct Captured {
    pub url: String,
    /// The page that asked for it: its origin is the Referer/Origin the stream expects.
    pub origin: String,
    /// That page's address and the hidden browser's user agent, so the
    /// request can be repeated as the browser made it.
    pub page: String,
    pub ua: String,
}

/// Closes the hidden window however the capture ends. Without it, a capture
/// the page gave up on (channel changed while connecting: the request is
/// dropped mid-wait) left its window open, playing that stream unseen for as
/// long as the app ran and eating the bandwidth of the stream on screen.
struct CloseOnDrop {
    app: AppHandle,
    label: String,
}

impl Drop for CloseOnDrop {
    fn drop(&mut self) {
        if let Some(w) = self.app.get_webview_window(&self.label) {
            let _ = w.destroy();
        }
    }
}

/// One hidden window at a time.
static BUSY: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());
static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

#[derive(serde::Deserialize)]
struct Msg {
    kind: String,
    value: String,
    origin: String,
    #[serde(default)]
    href: String,
    #[serde(default)]
    ua: String,
}

/// Open [url] in the hidden window and wait up to [timeout_ms] for the first
/// playlist its player asks for. Served to the page as the relay's /capture.
pub async fn capture(app: AppHandle, url: String, timeout_ms: Option<u64>) -> Result<Captured, String> {
    let page: tauri::Url = url.parse().map_err(|_| "bad url".to_string())?;
    if page.scheme() != "https" && page.scheme() != "http" {
        return Err("bad url".into());
    }
    let home = page.host_str().unwrap_or_default().to_string();
    let _one = BUSY.lock().await;

    let (tx, rx) = tokio::sync::oneshot::channel::<Result<Captured, String>>();
    let tx = Arc::new(Mutex::new(Some(tx)));
    let label = format!("capture-{}", COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed));

    let tx_title = tx.clone();
    let window = WebviewWindowBuilder::new(&app, &label, WebviewUrl::External(page.clone()))
        .title("")
        .visible(false)
        .focused(false)
        .skip_taskbar(true)
        .inner_size(960.0, 540.0)
        .incognito(true)
        .initialization_script_for_all_frames(SCRIPT)
        .on_new_window(|_url, _features| NewWindowResponse::Deny)
        // The top page stays on the site it was opened on (ads try to leave).
        .on_navigation(move |u| u.host_str().map(|h| h == home).unwrap_or(false) || u.scheme() == "about")
        .on_document_title_changed(move |_w, title| {
            let Some(json) = title.strip_prefix(PREFIX) else { return };
            let Ok(m) = serde_json::from_str::<Msg>(json) else { return };
            let result = match m.kind.as_str() {
                "m3u8" => Ok(Captured { url: m.value, origin: m.origin, page: m.href, ua: m.ua }),
                "challenge" => Err(format!("{} shows a bot check", m.value)),
                _ => return,
            };
            if let Some(tx) = tx_title.lock().ok().and_then(|mut g| g.take()) {
                let _ = tx.send(result);
            }
        })
        .build()
        .map_err(|e| format!("hidden window: {e}"))?;
    let _close = CloseOnDrop { app: app.clone(), label: label.clone() };

    let wait = Duration::from_millis(timeout_ms.unwrap_or(25_000).min(60_000));
    let outcome = match tokio::time::timeout(wait, rx).await {
        Ok(Ok(r)) => r,
        Ok(Err(_)) => Err("hidden window closed".into()),
        Err(_) => Err("no stream seen in time".into()),
    };
    let _ = window.destroy();
    // Whatever was left of it (a late title change) has nowhere to go.
    drop(tx);
    let _ = app.get_webview_window(&label).map(|w| w.destroy());
    outcome
}
