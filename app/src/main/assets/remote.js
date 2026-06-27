/* Vijana BaruBaru — phone-remote SPA */

const $  = (s) => document.querySelector(s);
const $$ = (s) => document.querySelectorAll(s);
const TOKEN_KEY = "vbtv.token";
const THEME_KEY = "vbtv.theme";

let token = localStorage.getItem(TOKEN_KEY) || "";
let me = null;            // { token, role, label }
let prefs = { networks: [], genres: [], denyLanguages: [] };
let lastDetails = null;
let searchTimer = null;
let recoverTimer = null;  // re-auth poll when locked out
let active = "browse";    // active tab — Browse is the discovery default
let browseLoaded = false;
// Live TV state — populated on first open and refreshed when the channels
// cache on the TV finishes loading.
let liveLoaded = false;
let liveChannelsAll = [];
let liveSelectedGroup = "";
let liveQuery = "";
let liveQueryTimer = null;
let livePollTimer = null;

/* ---------- icon helper ---------- */
const ic = (id, cls = "") => `<svg class="icon ${cls}"><use href="#i-${id}"/></svg>`;

/* ---------- theme ---------- */
function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  const use = document.getElementById("themeIcon");
  if (use) use.setAttribute("href", t === "light" ? "#i-moon" : "#i-sun");
}
function initTheme() {
  let t = localStorage.getItem(THEME_KEY);
  if (!t) t = matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  applyTheme(t);
}
initTheme();

/* ---------- fetch wrapper ---------- */
async function api(path, opts = {}) {
  const headers = Object.assign(
    { "Authorization": token ? "Bearer " + token : "" },
    opts.headers || {},
  );
  let res;
  try {
    res = await fetch(path, Object.assign({}, opts, { headers }));
  } catch (e) {
    showBanner("Network unreachable — retrying…", "error");
    throw e;
  }
  hideBanner();
  if (res.status === 403) {
    // Token rejected — start a quiet recovery poll instead of nuking immediately.
    startRecoveryPoll();
    throw new Error("forbidden");
  }
  return res;
}
const get  = (p)        => api(p).then(r => r.json());
const post = (p, body) => api(p, { method: "POST", body: body || null })
  .then(r => r.json());

/* ---------- toast ---------- */
let toastTimer;
function toast(msg) {
  const t = $("#toast"); t.textContent = msg; t.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), 1800);
}

/* ---------- banner (offline / re-auth) ---------- */
function showBanner(text, kind) {
  const b = $("#banner");
  b.textContent = ""; b.appendChild(svgInline("i-bulb"));
  const span = document.createElement("span"); span.textContent = " " + text;
  b.appendChild(span);
  b.className = "banner" + (kind === "error" ? " error" : "");
}
function svgInline(id) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "icon sm");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", "#" + id); svg.appendChild(use); return svg;
}
function hideBanner() { $("#banner").classList.add("hidden"); }

/* ---------- pair gate ---------- */
function showGate(opts = {}) {
  $("#gate").classList.remove("hidden");
  $("#app").classList.add("hidden");
  const err = $("#gateError"), wait = $("#gateWait");
  err.classList.toggle("hidden", !opts.error);
  if (opts.error) err.textContent = opts.error;
  wait.classList.toggle("hidden", !opts.waiting);
  if (opts.waiting) $("#gateWaitText").textContent = opts.waiting;
  $("#gateTitle").textContent = opts.title || "Pair this phone";
  $("#gateBody").textContent  = opts.body  ||
    "Enter the 6-character code shown on the TV — Settings → Remotes.";
}
function hideGate() {
  $("#gate").classList.add("hidden");
  $("#app").classList.remove("hidden");
}

async function pair(code) {
  const p = new URLSearchParams(); if (code) p.set("code", code);
  const res = await fetch("/api/pair?" + p.toString(), { method: "POST" });
  const j = await res.json();
  if (!j.token) throw new Error(j.error || "Pair failed");
  token = j.token; me = j;
  localStorage.setItem(TOKEN_KEY, token);
  return j;
}

$("#codeBtn").onclick = async () => {
  const code = $("#codeInput").value.trim().toUpperCase();
  if (code.length < 4) {
    showGate({ error: "Enter the code from the TV." }); return;
  }
  try {
    await pair(code);
    await afterPair();
  } catch (e) {
    showGate({ error: "Could not pair — check the code and try again." });
  }
};

$("#themeBtn").onclick = () => {
  const next = (document.documentElement.dataset.theme === "light") ? "dark" : "light";
  localStorage.setItem(THEME_KEY, next); applyTheme(next);
};

async function afterPair() {
  hideGate();
  await loadMe();
  if (!me) return;
  if (me.role === "PENDING") {
    showGate({
      title: "Waiting for approval",
      body: "Ask the TV owner to approve this phone in Settings → Remotes.",
      waiting: "Listening for approval…",
    });
    startRecoveryPoll();
    return;
  }
  try { prefs = await get("/api/me/prefs"); } catch (e) {}
  await loadHistory();
  selectTab(active === "details" ? "browse" : active);
  toast("Connected as " + me.label);
  // Non-blocking: ask the TV whether a newer release is on GitHub. Banner
  // hides itself if the user already dismissed this exact version.
  checkUpdate();
  // Network status banner — also handles the "phone can't reach TV" case
  // (we treat that the same as "TV is offline" from a UX perspective).
  startNetworkPoll();
}

async function loadMe() {
  try {
    const r = await api("/api/me");
    if (r.status !== 200) { me = null; return; }
    me = await r.json();
  } catch (e) { me = null; return; }
  $("#meLabel").textContent = me.label;
  const role = $("#meRole");
  role.textContent = me.role; role.className = "role " + me.role;
  // Devices view: managing other phones' permissions is genuinely
  // superuser-only. Debug pane is read-only and the data is already
  // token-gated, so make it available to any paired device — otherwise
  // a single misconfigured pair (reinstall, lost SUPERUSER) hides the
  // most useful diagnostic surface.
  if (me.role === "SUPERUSER") {
    $("#devicesBtn").classList.remove("hidden"); loadDevices();
  } else {
    $("#devicesBtn").classList.add("hidden");
  }
  if (me.role === "SUPERUSER" || me.role === "USER") {
    $("#debugBtn").classList.remove("hidden");
  } else {
    $("#debugBtn").classList.add("hidden");
  }
}

/* ---------- recovery: 403 or PENDING → poll until usable ---------- */
function startRecoveryPoll() {
  if (recoverTimer) return;
  recoverTimer = setInterval(async () => {
    if (!token) { stopRecoveryPoll(); return; }
    try {
      const r = await fetch("/api/me",
        { headers: { "Authorization": "Bearer " + token } });
      if (r.status === 200) {
        const j = await r.json();
        me = j;
        if (j.role === "PENDING") {
          showGate({
            title: "Waiting for approval",
            body: "Ask the TV owner to approve this phone in Settings → Remotes.",
            waiting: "Listening for approval…",
          });
          return;
        }
        stopRecoveryPoll();
        hideGate(); await afterRecover();
      } else if (r.status === 403) {
        // Token removed or device blocked — clear and ask for a new pair.
        token = ""; localStorage.removeItem(TOKEN_KEY);
        stopRecoveryPoll();
        showGate({ error: "This phone was removed — pair again." });
      }
    } catch (e) { /* keep polling */ }
  }, 3000);
}
function stopRecoveryPoll() {
  if (recoverTimer) { clearInterval(recoverTimer); recoverTimer = null; }
}
async function afterRecover() {
  await loadMe(); await loadHistory();
  selectTab(active === "details" ? "search" : active);
  toast("Reconnected");
}

/* ---------- bootstrap ---------- */
async function boot() {
  const params = new URLSearchParams(location.search);
  const urlCode = params.get("pair");
  if (urlCode && !token) {
    try { await pair(urlCode); history.replaceState(null, "", "/"); }
    catch (e) { showGate({ error: "Could not pair — open the QR on the TV again." }); return; }
  }
  if (!token) { showGate(); return; }
  try {
    const r = await fetch("/api/me",
      { headers: { "Authorization": "Bearer " + token } });
    if (r.status !== 200) {
      // Maybe the device was removed; offer to re-pair, but keep polling.
      showGate({ error: "Pairing expired — pair again or wait for re-approval." });
      startRecoveryPoll(); return;
    }
    me = await r.json();
  } catch (e) { showGate(); return; }
  await afterPair();
}

/* ---------- navigation stack ----------
   The six bottom tabs are "roots": switching between them replaces the
   single history entry so they never pile up. "details" and "episodes" are
   pushed FRAMES (history.pushState) so the phone's hardware/gesture Back pops
   them instead of reloading or exiting the whole SPA — that reload was what
   made navigation "reset" and feel broken. popstate re-renders whatever view
   the browser hands back, so Back/Forward Just Work. */
const ROOT_TABS = ["browse", "live", "search", "np", "dl", "prefs", "debug", "devices"];
let originTab = "browse";   // last root tab — pushed frames highlight it + Back falls back to it

function showPane(name) {
  active = name;
  $$(".pane").forEach(p => p.classList.remove("active"));
  $$(".tab").forEach(b => b.classList.remove("active"));
  const pane = $("#pane-" + name); if (pane) pane.classList.add("active");
  // Keep a bottom tab lit even on a pushed frame so the user keeps their
  // bearings: details highlights the tab it was opened from; the episode
  // picker highlights Now-playing.
  const tabFor = (name === "details") ? originTab
               : (name === "episodes") ? "np" : name;
  const btn = document.querySelector(`.tab[data-pane="${tabFor}"]`);
  if (btn) btn.classList.add("active");
  if (name === "np") refresh();
  if (name === "dl") loadDownloads();
  if (name === "devices") loadDevices();
  if (name === "debug") openDebug();
  else closeDebug();
  if (name === "browse" && !browseLoaded) loadBrowse();
  if (name === "live" && !liveLoaded) loadLive();
  if (name === "prefs") loadPrefs();
}

function renderView(view) {
  showPane(view.pane);
  if (view.pane === "details"  && view.ctx) renderDetailsContent(view.ctx);
  if (view.pane === "episodes" && view.ctx) renderEpisodes(view.ctx);
}

/** Navigate to a root tab — collapses back to a single history entry. */
function selectTab(name) {
  if (ROOT_TABS.includes(name)) originTab = name;
  history.replaceState({ pane: name, ctx: null }, "");
  renderView({ pane: name, ctx: null });
}

/** Push a back-able frame (details / episode picker). */
function pushView(pane, ctx) {
  history.pushState({ pane, ctx }, "");
  renderView({ pane, ctx });
}

window.addEventListener("popstate", (e) => {
  const st = e.state;
  if (st && st.pane) renderView({ pane: st.pane, ctx: st.ctx || null });
  else renderView({ pane: originTab || "browse", ctx: null });
});

$$(".tab").forEach(b => b.onclick = () => selectTab(b.dataset.pane));

// Topbar Devices + Debug shortcuts (superuser only — visibility from fetchMe()).
const devicesBtn = $("#devicesBtn");
if (devicesBtn) devicesBtn.onclick = () => selectTab("devices");
const debugBtn = $("#debugBtn");
if (debugBtn) debugBtn.onclick = () => selectTab("debug");

/* ---------- Update banner ---------- */
async function checkUpdate() {
  let r;
  try { r = await get("/api/update"); } catch { return; }
  if (!r || !r.tag) return;   // device says "available:false" → no tag field
  // The user might have dismissed THIS specific version already; honour that.
  if (localStorage.getItem("vbtv.dismissedUpdate") === r.tag) return;
  $("#ubVersion").textContent = "Update available — " + (r.name || ("v" + r.tag));
  const body = String(r.notes || "")
    .replace(/[#*`]+/g, "").split("\n").map(l => l.trim()).filter(Boolean).slice(0, 2).join(" · ");
  $("#ubBody").textContent = body || "Download the latest APK.";
  $("#ubDownload").href = r.apkUrl || r.htmlUrl || "#";
  $("#updateBanner").classList.remove("hidden");
  $("#ubDismiss").onclick = () => {
    localStorage.setItem("vbtv.dismissedUpdate", r.tag);
    $("#updateBanner").classList.add("hidden");
  };
}

/* ---------- Debug pane ---------- */
let debugTimer = null;
function openDebug() {
  if (debugTimer) return;
  refreshDebug();
  debugTimer = setInterval(refreshDebug, 2500);
}
function closeDebug() {
  if (debugTimer) { clearInterval(debugTimer); debugTimer = null; }
}
async function refreshDebug() {
  let d;
  try { d = await get("/api/debug"); } catch { return; }
  if (!d) return;

  const session = d.session || {};
  const cur = d.current || {};
  const channels = Array.isArray(d.channels) ? d.channels : [];
  const events = Array.isArray(d.events) ? d.events : [];
  const today = d.today || {};
  const week = d.week || {};
  const providers = Array.isArray(d.providers) ? d.providers : [];

  // Summary stat grid (this session)
  $("#debugSummary").innerHTML = [
    statHtml("Uptime", fmtUptime(session.uptimeMs), null),
    statHtml("Plays started", session.playsStarted ?? 0, null),
    statHtml("Failures", session.playsFailed ?? 0,
      `${session.httpErrors ?? 0} HTTP errors`),
    statHtml("Rebuffers", session.rebuffers ?? 0,
      `${session.freezes ?? 0} freezes`),
  ].join("");

  // Today + 7-day rollups
  $("#debugRollups").innerHTML = [
    rollupHtml("Today", today),
    rollupHtml("Last 7 days", week),
  ].join("");

  // Providers
  $("#debugProviders").innerHTML = providers.length === 0
    ? `<div class="muted small">No provider activity yet.</div>`
    : providers.map(p => {
        const cls = p.status === "down" ? "err"
                  : p.status === "degraded" ? "warn" : "ok";
        const label = p.status[0].toUpperCase() + p.status.slice(1);
        const lastFail = p.lastFailure
          ? `<div class="muted small">${escapeHtml(p.lastFailure)}</div>` : "";
        return `
          <div class="ch-row">
            <div>
              <div class="name">${escapeHtml(p.name)}</div>
              ${lastFail}
            </div>
            <div class="badges">
              ${badge(cls, label)}
              ${badge("ok", `${p.ok} ok`)}
              ${p.err > 0 ? badge("warn", `${p.err} err`) : ""}
            </div>
          </div>`;
      }).join("");

  // Current stream
  const ratingClass = "rating-" + (cur.rating || "excellent");
  const ratingPct = ({excellent:100, good:75, fair:45, poor:20})[cur.rating || "excellent"];
  const droppedPct = ((cur.droppedRatio || 0) * 100).toFixed(1);
  const bitrateMbps = ((cur.bitrateBps || 0) / 1e6).toFixed(2);
  $("#debugStream").innerHTML = cur.kind === "none" || !cur.title
    ? `<div class="muted small">Nothing playing.</div>`
    : `
      <div class="row"><span>Title</span><span>${escapeHtml(cur.title)}</span></div>
      <div class="row"><span>Kind</span><span>${escapeHtml(cur.kind)}</span></div>
      <div class="row"><span>Resolution</span><span>${escapeHtml(cur.resolution || "—")}</span></div>
      <div class="row"><span>Bitrate</span><span>${bitrateMbps} Mbps</span></div>
      <div class="row"><span>Buffer</span><span>${((cur.bufferMs||0)/1000).toFixed(1)}s</span></div>
      <div class="row"><span>Dropped frames</span><span>${droppedPct}%</span></div>
      <div class="row"><span>Quality</span><span>${escapeHtml(cur.rating || "—")}</span></div>
      <div class="rating-bar"><div class="${ratingClass}" style="width:${ratingPct}%"></div></div>
    `;

  // Channels list — show troublesome ones first (server already sorted by
  // failures*10 + freezes*5 + rebuffers). Limit to 15 to keep the page short.
  $("#debugScope").textContent = channels.length
    ? `(${channels.length} channel${channels.length === 1 ? "" : "s"} touched)`
    : "";
  $("#debugChannels").innerHTML = channels.length === 0
    ? `<div class="muted small">No channels played yet.</div>`
    : channels.slice(0, 15).map(ch => {
        const badges = [];
        if (ch.failures > 0) badges.push(badge("err", `${ch.failures} fail`));
        if (ch.freezes > 0)  badges.push(badge("err", `${ch.freezes} froze`));
        if (ch.rebuffers > 0) badges.push(badge("warn", `${ch.rebuffers} reb`));
        badges.push(badge("ok", `${ch.plays} play${ch.plays === 1 ? "" : "s"}`));
        const lastFail = ch.lastFailure ? `<div class="muted small">${escapeHtml(ch.lastFailure)}</div>` : "";
        return `
          <div class="ch-row">
            <div>
              <div class="name">${escapeHtml(ch.name || ch.id)}</div>
              ${lastFail}
            </div>
            <div class="badges">${badges.join("")}</div>
          </div>`;
      }).join("");

  // Events
  $("#debugEvents").innerHTML = events.length === 0
    ? `<div class="muted small">No events logged yet.</div>`
    : events.slice(0, 25).map(e => `
        <div class="ev severity-${escapeHtml(e.severity || "info")}">
          <span class="ts">${fmtTs(e.atMs)}</span>
          <span class="body">${escapeHtml(e.message || "")}</span>
        </div>`).join("");
}

function statHtml(label, value, sub) {
  return `
    <div class="stat">
      <span class="label">${escapeHtml(label)}</span>
      <span class="value">${escapeHtml(String(value))}</span>
      ${sub ? `<span class="sub">${escapeHtml(sub)}</span>` : ""}
    </div>`;
}
function rollupHtml(label, bucket) {
  const b = bucket || {};
  return `
    <div class="roll">
      <span class="label">${escapeHtml(label)}</span>
      <div class="nums">
        <span>Plays</span>     <span>${b.plays    ?? 0}</span>
        <span>Failed</span>    <span>${b.failed   ?? 0}</span>
        <span>Freezes</span>   <span>${b.freezes  ?? 0}</span>
        <span>Rebuffers</span> <span>${b.rebuffers?? 0}</span>
        <span>HTTP errs</span> <span>${b.httpErrors ?? 0}</span>
      </div>
    </div>`;
}

/* ---------- Bandwidth probe ---------- */
const bwTestBtn = $("#bwTestBtn");
if (bwTestBtn) {
  bwTestBtn.onclick = async () => {
    const result = $("#bwResult");
    bwTestBtn.disabled = true;
    bwTestBtn.textContent = "Testing…";
    result.innerHTML = `<span class="muted small">Downloading 2 MB from the TV's egress…</span>`;
    let r;
    try {
      r = await post("/api/debug/bandwidth");
    } catch (e) {
      result.innerHTML = `<span class="muted small">Couldn't reach the TV.</span>`;
      bwTestBtn.disabled = false; bwTestBtn.textContent = "Test now";
      return;
    }
    bwTestBtn.disabled = false; bwTestBtn.textContent = "Test now";
    renderBandwidthResult(r);
  };
}

function renderBandwidthResult(r) {
  const result = $("#bwResult");
  if (!r || r.verdict === "error") {
    result.innerHTML = `
      <div class="row">
        <span>Verdict</span>
        <span class="verdict-pill error">Error</span>
      </div>
      <div class="hint">${escapeHtml(r && r.error || "Test failed.")}</div>`;
    return;
  }
  const v = String(r.verdict || "fair");
  const mbps = Number(r.mbps || 0);
  // Compare against the currently-playing channel's bitrate (if any) and
  // give the user a one-line verdict that's actionable.
  let hint = "";
  if (v === "excellent") hint = "Plenty of headroom for any live channel.";
  else if (v === "good") hint = "1080p HD live channels should play smoothly.";
  else if (v === "fair") hint = "720p live channels OK; 1080p HD will rebuffer.";
  else hint = "Even SD channels will rebuffer. Move closer to your router or switch to wired.";

  result.innerHTML = `
    <div class="row">
      <span>Throughput</span>
      <span>${mbps.toFixed(2)} Mbps</span>
    </div>
    <div class="row">
      <span>Sample</span>
      <span>${Math.round((r.bytes || 0)/1024)} KB in ${Math.round(r.elapsedMs || 0)} ms</span>
    </div>
    <div class="row">
      <span>Verdict</span>
      <span class="verdict-pill ${escapeHtml(v)}">${escapeHtml(v.toUpperCase())}</span>
    </div>
    <div class="hint">${escapeHtml(hint)}</div>`;
}

// Clear button — POST /api/debug/clear, refresh once so the user sees the reset.
const debugClearBtn = $("#debugClearBtn");
if (debugClearBtn) {
  debugClearBtn.onclick = async () => {
    debugClearBtn.disabled = true;
    debugClearBtn.textContent = "Clearing…";
    try {
      await post("/api/debug/clear");
      await refreshDebug();
      showToast("Logs cleared");
    } catch (e) { showToast("Couldn't clear"); }
    debugClearBtn.disabled = false;
    debugClearBtn.textContent = "Clear";
  };
}

/* ---------- Network status banner ----------
 * Polls /api/network every 8s — light, doesn't need to be precise. State
 * comes from the TV's NetworkMonitor (Online / Checking / OfflineLong).
 * Banner is hidden when "online", quiet when "checking", severe when
 * offline >3 min OR when we can't reach the TV at all. */
let netBannerTimer = null;
async function pollNetwork() {
  let r = null;
  let phoneCanReachTv = true;
  try { r = await get("/api/network"); }
  catch { phoneCanReachTv = false; }
  const banner = $("#netBanner");
  const text = $("#netBannerText");
  if (!phoneCanReachTv) {
    banner.classList.remove("hidden");
    banner.classList.add("severe");
    text.textContent = "Phone can't reach the TV. Are you on the same Wi-Fi?";
    return;
  }
  const state = r && r.state;
  if (!state || state === "online") {
    banner.classList.add("hidden");
    banner.classList.remove("severe");
    return;
  }
  banner.classList.remove("hidden");
  if (state === "checking") {
    banner.classList.remove("severe");
    text.textContent = "Checking the TV's connection…";
  } else { // offlinelong
    banner.classList.add("severe");
    const mins = Math.floor((r.sinceMs || 0) / 60000);
    text.textContent = mins > 60
      ? "TV's been offline over an hour. Try moving closer to the router."
      : "TV is offline. We'll keep watching for it.";
  }
}
function startNetworkPoll() {
  if (netBannerTimer) return;
  pollNetwork();
  netBannerTimer = setInterval(pollNetwork, 8000);
}
function badge(severity, text) {
  return `<span class="badge ${severity}">${escapeHtml(text)}</span>`;
}
function fmtUptime(ms) {
  if (!ms) return "0s";
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${r}s`;
  return `${r}s`;
}
function fmtTs(elapsedMs) {
  const s = Math.floor(elapsedMs / 1000);
  const m = Math.floor(s / 60), r = s % 60;
  return `${m}:${String(r).padStart(2, "0")}`;
}

/* ---------- search ---------- */
$("#q").addEventListener("input", (e) => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => doSearch(e.target.value), 320);
});
async function doSearch(q) {
  const grid = $("#results");
  if (q.trim().length < 2) {
    grid.innerHTML = ""; $("#searchEmpty").classList.remove("hidden"); return;
  }
  $("#searchEmpty").classList.add("hidden");
  grid.innerHTML = skeletonGrid(6);
  let items = [];
  try { items = await get("/api/search?q=" + encodeURIComponent(q)); }
  catch (e) { grid.innerHTML = `<div class="muted small">Search failed.</div>`; return; }
  if (!items.length) {
    grid.innerHTML = `<div class="muted small">No matches.</div>`; return;
  }
  grid.innerHTML = "";
  items.forEach(it => grid.appendChild(searchCard(it)));
}
function skeletonGrid(n) {
  let out = ""; for (let i = 0; i < n; i++) {
    out += `<div class="card-item">
      <div class="poster"></div>
      <div class="meta"><div class="t" style="background:var(--surface-2);height:1.2em;border-radius:6px"></div></div>
    </div>`;
  } return out;
}
function searchCard(it) {
  const el = document.createElement("div");
  el.className = "card-item";
  const badge = it.isSeries
    ? `<span class="badge tv">TV</span>`
    : `<span class="badge movie">Movie</span>`;
  const rating = it.rating
    ? `<span class="rating">${ic("star","sm")}${it.rating.toFixed(1)}</span>` : "";
  el.innerHTML = `
    <img class="poster" loading="lazy" src="${it.cover}" onerror="this.style.opacity=.3" />
    <div class="meta">
      <div class="t">${escapeHtml(it.title)}</div>
      <div class="s">${badge}${it.year ? `<span>${it.year}</span>` : ""}${rating}</div>
    </div>`;
  el.onclick = () => openDetails(it);
  return el;
}
function escapeHtml(s) {
  return (s || "").replace(/[&<>"]/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;",
  }[c]));
}

/* ---------- details ---------- */
function openDetails(it) {
  pushView("details", it);
}
async function renderDetailsContent(it) {
  lastDetails = { item: it, seasons: [] };
  $("#dCover").src = it.cover || "";
  $("#dTitle").textContent = it.title;
  const subParts = [];
  subParts.push(it.isSeries ? "TV" : "Movie");
  if (it.year)   subParts.push(it.year);
  if (it.rating) subParts.push("★ " + it.rating.toFixed(1));
  $("#dSub").textContent = subParts.join(" · ");
  $("#dEpisodes").classList.toggle("hidden", !it.isSeries);

  // Description: TMDB picks carry their own overview; aoneroom records need a
  // details fetch.
  if (it.subjectId && it.subjectId.startsWith("tmdb:")) {
    $("#dDesc").textContent = it.overview || "";
  } else {
    $("#dDesc").textContent = "Loading…";
    try {
      const d = await get("/api/details?subjectId=" + encodeURIComponent(it.subjectId));
      $("#dDesc").textContent = d.description || "";
    } catch (e) { $("#dDesc").textContent = ""; }
  }

  // Real seasons + episodes — /api/episodes bridges TMDB→aoneroom and returns
  // only what actually exists, so no hardcoded Seasons 1-8 and no typing an
  // episode number that resolves to nothing.
  if (it.isSeries) {
    $("#dEpStatus").textContent = "Loading episodes…";
    $("#dSeason").innerHTML = ""; $("#dEpisode").innerHTML = "";
    const seasons = await fetchEpisodeMap(it);
    if (!seasons.length) {
      $("#dEpStatus").textContent = "Episodes unavailable for this title.";
      return;
    }
    lastDetails.seasons = seasons;
    const sel = $("#dSeason");
    seasons.forEach(s => {
      const o = document.createElement("option");
      o.value = s.season; o.textContent = "Season " + s.season;
      sel.appendChild(o);
    });
    fillDetailEpisodes(seasons[0].season);
    sel.onchange = () => fillDetailEpisodes(Number(sel.value));
    $("#dEpStatus").textContent = "";
  }
}
// Back returns to wherever you came from (Browse / Search / History / …)
// via the history stack, not a hardcoded tab.
$("#detailsBack").onclick = () => history.back();

/* ---------- real episode map (shared by details + the picker) ---------- */
async function fetchEpisodeMap(it) {
  const params = new URLSearchParams({ subjectId: it.subjectId || "" });
  if (it.title) params.set("title", it.title);
  if (it.year)  params.set("year", it.year);
  params.set("type", it.type != null ? it.type : 2);
  try {
    const d = await get("/api/episodes?" + params.toString());
    return (d && d.seasons) || [];
  } catch (e) { return []; }
}
function fillDetailEpisodes(se) {
  const season = (lastDetails.seasons || []).find(s => Number(s.season) === Number(se));
  const sel = $("#dEpisode"); sel.innerHTML = "";
  (season ? season.episodes : []).forEach(n => {
    const o = document.createElement("option");
    o.value = n; o.textContent = "Episode " + n;
    sel.appendChild(o);
  });
}

/* ---------- episode picker (jump to any season/episode, incl. while playing) ---------- */
let epPick = null;   // { subjectId, title, year, type, cover, se, ep, seasons }

function openEpisodePicker(ctx) { pushView("episodes", ctx); }
$("#epBack").onclick = () => history.back();

async function renderEpisodes(ctx) {
  epPick = Object.assign({}, ctx);
  $("#epPickTitle").textContent = ctx.title || "Episodes";
  $("#epSeasonChips").innerHTML = "";
  $("#epGrid").innerHTML = "";
  $("#epPickStatus").textContent = "Loading episodes…";
  const seasons = await fetchEpisodeMap(ctx);
  if (!seasons.length) {
    $("#epPickStatus").textContent = "No episodes found for this title.";
    return;
  }
  epPick.seasons = seasons;
  let activeSe = Number(ctx.se);
  if (!seasons.some(s => Number(s.season) === activeSe)) activeSe = Number(seasons[0].season);
  drawEpSeasons(activeSe);
  drawEpGrid(activeSe);
  $("#epPickStatus").textContent = "";
}
function drawEpSeasons(activeSe) {
  const host = $("#epSeasonChips"); host.innerHTML = "";
  epPick.seasons.forEach(s => {
    const chip = document.createElement("button");
    chip.className = "chip" + (Number(s.season) === Number(activeSe) ? " active" : "");
    chip.textContent = "Season " + s.season;
    chip.onclick = () => { drawEpSeasons(s.season); drawEpGrid(s.season); };
    host.appendChild(chip);
  });
}
function drawEpGrid(se) {
  const host = $("#epGrid"); host.innerHTML = "";
  const season = epPick.seasons.find(s => Number(s.season) === Number(se));
  if (!season) return;
  season.episodes.forEach(n => {
    const cell = document.createElement("button");
    const isCur = Number(epPick.se) === Number(se) && Number(epPick.ep) === Number(n);
    cell.className = "ep-cell" + (isCur ? " current" : "");
    cell.textContent = n;
    cell.onclick = async () => {
      const pp = new URLSearchParams({
        subjectId: epPick.subjectId, title: epPick.title || "",
        cover: epPick.cover || "", type: epPick.type != null ? epPick.type : 2,
        se: se, ep: n,
      });
      if (epPick.year) pp.set("year", epPick.year);
      await post("/api/play?" + pp.toString());
      toast(`Playing S${se} · E${n}…`);
      selectTab("np");
    };
    host.appendChild(cell);
  });
}

$("#dPlay").onclick = async () => {
  if (!lastDetails) return;
  const it = lastDetails.item;
  const p = new URLSearchParams({
    subjectId: it.subjectId, title: it.title, cover: it.cover || "", type: it.type,
  });
  if (it.year)     p.set("year", it.year);
  if (it.isSeries) {
    p.set("se", $("#dSeason").value || "1");
    p.set("ep", $("#dEpisode").value || "1");
  }
  await post("/api/play?" + p.toString());
  toast("Playing on TV…"); selectTab("np");
};
$("#dDl").onclick = async () => {
  if (!lastDetails) return;
  const it = lastDetails.item;
  const p = new URLSearchParams({
    subjectId: it.subjectId, title: it.title, cover: it.cover || "", type: it.type,
  });
  if (it.year)     p.set("year", it.year);
  if (it.isSeries) {
    p.set("se", $("#dSeason").value || "1");
    p.set("ep", $("#dEpisode").value || "1");
  }
  await post("/api/downloads/start?" + p.toString());
  toast("Queued for download"); selectTab("dl");
};

/* ---------- now playing ---------- */
const fmt = (ms) => { const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60); return m + ":" + String(s % 60).padStart(2, "0"); };

/** True while the user is actively dragging the volume slider — we want to
 *  pause the refresh→update→snap-back loop until they let go. */
let volSliding = false;
/** Same idea for the position scrubber. */
let posSliding = false;
let lastDurationMs = 0;

let lastState = null;   // most recent /api/state — used by the "All episodes" button
async function refresh() {
  try {
    const s = await get("/api/state");
    lastState = s;
    $("#npTitle").textContent = s.title || "Nothing playing";
    // Episode badge under the title — kept in sync with TV state so the
    // remote shows the same episode the user is actually watching, even
    // after an auto-advance to the next episode.
    const epBadge = $("#npEp");
    if (epBadge) {
      if (s.season != null && s.episode != null) {
        epBadge.textContent = `S${s.season} · E${s.episode}`;
        epBadge.hidden = false;
      } else {
        epBadge.hidden = true;
      }
    }
    // If the Details page selectors are open for the same series the TV
    // is auto-advancing through, mirror the live episode into the picker
    // so the user doesn't see a stale S1E1 while the TV plays S1E4.
    if (lastDetails?.item?.isSeries && s.season != null && s.episode != null) {
      const dSe = $("#dSeason"); const dEp = $("#dEpisode");
      if (dSe && Number(dSe.value) !== s.season) dSe.value = String(s.season);
      if (dEp && Number(dEp.value) !== s.episode) dEp.value = String(s.episode);
    }
    // Position display — skip while the user is dragging; their drag is
    // already updating the time text via the slider's `input` handler.
    if (!posSliding) {
      $("#npPos").textContent = fmt(s.position || 0);
    }
    $("#npDur").textContent = fmt(s.duration || 0);
    lastDurationMs = s.duration || 0;
    // Position scrubber: range is 0..1000 (thousandths of duration). Avoid
    // the snap-back fight by skipping the write while the user is dragging.
    if (!posSliding) {
      const pct = s.duration ? Math.round(s.position / s.duration * 1000) : 0;
      const scrub = $("#npScrub");
      if (Number(scrub.value) !== pct) scrub.value = pct;
      // Disable the scrubber for live streams (duration is 0/unknown) and
      // for movies that haven't started loading yet.
      scrub.disabled = !s.duration;
    }
    // Skip the play/pause icon swap if it's already correct — every textContent
    // / innerHTML write is a layout invalidation, which is what made tapping
    // feel like the page "jumped" each second when the poll fired.
    const wantPlay = s.playing ? "pause" : "play";
    if ($("#pp").dataset.icon !== wantPlay) {
      $("#pp").innerHTML = ic(wantPlay, "lg");
      $("#pp").dataset.icon = wantPlay;
    }
    // Toggle the episode controls row visibility based on series state.
    // CSS hides the row when data-show-eps="false" — we flip the attr so
    // the user only sees Prev/Next/Stop while a series is playing.
    // Show whenever a SERIES is playing (type==1), even if the current
    // episode isn't tracked. For HBO-tier titles (House of the Dragon,
    // Wednesday, etc.) the play uses aoneroom's subject-level resource —
    // state.episode comes back null, but the show IS a series and the
    // user should still be able to open the picker to switch episodes.
    // Previously the picker was gated on s.episode != null and stayed
    // hidden for those titles, locking the user into whatever the
    // subject-level resource happened to be.
    const playingSeries = s.subjectId && (s.type === 1 || s.season != null || s.episode != null);
    const showEps = playingSeries ? "true" : "false";
    document.querySelectorAll(".np-eps").forEach(el => { el.dataset.showEps = showEps; });
    // Cache the title for the Live channel grid render so the
    // currently-playing tile shows a "NOW PLAYING" badge instead of
    // being clickable. Re-render the grid only when the title actually
    // changed — otherwise every 1 s poll thrashes the DOM.
    if (s.title !== lastNowPlayingTitle) {
      lastNowPlayingTitle = s.title || "";
      if (liveChannelsAll.length) renderLiveChannels();
    }
    const volStr = (s.volume ?? "—") + (s.volume != null ? "%" : "");
    if ($("#volPct").textContent !== volStr) $("#volPct").textContent = volStr;
    if (!volSliding && typeof s.volume === "number" &&
        Number($("#volSlider").value) !== s.volume) {
      $("#volSlider").value = s.volume;
    }
    syncTracks(s);
  } catch (e) { /* ignore */ }
}

/** Render the quality + audio pickers when there's an active stream. */
function syncTracks(s) {
  const qs = s.qualities || [], ds = s.dubs || [];
  const card = $("#trackCard");
  // Use the `hidden` attribute (boolean) rather than toggling style.display.
  // Together with the layout-stability rules in CSS, this avoids the brief
  // reflow the user saw every time playback state ticked.
  const show = qs.length > 0 || ds.length > 0;
  if (card.hidden === !show) { /* no-op when state is unchanged */ }
  else card.hidden = !show;
  if (!show) return;
  fillSelect($("#qualitySel"), qs, s.quality, async (v) => {
    await post("/api/quality?label=" + encodeURIComponent(v));
    toast("Quality: " + v);
  });
  fillSelect($("#dubSel"), ds, s.dub, async (v) => {
    await post("/api/dub?name=" + encodeURIComponent(v));
    toast("Audio: " + v);
  });
}

function fillSelect(sel, options, current, onPick) {
  // Skip a rebuild if the option set hasn't changed (avoids dropping focus).
  const sig = options.join("|") + "::" + (current || "");
  if (sel.dataset.sig === sig) return;
  sel.dataset.sig = sig;
  sel.innerHTML = "";
  options.forEach(o => {
    const opt = document.createElement("option");
    opt.value = o; opt.textContent = o;
    if (o === current) opt.selected = true;
    sel.appendChild(opt);
  });
  sel.onchange = () => onPick(sel.value);
}
// Transport / volume buttons — fire-and-forget + optimistic UI. The previous
// version `await`-ed each POST and then called refresh(), so the user saw two
// network round trips per tap (~250-500ms over home Wi-Fi). Now we update the
// local UI immediately and let the existing 1s state poll reconcile if our
// guess was wrong. Feels snappy like Netflix's mobile control.
$("#pp").onclick = () => {
  // Optimistic icon swap so the button feels responsive even before the TV
  // acknowledges. The next /api/state poll will overwrite if we're wrong.
  const swap = $("#pp").dataset.icon === "play" ? "pause" : "play";
  $("#pp").innerHTML = ic(swap, "lg");
  $("#pp").dataset.icon = swap;
  post("/api/playpause");
};
// Episode + close controls. Fire-and-forget POSTs — the next /api/state
// poll refreshes the Now Playing card with the new episode title within
// 1 s, so we don't bother waiting on the response.
$("#epNext")?.addEventListener("click", () => post("/api/episode/next"));
$("#epPrev")?.addEventListener("click", () => post("/api/episode/prev"));
$("#npClose")?.addEventListener("click", () => post("/api/player/close"));
// "All episodes" → open the real season/episode picker for the playing series.
$("#epList")?.addEventListener("click", () => {
  const s = lastState;
  if (!s || !s.subjectId) { toast("Episode list not available yet"); return; }
  // For HBO-tier titles the play uses the subject-level resource and the
  // state doesn't carry season/episode. Fall through to the picker with
  // se=1 ep=1 so it can fetch the season list from /api/details — the
  // picker has its own "show came back empty" handling.
  openEpisodePicker({
    subjectId: s.subjectId, title: s.title, year: s.year,
    type: s.type != null ? s.type : 1, cover: "",
    se: s.season ?? 1, ep: s.episode ?? 1,
  });
});
$("#back10").onclick = () => {
  post("/api/seekby?ms=-10000");
  // Pull the displayed position back optimistically so the time text and
  // scrubber don't lag a second behind the user's tap.
  if (lastDurationMs) {
    const cur = Math.max(0, parseTime($("#npPos").textContent) - 10_000);
    $("#npPos").textContent = fmt(cur);
    $("#npScrub").value = Math.round(cur / lastDurationMs * 1000);
  }
};
$("#fwd10").onclick = () => {
  post("/api/seekby?ms=10000");
  if (lastDurationMs) {
    const cur = Math.min(lastDurationMs, parseTime($("#npPos").textContent) + 10_000);
    $("#npPos").textContent = fmt(cur);
    $("#npScrub").value = Math.round(cur / lastDurationMs * 1000);
  }
};
$("#volUp").onclick = () => {
  // Bump the displayed volume by one step so the user gets immediate feedback
  // — Android's AudioManager steps in ~6.7% chunks on STREAM_MUSIC.
  const cur = Number($("#volSlider").value) || 0;
  const next = Math.min(100, cur + 7);
  $("#volSlider").value = next;
  $("#volPct").textContent = next + "%";
  post("/api/volume?up=1");
};
$("#volDown").onclick = () => {
  const cur = Number($("#volSlider").value) || 0;
  const next = Math.max(0, cur - 7);
  $("#volSlider").value = next;
  $("#volPct").textContent = next + "%";
  post("/api/volume?down=1");
};

/** "m:ss" -> ms. Round-trip-safe for the optimistic seek logic above. */
function parseTime(s) {
  const parts = (s || "0:00").split(":").map(n => Number(n) || 0);
  if (parts.length === 3) return ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000;
  return (parts[0] * 60 + parts[1]) * 1000;
}

/* ---------- volume slider ---------- */
let volSendTimer = null;
$("#volSlider").addEventListener("pointerdown", () => { volSliding = true; });
$("#volSlider").addEventListener("pointerup",   () => { volSliding = false; });
$("#volSlider").addEventListener("pointercancel",() => { volSliding = false; });
$("#volSlider").addEventListener("input", (e) => {
  const v = Number(e.target.value);
  $("#volPct").textContent = v + "%";
  clearTimeout(volSendTimer);
  // Debounce the network call — sliding fires `input` many times per second.
  // 60ms is below the perceptible threshold but still drops 90%+ of redundant
  // POSTs on a typical 5-second slide. Lower had glitchier feel on slow Wi-Fi.
  volSendTimer = setTimeout(() => {
    post("/api/volume?set=" + v);
  }, 60);
});

/* ---------- position scrubber ---------- */
// `input` fires continuously while the user drags — we only update the
// preview time text. `change` fires once when they let go — that's when we
// actually tell the TV to seek. This matches how every native scrubber UX
// behaves (vs the volume slider where a debounced network call during drag
// is fine because each step is independent).
$("#npScrub").addEventListener("pointerdown",   () => { posSliding = true; });
$("#npScrub").addEventListener("pointerup",     () => { posSliding = false; });
$("#npScrub").addEventListener("pointercancel", () => { posSliding = false; });
$("#npScrub").addEventListener("input", (e) => {
  // Live preview of the target time while dragging. lastDurationMs is the
  // duration as of the most recent /api/state poll, refreshed each second.
  if (!lastDurationMs) return;
  const targetMs = Math.round(Number(e.target.value) / 1000 * lastDurationMs);
  $("#npPos").textContent = fmt(targetMs);
});
$("#npScrub").addEventListener("change", async (e) => {
  if (!lastDurationMs) return;
  const targetMs = Math.round(Number(e.target.value) / 1000 * lastDurationMs);
  await post("/api/seek?ms=" + targetMs);
  // Force the next poll to pick up the new position promptly.
  refresh();
});

/* ---------- history ---------- */
$("#histClear")?.addEventListener("click", async () => {
  if (!confirm("Clear all continue-watching history?")) return;
  try { await post("/api/history/clear"); } catch (e) {}
  loadHistory();
  toast("History cleared");
});
async function loadHistory() {
  let items = []; try { items = await get("/api/history"); } catch (e) {}
  const el = $("#history"); el.innerHTML = "";
  if (!items.length) {
    el.innerHTML = `<div class="muted small">Nothing watched yet.</div>`; return;
  }
  items.forEach(it => {
    const label = it.season > 0 ? `S${it.season}E${it.episode}` : "Resume";
    const pct = Math.round((it.progress || 0) * 100);
    const row = document.createElement("div"); row.className = "row-item";
    row.innerHTML = `
      <img loading="lazy" src="${it.cover}" onerror="this.style.opacity=.3" />
      <div class="body">
        <div class="t">${escapeHtml(it.title)}</div>
        <div class="s">${label} · ${pct}%</div>
        <div class="minibar"><div style="width:${pct}%"></div></div>
      </div>
      <div class="x" title="Remove">${ic("close","sm")}</div>`;
    row.querySelector(".body").onclick = async () => {
      await post("/api/play?" + new URLSearchParams({
        subjectId: it.subjectId, title: it.title, cover: it.cover, type: it.type,
        se: it.season || "", ep: it.episode || "",
      }));
      toast("Playing on TV…"); selectTab("np");
    };
    row.querySelector(".x").onclick = async (e) => {
      e.stopPropagation();
      await post("/api/history/delete?key=" + encodeURIComponent(it.key));
      loadHistory();
    };
    el.appendChild(row);
  });
}

/* ---------- downloads ---------- */
async function loadDownloads() {
  let items = []; try { items = await get("/api/downloads"); } catch (e) {}
  const el = $("#downloads"); el.innerHTML = "";
  $("#dlEmpty").classList.toggle("hidden", items.length > 0);
  items.forEach(it => {
    const label = it.season > 0 ? `S${it.season}E${it.episode}` : "Movie";
    const ready = it.status === "COMPLETE";
    const status = ready ? "Ready"
      : it.status === "DOWNLOADING" ? `${it.percent}% · downloading`
      : it.status === "FAILED" ? "Failed" : "Queued";
    const row = document.createElement("div"); row.className = "row-item";
    row.innerHTML = `
      <img loading="lazy" src="${it.cover}" onerror="this.style.opacity=.3" />
      <div class="body">
        <div class="t">${escapeHtml(it.title)}</div>
        <div class="s">
          ${ready ? ic("check","sm") : ""}
          <span>${label} · ${status}</span>
        </div>
        <div class="minibar"><div style="width:${it.percent}%"></div></div>
      </div>
      <div class="x" title="Remove">${ic("trash","sm")}</div>`;
    row.querySelector(".x").onclick = async () => {
      await post("/api/downloads/delete?key=" + encodeURIComponent(it.key));
      loadDownloads();
    };
    el.appendChild(row);
  });
}

/* ---------- devices (superuser) ---------- */
async function loadDevices() {
  if (!me || me.role !== "SUPERUSER") return;
  let items = []; try { items = await get("/api/devices"); } catch (e) {}
  const el = $("#devices"); el.innerHTML = "";
  try {
    const code = await get("/api/pair_code"); $("#pairCode").textContent = code.code;
  } catch (e) {}
  items.forEach(d => {
    const card = document.createElement("div"); card.className = "card dev-card";
    card.innerHTML = `
      <div class="dev-row">
        <div>
          <div class="name">${escapeHtml(d.label)} ${d.isMe ? '<span class="badge">this phone</span>' : ''}</div>
          <div class="ip">${d.ip} · last seen ${ago(d.lastSeen)}</div>
        </div>
        <div class="actions">
          <select ${d.isMe ? "disabled" : ""}>
            <option value="SUPERUSER" ${d.role === "SUPERUSER" ? "selected" : ""}>Superuser</option>
            <option value="USER"      ${d.role === "USER" ? "selected" : ""}>User</option>
            <option value="PENDING"   ${d.role === "PENDING" ? "selected" : ""}>Pending</option>
            <option value="BLOCKED"   ${d.role === "BLOCKED" ? "selected" : ""}>Blocked</option>
          </select>
          <button class="icon-btn ghost" ${d.isMe ? "disabled" : ""} aria-label="Remove">
            ${ic("trash","sm")}
          </button>
        </div>
      </div>`;
    const sel = card.querySelector("select");
    const rm  = card.querySelector(".icon-btn");
    sel.onchange = async () => {
      await post("/api/devices/role?token=" + encodeURIComponent(d.token) + "&role=" + sel.value);
      toast("Updated"); loadDevices();
    };
    rm.onclick = async () => {
      await post("/api/devices/remove?token=" + encodeURIComponent(d.token));
      toast("Removed"); loadDevices();
    };
    el.appendChild(card);
  });
}
$("#regenCode").onclick = async () => {
  const j = await post("/api/pair_code/regen");
  $("#pairCode").textContent = j.code; toast("Pair code regenerated");
};
function ago(ms) {
  const s = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  if (s < 60) return s + "s ago";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
}

/* ---------- browse ---------- */
const BROWSE_ROWS = [
  { id: "trending",        title: "Trending today" },
  { id: "popular_movies",  title: "Popular movies" },
  { id: "popular_tv",      title: "Popular TV" },
  { id: "netflix", title: "Netflix",      net: "Netflix" },
  { id: "hbo",     title: "HBO",          net: "HBO" },
  { id: "disney",  title: "Disney+",      net: "Disney+" },
  { id: "prime",   title: "Prime Video",  net: "Prime" },
  { id: "apple",   title: "Apple TV+",    net: "Apple" },
  { id: "hulu",    title: "Hulu",         net: "Hulu" },
];

async function loadBrowse() {
  browseLoaded = true;
  const host = $("#browseRows"); host.innerHTML = "";

  // Float user's favourite networks to the top of the order.
  const favSet = new Set(prefs.networks || []);
  const ordered = [
    ...BROWSE_ROWS.filter(r => favSet.has(r.id)),
    ...BROWSE_ROWS.filter(r => !favSet.has(r.id)),
  ];

  ordered.forEach(r => {
    const row = document.createElement("div"); row.className = "row-h";
    row.innerHTML = `
      <h3>${escapeHtml(r.title)}
        ${r.net ? `<span class="net">${r.net}</span>` : ""}
      </h3>
      <div class="scroller" id="row-${r.id}">
        ${skeletonGrid(4)}
      </div>`;
    host.appendChild(row);
  });

  // Fetch each row independently — first ones paint fast, slow rows fill later.
  ordered.forEach(async (r) => {
    let items = [];
    try { items = await get("/api/browse?slice=" + encodeURIComponent(r.id)); }
    catch (e) { items = []; }
    const el = document.getElementById("row-" + r.id);
    if (!el) return;
    el.innerHTML = "";
    if (!items.length) {
      el.innerHTML = `<div class="muted small" style="padding:6px 0">Nothing here.</div>`;
      return;
    }
    items.forEach(it => el.appendChild(searchCard(it)));
  });
}

/* ---------- live TV ---------- */
async function loadLive() {
  liveLoaded = true;
  await refreshLive(/*showSkeleton*/ true);
  // If the TV didn't have channels cached yet, the first response will say
  // loaded=false. Poll every 1.5s until populated, then stop.
  if (livePollTimer) clearTimeout(livePollTimer);
  const poll = async () => {
    if (active !== "live") return;
    const resp = await fetchLiveChannels();
    if (resp && resp.loaded) {
      liveChannelsAll = resp.channels;
      renderLiveChannels();
      renderLiveGroups();
      return;
    }
    livePollTimer = setTimeout(poll, 1500);
  };
  if (!liveChannelsAll.length) livePollTimer = setTimeout(poll, 1500);
}

async function fetchLiveChannels() {
  const params = new URLSearchParams();
  if (liveSelectedGroup) params.set("group", liveSelectedGroup);
  if (liveQuery) params.set("q", liveQuery);
  try {
    return await get("/api/live/channels?" + params.toString());
  } catch (e) { return null; }
}

async function refreshLive(showSkeleton) {
  const grid = $("#liveChannels");
  const status = $("#liveStatus");
  if (showSkeleton) {
    grid.innerHTML = skeletonGrid(6);
    status.textContent = "Loading channels…";
  }
  const resp = await fetchLiveChannels();
  if (!resp) {
    status.textContent = "Could not reach the TV.";
    return;
  }
  liveChannelsAll = resp.channels;
  if (!resp.loaded && !liveChannelsAll.length) {
    status.textContent = "The TV is fetching the catalog — this takes a moment on first use.";
  } else {
    status.textContent = `${liveChannelsAll.length} live channel${liveChannelsAll.length === 1 ? "" : "s"}.`;
  }
  renderLiveChannels();
  renderLiveGroups();
}

function renderLiveChannels() {
  const grid = $("#liveChannels");
  if (!liveChannelsAll.length) {
    grid.innerHTML = `<div class="muted small" style="grid-column:1/-1">No channels match.</div>`;
    return;
  }
  // Compare against the title in /api/state (case-insensitive, ignoring
  // dlhd's group suffixes) so the currently-playing channel is marked
  // visually and tapping it is a no-op — answers the user request
  // "prevent users from clicking or picking the same channel that's
  // already playing".
  const nowPlaying = (lastNowPlayingTitle || "").trim().toLowerCase();
  grid.innerHTML = "";
  liveChannelsAll.forEach(ch => {
    const card = document.createElement("div");
    card.className = "livecard";
    const logoHtml = ch.logo
      ? `<img src="${escapeHtml(ch.logo)}" alt="" onerror="this.remove()"/>`
      : `<span class="ph-letters">${escapeHtml((ch.name||"").slice(0,3).toUpperCase())}</span>`;
    const isCurrent = nowPlaying && (ch.name || "").trim().toLowerCase() === nowPlaying;
    if (isCurrent) card.classList.add("now-playing");
    // ch.sweep === "down" means the CI deep-probe (data/health.json) saw
    // this channel fail its full playback chain on the most recent sweep.
    // Advisory only — we still let the user tap it, because the sweep
    // runs from a different network than the user's device and false
    // positives are common.
    const oftenOffline = ch.sweep === "down";
    if (oftenOffline) card.classList.add("often-offline");
    card.innerHTML = `
      <div class="logo-wrap">
        ${logoHtml}
        <span class="live-pill">${isCurrent ? "NOW PLAYING" : "LIVE"}</span>
        ${oftenOffline ? `<span class="offline-pill">OFFLINE?</span>` : ""}
      </div>
      <div class="name">${escapeHtml(ch.name)}</div>`;
    if (!isCurrent) {
      card.addEventListener("click", async () => {
        try {
          // Server reads params from the query string only (NanoHTTPD doesn't
          // auto-parse form bodies). Match the /api/play VOD pattern.
          await post("/api/live/play?id=" + encodeURIComponent(ch.id));
          showToast(`Playing ${ch.name}`);
        } catch (e) {
          showToast("Couldn't play");
        }
      });
    }
    grid.appendChild(card);
  });
}

// Updated by the /api/state poll. The render loop compares this against
// each channel's name to mark the now-playing tile.
let lastNowPlayingTitle = "";

async function renderLiveGroups() {
  // Distinct groups from the currently-loaded channel set (cheap, no extra fetch).
  // For the "All" chip we keep it always present.
  let groups;
  try {
    groups = await get("/api/live/groups");
  } catch (e) {
    groups = Array.from(new Set(liveChannelsAll.map(c => c.group).filter(Boolean)));
  }
  const bar = $("#liveGroups");
  bar.innerHTML = "";
  const mk = (label, value, active) => {
    const b = document.createElement("button");
    b.className = "chip" + (active ? " active" : "");
    b.textContent = label;
    b.onclick = () => {
      liveSelectedGroup = value;
      refreshLive(true);
    };
    return b;
  };
  bar.appendChild(mk("All", "", liveSelectedGroup === ""));
  groups.forEach(g => bar.appendChild(mk(shortGroup(g), g, liveSelectedGroup === g)));
}

function shortGroup(g) {
  const i = g.indexOf(" (");
  return i > 0 ? g.slice(0, i) : g;
}

// Search box (debounced).
const liveSearchEl = document.getElementById("liveQ");
if (liveSearchEl) {
  liveSearchEl.addEventListener("input", (e) => {
    clearTimeout(liveQueryTimer);
    liveQueryTimer = setTimeout(() => {
      liveQuery = e.target.value || "";
      refreshLive(false);
    }, 300);
  });
}

/* ---------- Live sub-tab switching (Channels / Schedule) ---------- */
let liveScheduleLoaded = false;
function selectLiveSubtab(which) {
  // Toggle pill state + pane visibility. The Live tab now mirrors the TV's
  // Live structure (Channels grid vs Schedule by category).
  document.querySelectorAll("#pane-live .subtab").forEach(b => {
    b.classList.toggle("active", b.dataset.sub === which);
  });
  $("#liveChannelsPane").classList.toggle("hidden", which !== "channels");
  $("#liveSchedulePane").classList.toggle("hidden", which !== "schedule");
  if (which === "schedule" && !liveScheduleLoaded) loadLiveSchedule();
}
document.querySelectorAll("#pane-live .subtab").forEach(b => {
  b.onclick = () => selectLiveSubtab(b.dataset.sub);
});

/* ---------- Live Schedule ---------- */
let liveScheduleData = null;
let liveScheduleQuery = "";
let liveScheduleCategory = "all";
let liveScheduleQueryTimer = null;

// Meta-categories the user asked for, mapped from the raw category strings the
// catalog publishes (which look like "Soccer", "NBA", "Cricket Test Match", …).
// First match wins; "Other" catches anything not classified.
const SCHED_CATEGORIES = [
  { id: "all",       label: "All",      match: () => true },
  { id: "sports",    label: "Sports",   match: s => /\b(soccer|football|nfl|nba|mlb|nhl|cricket|tennis|rugby|hockey|boxing|mma|wrestl|racing|f1|formula|gp|golf|baseball|basketball|sports?|league|cup|liga|premier|champion|olympic)\b/i.test(s) },
  { id: "news",      label: "News",     match: s => /\b(news|breaking|world|politics?|press)\b/i.test(s) },
  { id: "movies",    label: "Movies",   match: s => /\b(movies?|film|cinema)\b/i.test(s) },
  { id: "shows",     label: "TV Shows", match: s => /\b(shows?|series|drama|talk|sitcom|reality|episodes?)\b/i.test(s) },
  { id: "other",     label: "Other",    match: () => true },
];

function classifyScheduleCategory(name) {
  const s = String(name || "");
  for (const cat of SCHED_CATEGORIES) {
    if (cat.id === "all" || cat.id === "other") continue;
    if (cat.match(s)) return cat.id;
  }
  return "other";
}

async function loadLiveSchedule() {
  liveScheduleLoaded = true;
  const status = $("#liveScheduleStatus");
  const list = $("#liveScheduleList");
  status.textContent = "Loading schedule…";
  list.innerHTML = "";
  let data;
  try {
    data = await get("/api/live/schedule");
  } catch (e) { status.textContent = "Could not reach the TV."; return; }
  if (!Array.isArray(data) || data.length === 0) {
    status.textContent = "No schedule available right now.";
    renderScheduleChips([]);
    return;
  }
  liveScheduleData = data;
  renderScheduleChips(data);
  renderLiveSchedule(data);
}

function renderScheduleChips(data) {
  // Always show the same meta-category set regardless of which categories the
  // current payload happens to populate — keeps the filter row stable so
  // tapping a chip doesn't shift other chips around (the original
  // "page moves on tap" complaint from much earlier).
  const bar = $("#schedChips");
  bar.innerHTML = "";
  SCHED_CATEGORIES.forEach(cat => {
    const chip = document.createElement("div");
    chip.className = "chip" + (cat.id === liveScheduleCategory ? " active" : "");
    chip.textContent = cat.label;
    chip.onclick = () => {
      liveScheduleCategory = cat.id;
      renderScheduleChips(liveScheduleData || []);
      renderLiveSchedule(liveScheduleData || []);
    };
    bar.appendChild(chip);
  });
}

const schedQEl = document.getElementById("schedQ");
if (schedQEl) {
  schedQEl.addEventListener("input", (e) => {
    clearTimeout(liveScheduleQueryTimer);
    liveScheduleQueryTimer = setTimeout(() => {
      liveScheduleQuery = (e.target.value || "").trim().toLowerCase();
      if (liveScheduleData) renderLiveSchedule(liveScheduleData);
    }, 200);
  });
}

function renderLiveSchedule(data) {
  // Filter out events whose start time has already passed (today). The
  // schedule is published in UTC HH:MM as documented in LiveModels.kt;
  // compare against the user's current UTC clock so timezone-of-viewer
  // doesn't matter — events disappear when their broadcast starts, not
  // when the user's local time rolls past them.
  const now = new Date();
  const nowMinutes = now.getUTCHours() * 60 + now.getUTCMinutes();
  const todayUtc = now.toLocaleDateString(undefined, {
    weekday: "long", month: "short", day: "numeric", timeZone: "UTC",
  });
  const list = $("#liveScheduleList");
  const status = $("#liveScheduleStatus");
  let liveCount = 0;
  let upcomingCount = 0;

  const q = liveScheduleQuery;
  const cat = liveScheduleCategory;
  // Prep: keep each category, filter its events, drop empty categories.
  const filtered = data.map(g => {
    const groupMeta = classifyScheduleCategory(g.category);
    if (cat !== "all" && groupMeta !== cat) return { ...g, events: [] };
    const events = (g.events || []).filter(e => {
      const m = /^(\d{1,2}):(\d{2})$/.exec(e.time || "");
      if (!m) return true;
      const evMinutes = parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
      const stillOnAir = evMinutes >= nowMinutes - 60;
      if (!stillOnAir) return false;
      if (q) {
        const hay = ((e.title || "") + " " + (g.category || "") + " " +
          (e.channels || []).map(c => c.name).join(" ")).toLowerCase();
        if (!hay.includes(q)) return false;
      }
      if (evMinutes <= nowMinutes) liveCount++;
      else upcomingCount++;
      return true;
    });
    return { ...g, events };
  }).filter(g => g.events.length > 0);

  status.textContent = filtered.length
    ? `${todayUtc} (UTC) — ${liveCount} on now, ${upcomingCount} upcoming`
    : `No upcoming events for ${todayUtc} (UTC).`;

  list.innerHTML = "";
  filtered.forEach(cat => {
    const block = document.createElement("div");
    block.className = "schedule-category";
    const head = document.createElement("h3");
    head.textContent = cat.category || "Other";
    block.appendChild(head);
    cat.events.forEach(ev => {
      const row = document.createElement("div");
      row.className = "schedule-event";
      // Channel chips clickable — playing a channel from the schedule
      // does the same as tapping it in the grid. For FIFA World Cup-style
      // events that list 50-100+ mirror feeds, slicing to a few inline
      // pills hid the rest. Show 4 inline; if there are more, render a
      // <select> dropdown listing them all so the user can pick by name
      // without scrolling a wall of pills.
      const channels = ev.channels || [];
      const INLINE_CHIPS = 4;
      const inline = channels.slice(0, INLINE_CHIPS);
      const overflow = channels.slice(INLINE_CHIPS);
      const chsHtml = inline.map(ch => {
        const safe = (ch.name || ch.id).replace(/['"<>&]/g, "");
        return `<span class="ch-pill" data-cid="${ch.id}">${escapeHtml(safe)}</span>`;
      }).join("");
      const moreHtml = overflow.length === 0 ? "" : `
        <select class="ch-more" data-eid="${escapeHtml(ev.title || '')}">
          <option value="">+${overflow.length} more…</option>
          ${overflow.map(ch =>
            `<option value="${ch.id}">${escapeHtml(ch.name || ch.id)}</option>`,
          ).join("")}
        </select>`;
      row.innerHTML = `
        <span class="when">${escapeHtml(ev.time || "")}</span>
        <span class="what">${escapeHtml(ev.title || "")}</span>
        <span class="chs">${chsHtml}${moreHtml}</span>`;
      row.querySelectorAll(".ch-pill").forEach(pill => {
        pill.addEventListener("click", async () => {
          try {
            await post("/api/live/play?id=" + encodeURIComponent(pill.dataset.cid));
            showToast(`Playing ${pill.textContent}`);
          } catch (e) { showToast("Couldn't play"); }
        });
      });
      // Overflow dropdown — same play action as a pill.
      const moreSel = row.querySelector(".ch-more");
      if (moreSel) {
        moreSel.addEventListener("change", async (e) => {
          const id = e.target.value;
          if (!id) return;
          const label = e.target.options[e.target.selectedIndex].textContent;
          try {
            await post("/api/live/play?id=" + encodeURIComponent(id));
            showToast(`Playing ${label}`);
          } catch (err) { showToast("Couldn't play"); }
          e.target.selectedIndex = 0;   // reset back to "+N more…"
        });
      }
      block.appendChild(row);
    });
    list.appendChild(block);
  });
}

/* ---------- preferences ---------- */
const NETWORK_CHIPS = [
  { id: "netflix", label: "Netflix" },
  { id: "hbo",     label: "HBO"     },
  { id: "disney",  label: "Disney+" },
  { id: "prime",   label: "Prime"   },
  { id: "apple",   label: "Apple TV+" },
  { id: "hulu",    label: "Hulu"    },
];
const LANG_CHIPS = [
  { id: "hi", label: "Hindi" },  { id: "ta", label: "Tamil" },
  { id: "te", label: "Telugu" }, { id: "ml", label: "Malayalam" },
  { id: "ko", label: "Korean" }, { id: "ja", label: "Japanese" },
  { id: "es", label: "Spanish" },{ id: "fr", label: "French" },
  { id: "de", label: "German" }, { id: "zh", label: "Chinese" },
];
let movieGenresCached = null;

async function loadPrefs() {
  try { prefs = await get("/api/me/prefs"); }
  catch (e) { prefs = { networks: [], genres: [], denyLanguages: [] }; }
  renderChipSet($("#prefNetworks"), NETWORK_CHIPS, prefs.networks, (set) =>
    savePrefs({ networks: Array.from(set) }));
  if (!movieGenresCached) {
    try { movieGenresCached = await get("/api/genres"); }
    catch (e) { movieGenresCached = []; }
  }
  renderChipSet(
    $("#prefGenres"),
    movieGenresCached.map(g => ({ id: g.id, label: g.name })),
    prefs.genres,
    (set) => savePrefs({ genres: Array.from(set).map(Number) }),
  );
  renderChipSet($("#prefLangs"), LANG_CHIPS, prefs.denyLanguages, (set) =>
    savePrefs({ denyLanguages: Array.from(set) }), /*deny=*/true);
}

function renderChipSet(host, items, selected, onChange, deny) {
  host.innerHTML = "";
  const sel = new Set((selected || []).map(String));
  items.forEach(it => {
    const chip = document.createElement("div");
    const isOn = sel.has(String(it.id));
    chip.className = "chip" + (deny ? " deny" : "") + (isOn ? " on" : "");
    chip.textContent = it.label;
    chip.onclick = () => {
      if (sel.has(String(it.id))) sel.delete(String(it.id));
      else sel.add(String(it.id));
      chip.classList.toggle("on");
      onChange(sel);
    };
    host.appendChild(chip);
  });
}

let saveTimer;
function savePrefs(patch) {
  Object.assign(prefs, patch);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    const body = new URLSearchParams();
    if (patch.networks)      body.set("networks", patch.networks.join(","));
    if (patch.genres)        body.set("genres", patch.genres.join(","));
    if (patch.denyLanguages) body.set("denyLanguages", patch.denyLanguages.join(","));
    try {
      await post("/api/me/prefs?" + body.toString());
      browseLoaded = false; // re-order favourites on next Browse visit
      toast("Preferences saved");
    } catch (e) {}
  }, 400);
}

/* ---------- polling ---------- */
setInterval(() => {
  if (active === "np")     refresh();
  if (active === "dl")     loadDownloads();
  if (active === "devices") loadDevices();
}, 1500);

boot();
